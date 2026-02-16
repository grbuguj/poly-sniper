package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ BTC 5M 전용 오즈 서비스
 *
 * poly_bug PolymarketOddsService에서 검증된 slug 방식 사용:
 *   btc-updown-5m-{epochSecond}
 */
@Slf4j
@Service
public class OddsService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GAMMA = "https://gamma-api.polymarket.com";
    private static final String CLOB = "https://clob.polymarket.com";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // 캐시
    private volatile MarketOdds cachedOdds;
    private volatile long cacheTime;
    private volatile String cachedSlug = ""; // slug 변경 감지

    @Value("${sniper.odds-cache-ttl-ms:500}")
    private long cacheTtlMs;

    public OddsService(@Value("${sniper.http-timeout-ms:2000}") int httpTimeoutMs) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public record MarketOdds(
            double upOdds,
            double downOdds,
            String marketId,
            String upTokenId,
            String downTokenId,
            long fetchTimeMs
    ) {}

    /**
     * BTC 5M 오즈 조회 (캐시 우선)
     */
    public MarketOdds getOdds() {
        String currentSlug = buildSlug();

        // slug가 변경됐으면 (새 5분봉) 캐시 무효화
        if (!currentSlug.equals(cachedSlug)) {
            cachedOdds = null;
            cachedSlug = currentSlug;
        }

        if (cachedOdds != null && (System.currentTimeMillis() - cacheTime) < cacheTtlMs) {
            return cachedOdds;
        }
        return fetchFresh(currentSlug);
    }

    private MarketOdds fetchFresh(String slug) {
        long start = System.currentTimeMillis();
        try {
            // 1. Gamma events API
            String url = GAMMA + "/events?slug=" + slug;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return cachedOdds;

            JsonNode events = objectMapper.readTree(json);
            if (!events.isArray() || events.isEmpty()) {
                log.debug("events?slug={} → 결과 없음", slug);
                return cachedOdds;
            }

            JsonNode markets = events.get(0).path("markets");
            if (!markets.isArray() || markets.isEmpty()) return cachedOdds;

            // 5M은 단일 마켓 — markets[0]
            JsonNode mkt = markets.get(0);
            String condId = mkt.path("conditionId").asText("unknown");

            // 토큰 ID 파싱
            String upTokenId = null, downTokenId = null;
            String tokenStr = mkt.path("clobTokenIds").asText("[]");
            try {
                JsonNode tokens = objectMapper.readTree(tokenStr);
                if (tokens.isArray() && tokens.size() >= 2) {
                    upTokenId = tokens.get(0).asText();
                    downTokenId = tokens.get(1).asText();
                }
            } catch (Exception ignored) {}

            // 2. CLOB에서 정밀 오즈 (우선)
            if (upTokenId != null) {
                MarketOdds clobOdds = fetchClobOdds(condId, upTokenId, downTokenId, start);
                if (clobOdds != null) {
                    cachedOdds = clobOdds;
                    cacheTime = System.currentTimeMillis();
                    return clobOdds;
                }
            }

            // 3. Gamma outcomePrices fallback
            String pricesStr = mkt.path("outcomePrices").asText("");
            if (!pricesStr.isBlank()) {
                JsonNode prices = objectMapper.readTree(pricesStr);
                if (prices.isArray() && prices.size() >= 2) {
                    double up = prices.get(0).asDouble(0.5);
                    double down = prices.get(1).asDouble(0.5);
                    long elapsed = System.currentTimeMillis() - start;
                    MarketOdds odds = new MarketOdds(up, down, condId,
                            upTokenId != null ? upTokenId : "",
                            downTokenId != null ? downTokenId : "", elapsed);
                    cachedOdds = odds;
                    cacheTime = System.currentTimeMillis();
                    log.info("✅ 오즈(Gamma) Up {}¢ Down {}¢ | {} | {}ms",
                            String.format("%.0f", up * 100), String.format("%.0f", down * 100),
                            slug, elapsed);
                    return odds;
                }
            }

            return cachedOdds;

        } catch (Exception e) {
            log.warn("오즈 조회 실패: {}", e.getMessage());
            return cachedOdds;
        }
    }

    /**
     * CLOB 오더북에서 정밀 가격
     */
    private MarketOdds fetchClobOdds(String condId, String upTokenId, String downTokenId, long startTime) {
        try {
            // Up 가격 (CLOB price API)
            String upUrl = CLOB + "/price?token_id=" + upTokenId + "&side=BUY";
            String upJson = httpGet(upUrl);
            double upOdds = 0.5;
            if (upJson != null) {
                JsonNode node = objectMapper.readTree(upJson);
                upOdds = node.path("price").asDouble(0.5);
            }

            // Down 가격
            double downOdds = 1.0 - upOdds;
            if (downTokenId != null) {
                try {
                    String downUrl = CLOB + "/price?token_id=" + downTokenId + "&side=BUY";
                    String downJson = httpGet(downUrl);
                    if (downJson != null) {
                        JsonNode node = objectMapper.readTree(downJson);
                        double d = node.path("price").asDouble(-1);
                        if (d > 0) downOdds = d;
                    }
                } catch (Exception ignored) {}
            }

            if (upOdds <= 0.01 || upOdds >= 0.99) return null;

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("✅ 오즈(CLOB) Up {}¢ Down {}¢ | {}ms",
                    String.format("%.1f", upOdds * 100), String.format("%.1f", downOdds * 100), elapsed);
            return new MarketOdds(upOdds, downOdds, condId, upTokenId, downTokenId, elapsed);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * BTC 5M slug 생성 (poly_bug 검증 방식)
     * 형식: btc-updown-5m-{epochSecond}
     */
    String buildSlug() {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int minute = nowET.getMinute();
        int windowStart = (minute / 5) * 5;
        ZonedDateTime windowStartTime = nowET.withMinute(windowStart).withSecond(0).withNano(0);
        long epochSecond = windowStartTime.toEpochSecond();
        return String.format("btc-updown-5m-%d", epochSecond);
    }

    private String httpGet(String url) {
        try {
            Request req = new Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .build();
            try (Response res = httpClient.newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) return null;
                return res.body().string();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
