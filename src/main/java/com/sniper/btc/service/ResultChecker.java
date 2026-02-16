package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 5M 배팅 결과 자동 판정 — Polymarket 실제 정산 기준
 *
 * 🔧 CRITICAL FIX: Chainlink 자체 판정 → Polymarket 정산 결과 사용
 *   - Chainlink 종가 ≠ Polymarket 오라클 종가 (서로 다른 소스)
 *   - 실제 돈은 Polymarket 기준으로 정산되므로 Polymarket API 결과를 사용해야 함
 *
 * 판정 순서:
 *   1. Polymarket Gamma API → tokens[].winner (PRIMARY)
 *   2. 10분 초과 & Polymarket 실패 → Chainlink/Binance fallback (LAST RESORT)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultChecker {

    private final TradeRepository tradeRepository;
    private final ChainlinkPriceService chainlink;
    private final BalanceService balanceService;
    private final SniperScanner sniperScanner;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GAMMA = "https://gamma-api.polymarket.com";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Polymarket 정산 결과
    private record MarketResolution(boolean yesWon, boolean resolved) {}

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void checkPending() {
        List<Trade> pending = tradeRepository.findByResultOrderByCreatedAtDesc(Trade.TradeResult.PENDING);
        if (pending.isEmpty()) return;

        for (Trade trade : pending) {
            if (trade.getAction() == Trade.TradeAction.HOLD) continue;

            // 캔들 마감 시각 계산
            LocalDateTime candleClose = calcCandleClose(trade.getCreatedAt());
            LocalDateTime now = LocalDateTime.now();

            // 캔들 마감 전이면 대기
            if (now.isBefore(candleClose)) continue;

            long minSinceClose = ChronoUnit.MINUTES.between(candleClose, now);

            // ============================================================
            // PRIMARY: Polymarket 실제 정산 결과 조회
            // ============================================================
            MarketResolution resolution = queryPolymarketResolution(trade);

            if (resolution != null && resolution.resolved()) {
                // ✅ Polymarket 정산 완료 — 실제 결과 사용
                boolean betOnYes = trade.getAction() == Trade.TradeAction.BUY_YES;
                boolean win = (betOnYes == resolution.yesWon());

                // exitPrice: Chainlink/Binance 참고용 (표시 목적)
                double displayClosePrice = resolveDisplayClosePrice(trade, minSinceClose);

                applyResult(trade, win, displayClosePrice, "POLYMARKET");
                continue;
            }

            // ============================================================
            // FALLBACK: 10분 초과 & Polymarket 실패 → Chainlink/Binance
            // ============================================================
            if (minSinceClose >= 10) {
                log.warn("⚠️ Trade #{} Polymarket 정산 조회 실패 ({}분 경과) → Chainlink fallback",
                        trade.getId(), minSinceClose);

                double closePrice = resolveDisplayClosePrice(trade, minSinceClose);
                if (closePrice <= 0) continue;

                Double openPrice = trade.getOpenPrice();
                if (openPrice == null || openPrice <= 0) {
                    double binanceOpen = fetchBinanceOpen(trade.getCreatedAt());
                    if (binanceOpen > 0) {
                        trade.setOpenPrice(binanceOpen);
                        openPrice = binanceOpen;
                    } else {
                        continue;
                    }
                }

                boolean priceWentUp = closePrice > openPrice;
                boolean betOnUp = trade.getAction() == Trade.TradeAction.BUY_YES;
                boolean win = (betOnUp == priceWentUp);

                applyResult(trade, win, closePrice, "CHAINLINK_FALLBACK");
                continue;
            }

            // 아직 대기 중
            if (minSinceClose >= 2 && minSinceClose % 2 == 0) {
                log.debug("⏳ Trade #{} 정산 대기 중 ({}분 경과)", trade.getId(), minSinceClose);
            }
        }
    }

    /**
     * Polymarket Gamma API에서 마켓 정산 결과 조회
     * tokens[].winner 필드로 실제 승자 확인
     */
    private MarketResolution queryPolymarketResolution(Trade trade) {
        try {
            String slug = buildSlugForTrade(trade.getCreatedAt());
            String url = GAMMA + "/events?slug=" + slug;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return null;

            JsonNode events = objectMapper.readTree(json);
            if (!events.isArray() || events.isEmpty()) return null;

            JsonNode markets = events.get(0).path("markets");
            if (!markets.isArray() || markets.isEmpty()) return null;

            JsonNode mkt = markets.get(0);

            // 마켓 종료 여부 확인
            boolean closed = mkt.path("closed").asBoolean(false);
            if (!closed) return new MarketResolution(false, false); // 아직 미정산

            // tokens 파싱 (JSON 배열 또는 문자열)
            JsonNode tokens = mkt.path("tokens");
            if (tokens.isTextual()) {
                tokens = objectMapper.readTree(tokens.asText("[]"));
            }

            if (!tokens.isArray() || tokens.isEmpty()) {
                log.debug("tokens 배열 비어있음: slug={}", slug);
                return null;
            }

            // winner 필드 확인
            for (JsonNode token : tokens) {
                boolean winner = token.path("winner").asBoolean(false);
                if (winner) {
                    String outcome = token.path("outcome").asText("");
                    boolean yesWon = outcome.equalsIgnoreCase("Yes")
                            || outcome.equalsIgnoreCase("Up");
                    log.info("✅ Polymarket 정산: {} 승리 (slug={})", outcome, slug);
                    return new MarketResolution(yesWon, true);
                }
            }

            // closed=true인데 winner가 없으면 아직 정산 중일 수 있음
            log.debug("마켓 closed=true지만 winner 미확정: slug={}", slug);
            return null;

        } catch (Exception e) {
            log.warn("Polymarket 정산 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Trade의 createdAt으로 Polymarket slug 역산
     * OddsService.buildSlug()와 동일한 로직
     */
    private String buildSlugForTrade(LocalDateTime createdAt) {
        ZonedDateTime etTime = createdAt.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ET);
        int minute = etTime.getMinute();
        int windowStart = (minute / 5) * 5;
        ZonedDateTime windowStartTime = etTime.withMinute(windowStart)
                .withSecond(0).withNano(0);
        long epochSecond = windowStartTime.toEpochSecond();
        return String.format("btc-updown-5m-%d", epochSecond);
    }

    /**
     * 결과 적용 (WIN/LOSE → DB 저장)
     */
    private void applyResult(Trade trade, boolean win, double exitPrice, String source) {
        trade.setResult(win ? Trade.TradeResult.WIN : Trade.TradeResult.LOSE);
        trade.setExitPrice(exitPrice);
        trade.setResolvedAt(LocalDateTime.now());

        if (win) {
            double payout = trade.getBetAmount() / trade.getOdds();
            double pnl = payout - trade.getBetAmount();
            trade.setPnl(pnl);
            balanceService.addWinnings(trade.getBetAmount(), trade.getOdds());
            sniperScanner.recordWin();
            log.info("✅ WIN [{}] | {} @ ${} → ${} | +${} | 잔액 ${}",
                    source, trade.getAction(),
                    fmt(trade.getOpenPrice()), fmt(exitPrice),
                    fmt(pnl), fmt(balanceService.getBalance()));
        } else {
            trade.setPnl(-trade.getBetAmount());
            sniperScanner.recordLoss();
            log.info("❌ LOSE [{}] | {} @ ${} → ${} | -${} | 잔액 ${}",
                    source, trade.getAction(),
                    fmt(trade.getOpenPrice()), fmt(exitPrice),
                    fmt(trade.getBetAmount()), fmt(balanceService.getBalance()));
        }

        trade.setBalanceAfter(balanceService.getBalance());
        tradeRepository.save(trade);
    }

    /**
     * 표시용 종가 조회 (Chainlink → Binance fallback)
     * WIN/LOSE 판정에는 사용하지 않음 — 대시보드 표시 전용
     */
    private double resolveDisplayClosePrice(Trade trade, long minSinceClose) {
        // Chainlink 스냅샷
        double chainlinkClose = resolveChainlinkClose(trade);
        if (chainlinkClose > 0) return chainlinkClose;

        // Binance fallback
        if (minSinceClose >= 2) {
            double binanceClose = fetchBinanceClose(trade.getCreatedAt());
            if (binanceClose > 0) return binanceClose;
        }

        // 현재가 최종 fallback
        if (minSinceClose >= 5) {
            return chainlink.getPrice();
        }

        return 0;
    }

    /**
     * 배팅이 속한 5M 캔들의 마감 시각 계산
     */
    private LocalDateTime calcCandleClose(LocalDateTime createdAt) {
        int minute = createdAt.getMinute();
        int windowStart = (minute / 5) * 5;
        return createdAt.truncatedTo(ChronoUnit.HOURS)
                .plusMinutes(windowStart + 5);
    }

    /**
     * Chainlink 종가 조회 — epoch 300초 정규화
     */
    private double resolveChainlinkClose(Trade trade) {
        LocalDateTime created = trade.getCreatedAt();
        int minute = created.getMinute();
        int windowStart = (minute / 5) * 5;

        LocalDateTime candleStartLdt = created.truncatedTo(ChronoUnit.HOURS)
                .plusMinutes(windowStart);
        long startEpoch = candleStartLdt.atZone(ZoneId.systemDefault()).toEpochSecond();
        long boundaryTsSec = startEpoch - (startEpoch % 300);
        long nextBoundary = boundaryTsSec + 300;

        Double chainlinkClose = chainlink.getCloseAt(nextBoundary);
        if (chainlinkClose != null && chainlinkClose > 0) {
            return chainlinkClose;
        }
        return 0;
    }

    /**
     * Binance API fallback — 캔들 종가
     */
    private double fetchBinanceClose(LocalDateTime tradeTime) {
        try {
            int minute = tradeTime.getMinute();
            int windowStart = (minute / 5) * 5;
            LocalDateTime candleStart = tradeTime.truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes(windowStart);
            long startMs = candleStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            String url = String.format(
                    "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=5m&startTime=%d&limit=1",
                    startMs);

            Request req = new Request.Builder().url(url).get().build();
            try (Response res = httpClient.newCall(req).execute()) {
                if (res.body() == null) return 0;
                JsonNode data = objectMapper.readTree(res.body().string());
                if (!data.isArray() || data.isEmpty()) return 0;
                return data.get(0).get(4).asDouble();
            }
        } catch (Exception e) {
            log.warn("Binance 종가 조회 실패: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Binance에서 5M 캔들 시초가 복구
     */
    private double fetchBinanceOpen(LocalDateTime tradeTime) {
        try {
            int minute = tradeTime.getMinute();
            int windowStart = (minute / 5) * 5;
            LocalDateTime candleStart = tradeTime.truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes(windowStart);
            long startMs = candleStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            String url = String.format(
                    "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=5m&startTime=%d&limit=1",
                    startMs);

            Request req = new Request.Builder().url(url).get().build();
            try (Response res = httpClient.newCall(req).execute()) {
                if (res.body() == null) return 0;
                JsonNode data = objectMapper.readTree(res.body().string());
                if (!data.isArray() || data.isEmpty()) return 0;
                return data.get(0).get(1).asDouble();
            }
        } catch (Exception e) {
            log.warn("Binance 시초가 조회 실패: {}", e.getMessage());
            return 0;
        }
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

    private String fmt(double v) { return String.format("%.2f", v); }
    private String fmt(Double v) { return v != null ? String.format("%.2f", v) : "N/A"; }
}
