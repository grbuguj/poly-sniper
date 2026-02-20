package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ BTC 5M 전용 오즈 서비스 — 백그라운드 프리페치 방식
 *
 * 스캔 루프가 절대 HTTP 블로킹하지 않도록:
 * - 별도 스레드가 100ms마다 오즈를 HTTP 프리페치
 * - getOdds()는 항상 캐시만 읽어서 0ms 리턴
 * - slug 변경(새 5분봉) 시 즉시 감지하여 새 마켓 조회
 */
@Slf4j
@Service
public class OddsService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GAMMA = "https://gamma-api.polymarket.com";
    private static final String CLOB = "https://clob.polymarket.com";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // 캐시 (volatile — 스캔 스레드에서 안전하게 읽기)
    private volatile MarketOdds cachedOdds;
    private volatile long cacheTime;
    private volatile String cachedSlug = "";
    private volatile long lastFetchDurationMs;
    private volatile long lastClobSuccessTime = 0;  // CLOB 마지막 성공 시각
    private static final long CLOB_FRESHNESS_MS = 3000; // CLOB 3초 이내면 Gamma 무시

    // 프리페치 스레드
    private final ScheduledExecutorService prefetchExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "odds-prefetch");
                t.setDaemon(true);
                return t;
            });

    @Value("${sniper.odds-prefetch-interval-ms:100}")
    private long prefetchIntervalMs;

    public OddsService(@Value("${sniper.http-timeout-ms:2000}") int httpTimeoutMs) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(httpTimeoutMs, TimeUnit.MILLISECONDS)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS))
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

    @PostConstruct
    public void startPrefetch() {
        log.info("🔄 오즈 프리페치 시작 — {}ms 간격 (논블로킹)", prefetchIntervalMs);
        prefetchExecutor.scheduleAtFixedRate(this::prefetchOdds, 1000, prefetchIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stopPrefetch() {
        prefetchExecutor.shutdownNow();
        log.info("🛑 오즈 프리페치 종료");
    }

    /**
     * 스캔 루프에서 호출 — 항상 캐시만 리턴 (0ms, 절대 블로킹 없음)
     */
    public MarketOdds getOdds() {
        return cachedOdds;
    }

    /**
     * 마지막 HTTP 호출 소요시간
     */
    public long getLastFetchDurationMs() {
        return lastFetchDurationMs;
    }

    /**
     * 캐시 나이 (ms)
     */
    public long getCacheAgeMs() {
        return cacheTime > 0 ? System.currentTimeMillis() - cacheTime : -1;
    }

    // ==================== 백그라운드 프리페치 ====================

    /**
     * 백그라운드 스레드에서 100ms마다 실행
     * - slug 변경 감지 → 새 마켓 즉시 조회
     * - HTTP 실패해도 기존 캐시 유지
     */
    private void prefetchOdds() {
        try {
            String currentSlug = buildSlug();

            // slug 변경됐으면 (새 5분봉) 캐시 무효화
            if (!currentSlug.equals(cachedSlug)) {
                log.info("🔄 새 5분봉 감지 → 오즈 프리페치 slug={}", currentSlug);
                cachedOdds = null;
                cachedSlug = currentSlug;
                lastClobSuccessTime = 0; // 새 캔들 → CLOB 재조회 필요
            }

            fetchFresh(currentSlug);

        } catch (Exception e) {
            log.debug("프리페치 오류: {}", e.getMessage());
        }
    }

    private void fetchFresh(String slug) {
        long start = System.currentTimeMillis();
        try {
            // 1. Gamma events API
            String url = GAMMA + "/events?slug=" + slug;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return;

            JsonNode events = objectMapper.readTree(json);
            if (!events.isArray() || events.isEmpty()) {
                log.debug("events?slug={} → 결과 없음", slug);
                return;
            }

            JsonNode markets = events.get(0).path("markets");
            if (!markets.isArray() || markets.isEmpty()) return;

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
                    lastClobSuccessTime = System.currentTimeMillis();
                    lastFetchDurationMs = clobOdds.fetchTimeMs();
                    return;
                }
            }

            // 3. Gamma fallback 제거 — CLOB /book만 사용
            // Gamma outcomePrices는 실제 오더북과 괴리가 크므로 (50¢ vs 실제 60¢) 사용하지 않음
            if (cachedOdds == null) {
                log.debug("CLOB 오더북 조회 실패 — Gamma fallback 비활성 (정확도 우선)");
            }

        } catch (Exception e) {
            log.warn("오즈 프리페치 실패: {}", e.getMessage());
        }
    }

    /**
     * CLOB 오더북에서 정밀 가격 — /book 엔드포인트로 실제 best bid/ask 조회
     */
    private MarketOdds fetchClobOdds(String condId, String upTokenId, String downTokenId, long startTime) {
        try {
            // Up 토큰 오더북에서 best ask (BUY 시 매칭 대상)
            double upBestAsk = fetchBestAsk(upTokenId);
            double downBestAsk = fetchBestAsk(downTokenId);

            // best ask가 없으면 유동성 없음 → null
            if (upBestAsk <= 0 || downBestAsk <= 0) {
                log.warn("⚠️ CLOB 오더북 비어있음 — upAsk={} downAsk={}", upBestAsk, downBestAsk);
                return null;
            }

            // 유효성 검증
            if (upBestAsk <= 0.01 || upBestAsk >= 0.99) return null;
            if (downBestAsk <= 0.01 || downBestAsk >= 0.99) return null;

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("✅ 오즈(CLOB/book) Up ask {}¢ Down ask {}¢ | {}ms",
                    String.format("%.0f", upBestAsk * 100), String.format("%.0f", downBestAsk * 100), elapsed);
            return new MarketOdds(upBestAsk, downBestAsk, condId, upTokenId, downTokenId, elapsed);

        } catch (Exception e) {
            log.warn("CLOB 오더북 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * CLOB /book 에서 best ask (최저 매도호가) 추출
     * BUY 주문은 ask에 매칭되므로, 실제 체결 가능한 가격 = best ask
     */
    private double fetchBestAsk(String tokenId) {
        try {
            String url = CLOB + "/book?token_id=" + tokenId;
            String json = httpGet(url);
            if (json == null) return -1;

            JsonNode book = objectMapper.readTree(json);
            JsonNode asks = book.path("asks");
            if (!asks.isArray() || asks.isEmpty()) return -1;

            // asks는 높은가→낮은가 순서, 마지막이 best ask (최저가)
            double bestAsk = Double.MAX_VALUE;
            for (JsonNode ask : asks) {
                double price = ask.path("price").asDouble(0);
                double size = ask.path("size").asDouble(0);
                if (price > 0 && size >= 5.0 && price < bestAsk) { // 최소 5토큰 깊이 확인
                    bestAsk = price;
                }
            }
            return bestAsk < Double.MAX_VALUE ? bestAsk : -1;

        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * BTC 5M slug 생성 (poly_bug 검증 방식)
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
