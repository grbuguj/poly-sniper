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
    private final RedeemService redeemService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GAMMA = "https://gamma-api.polymarket.com";
    private static final String CLOB = "https://clob.polymarket.com";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Polymarket 정산 결과
    private record MarketResolution(boolean yesWon, boolean resolved) {}

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
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
            // SECONDARY: 잔액 변동 판정 (Gamma API 실패 시)
            //
            // 🔧 FIX: auto-redeem이 없으면 WIN해도 잔액이 즉시 안 올라감
            //   → 잔액 증가 시에만 WIN 판정 (확실한 경우)
            //   → 잔액 미변동은 LOSE로 판정하지 않음 (auto-redeem 미지원)
            //   → LOSE 판정은 Gamma API에서만
            // ============================================================
            if (minSinceClose >= 2) {
                Double balAtBet = trade.getBalanceAtBet();
                if (balAtBet != null && balAtBet > 0) {
                    double currentLive = balanceService.getLiveBalance();
                    double diff = currentLive - balAtBet;
                    double expectedPayout = trade.getBetAmount() / trade.getOdds();

                    if (diff > expectedPayout * 0.5) {
                        // 잔액이 payout의 50%+ 증가 → WIN (확실)
                        double displayClose = resolveDisplayClosePrice(trade, minSinceClose);
                        log.info("💰 잔액 변동 WIN 감지: ${} → ${} (+${}, payout ${})",
                                fmt(balAtBet), fmt(currentLive), fmt(diff), fmt(expectedPayout));
                        applyResult(trade, true, displayClose > 0 ? displayClose : 0, "BALANCE_CHANGE");
                        continue;
                    }

                    // 🔧 잔액 미변동 → LOSE 판정 안 함 (auto-redeem 미지원)
                    // Gamma API 정산 결과만 기다림
                }
            }

            // ============================================================
            // TIMEOUT: 20분 초과 → CANCELLED
            // 🔧 FIX: 15분 → 20분 (auto-redeem 없이 Gamma API만 사용하므로 여유 확보)
            // ============================================================
            if (minSinceClose >= 20) {
                // 20분 초과: Polymarket 정산 불가 → CANCELLED 처리
                log.warn("⚠️ Trade #{} Polymarket 정산 20분 초과 → CANCELLED 처리", trade.getId());
                trade.setResult(Trade.TradeResult.CANCELLED);
                trade.setResolvedAt(LocalDateTime.now());
                trade.setPnl(0);
                // 배팅액 환불 (Polymarket에서 실제 환불됨)
                balanceService.addWinnings(trade.getBetAmount(), 1.0); // 원금 복구
                trade.setBalanceAfter(balanceService.getBalance());
                tradeRepository.save(trade);
                log.info("🔄 Trade #{} CANCELLED — 배팅액 ${} 환불", trade.getId(), fmt(trade.getBetAmount()));
                continue;
            }

            // 아직 대기 중 — Polymarket 정산만 기다림 (대부분 1~3분 내 완료)
            if (minSinceClose >= 1) {
                log.info("⏳ Trade #{} Polymarket 정산 대기 중 ({}분 경과, 최대 20분)", trade.getId(), minSinceClose);
            }
        }
    }

    /**
     * Polymarket Gamma API에서 마켓 정산 결과 조회
     *
     * 🔧 FIX: slug 역산 → conditionId 직접 조회로 변경
     *   - slug 역산은 시간대/에포크 오차로 실패할 수 있음
     *   - conditionId는 배팅 시 정확히 저장되므로 100% 매칭
     *
     * 조회 순서:
     *   1. condition_id로 /markets 직접 조회 (PRIMARY)
     *   2. slug 역산 fallback (conditionId 없는 과거 Trade용)
     */
    private MarketResolution queryPolymarketResolution(Trade trade) {
        // 1차: conditionId 직접 조회
        String conditionId = trade.getMarketId();
        if (conditionId != null && !conditionId.isBlank() && !"unknown".equals(conditionId)) {
            MarketResolution result = queryByConditionId(conditionId);
            if (result != null) return result;
        }

        // 2차: slug 역산 fallback (과거 Trade 호환)
        return queryBySlug(trade);
    }

    /**
     * conditionId로 CLOB API 직접 조회 — 가장 정확한 방법
     *
     * 🔧 FIX: Gamma /markets?condition_id= 는 필터링이 안 됨 (전체 마켓 리턴)
     *   → CLOB /markets/<conditionId> 사용 (conditionId로 직접 조회, 정확한 정산 결과)
     */
    private MarketResolution queryByConditionId(String conditionId) {
        try {
            String url = CLOB + "/markets/" + conditionId;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return null;

            JsonNode mkt = objectMapper.readTree(json);

            // 에러 응답 체크
            if (mkt.has("error") || mkt.has("type")) {
                log.debug("CLOB 마켓 조회 실패: {}", conditionId.substring(0, Math.min(10, conditionId.length())));
                return null;
            }

            // closed 확인
            boolean closed = mkt.path("closed").asBoolean(false);
            if (!closed) return new MarketResolution(false, false); // 아직 미정산

            // tokens 배열에서 winner 확인
            JsonNode tokens = mkt.path("tokens");
            if (!tokens.isArray() || tokens.isEmpty()) return null;

            for (JsonNode token : tokens) {
                boolean winner = token.path("winner").asBoolean(false);
                if (winner) {
                    String outcome = token.path("outcome").asText("");
                    boolean yesWon = outcome.equalsIgnoreCase("Yes")
                            || outcome.equalsIgnoreCase("Up");
                    log.info("✅ CLOB 정산: {} 승리 (conditionId={})", outcome,
                            conditionId.substring(0, Math.min(10, conditionId.length())));
                    return new MarketResolution(yesWon, true);
                }
            }

            // closed=true + winner 없음 → 아직 정산 처리중
            log.debug("CLOB closed=true but no winner yet: {}", conditionId.substring(0, Math.min(10, conditionId.length())));
            return null;
        } catch (Exception e) {
            log.warn("CLOB conditionId 정산 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * slug 역산 fallback (과거 Trade 호환)
     */
    private MarketResolution queryBySlug(Trade trade) {
        try {
            String slug = buildSlugForTrade(trade.getCreatedAt());
            String url = GAMMA + "/events?slug=" + slug;
            String json = httpGet(url);
            if (json == null || json.isBlank()) return null;

            JsonNode events = objectMapper.readTree(json);
            if (!events.isArray() || events.isEmpty()) return null;

            JsonNode markets = events.get(0).path("markets");
            if (!markets.isArray() || markets.isEmpty()) return null;

            return parseMarketResolution(markets.get(0), "slug=" + slug);
        } catch (Exception e) {
            log.warn("slug 기반 정산 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 마켓 JSON에서 정산 결과 파싱 (공통 로직)
     */
    private MarketResolution parseMarketResolution(JsonNode mkt, String source) {
        // 마켓 종료 여부 확인
        boolean closed = mkt.path("closed").asBoolean(false);
        if (!closed) return new MarketResolution(false, false); // 아직 미정산

        // tokens 파싱 (JSON 배열 또는 문자열)
        JsonNode tokens = mkt.path("tokens");
        if (tokens.isTextual()) {
            try {
                tokens = objectMapper.readTree(tokens.asText("[]"));
            } catch (Exception e) {
                return null;
            }
        }

        if (!tokens.isArray() || tokens.isEmpty()) {
            log.debug("tokens 배열 비어있음: {}", source);
            return null;
        }

        // winner 필드 확인
        for (JsonNode token : tokens) {
            boolean winner = token.path("winner").asBoolean(false);
            if (winner) {
                String outcome = token.path("outcome").asText("");
                boolean yesWon = outcome.equalsIgnoreCase("Yes")
                        || outcome.equalsIgnoreCase("Up");
                log.info("✅ Polymarket 정산: {} 승리 ({})", outcome, source);
                return new MarketResolution(yesWon, true);
            }
        }

        // closed=true + winner 없음 → outcomePrices fallback
        // 일부 마켓은 winner 필드 대신 outcomePrices로 판별 가능
        String pricesStr = mkt.path("outcomePrices").asText("");
        if (!pricesStr.isBlank()) {
            try {
                JsonNode prices = objectMapper.readTree(pricesStr);
                if (prices.isArray() && prices.size() >= 2) {
                    double upPrice = prices.get(0).asDouble(0.5);
                    double downPrice = prices.get(1).asDouble(0.5);
                    // 정산 후 가격이 0 또는 1이면 확정
                    if (upPrice >= 0.99) {
                        log.info("✅ Polymarket 정산 (outcomePrices): Up 승리 ({})", source);
                        return new MarketResolution(true, true);
                    }
                    if (downPrice >= 0.99) {
                        log.info("✅ Polymarket 정산 (outcomePrices): Down 승리 ({})", source);
                        return new MarketResolution(false, true);
                    }
                }
            } catch (Exception ignored) {}
        }

        log.debug("마켓 closed=true지만 winner 미확정: {}", source);
        return null;
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
            // 🔧 FIX: 실제 체결 토큰 수 기반 PnL (슬리피지 반영)
            // WIN 시 각 토큰 = $1 → payout = actualSize
            Double actualSize = trade.getActualSize();
            double payout;
            if (actualSize != null && actualSize > 0) {
                payout = actualSize; // 실제 토큰 수 × $1
            } else {
                payout = trade.getBetAmount() / trade.getOdds(); // fallback: 이론값
            }
            double pnl = payout - trade.getBetAmount();
            trade.setPnl(pnl);
            balanceService.addWinnings(trade.getBetAmount(), trade.getOdds());
            sniperScanner.recordWin();

            // 🔧 자동 Redeem: CTF 포지션 → USDC 전환
            String conditionId = trade.getMarketId();
            if (redeemService.isConfigured() && conditionId != null && !conditionId.isBlank()) {
                redeemService.redeemAsync(conditionId, false).thenAccept(result -> {
                    if (result.isSuccess()) {
                        log.info("💰 Auto-Redeem 성공: {} → 잔액 곧 반영", result.txHash());
                    } else if (result.isNoBalance()) {
                        log.info("📭 이미 Redeem 완료이거나 잔액 없음");
                    } else {
                        log.warn("⚠️ Auto-Redeem 실패: {} — 수동 확인 필요", result.message());
                    }
                });
            }
            // Redeem 후 잔액 반영 폴링
            balanceService.startRedeemPolling(payout);

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
