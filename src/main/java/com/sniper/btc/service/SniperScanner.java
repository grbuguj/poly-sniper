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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ BTC 5M 전용 스나이퍼 — poly_bug OddsGapScanner 5M 로직 정합 버전
 *
 * poly_bug에서 가져온 필터:
 * 1. 캔들 포지션 필터 (시작40초·마감40초 제외, position 1-3만)
 * 2. 모멘텀 일관성 (10틱 추적, abs >= 0.4 필수)
 * 3. 횡보 감지 (시초가 5회+ 교차 → 스킵)
 * 4. 가격 레인지 필터 (60틱 고저차 과소 → 스킵)
 * 5. 스프레드 검증 (up+down > 1.05 → 스킵)
 * 6. 시간당 한도 (5M: 시간당 5건)
 * 7. 서킷브레이커 (3연패 → 5분 정지)
 * 8. 역방향 비활성화 (5M은 너무 짧아서 반전 불발)
 * 9. 쿨다운 90초
 * 10. 최소 변동폭 0.03% (BTC 5M = 0.06 * 0.5)
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

    @Value("${sniper.dry-run:true}")
    private boolean dryRun;

    @Value("${sniper.min-bet:1.0}")
    private double minBet;

    @Value("${sniper.max-bet:10.0}")
    private double maxBet;

    private final ScheduledExecutorService scanExecutor = Executors.newSingleThreadScheduledExecutor();

    // === poly_bug 동일 상수 ===
    // 쿨다운: 캔들당 1건 (같은 마켓 중복 방지, 새 캔들은 즉시 진입)
    private static final double MIN_PRICE_MOVE = 0.03;     // BTC 5M = 0.06 * 0.5
    private static final double MAX_SPREAD = 1.05;
    private static final double BASE_FORWARD_GAP = 0.06;

    // 시간당 한도 제거 — 쿨다운 30초 + 서킷브레이커로 충분
    private static final int MOMENTUM_WINDOW = 10;
    private static final long CIRCUIT_BREAKER_DURATION = 300_000; // 5분
    private static final double MIN_BALANCE = 1.0;
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // 캔들당 1건 제한
    private volatile int lastTradedCandleWindow = -1;



    // ⭐ 마스터 스위치 (대시보드에서 ON/OFF)
    private volatile boolean enabled = false;

    // 성과 통계
    private volatile int totalScans = 0;
    private volatile int totalTrades = 0;
    private volatile int wins = 0;
    private volatile int losses = 0;
    private volatile long totalScanTimeMs = 0;

    // 승률 캐시 (30초마다 갱신)
    private volatile double recentWinRate = 0.50;
    private volatile long winRateLastCalc = 0;

    // ⭐ poly_bug 동일: 모멘텀 추적 (10틱)
    private final Deque<Integer> momentumTicks = new ConcurrentLinkedDeque<>();

    // ⭐ poly_bug 동일: 가격 속도 추적
    private volatile double lastPrice = 0;
    private volatile long lastPriceTime = 0;

    // ⭐ poly_bug 동일: 횡보 감지 (시초가 교차 횟수)
    private volatile int crossCount = 0;
    private volatile int lastCrossDir = 0; // +1 or -1
    private volatile int lastResetWindow = -1; // 5분봉 교체시 리셋

    // ⭐ poly_bug 동일: 가격 레인지 (60틱 고저)
    private volatile double rangeMin = Double.MAX_VALUE;
    private volatile double rangeMax = Double.MIN_VALUE;
    private volatile int rangeTicks = 0;

    // ⭐ poly_bug 동일: 서킷브레이커
    private volatile long circuitBreakerUntil = 0;
    private volatile long lastCircuitCheck = 0;

    // ⭐ 실시간 로그 버퍼 (대시보드용, 최대 200줄)
    private final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static final int MAX_LOG_LINES = 200;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final Map<String, Long> throttleMap = new ConcurrentHashMap<>();

    /**
     * 통계 초기화 (DB 삭제 시 함께 호출)
     */
    public void resetStats() {
        totalScans = 0;
        totalTrades = 0;
        wins = 0;
        losses = 0;
        totalScanTimeMs = 0;
        recentWinRate = 0.50;
        winRateLastCalc = 0;
        lastTradedCandleWindow = -1;
        crossCount = 0;
        lastCrossDir = 0;
        lastResetWindow = -1;
        rangeMin = Double.MAX_VALUE;
        rangeMax = Double.MIN_VALUE;
        rangeTicks = 0;
        circuitBreakerUntil = 0;
        lastCircuitCheck = 0;
        momentumTicks.clear();
        logBuffer.clear();
        log.info("🗑️ 통계 초기화 완료");
    }

    @PostConstruct
    public void start() {
        log.info("🚀 BTC 5M Sniper 시작 — {}ms 간격, {} 모드 | poly_bug 정합 V2",
                scanIntervalMs, dryRun ? "DRY-RUN" : "🔴 LIVE");
        log.info("   최소변동 {}% | 캔들당 1건 | 서킷브레이커 3연패→5분정지",
                MIN_PRICE_MOVE);
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
            // 0. 마스터 스위치 OFF면 스캔 중단
            if (!enabled) return;

            // 1. Chainlink 연결 확인
            if (!chainlink.isConnected()) {
                addLogThrottled("⚠️", "연결", "Chainlink 미연결");
                return;
            }

            // 1.5 워밍업 체크
            if (!chainlink.isWarmedUp()) {
                addLogThrottled("⏳", "대기", "워밍업 중 (다음 5분봉 경계 대기)");
                return;
            }

            double currentPrice = chainlink.getPrice();
            double openPrice = chainlink.get5mOpen();
            if (currentPrice <= 0 || openPrice <= 0) return;

            // ⭐ 5분봉 교체시 필터 리셋
            reset5mWindowIfNeeded();

            // ⭐ 서킷브레이커 체크
            if (System.currentTimeMillis() - lastCircuitCheck > 30_000) {
                checkCircuitBreaker();
                lastCircuitCheck = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() < circuitBreakerUntil) {
                long remain = (circuitBreakerUntil - System.currentTimeMillis()) / 1000;
                addLogThrottled("🔴", "서킷", String.format("3연패 정지 %d초 남음", remain));
                return;
            }

            // 2. 캔들당 1건 체크
            int currentCandleWindow = getCurrentCandleWindow();
            if (currentCandleWindow == lastTradedCandleWindow) {
                addLogThrottled("⏱️", "쿨다운", "이미 이 캔들에서 배팅 완료");
                return;
            }

            // 3. 변동률 계산
            double priceDiffPct = ((currentPrice - openPrice) / openPrice) * 100;
            double absDiff = Math.abs(priceDiffPct);

            // ⭐ poly_bug 동일: 속도 추적
            double velocity = trackVelocity(currentPrice);

            // ⭐ poly_bug 동일: 모멘텀 추적
            trackMomentum(priceDiffPct);

            // ⭐ poly_bug 동일: 횡보 감지
            trackCrossCount(priceDiffPct);

            // ⭐ poly_bug 동일: 가격 레인지 추적
            trackPriceRange(currentPrice);

            // ⭐ poly_bug 동일: 최소 변동폭 0.03%
            if (absDiff < MIN_PRICE_MOVE) {
                addLogThrottled("📊", "스캔",
                        String.format("$%,.2f %+.4f%% → 변동부족 (<%.2f%%)", currentPrice, priceDiffPct, MIN_PRICE_MOVE));
                return;
            }

            // ⭐ poly_bug 동일: 횡보 필터 (5회+ 교차)
            if (crossCount >= 5) {
                addLogThrottled("📊", "횡보", String.format("시초가 %d회 교차 → 스킵", crossCount));
                return;
            }

            // ⭐ poly_bug 동일: 가격 레인지 필터
            double rangePct = getPriceRangePct();
            if (rangePct > 0 && rangePct < MIN_PRICE_MOVE * 0.8) {
                addLogThrottled("📊", "레인지",
                        String.format("%.3f%% < %.3f%% → 갇힌 가격", rangePct, MIN_PRICE_MOVE * 0.8));
                return;
            }

            // ⭐ poly_bug 동일: 캔들 포지션 필터 (position 1-3만)
            int candlePos = getCandlePosition();
            if (candlePos < 1) {
                addLogThrottled("📊", "캔들", candlePos == 0 ? "시작 40초 대기" : "마감 40초 차단");
                return;
            }

            // 4. 오즈 조회
            OddsService.MarketOdds odds = oddsService.getOdds();
            if (odds == null) {
                addLogThrottled("⚠️", "오즈", "오즈 조회 실패");
                return;
            }

            // ⭐ poly_bug 동일: 스프레드 검증
            double spread = odds.upOdds() + odds.downOdds();
            if (spread > MAX_SPREAD) {
                addLogThrottled("📊", "스프레드",
                        String.format("%.1f%% > %.0f%% → 스킵", spread * 100, MAX_SPREAD * 100));
                return;
            }

            // 5. 승률 갱신 (30초마다)
            refreshWinRate();

            double balance = balanceService.getBalance();
            if (balance < MIN_BALANCE) {
                addLog("💸", "잔액", "잔액 부족 $" + String.format("%.2f", balance));
                return;
            }

            // ⭐ poly_bug 동일: 모멘텀 일관성 체크 (abs >= 0.4 필수)
            double momentumScore = getMomentumConsistency();
            double absMomentum = Math.abs(momentumScore);
            if (absMomentum < 0.4) {
                addLogThrottled("📊", "모멘텀",
                        String.format("일관성 %.0f%% < 40%% → 방향 불명확", absMomentum * 100));
                return;
            }

            // 6. 방향 & 오즈
            String priceDir = priceDiffPct > 0 ? "UP" : "DOWN";
            double fwdMarketOdds = "UP".equals(priceDir) ? odds.upOdds() : odds.downOdds();

            // ⭐ poly_bug 동일: 시간 보너스
            double timeBonus = getTimeBonus();

            // 7. ⭐ poly_bug 동일: 확률 추정 + EV 계산 (순방향만, 역방향 비활성화)
            EvCalculator.EvResult fwd = evCalculator.calcForward(
                    priceDiffPct, odds.upOdds(), velocity, momentumScore, timeBonus, balance);

            // ⭐ poly_bug 동일: 동적 임계값 (승률 기반)
            double adaptiveGap = getAdaptiveGap(BASE_FORWARD_GAP);
            double fwdGap = fwd.estimatedProb() - fwdMarketOdds;

            if ("HOLD".equals(fwd.direction()) || fwdGap < adaptiveGap) {
                addLog("🔍", "분석",
                        String.format("$%,.2f %+.3f%% | Up%.0f¢ | 추정%.0f%% 갭%.1f%%<%.1f%% EV%+.1f%% → HOLD",
                                currentPrice, priceDiffPct, odds.upOdds() * 100,
                                fwd.estimatedProb() * 100, fwdGap * 100, adaptiveGap * 100, fwd.ev() * 100));
                return;
            }

            // 8. 🎯 순방향 배팅 실행!
            long elapsed = (System.nanoTime() - scanStart) / 1_000_000;
            addLog("🎯", "배팅",
                    String.format("[FWD] %s $%.2f @ %.0f¢ | EV+%.1f%% | 갭%.1f%% | 모멘텀%.0f%%",
                            fwd.direction(), fwd.betAmount(), fwdMarketOdds * 100,
                            fwd.ev() * 100, fwdGap * 100, absMomentum * 100));
            executeTrade(fwd, odds, currentPrice, openPrice, priceDiffPct, elapsed);

        } catch (Exception e) {
            if (totalScans % 100 == 0) {
                log.warn("스캔 에러: {}", e.getMessage());
            }
        } finally {
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
            totalScanTimeMs += scanMs;
        }
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 캔들 포지션 (5M 전용)
    // 0=시작40초, 1=초반, 2=중반, 3=후반
    // 마감 제외 없음 — 후반이 방향 확신 가장 높은 구간
    // =========================================================================
    private int getCandlePosition() {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int minute = nowET.getMinute();
        int second = nowET.getSecond();
        int elapsed = (minute % 5) * 60 + second;
        int total = 300;

        if (elapsed < 40) return 0;    // 시작 40초 대기 (방향 미확정)
        if (elapsed >= 285) return -1; // 마감 15초 차단 (마켓 정산/교체 구간)
        double pct = (double) elapsed / total;
        if (pct < 0.30) return 1;
        if (pct < 0.70) return 2;
        return 3;
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 시간 보너스 (5M 전용)
    // =========================================================================
    private double getTimeBonus() {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int minute = nowET.getMinute();
        int elapsed = minute % 5;
        if (elapsed >= 4) return 0.07;
        if (elapsed >= 3) return 0.05;
        if (elapsed >= 2) return 0.03;
        if (elapsed >= 1) return 0.01;
        return 0.0;
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 모멘텀 추적
    // =========================================================================
    private void trackMomentum(double priceDiffPct) {
        momentumTicks.addLast(priceDiffPct >= 0 ? 1 : -1);
        while (momentumTicks.size() > MOMENTUM_WINDOW) momentumTicks.pollFirst();
    }

    private double getMomentumConsistency() {
        if (momentumTicks.size() < 3) return 0.0;
        int sum = 0;
        for (int t : momentumTicks) sum += t;
        return (double) sum / momentumTicks.size();
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 가격 속도 추적 (%/초)
    // =========================================================================
    private double trackVelocity(double currentPrice) {
        long now = System.currentTimeMillis();
        if (lastPrice <= 0 || lastPriceTime <= 0) {
            lastPrice = currentPrice;
            lastPriceTime = now;
            return 0.0;
        }
        double elapsed = (now - lastPriceTime) / 1000.0;
        double vel = elapsed > 0 ? ((currentPrice - lastPrice) / lastPrice * 100) / elapsed : 0.0;
        lastPrice = currentPrice;
        lastPriceTime = now;
        return vel;
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 횡보 감지 (시초가 교차 횟수)
    // =========================================================================
    private void trackCrossCount(double priceDiffPct) {
        int currentDir = priceDiffPct >= 0 ? 1 : -1;
        if (lastCrossDir == 0) {
            lastCrossDir = currentDir;
            return;
        }
        if (lastCrossDir != currentDir) {
            crossCount++;
            lastCrossDir = currentDir;
        }
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 가격 레인지 추적 (60틱 고저)
    // =========================================================================
    private void trackPriceRange(double price) {
        rangeMin = Math.min(rangeMin, price);
        rangeMax = Math.max(rangeMax, price);
        rangeTicks++;
        if (rangeTicks > 60) {
            rangeMin = price;
            rangeMax = price;
            rangeTicks = 1;
        }
    }

    private double getPriceRangePct() {
        if (rangeTicks < 10 || rangeMin <= 0) return -1;
        return ((rangeMax - rangeMin) / rangeMin) * 100;
    }

    // =========================================================================
    // ⭐ 5분봉 교체시 필터 상태 리셋
    // =========================================================================
    private void reset5mWindowIfNeeded() {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int current5mWindow = nowET.getMinute() / 5;
        if (current5mWindow != lastResetWindow) {
            lastResetWindow = current5mWindow;
            crossCount = 0;
            lastCrossDir = 0;
            rangeMin = Double.MAX_VALUE;
            rangeMax = Double.MIN_VALUE;
            rangeTicks = 0;
            momentumTicks.clear();
        }
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 서킷브레이커 (3연패 → 5분 정지)
    // =========================================================================
    private void checkCircuitBreaker() {
        try {
            var recent = tradeRepository.findRecentResolved(10);
            if (recent.size() < 3) return;
            boolean threeConsecLoss = recent.stream().limit(3)
                    .allMatch(t -> t.getResult() == Trade.TradeResult.LOSE);
            if (threeConsecLoss && System.currentTimeMillis() >= circuitBreakerUntil) {
                circuitBreakerUntil = System.currentTimeMillis() + CIRCUIT_BREAKER_DURATION;
                addLog("🔴", "서킷", "3연패 감지 → 5분 정지!");
                log.warn("🔴 서킷브레이커 발동: BTC 5M 3연패 → 5분 정지");
            }
        } catch (Exception e) {
            log.debug("서킷브레이커 체크 오류: {}", e.getMessage());
        }
    }



    // =========================================================================
    // ⭐ poly_bug 동일: 승률 기반 동적 임계값
    // =========================================================================
    private double getAdaptiveGap(double baseGap) {
        if (recentWinRate >= 0.65) return baseGap - 0.02;
        if (recentWinRate >= 0.55) return baseGap;
        if (recentWinRate >= 0.45) return baseGap + 0.03;
        return baseGap + 0.05; // 40%미만 → 매우 보수적
    }

    // =========================================================================
    // 배팅 실행
    // =========================================================================
    private void executeTrade(EvCalculator.EvResult ev, OddsService.MarketOdds odds,
                               double currentPrice, double openPrice, double priceDiffPct, long scanMs) {
        lastTradedCandleWindow = getCurrentCandleWindow();
        totalTrades++;

        boolean isBuyYes = "UP".equals(ev.direction());
        Trade.TradeAction action = isBuyYes ? Trade.TradeAction.BUY_YES : Trade.TradeAction.BUY_NO;
        double mktOdds = isBuyYes ? odds.upOdds() : odds.downOdds();
        String tokenId = isBuyYes ? odds.upTokenId() : odds.downTokenId();

        OrderService.OrderResult order = orderService.placeOrder(tokenId, ev.betAmount(), mktOdds, "BUY");

        // 실제 배팅 금액 (최소 5토큰 제약 반영)
        double actualBet = order.actualAmount() > 0 ? order.actualAmount() : ev.betAmount();
        balanceService.deductBet(actualBet);

        // LIVE: 주문 후 실잔액 재동기화 (1초 대기 후, Polymarket 반영 시간)
        if (!dryRun) {
            scanExecutor.schedule(() -> balanceService.refreshIfLive(), 2, TimeUnit.SECONDS);
        }

        Trade trade = Trade.builder()
                .coin("BTC")
                .timeframe("5M")
                .action(action)
                .betAmount(actualBet)
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
                .detail(String.format("orderId=%s | scanMs=%d | oddsFetchMs=%d | momentum=%.2f",
                        order.orderId(), scanMs, odds.fetchTimeMs(), getMomentumConsistency()))
                .scanToTradeMs(scanMs)
                .build();

        // 🔧 FIX: 동기 저장 (poly_bug 동일 — 비동기면 저장 실패 시 ResultChecker가 해당 trade를 못 찾음)
        try {
            tradeRepository.save(trade);
        } catch (Exception e) {
            log.error("DB 저장 실패: {}", e.getMessage());
        }

        log.info("🎯 [{}] {} ${} ({}토큰) @ {}¢ | EV+{}% | 가격${} (시초${}) {}% | 모멘텀{}% | {}ms",
                ev.strategy(),
                action, String.format("%.2f", actualBet), String.format("%.0f", order.actualSize()),
                String.format("%.0f", mktOdds * 100), String.format("%.1f", ev.ev() * 100),
                String.format("%.2f", currentPrice), String.format("%.2f", openPrice),
                String.format("%+.3f", priceDiffPct),
                String.format("%.0f", Math.abs(getMomentumConsistency()) * 100), scanMs);
    }

    /**
     * 현재 5분봄 윈도우 ID (ET 기준)
     * 예: 18:05~18:09 = 같은 윈도우
     */
    private int getCurrentCandleWindow() {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        return nowET.getHour() * 12 + nowET.getMinute() / 5;
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
        } catch (Exception e) { /* 무시 */ }
    }

    // === 대시보드용 통계 API ===

    // === 마스터 스위치 제어 ===

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean on) {
        this.enabled = on;
        if (on) {
            addLog("🟢", "시스템", "Sniper ON — 스캔 시작");
            log.info("🟢 Sniper ON — {} 모드", dryRun ? "DRY-RUN" : "🔴 LIVE");
        } else {
            addLog("🔴", "시스템", "Sniper OFF — 스캔 정지");
            log.info("🔴 Sniper OFF");
        }
    }

    public SniperStats getStats() {
        double avgScanMs = totalScans > 0 ? (double) totalScanTimeMs / totalScans : 0;
        return new SniperStats(totalScans, totalTrades, wins, losses, recentWinRate,
                balanceService.getBalance(), avgScanMs, chainlink.isConnected(),
                dryRun, lastTradedCandleWindow, enabled);
    }

    public void recordWin() { wins++; }
    public void recordLoss() { losses++; }

    // === 로그 버퍼 ===

    private void addLog(String icon, String category, String message) {
        String time = LocalDateTime.now().format(LOG_TIME);
        String line = String.format("%s %s [%s] %s", time, icon, category, message);
        logBuffer.addFirst(line);
        while (logBuffer.size() > MAX_LOG_LINES) logBuffer.pollLast();
    }

    private void addLogThrottled(String icon, String category, String message) {
        long now = System.currentTimeMillis();
        Long last = throttleMap.get(category);
        if (last != null && now - last < 500) return;
        throttleMap.put(category, now);
        addLog(icon, category, message);
    }

    public List<String> getRecentLogs(int count) {
        List<String> result = new ArrayList<>();
        int i = 0;
        for (String line : logBuffer) {
            if (i++ >= count) break;
            result.add(line);
        }
        return result;
    }

    public record SniperStats(
            int totalScans, int totalTrades, int wins, int losses,
            double winRate, double balance, double avgScanMs,
            boolean chainlinkConnected, boolean dryRun, int lastTradedCandle,
            boolean enabled
    ) {}
}
