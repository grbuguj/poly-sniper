package com.sniper.btc.service;

import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * ⚡ BTC 5M 전용 스나이퍼 — 500ms 메인루프
 *
 * poly_bug의 OddsGapScanner에서 BTC 5M만 추출 + 극한 최적화
 *
 * 속도 최적화 포인트:
 * 1. 스캔 대상: 1개 (BTC 5M) — poly_bug: 12개
 * 2. 외부 API: Chainlink WS + Polymarket HTTP 2개만
 * 3. Claude AI: 제거 (순수 수학)
 * 4. MarketDataService: 제거 (Binance/CoinGecko 5+개 API 불필요)
 * 5. DB 저장: 비동기
 * 6. HTTP 타임아웃: 2초
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SniperScanner {

    private final ChainlinkPriceService chainlink;
    private final OddsService oddsService;
    private final EvCalculator evCalculator;
    private final BalanceService balanceService;
    private final OrderService orderService;
    private final TradeRepository tradeRepository;

    @Value("${sniper.scan-interval-ms:500}")
    private int scanIntervalMs;

    @Value("${sniper.cooldown-ms:60000}")
    private long cooldownMs;

    @Value("${sniper.min-ev:0.10}")
    private double minEv;

    @Value("${sniper.min-gap:0.06}")
    private double minGap;

    @Value("${sniper.dry-run:true}")
    private boolean dryRun;

    private final ScheduledExecutorService scanExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastTradeTime = 0;

    // 성과 통계 (메모리)
    private volatile int totalScans = 0;
    private volatile int totalTrades = 0;
    private volatile int wins = 0;
    private volatile int losses = 0;
    private volatile long totalScanTimeMs = 0;

    // 승률 캐시 (30초마다 갱신)
    private volatile double recentWinRate = 0.76; // 5M BTC 역사적 승률
    private volatile long winRateLastCalc = 0;

    @PostConstruct
    public void start() {
        log.info("🚀 BTC 5M Sniper 시작 — {}ms 간격, {} 모드",
                scanIntervalMs, dryRun ? "DRY-RUN" : "🔴 LIVE");
        scanExecutor.scheduleAtFixedRate(this::scan, 3000, scanIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        log.info("🛑 Sniper 종료 — 총 {}스캔, {}배팅", totalScans, totalTrades);
        scanExecutor.shutdownNow();
    }

    /**
     * 메인 스캔 루프 (500ms마다)
     */
    private void scan() {
        long scanStart = System.nanoTime();
        totalScans++;

        try {
            // 1. Chainlink 가격 확인
            if (!chainlink.isConnected()) return;

            double currentPrice = chainlink.getPrice();
            double openPrice = chainlink.get5mOpen();
            if (currentPrice <= 0 || openPrice <= 0) return;

            // 2. 쿨다운 체크
            if (isOnCooldown()) return;

            // 3. 변동률 계산
            double priceDiffPct = ((currentPrice - openPrice) / openPrice) * 100;
            double absDiff = Math.abs(priceDiffPct);

            // 최소 변동폭: 0.02% (BTC $97K 기준 ~$19)
            if (absDiff < 0.02) return;

            // 4. 오즈 조회
            OddsService.MarketOdds odds = oddsService.getOdds();
            if (odds == null) return;

            // 5. 승률 갱신 (30초마다)
            refreshWinRate();

            double balance = balanceService.getBalance();
            if (balance < 1.0) return;

            // 6. EV 계산 — 순방향 우선, 역방향 보조
            EvCalculator.EvResult fwd = evCalculator.calcForward(priceDiffPct, odds.upOdds(), recentWinRate, balance);
            EvCalculator.EvResult rev = evCalculator.calcReverse(priceDiffPct, odds.upOdds(), recentWinRate, balance);

            // 최선의 기회 선택
            EvCalculator.EvResult best = selectBest(fwd, rev);
            if (best == null || "HOLD".equals(best.direction())) return;

            // 7. 🎯 배팅 실행!
            long elapsed = (System.nanoTime() - scanStart) / 1_000_000;
            executeTrade(best, odds, currentPrice, openPrice, priceDiffPct, elapsed);

        } catch (Exception e) {
            if (totalScans % 100 == 0) { // 로그 스팸 방지
                log.warn("스캔 에러: {}", e.getMessage());
            }
        } finally {
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
            totalScanTimeMs += scanMs;
        }
    }

    private EvCalculator.EvResult selectBest(EvCalculator.EvResult fwd, EvCalculator.EvResult rev) {
        boolean fwdOk = !"HOLD".equals(fwd.direction()) && fwd.ev() >= minEv && fwd.gap() >= minGap;
        boolean revOk = !"HOLD".equals(rev.direction()) && rev.ev() >= minEv && rev.gap() >= minGap;

        if (fwdOk && revOk) return fwd.ev() >= rev.ev() ? fwd : rev;
        if (fwdOk) return fwd;
        if (revOk) return rev;
        return null;
    }

    private void executeTrade(EvCalculator.EvResult ev, OddsService.MarketOdds odds,
                               double currentPrice, double openPrice, double priceDiffPct, long scanMs) {
        // 쿨다운 등록
        lastTradeTime = System.currentTimeMillis();
        totalTrades++;

        boolean isBuyYes = "UP".equals(ev.direction());
        Trade.TradeAction action = isBuyYes ? Trade.TradeAction.BUY_YES : Trade.TradeAction.BUY_NO;
        double mktOdds = isBuyYes ? odds.upOdds() : odds.downOdds();
        String tokenId = isBuyYes ? odds.upTokenId() : odds.downTokenId();

        // 잔액 차감
        balanceService.deductBet(ev.betAmount());

        // 주문 실행 (DRY-RUN/LIVE 자동 분기)
        OrderService.OrderResult order = orderService.placeOrder(tokenId, ev.betAmount(), mktOdds, "BUY");

        // DB 저장 (비동기)
        Trade trade = Trade.builder()
                .coin("BTC")
                .timeframe("5M")
                .action(action)
                .betAmount(ev.betAmount())
                .odds(mktOdds)
                .entryPrice(currentPrice)
                .openPrice(openPrice)
                .estimatedProb(ev.estimatedProb())
                .ev(ev.ev())
                .gap(ev.gap())
                .priceDiffPct(priceDiffPct)
                .balanceAfter(balanceService.getBalance())
                .marketId(odds.marketId())
                .strategy(ev.strategy())
                .reason(ev.reason())
                .detail(String.format("orderId=%s | scanMs=%d | oddsFetchMs=%d",
                        order.orderId(), scanMs, odds.fetchTimeMs()))
                .scanToTradeMs(scanMs)
                .build();

        CompletableFuture.runAsync(() -> {
            try { tradeRepository.save(trade); }
            catch (Exception e) { log.error("DB 저장 실패: {}", e.getMessage()); }
        });

        log.info("🎯 [{}] {} ${} @ {}¢ | EV+{}% | 가격${} (시초${}) {}% | {}ms",
                ev.strategy(), action, String.format("%.2f", ev.betAmount()),
                String.format("%.0f", mktOdds * 100), String.format("%.1f", ev.ev() * 100),
                String.format("%.2f", currentPrice), String.format("%.2f", openPrice),
                String.format("%+.3f", priceDiffPct), scanMs);
    }

    private boolean isOnCooldown() {
        return (System.currentTimeMillis() - lastTradeTime) < cooldownMs;
    }

    private void refreshWinRate() {
        if (System.currentTimeMillis() - winRateLastCalc < 30_000) return;
        winRateLastCalc = System.currentTimeMillis();
        try {
            var recent = tradeRepository.findRecentResolved(50);
            if (recent.size() >= 5) {
                long w = recent.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
                recentWinRate = (double) w / recent.size();
            }
        } catch (Exception e) {
            // 무시
        }
    }

    // === 대시보드용 통계 API ===

    public SniperStats getStats() {
        double avgScanMs = totalScans > 0 ? (double) totalScanTimeMs / totalScans : 0;
        return new SniperStats(totalScans, totalTrades, wins, losses, recentWinRate,
                balanceService.getBalance(), avgScanMs, chainlink.isConnected(),
                dryRun, lastTradeTime);
    }

    public void recordWin() { wins++; }
    public void recordLoss() { losses++; }

    public record SniperStats(
            int totalScans, int totalTrades, int wins, int losses,
            double winRate, double balance, double avgScanMs,
            boolean chainlinkConnected, boolean dryRun, long lastTradeTime
    ) {}
}
