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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 5M 배팅 결과 자동 판정 — poly_bug TradeResultChecker 정합 버전
 *
 * 30초마다 실행, PENDING 중 5분 경과한 배팅을 Chainlink 종가로 판정
 * Chainlink 미수신 시 Binance API fallback (poly_bug 동일)
 *
 * 🔧 FIX:
 * - UTC fallback 잘못된 경계 조회 제거
 * - poly_bug 동일 epoch 300초 정규화
 * - Binance fallback 추가
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

            // Chainlink 종가 조회 (poly_bug 동일 로직)
            double closePrice = resolveClosePrice(trade);
            if (closePrice <= 0) {
                // 캔들 마감 후 2분 이상 → Binance fallback (poly_bug 동일)
                long minSinceClose = ChronoUnit.MINUTES.between(candleClose, now);
                if (minSinceClose >= 2) {
                    closePrice = fetchBinanceClose(trade.getCreatedAt());
                    if (closePrice > 0) {
                        log.warn("⚠️ Chainlink 종가 없음 → Binance fallback: ${}", fmt(closePrice));
                    }
                }
                if (closePrice <= 0) {
                    // 마감 후 7분+ → 현재가 최종 fallback
                    if (minSinceClose >= 7) {
                        closePrice = chainlink.getPrice();
                        if (closePrice <= 0) continue;
                        log.warn("⚠️ Binance도 실패 → 현재가 fallback: ${}", fmt(closePrice));
                    } else {
                        continue; // 아직 대기
                    }
                }
            }

            // 판정: 시초가 vs 종가 (poly_bug determineResult 동일)
            Double openPrice = trade.getOpenPrice();
            if (openPrice == null || openPrice <= 0) {
                // 시초가 없으면 Binance에서 복구 시도
                double binanceOpen = fetchBinanceOpen(trade.getCreatedAt());
                if (binanceOpen > 0) {
                    trade.setOpenPrice(binanceOpen);
                    openPrice = binanceOpen;
                    log.warn("⚠️ Trade #{} openPrice 없음 → Binance 복구: ${}", trade.getId(), fmt(binanceOpen));
                } else {
                    log.warn("⚠️ Trade #{} openPrice 복구 실패 — 판정 불가, 재시도 예정", trade.getId());
                    continue;
                }
            }

            boolean priceWentUp = closePrice > openPrice;
            boolean betOnUp = trade.getAction() == Trade.TradeAction.BUY_YES;
            boolean win = (betOnUp == priceWentUp);

            // 동가 처리: closePrice == openPrice → priceWentUp=false → UP 배팅 LOSE
            // poly_bug 동일 (strict greater than)

            trade.setResult(win ? Trade.TradeResult.WIN : Trade.TradeResult.LOSE);
            trade.setExitPrice(closePrice);
            trade.setResolvedAt(LocalDateTime.now());

            if (win) {
                double payout = trade.getBetAmount() / trade.getOdds();
                double pnl = payout - trade.getBetAmount();
                trade.setPnl(pnl);
                balanceService.addWinnings(trade.getBetAmount(), trade.getOdds());
                sniperScanner.recordWin();
                log.info("✅ WIN | {} @ ${} → ${} | +${} | 잔액 ${}",
                        trade.getAction(), fmt(trade.getOpenPrice()), fmt(closePrice),
                        fmt(pnl), fmt(balanceService.getBalance()));
            } else {
                trade.setPnl(-trade.getBetAmount());
                sniperScanner.recordLoss();
                log.info("❌ LOSE | {} @ ${} → ${} | -${} | 잔액 ${}",
                        trade.getAction(), fmt(trade.getOpenPrice()), fmt(closePrice),
                        fmt(trade.getBetAmount()), fmt(balanceService.getBalance()));
            }

            trade.setBalanceAfter(balanceService.getBalance());
            tradeRepository.save(trade);
        }
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
     * Chainlink 종가 조회 — poly_bug 동일 epoch 300초 정규화
     *
     * ChainlinkPriceService에서 캔들 전환 시 closeSnapshots에 저장하는 키:
     *   boundary = (chainlinkTsSec / 300) * 300  ← 새 캔들의 경계
     *
     * 따라서 여기서도 동일한 방식으로 경계를 계산해야 함
     */
    private double resolveClosePrice(Trade trade) {
        LocalDateTime created = trade.getCreatedAt();
        int minute = created.getMinute();
        int windowStart = (minute / 5) * 5;

        // 캔들 시작 시각 → epoch 변환 → 300초 배수 정규화 (poly_bug 동일)
        LocalDateTime candleStartLdt = created.truncatedTo(ChronoUnit.HOURS)
                .plusMinutes(windowStart);
        long startEpoch = candleStartLdt.atZone(ZoneId.systemDefault()).toEpochSecond();
        long boundaryTsSec = startEpoch - (startEpoch % 300); // UTC 기준 300초 배수

        // 다음 경계 = 종가 시점 (ChainlinkPriceService가 이 키로 저장)
        long nextBoundary = boundaryTsSec + 300;

        Double chainlinkClose = chainlink.getCloseAt(nextBoundary);
        if (chainlinkClose != null && chainlinkClose > 0) {
            log.debug("⛓ Chainlink 종가: ${} (boundary={})", chainlinkClose, nextBoundary);
            return chainlinkClose;
        }

        return 0;
    }

    /**
     * 🔧 FIX: Binance API fallback (poly_bug 동일)
     * Chainlink 종가 스냅샷이 없을 때 (서버 재시작 등) Binance에서 캔들 종가 조회
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
                // [0]=openTime, [1]=open, [2]=high, [3]=low, [4]=close
                return data.get(0).get(4).asDouble();
            }
        } catch (Exception e) {
            log.warn("Binance 종가 조회 실패: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 🔧 FIX: Binance에서 5M 캔들 시초가 복구 (openPrice null/0 안전장치)
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
                return data.get(0).get(1).asDouble(); // [1] = open
            }
        } catch (Exception e) {
            log.warn("Binance 시초가 조회 실패: {}", e.getMessage());
            return 0;
        }
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
