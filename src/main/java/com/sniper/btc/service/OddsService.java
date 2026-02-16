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
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ BTC 5M 전용 오즈 서비스
 *
 * 최적화:
 * - 하나의 마켓만 추적 (BTC 5M up/down)
 * - 500ms 캐시 (poly_bug: 1000ms)
 * - HTTP 타임아웃 2초
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

    @Value("${sniper.odds-cache-ttl-ms:500}")
    private long cacheTtlMs;

    @Value("${sniper.http-timeout-ms:2000}")
    private int httpTimeoutMs;

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
        if (cachedOdds != null && (System.currentTimeMillis() - cacheTime) < cacheTtlMs) {
            return cachedOdds;
        }
        return fetchFresh();
    }

    private MarketOdds fetchFresh() {
        long start = System.currentTimeMillis();
        try {
            // 1단계: Gamma에서 현재 BTC 5M 마켓 찾기
            String slug = buildSlug();
            String gammaUrl = GAMMA + "/events?slug=" + slug;

            Request req = new Request.Builder().url(gammaUrl)
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("Gamma API 실패: {}", resp.code());
                    return cachedOdds; // 이전 캐시 반환
                }

                JsonNode events = objectMapper.readTree(resp.body().string());
                if (!events.isArray() || events.isEmpty()) {
                    return cachedOdds;
                }

                JsonNode markets = events.get(0).path("markets");
                if (!markets.isArray() || markets.isEmpty()) {
                    return cachedOdds;
                }

                // "Up" 마켓 찾기
                for (JsonNode mkt : markets) {
                    String groupTitle = mkt.path("groupItemTitle").asText("");
                    if ("Up".equalsIgnoreCase(groupTitle)) {
                        String condId = mkt.path("conditionId").asText();
                        double upPrice = mkt.path("outcomePrices").isTextual()
                                ? parseFirstPrice(mkt.path("outcomePrices").asText())
                                : 0.5;

                        // token IDs
                        String tokens = mkt.path("clobTokenIds").asText("[]");
                        String[] tokenIds = parseTokenIds(tokens);

                        // 2단계: CLOB에서 정밀 오즈 가져오기 (더 정확)
                        MarketOdds clobOdds = fetchClobOdds(condId, tokenIds, start);
                        if (clobOdds != null) {
                            cachedOdds = clobOdds;
                            cacheTime = System.currentTimeMillis();
                            return clobOdds;
                        }

                        // fallback: Gamma 가격 사용
                        long elapsed = System.currentTimeMillis() - start;
                        MarketOdds odds = new MarketOdds(upPrice, 1.0 - upPrice,
                                condId, tokenIds.length > 0 ? tokenIds[0] : "",
                                tokenIds.length > 1 ? tokenIds[1] : "", elapsed);
                        cachedOdds = odds;
                        cacheTime = System.currentTimeMillis();
                        return odds;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("오즈 조회 실패: {}", e.getMessage());
        }
        return cachedOdds;
    }

    /**
     * CLOB 오더북에서 정밀 mid-price
     */
    private MarketOdds fetchClobOdds(String condId, String[] tokenIds, long startTime) {
        if (tokenIds.length < 2) return null;
        try {
            String url = CLOB + "/book?token_id=" + tokenIds[0];
            Request req = new Request.Builder().url(url).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonNode book = objectMapper.readTree(resp.body().string());

                double bestBid = 0, bestAsk = 1;
                JsonNode bids = book.path("bids");
                JsonNode asks = book.path("asks");

                if (bids.isArray() && !bids.isEmpty()) {
                    bestBid = bids.get(0).path("price").asDouble(0);
                }
                if (asks.isArray() && !asks.isEmpty()) {
                    bestAsk = asks.get(0).path("price").asDouble(1);
                }

                double midPrice = (bestBid + bestAsk) / 2.0;
                if (midPrice <= 0.01 || midPrice >= 0.99) return null;

                long elapsed = System.currentTimeMillis() - startTime;
                return new MarketOdds(midPrice, 1.0 - midPrice,
                        condId, tokenIds[0], tokenIds[1], elapsed);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 현재 시간 기반 BTC 5M 마켓 slug 생성
     * 형식: will-bitcoin-go-up-or-down-in-the-next-5-minutes-Mon-Feb-16-2026-1-45-PM-ET
     */
    private String buildSlug() {
        ZonedDateTime now = ZonedDateTime.now(ET);

        // 5분 단위 바닥
        int minute = now.getMinute();
        int slot = (minute / 5) * 5;
        ZonedDateTime slotTime = now.withMinute(slot).withSecond(0);

        String dow = slotTime.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String month = slotTime.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        int day = slotTime.getDayOfMonth();
        int year = slotTime.getYear();

        int hour12 = slotTime.getHour() % 12;
        if (hour12 == 0) hour12 = 12;
        String ampm = slotTime.getHour() < 12 ? "AM" : "PM";
        int min = slotTime.getMinute();

        return String.format("will-bitcoin-go-up-or-down-in-the-next-5-minutes-%s-%s-%d-%d-%d-%02d-%s-ET",
                dow, month, day, year, hour12, min, ampm);
    }

    private double parseFirstPrice(String pricesStr) {
        try {
            String cleaned = pricesStr.replaceAll("[\\[\\]\"]", "");
            String[] parts = cleaned.split(",");
            return Double.parseDouble(parts[0].trim());
        } catch (Exception e) {
            return 0.5;
        }
    }

    private String[] parseTokenIds(String tokensStr) {
        try {
            String cleaned = tokensStr.replaceAll("[\\[\\]\"]", "");
            return cleaned.split(",");
        } catch (Exception e) {
            return new String[]{};
        }
    }
}
