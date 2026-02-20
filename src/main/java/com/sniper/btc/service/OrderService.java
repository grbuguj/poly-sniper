package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Polymarket CLOB 주문 서비스 (LIVE 모드 전용)
 *
 * ⚡ 최적화:
 * - 사전 캐싱: credentials, domainSep, HMAC 키, 주소 바이트 (1회)
 * - 사전 준비: orderHash 정적 부분 프리빌드
 * - 커넥션 프리워밍: 시작 시 TCP+TLS 핸드셰이크 완료
 * - tokenId 프리파싱: 오즈 변경 시 BigInteger 미리 계산
 */
@Slf4j
@Service
public class OrderService {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${polymarket.private-key:}")
    private String privateKey;

    @Value("${polymarket.api-key:}")
    private String apiKey;

    @Value("${polymarket.passphrase:}")
    private String passphrase;

    @Value("${polymarket.api-secret:}")
    private String apiSecret;

    @Value("${polymarket.funder:}")
    private String funder;

    @Value("${sniper.dry-run:true}")
    private boolean dryRun;

    private static final String CLOB = "https://clob.polymarket.com";
    private static final String CHAIN_ID = "137";
    private static final String EXCHANGE_CONTRACT = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";
    private static final int FEE_RATE_BPS = 1000;
    private static final double MIN_SIZE = 5.0;

    private static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)";
    private static final byte[] ORDER_TYPE_HASH_BYTES = Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
    private static final byte[] DOMAIN_TYPE_HASH_BYTES = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8));

    // ======== ⚡ 사전 캐싱 (1회 초기화) ========
    private volatile Credentials cachedCredentials;
    private volatile String cachedSigner;
    private volatile String cachedMaker;
    private volatile int cachedSigType;
    private volatile byte[] cachedDomainSeparator;
    private volatile javax.crypto.spec.SecretKeySpec cachedHmacKey;

    // ======== ⚡ 주문 사전 준비: 정적 바이트 프리빌드 ========
    private volatile byte[] paddedMaker;      // maker 주소 32바이트
    private volatile byte[] paddedSigner;     // signer 주소 32바이트
    private static final byte[] PADDED_TAKER = Numeric.toBytesPadded(BigInteger.ZERO, 32);
    private static final byte[] PADDED_EXPIRATION = Numeric.toBytesPadded(BigInteger.ZERO, 32);
    private static final byte[] PADDED_NONCE = Numeric.toBytesPadded(BigInteger.ZERO, 32);
    private static final byte[] PADDED_FEE_RATE = Numeric.toBytesPadded(BigInteger.valueOf(FEE_RATE_BPS), 32);
    private static final byte[] PADDED_SIDE_BUY = Numeric.toBytesPadded(BigInteger.ZERO, 32);
    private static final byte[] PADDED_SIDE_SELL = Numeric.toBytesPadded(BigInteger.ONE, 32);

    // ======== ⚡ tokenId 프리파싱 캐시 ========
    private volatile String cachedUpTokenId;
    private volatile String cachedDownTokenId;
    private volatile byte[] paddedUpTokenId;
    private volatile byte[] paddedDownTokenId;

    // ======== ⚡ sigType 바이트 캐시 ========
    private volatile byte[] paddedSigType;

    /** 시작 시 1회 초기화 */
    private void ensureInitialized() {
        if (cachedCredentials != null) return;
        synchronized (this) {
            if (cachedCredentials != null) return;
            if (privateKey == null || privateKey.isEmpty()) return;

            cachedCredentials = Credentials.create(privateKey);
            cachedSigner = Keys.toChecksumAddress(cachedCredentials.getAddress());
            cachedMaker = (funder != null && !funder.isEmpty()) ? funder : cachedSigner;
            cachedSigType = (funder != null && !funder.isEmpty()) ? 1 : 0;
            cachedDomainSeparator = buildDomainSeparator();

            if (apiSecret != null && !apiSecret.isEmpty()) {
                cachedHmacKey = new javax.crypto.spec.SecretKeySpec(
                        java.util.Base64.getUrlDecoder().decode(apiSecret), "HmacSHA256");
            }

            // ⚡ 정적 바이트 프리빌드
            paddedMaker = Numeric.toBytesPadded(
                    new BigInteger(Numeric.cleanHexPrefix(cachedMaker), 16), 32);
            paddedSigner = Numeric.toBytesPadded(
                    new BigInteger(Numeric.cleanHexPrefix(cachedSigner), 16), 32);
            paddedSigType = Numeric.toBytesPadded(BigInteger.valueOf(cachedSigType), 32);

            log.info("⚡ OrderService 초기화 완료 — signer={} maker={} sigType={} (정적 바이트 프리빌드 OK)",
                    cachedSigner, cachedMaker, cachedSigType);
        }
    }

    // ======== ③ 커넥션 프리워밍 ========
    @PostConstruct
    public void warmUpConnection() {
        // 별도 스레드에서 실행 (시작 지연 방지)
        Thread warmup = new Thread(() -> {
            try {
                Thread.sleep(2000); // 다른 서비스 초기화 대기
                ensureInitialized();

                // CLOB 엔드포인트에 GET 요청 → TCP+TLS 핸드셰이크 완료
                long start = System.currentTimeMillis();
                Request req = new Request.Builder()
                        .url(CLOB + "/tick-size?token_id=placeholder")
                        .header("Accept", "application/json")
                        .build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("🔌 CLOB 커넥션 프리워밍 완료 — {}ms (HTTP/2={}) | 첫 주문 핸드셰이크 생략",
                            elapsed, resp.protocol());
                }
            } catch (Exception e) {
                log.warn("🔌 커넥션 프리워밍 실패 (첫 주문에서 핸드셰이크): {}", e.getMessage());
            }
        }, "clob-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    // ======== ⚡ tokenId 프리파싱 (OddsService에서 호출) ========
    /**
     * 오즈 변경 시 tokenId를 미리 BigInteger → 32바이트로 변환
     * 스캔 루프에서 호출하면 주문 시 파싱 시간 0ms
     */
    public void prepareTokenIds(String upTokenId, String downTokenId) {
        if (upTokenId != null && !upTokenId.equals(cachedUpTokenId)) {
            cachedUpTokenId = upTokenId;
            paddedUpTokenId = Numeric.toBytesPadded(new BigInteger(upTokenId), 32);
        }
        if (downTokenId != null && !downTokenId.equals(cachedDownTokenId)) {
            cachedDownTokenId = downTokenId;
            paddedDownTokenId = Numeric.toBytesPadded(new BigInteger(downTokenId), 32);
        }
    }

    public record OrderResult(boolean success, String orderId, String error, double actualAmount, double actualSize, String status) {
        public OrderResult(boolean success, String orderId, String error) {
            this(success, orderId, error, 0, 0, "");
        }
    }

    // ── HMAC 서명 생성 ──
    private String buildHmacSignature(long timestamp, String method, String requestPath, String body) throws Exception {
        String message = timestamp + method + requestPath;
        if (body != null && !body.isEmpty()) {
            message += body;
        }
        ensureInitialized();
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(cachedHmacKey);
        byte[] hmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getUrlEncoder().encodeToString(hmac);
    }

    // ── L2 인증 헤더 ──
    private Request.Builder withL2Headers(Request.Builder builder, long timestamp, String signature) {
        ensureInitialized();
        return builder
                .header("POLY_ADDRESS", cachedSigner)
                .header("POLY_API_KEY", apiKey)
                .header("POLY_PASSPHRASE", passphrase)
                .header("POLY_TIMESTAMP", String.valueOf(timestamp))
                .header("POLY_SIGNATURE", signature);
    }

    // ── FOK 슬리피지 ──
    private static final double BASE_SLIPPAGE_TICKS = 1;
    private static final double RETRY_SLIPPAGE_TICKS = 2; // 재시도당 +2틱

    /**
     * 주문 실행
     * @param retryCount FOK 재시도 횟수 (0=첫 시도, 1=1차 재시도...) → 재시도마다 +1틱 추가
     */
    public OrderResult placeOrder(String tokenId, double amount, double price, String side, int retryCount) {
        double totalSlippageTicks = BASE_SLIPPAGE_TICKS + (retryCount * RETRY_SLIPPAGE_TICKS);
        double slippagePrice = "BUY".equalsIgnoreCase(side)
                ? price + (totalSlippageTicks * 0.01)
                : price - (totalSlippageTicks * 0.01);
        slippagePrice = Math.max(0.01, Math.min(0.99, slippagePrice));

        double tickPrice = Math.round(slippagePrice * 100.0) / 100.0;
        double actualSize = Math.max(MIN_SIZE, Math.floor((amount / tickPrice) * 100.0) / 100.0);
        double actualAmount = actualSize * tickPrice;

        log.info("📈 FOK 슬리피지: {}¢ → {}¢ (+{}틱{})",
                Math.round(price * 100), Math.round(tickPrice * 100), (int) totalSlippageTicks,
                retryCount > 0 ? " 재시도#" + retryCount : "");

        if (dryRun) {
            log.info("🧪 [DRY-RUN] 주문 시뮬: {} ${} ({}토큰) @ {} ({})",
                    side, fmt(actualAmount), fmt(actualSize), fmt(tickPrice), tokenId.substring(0, 8));
            return new OrderResult(true, "DRY-" + System.currentTimeMillis(), null, actualAmount, actualSize, "MATCHED");
        }
        try {
            return executeLiveOrder(tokenId, amount, tickPrice, side);
        } catch (Exception e) {
            log.error("❌ LIVE 주문 실패: {}", e.getMessage());
            return new OrderResult(false, null, e.getMessage());
        }
    }

    private OrderResult executeLiveOrder(String tokenId, double amount, double price, String side) throws Exception {
        long orderStart = System.nanoTime();
        ensureInitialized();
        if (cachedCredentials == null) {
            return new OrderResult(false, null, "Private key not configured");
        }

        String signer = cachedSigner;
        String maker = cachedMaker;
        int sigType = cachedSigType;

        // ── 금액 계산 ──
        double tickPrice = Math.round(price * 100.0) / 100.0;
        double size = Math.max(MIN_SIZE, Math.floor((amount / tickPrice) * 100.0) / 100.0);
        long makerAmountRaw = Math.round(size * tickPrice * 1e6);
        makerAmountRaw = (makerAmountRaw / 10000) * 10000;
        long takerAmountRaw = Math.round(size * 1e6);
        takerAmountRaw = (takerAmountRaw / 100) * 100;

        if (makerAmountRaw <= 0 || takerAmountRaw <= 0) {
            log.error("❌ 금액 계산 오류: maker={} taker={} size={} price={}",
                    makerAmountRaw, takerAmountRaw, size, tickPrice);
            return new OrderResult(false, null, "Invalid amount calculation", 0, 0, "REJECTED");
        }

        BigInteger salt = BigInteger.valueOf(System.currentTimeMillis());
        int sideInt = "BUY".equalsIgnoreCase(side) ? 0 : 1;

        // ── ⚡ EIP-712 서명 (프리빌드된 정적 바이트 사용) ──
        byte[] orderHash = buildOrderHashFast(salt, tokenId,
                BigInteger.valueOf(makerAmountRaw), BigInteger.valueOf(takerAmountRaw),
                sideInt);

        byte[] digest = Hash.sha3(concat(new byte[]{0x19, 0x01}, cachedDomainSeparator, orderHash));
        Sign.SignatureData sig = Sign.signMessage(digest, cachedCredentials.getEcKeyPair(), false);
        String signature = Numeric.toHexStringNoPrefix(sig.getR())
                + Numeric.toHexStringNoPrefix(sig.getS())
                + String.format("%02x", sig.getV()[0]);

        long signDoneNs = System.nanoTime();

        // ── JSON 빌드 ──
        java.util.LinkedHashMap<String, Object> orderMap = new java.util.LinkedHashMap<>();
        orderMap.put("salt", salt.longValue());
        orderMap.put("maker", maker);
        orderMap.put("signer", signer);
        orderMap.put("taker", "0x0000000000000000000000000000000000000000");
        orderMap.put("tokenId", tokenId);
        orderMap.put("makerAmount", String.valueOf(makerAmountRaw));
        orderMap.put("takerAmount", String.valueOf(takerAmountRaw));
        orderMap.put("expiration", "0");
        orderMap.put("nonce", "0");
        orderMap.put("feeRateBps", String.valueOf(FEE_RATE_BPS));
        orderMap.put("side", side.toUpperCase());
        orderMap.put("signatureType", sigType);
        orderMap.put("signature", "0x" + signature);

        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("order", orderMap);
        payload.put("owner", apiKey);
        payload.put("orderType", "FOK");
        payload.put("postOnly", false);

        String orderJson = objectMapper.writeValueAsString(payload);

        // ── HMAC L2 서명 & 전송 ──
        long timestamp = System.currentTimeMillis() / 1000;
        String hmacSig = buildHmacSignature(timestamp, "POST", "/order", orderJson);

        Request request = withL2Headers(new Request.Builder(), timestamp, hmacSig)
                .url(CLOB + "/order")
                .post(RequestBody.create(orderJson, MediaType.parse("application/json")))
                .build();

        long httpStartNs = System.nanoTime();

        try (Response resp = httpClient.newCall(request).execute()) {
            long totalMs = (System.nanoTime() - orderStart) / 1_000_000;
            long signMs = (signDoneNs - orderStart) / 1_000_000;
            long httpMs = (System.nanoTime() - httpStartNs) / 1_000_000;

            String body = resp.body() != null ? resp.body().string() : "";
            if (resp.isSuccessful()) {
                JsonNode result = objectMapper.readTree(body);
                String orderId = result.path("orderID").asText("unknown");
                String status = result.path("status").asText("");
                double actualUsd = size * tickPrice;

                if ("matched".equalsIgnoreCase(status)) {
                    log.info("✅ FOK 즉시 체결: {} ({}) ${} ({}tok) | ⚡ 서명 {}ms + HTTP {}ms = 총 {}ms",
                            orderId, side, fmt(actualUsd), fmt(size), signMs, httpMs, totalMs);
                } else if ("live".equalsIgnoreCase(status)) {
                    log.warn("⚠️ 주문 live 상태 (미체결 가능): {} ({}) ${} | {}ms", orderId, side, fmt(actualUsd), totalMs);
                } else {
                    log.info("✅ 주문 응답: {} status={} ({}) ${} | {}ms", orderId, status, side, fmt(actualUsd), totalMs);
                }
                return new OrderResult(true, orderId, null, actualUsd, size, status.toUpperCase());
            } else {
                log.error("❌ [주문실패] 주문 거부: {} | {}ms", body, totalMs);
                return new OrderResult(false, null, body, 0, 0, "REJECTED");
            }
        }
    }

    // ======== ⚡ 프리빌드 orderHash (정적 바이트 재사용) ========
    /**
     * 기존 buildOrderHash 대비 개선:
     * - maker/signer/taker/expiration/nonce/feeRate/sigType → 캐시된 바이트 배열 직접 사용
     * - tokenId → 프리파싱 캐시 활용 (miss 시 즉석 계산)
     * - hex 파싱, BigInteger 변환, 패딩 연산 최소화
     */
    private byte[] buildOrderHashFast(BigInteger salt, String tokenId,
                                       BigInteger makerAmt, BigInteger takerAmt,
                                       int side) {
        // tokenId: 캐시 hit이면 프리파싱된 바이트 사용
        byte[] paddedToken;
        if (tokenId.equals(cachedUpTokenId) && paddedUpTokenId != null) {
            paddedToken = paddedUpTokenId;
        } else if (tokenId.equals(cachedDownTokenId) && paddedDownTokenId != null) {
            paddedToken = paddedDownTokenId;
        } else {
            paddedToken = Numeric.toBytesPadded(new BigInteger(tokenId), 32);
        }

        return Hash.sha3(concat(
                ORDER_TYPE_HASH_BYTES,           // static (class constant)
                Numeric.toBytesPadded(salt, 32), // dynamic (timestamp)
                paddedMaker,                     // ⚡ pre-built
                paddedSigner,                    // ⚡ pre-built
                PADDED_TAKER,                    // ⚡ static constant
                paddedToken,                     // ⚡ pre-parsed cache
                Numeric.toBytesPadded(makerAmt, 32),  // dynamic
                Numeric.toBytesPadded(takerAmt, 32),  // dynamic
                PADDED_EXPIRATION,               // ⚡ static constant
                PADDED_NONCE,                    // ⚡ static constant
                PADDED_FEE_RATE,                 // ⚡ static constant
                side == 0 ? PADDED_SIDE_BUY : PADDED_SIDE_SELL,  // ⚡ static constant
                paddedSigType                    // ⚡ pre-built
        ));
    }

    // ── Polymarket 실제 USDC 잔액 조회 ──
    public double fetchLiveBalance() {
        try {
            ensureInitialized();
            long timestamp = System.currentTimeMillis() / 1000;
            String requestPath = "/balance-allowance";
            String fullUrl = CLOB + requestPath + "?asset_type=COLLATERAL&signature_type=" + cachedSigType;

            String signature = buildHmacSignature(timestamp, "GET", requestPath, null);

            Request request = withL2Headers(new Request.Builder(), timestamp, signature)
                    .url(fullUrl)
                    .get()
                    .build();

            try (Response resp = httpClient.newCall(request).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.isSuccessful()) {
                    JsonNode json = objectMapper.readTree(body);
                    double raw = json.has("balance") ? json.get("balance").asDouble() : Double.parseDouble(body.replace("\"", "").trim());
                    double balance = raw > 1_000_000 ? raw / 1e6 : raw;
                    log.info("💰 Polymarket 실제 잔액: ${} (raw={})", fmt(balance), raw);
                    return balance;
                } else {
                    log.error("❌ 잔액 조회 실패: {} {}", resp.code(), body);
                    return -1;
                }
            }
        } catch (Exception e) {
            log.error("❌ 잔액 조회 에러: {}", e.getMessage());
            return -1;
        }
    }

    // ── EIP-712 ──
    private byte[] buildDomainSeparator() {
        return Hash.sha3(concat(
                DOMAIN_TYPE_HASH_BYTES,
                Hash.sha3("Polymarket CTF Exchange".getBytes(StandardCharsets.UTF_8)),
                Hash.sha3("1".getBytes(StandardCharsets.UTF_8)),
                Numeric.toBytesPadded(new BigInteger(CHAIN_ID), 32),
                Numeric.toBytesPadded(new BigInteger(Numeric.cleanHexPrefix(EXCHANGE_CONTRACT), 16), 32)
        ));
    }

    private byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] result = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    public boolean isLive() {
        return !dryRun && apiKey != null && !apiKey.isEmpty();
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
