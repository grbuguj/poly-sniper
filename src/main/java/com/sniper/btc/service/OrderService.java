package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Polymarket CLOB 주문 서비스 (LIVE 모드 전용)
 *
 * poly_bug의 PolymarketOrderService와 동일 EIP-712 서명
 */
@Slf4j
@Service
public class OrderService {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
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

    @Value("${sniper.dry-run:true}")
    private boolean dryRun;

    private static final String CLOB = "https://clob.polymarket.com";
    private static final String CHAIN_ID = "137";
    private static final String EXCHANGE_CONTRACT = "0x4bFb41d5B3570DeFd03C39a9A4D8dE6Bd8B8982E";

    private static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,address taker,uint256 tokenId,uint256 makerAmount,uint256 takerAmount,uint256 expiration,uint256 nonce,uint256 feeRateBps,uint8 side,uint8 signatureType)";
    private static final byte[] ORDER_TYPE_HASH_BYTES = Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
    private static final byte[] DOMAIN_TYPE_HASH_BYTES = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.UTF_8));

    public record OrderResult(boolean success, String orderId, String error) {}

    /**
     * 주문 실행
     * @return DRY-RUN이면 시뮬레이션, LIVE면 실제 주문
     */
    public OrderResult placeOrder(String tokenId, double amount, double price, String side) {
        if (dryRun) {
            log.info("🧪 [DRY-RUN] 주문 시뮬: {} ${} @ {} ({})", side, fmt(amount), fmt(price), tokenId.substring(0, 8));
            return new OrderResult(true, "DRY-" + System.currentTimeMillis(), null);
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
        String maker = credentials.getAddress();
        String signer = maker;

        BigInteger salt = BigInteger.valueOf(System.currentTimeMillis());
        BigInteger tokenIdBig = new BigInteger(tokenId);
        int sideInt = "BUY".equalsIgnoreCase(side) ? 0 : 1;

        long makerAmountRaw = (long) (amount * 1e6);
        long takerAmountRaw = (long) ((amount / price) * 1e6);

        BigInteger expiration = BigInteger.ZERO;
        BigInteger nonce = BigInteger.ZERO;
        BigInteger feeRateBps = BigInteger.ZERO;

        // EIP-712 서명
        byte[] domainSep = buildDomainSeparator();
        byte[] orderHash = buildOrderHash(salt, maker, signer, tokenIdBig,
                BigInteger.valueOf(makerAmountRaw), BigInteger.valueOf(takerAmountRaw),
                expiration, nonce, feeRateBps, sideInt);

        byte[] digest = Hash.sha3(concat(new byte[]{0x19, 0x01}, domainSep, orderHash));
        Sign.SignatureData sig = Sign.signMessage(digest, credentials.getEcKeyPair(), false);
        String signature = Numeric.toHexStringNoPrefix(sig.getR())
                + Numeric.toHexStringNoPrefix(sig.getS())
                + String.format("%02x", sig.getV()[0]);

        // 주문 JSON
        String orderJson = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("salt", salt.toString());
            put("maker", maker);
            put("signer", signer);
            put("taker", "0x0000000000000000000000000000000000000000");
            put("tokenId", tokenId);
            put("makerAmount", String.valueOf(makerAmountRaw));
            put("takerAmount", String.valueOf(takerAmountRaw));
            put("expiration", "0");
            put("nonce", "0");
            put("feeRateBps", "0");
            put("side", side.toUpperCase());
            put("signatureType", 0);
            put("signature", "0x" + signature);
        }});

        long timestamp = System.currentTimeMillis() / 1000;
        String bodyForSig = "POST" + timestamp + "/order" + orderJson;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                java.util.Base64.getDecoder().decode(apiSecret), "HmacSHA256");
        mac.init(secretKey);
        byte[] hmacSig = mac.doFinal(bodyForSig.getBytes(StandardCharsets.UTF_8));

        Request request = new Request.Builder()
                .url(CLOB + "/order")
                .post(RequestBody.create(orderJson, MediaType.parse("application/json")))
                .header("POLY_API_KEY", apiKey)
                .header("POLY_PASSPHRASE", passphrase)
                .header("POLY_TIMESTAMP", String.valueOf(timestamp))
                .header("POLY_SIGNATURE", java.util.Base64.getEncoder().encodeToString(hmacSig))
                .build();

        try (Response resp = httpClient.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (resp.isSuccessful()) {
                JsonNode result = objectMapper.readTree(body);
                String orderId = result.path("orderID").asText("unknown");
                log.info("✅ LIVE 주문 성공: {} ({})", orderId, side);
                return new OrderResult(true, orderId, null);
            } else {
                log.error("❌ LIVE 주문 거부: {} {}", resp.code(), body);
                return new OrderResult(false, null, body);
            }
        }
    }

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
                                   BigInteger expiration, BigInteger nonce, BigInteger feeRate, int side) {
        return Hash.sha3(concat(
                ORDER_TYPE_HASH_BYTES,
                Numeric.toBytesPadded(salt, 32),
                Numeric.toBytesPadded(new BigInteger(Numeric.cleanHexPrefix(maker), 16), 32),
                Numeric.toBytesPadded(new BigInteger(Numeric.cleanHexPrefix(signer), 16), 32),
                Numeric.toBytesPadded(BigInteger.ZERO, 32), // taker
                Numeric.toBytesPadded(tokenId, 32),
                Numeric.toBytesPadded(makerAmt, 32),
                Numeric.toBytesPadded(takerAmt, 32),
                Numeric.toBytesPadded(expiration, 32),
                Numeric.toBytesPadded(nonce, 32),
                Numeric.toBytesPadded(feeRate, 32),
                Numeric.toBytesPadded(BigInteger.valueOf(side), 32),
                Numeric.toBytesPadded(BigInteger.ZERO, 32) // signatureType
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

    private String fmt(double v) { return String.format("%.2f", v); }
}
