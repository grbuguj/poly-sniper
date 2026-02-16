package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Slf4j
@Service
public class OrderService {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .protocols(java.util.Arrays.asList(Protocol.HTTP_1_1))
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
    private static final int FEE_RATE_BPS = 1000; // 10% — Polymarket 표준
    private static final double MIN_SIZE = 5.0;   // 최소 주문 수량 (토큰)

    private static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)";
    private static final byte[] ORDER_TYPE_HASH_BYTES = Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
    private static final byte[] DOMAIN_TYPE_HASH_BYTES = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8));

    public record OrderResult(boolean success, String orderId, String error, double actualAmount, double actualSize) {
        // 기존 호환용
        public OrderResult(boolean success, String orderId, String error) {
            this(success, orderId, error, 0, 0);
        }
    }

    // ── HMAC 서명 생성 (Polymarket L2) ──
    private String buildHmacSignature(long timestamp, String method, String requestPath, String body) throws Exception {
        String message = timestamp + method + requestPath;
        if (body != null && !body.isEmpty()) {
            message += body;
        }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(
                java.util.Base64.getUrlDecoder().decode(apiSecret), "HmacSHA256");
        mac.init(key);
        byte[] hmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getUrlEncoder().encodeToString(hmac);
    }

    // ── L2 인증 헤더 빌더 ──
    private Request.Builder withL2Headers(Request.Builder builder, long timestamp, String signature) {
        String address = Keys.toChecksumAddress(Credentials.create(privateKey).getAddress());
        return builder
                .header("POLY_ADDRESS", address)
                .header("POLY_API_KEY", apiKey)
                .header("POLY_PASSPHRASE", passphrase)
                .header("POLY_TIMESTAMP", String.valueOf(timestamp))
                .header("POLY_SIGNATURE", signature);
    }

    /**
     * 주문 실행
     * @param tokenId  조건부 토큰 ID
     * @param amount   배팅 금액 (달러) - 최소 5토큰 제약으로 실제 금액은 다를 수 있음
     * @param price    토큰 가격 (0.01~0.99)
     * @param side     "BUY" 또는 "SELL"
     */
    public OrderResult placeOrder(String tokenId, double amount, double price, String side) {
        // 실제 토큰 수량 & USDC 계산 (대시보드 표시용)
        double actualSize = Math.max(MIN_SIZE, Math.floor((amount / price) * 100.0) / 100.0);
        double actualAmount = actualSize * price;

        if (dryRun) {
            log.info("🧪 [DRY-RUN] 주문 시뮬: {} ${} ({}토큰) @ {} ({})", side, fmt(actualAmount), fmt(actualSize), fmt(price), tokenId.substring(0, 8));
            return new OrderResult(true, "DRY-" + System.currentTimeMillis(), null, actualAmount, actualSize);
        }
        try {
            return executeLiveOrder(tokenId, amount, price, side);
        } catch (Exception e) {
            log.error("❌ LIVE 주문 실패: {}", e.getMessage());
            return new OrderResult(false, null, e.getMessage());
        }
    }

    private OrderResult executeLiveOrder(String tokenId, double amount, double price, String side) throws Exception {
        if (privateKey == null || privateKey.isEmpty()) {
            return new OrderResult(false, null, "Private key not configured");
        }

        Credentials credentials = Credentials.create(privateKey);
        String signer = Keys.toChecksumAddress(credentials.getAddress());
        String maker = (funder != null && !funder.isEmpty()) ? funder : signer;
        int sigType = (funder != null && !funder.isEmpty()) ? 1 : 0;

        // ── 금액 계산 (Python SDK 방식) ──
        // size = 토큰 수량, 최소 5개
        double size = Math.max(MIN_SIZE, Math.floor((amount / price) * 100.0) / 100.0);
        // BUY: makerAmount = USDC (size * price), takerAmount = 토큰 수
        long makerAmountRaw = (long) (size * price * 1e6);
        long takerAmountRaw = (long) (size * 1e6);

        BigInteger salt = BigInteger.valueOf(System.currentTimeMillis());
        BigInteger tokenIdBig = new BigInteger(tokenId);
        int sideInt = "BUY".equalsIgnoreCase(side) ? 0 : 1;

        BigInteger expiration = BigInteger.ZERO;
        BigInteger nonce = BigInteger.ZERO;
        BigInteger feeRate = BigInteger.valueOf(FEE_RATE_BPS);

        // ── EIP-712 서명 ──
        byte[] domainSep = buildDomainSeparator();
        byte[] orderHash = buildOrderHash(salt, maker, signer, tokenIdBig,
                BigInteger.valueOf(makerAmountRaw), BigInteger.valueOf(takerAmountRaw),
                expiration, nonce, feeRate, sideInt, sigType);

        byte[] digest = Hash.sha3(concat(new byte[]{0x19, 0x01}, domainSep, orderHash));
        Sign.SignatureData sig = Sign.signMessage(digest, credentials.getEcKeyPair(), false);
        String signature = Numeric.toHexStringNoPrefix(sig.getR())
                + Numeric.toHexStringNoPrefix(sig.getS())
                + String.format("%02x", sig.getV()[0]);

        // ── JSON 빌드 (Python SDK 동일 구조) ──
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
        payload.put("orderType", "GTC");

        // postOnly 필드 추가 (Python SDK 동일)
        payload.put("postOnly", false);

        String orderJson = objectMapper.writeValueAsString(payload);

        log.info("📤 주문 전송: {} {} 토큰 @ {} (${}) sigType={}", side, size, fmt(price), fmt(size * price), sigType);
        log.info("📋 ORDER JSON: {}", orderJson);

        // ── HMAC L2 서명 & 전송 ──
        long timestamp = System.currentTimeMillis() / 1000;
        String hmacSig = buildHmacSignature(timestamp, "POST", "/order", orderJson);

        Request request = withL2Headers(new Request.Builder(), timestamp, hmacSig)
                .url(CLOB + "/order")
                .post(RequestBody.create(orderJson, MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (resp.isSuccessful()) {
                JsonNode result = objectMapper.readTree(body);
                String orderId = result.path("orderID").asText("unknown");
                String status = result.path("status").asText("");
                double actualUsd = size * price;
                log.info("✅ LIVE 주문 성공: {} status={} ({}) ${} ({}tok)", orderId, status, side, fmt(actualUsd), fmt(size));
                return new OrderResult(true, orderId, null, actualUsd, size);
            } else {
                log.error("❌ LIVE 주문 거부: {} {}", resp.code(), body);
                return new OrderResult(false, null, body);
            }
        }
    }

    // ── Polymarket 실제 USDC 잔액 조회 ──
    public double fetchLiveBalance() {
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String requestPath = "/balance-allowance";
            int sigType = (funder != null && !funder.isEmpty()) ? 1 : 0;
            String fullUrl = CLOB + requestPath + "?asset_type=COLLATERAL&signature_type=" + sigType;

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

    // ── EIP-712 관련 ──
    private byte[] buildDomainSeparator() {
        return Hash.sha3(concat(
                DOMAIN_TYPE_HASH_BYTES,
                Hash.sha3("Polymarket CTF Exchange".getBytes(StandardCharsets.UTF_8)),
                Hash.sha3("1".getBytes(StandardCharsets.UTF_8)),
                Numeric.toBytesPadded(new BigInteger(CHAIN_ID), 32),
                Numeric.toBytesPadded(new BigInteger(Numeric.cleanHexPrefix(EXCHANGE_CONTRACT), 16), 32)
        ));
    }

    private byte[] buildOrderHash(BigInteger salt, String maker, String signer,
                                   BigInteger tokenId, BigInteger makerAmt, BigInteger takerAmt,
                                   BigInteger expiration, BigInteger nonce, BigInteger feeRate,
                                   int side, int sigType) {
        return Hash.sha3(concat(
                ORDER_TYPE_HASH_BYTES,
                Numeric.toBytesPadded(salt, 32),
                Numeric.toBytesPadded(new BigInteger(Numeric.cleanHexPrefix(maker), 16), 32),
                Numeric.toBytesPadded(new BigInteger(Numeric.cleanHexPrefix(signer), 16), 32),
                Numeric.toBytesPadded(BigInteger.ZERO, 32),  // taker = 0x0
                Numeric.toBytesPadded(tokenId, 32),
                Numeric.toBytesPadded(makerAmt, 32),
                Numeric.toBytesPadded(takerAmt, 32),
                Numeric.toBytesPadded(expiration, 32),
                Numeric.toBytesPadded(nonce, 32),
                Numeric.toBytesPadded(feeRate, 32),
                Numeric.toBytesPadded(BigInteger.valueOf(side), 32),
                Numeric.toBytesPadded(BigInteger.valueOf(sigType), 32)
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
