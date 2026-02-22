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
 * 1. 티어드 조기진입 (오즈 미반영 감지, 하드블록 없음) + 마감 15초 차단
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

    @Value("${sniper.scan-interval-ms:100}")
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
    private static final double FALLBACK_MIN_MOVE = 0.03;  // ATR 미준비 시 폴백
    private static final double ATR_ENTRY_MULT = 0.5;      // 진입 임계값 = ATR × 0.5
    private static final double ATR_RANGE_MULT = 0.3;      // 레인지 필터 = ATR × 0.3
    private static final double MAX_SPREAD = 1.05;
    private static final double BASE_FORWARD_GAP = 0.03; // 🔧 FIX: 0.06→0.03 (5분봉 현실 반영)

    // 시간당 한도 제거 — 쿨다운 30초 + 서킷브레이커로 충분
    private static final int MOMENTUM_WINDOW = 10;
    private static final long CIRCUIT_BREAKER_DURATION = 300_000; // 5분
    private static final double MIN_BALANCE = 1.0;
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // ⭐ CUSUM 필터 상수 (Lopez de Prado, Financial ML)
    // S⁺ = max(0, S⁺ + (return - expected)), trigger when S⁺ > h
    private static final double CUSUM_EXPECTED_RETURN = 0.0;  // 5분봉 기대수익률 ≈ 0
    private static final double CUSUM_ATR_MULT = 0.4;         // CUSUM 임계값 = ATR × 0.4
    private static final double CUSUM_FALLBACK_H = 0.025;     // ATR 미준비 시 폴백 임계값

    // ⭐ 변동성 레짐별 파라미터 조정
    // LOW: 조용한 시장 → 낮은 기준, 공격적 진입
    // NORMAL: 기본 설정
    // HIGH: 활발한 시장 → 높은 기준, 보수적 진입
    // EXTREME: 극단 변동 → 매우 보수적, 모멘텀 강하게 요구
    private static final Map<ChainlinkPriceService.VolRegime, double[]> REGIME_PARAMS = Map.of(
        // [entryMult, rangeMult, momentumMin, cusumMult, gapAdj]
        ChainlinkPriceService.VolRegime.LOW,     new double[]{0.4, 0.25, 0.35, 0.35, -0.01},
        ChainlinkPriceService.VolRegime.NORMAL,  new double[]{0.5, 0.30, 0.40, 0.40, 0.00},
        ChainlinkPriceService.VolRegime.HIGH,    new double[]{0.6, 0.35, 0.50, 0.50, +0.01},
        ChainlinkPriceService.VolRegime.EXTREME, new double[]{0.7, 0.40, 0.60, 0.60, +0.02}
    );

    // 캔들당 1건 제한
    private volatile int lastTradedCandleWindow = -1;

    // ⚡ FOK 재시도: executeTrade 내부 루프에서 처리 (scan 재진입 없음)
    // MAX_FOK_RETRIES: 최대 3회 재시도, 매회 +2틱, 60¢ 초과 시 포기
    private static final int MAX_FOK_RETRIES = 3;



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
    private volatile double smoothedVelocity = 0;  // EMA 평활 속도
    private static final double VELOCITY_EMA_ALPHA = 0.3; // 새 값 30% 반영

    // ⭐ poly_bug 동일: 횡보 감지 (시초가 교차 횟수)
    private volatile int crossCount = 0;
    private volatile int lastCrossDir = 0; // +1 or -1
    private volatile int lastResetWindow = -1; // 5분봉 교체시 리셋

    // ⭐ poly_bug 동일: 가격 레인지 (60틱 고저) — CUSUM 보조용
    private volatile double rangeMin = Double.MAX_VALUE;
    private volatile double rangeMax = Double.MIN_VALUE;
    private volatile int rangeTicks = 0;

    // ⭐ CUSUM 필터 (Lopez de Prado) — 레인지 필터 대체
    // S⁺ = max(0, S⁺ + (ret - expected)), S⁻ = min(0, S⁻ + (ret + expected))
    // |S⁺| > h OR |S⁻| > h → 유의미한 방향성 누적 감지
    private volatile double cusumPos = 0;        // 상승 누적합
    private volatile double cusumNeg = 0;        // 하락 누적합
    private volatile double lastCusumPrice = 0;  // CUSUM 기준 가격
    private volatile boolean cusumTriggered = false; // CUSUM 신호 발생 여부

    // ⭐ poly_bug 동일: 서킷브레이커
    private volatile long circuitBreakerUntil = 0;
    private volatile long lastCircuitCheck = 0;
    private volatile long circuitTriggeredForTradeId = 0; // 마지막 발동 시 최신 trade ID

    // ⭐ 실시간 로그 버퍼 (대시보드용, 최대 200줄)
    private final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static final int MAX_LOG_LINES = 200;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final Map<String, Long> throttleMap = new ConcurrentHashMap<>();

    // ⚡ 실시간 스캔 속도 메트릭 (대시보드 500ms 폴링용)
    private volatile long lastScanDurationUs = 0;   // 마지막 스캔 소요 (마이크로초)
    private volatile String lastFilterHit = "-";     // 마지막으로 걸린 필터
    private volatile long scanEpochCounter = 0;      // 초당 스캔수 계산용
    private volatile long scanEpochStart = System.currentTimeMillis();
    private volatile double currentScansPerSec = 0;  // 초당 스캔수

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
        circuitTriggeredForTradeId = 0;
        momentumTicks.clear();
        logBuffer.clear();
        // CUSUM 리셋
        cusumPos = 0;
        cusumNeg = 0;
        lastCusumPrice = 0;
        cusumTriggered = false;
        log.info("🗑️ 통계 초기화 완료");
    }

    @PostConstruct
    public void start() {
        log.info("🚀 BTC 5M Sniper 시작 — {}ms 간격, {} 모드 | ATR+CUSUM+레짐 V4",
                scanIntervalMs, dryRun ? "DRY-RUN" : "🔴 LIVE");
        log.info("   ATR동적진입 | CUSUM필터 | 변동성레짐(LOW/NORMAL/HIGH/EXTREME) | 캔들당1건 | 서킷브레이커");
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
            if (!enabled) { lastFilterHit = "OFF"; return; }

            // 1. Chainlink 연결 확인
            if (!chainlink.isConnected()) {
                addLogThrottled("⚠️", "연결", "Chainlink 미연결");
                lastFilterHit = "미연결"; return;
            }

            // 1.5 워밍업 체크
            if (!chainlink.isWarmedUp()) {
                addLogThrottled("⏳", "대기", "워밍업 중 (다음 5분봉 경계 대기)");
                lastFilterHit = "워밍업"; return;
            }

            double currentPrice = chainlink.getPrice();
            double openPrice = chainlink.get5mOpen();
            if (currentPrice <= 0 || openPrice <= 0) { lastFilterHit = "가격없음"; return; }

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
                lastFilterHit = "서킷브레이커"; return;
            }

            // 2. 캔들당 1건 체크
            int currentCandleWindow = getCurrentCandleWindow();
            if (currentCandleWindow == lastTradedCandleWindow) {
                addLogThrottled("⏱️", "쿨다운", "이미 이 캔들에서 배팅 완료");
                lastFilterHit = "캔들쿨다운"; return;
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

            // ⭐ poly_bug 동일: 가격 레인지 추적 (보조 로깅용)
            trackPriceRange(currentPrice);

            // ⭐ CUSUM 필터 추적 (Lopez de Prado — 레인지 필터 대체)
            trackCusum(currentPrice);

            // ⭐ ATR 기반 동적 임계값 계산 (레짐 연동)
            double dynamicMinMove = getDynamicMinMove();
            double dynamicRangeThreshold = getDynamicRangeThreshold();
            ChainlinkPriceService.VolRegime regime = chainlink.getVolatilityRegime();

            // ⭐ poly_bug 동일: 최소 변동폭 (ATR 기반 동적)
            if (absDiff < dynamicMinMove) {
                addLogThrottled("📊", "스캔",
                        String.format("$%,.2f %+.4f%% → 변동부족 (<%.3f%% ATR×%.1f)", currentPrice, priceDiffPct, dynamicMinMove, ATR_ENTRY_MULT));
                lastFilterHit = "변동부족"; return;
            }

            // ⭐ poly_bug 동일: 횡보 필터 (5회+ 교차)
            if (crossCount >= 5) {
                addLogThrottled("📊", "횡보", String.format("시초가 %d회 교차 → 스킵", crossCount));
                lastFilterHit = "횡보"; return;
            }

            // ⭐ CUSUM 필터 (레인지 필터 대체 — Lopez de Prado)
            // CUSUM 미발동 = 가격이 한 방향으로 유의미하게 움직이지 않음
            if (!cusumTriggered && rangeTicks >= 10) {
                double rangePct = getPriceRangePct();
                addLogThrottled("📊", "CUSUM",
                        String.format("미발동 S⁺=%.4f S⁻=%.4f h=%.4f | 레인지%.3f%% → 방향 불확실",
                                cusumPos, cusumNeg, getCusumThreshold(), rangePct > 0 ? rangePct : 0));
                lastFilterHit = "CUSUM"; return;
            }

            // ⭐ poly_bug 동일: 캔들 포지션 필터 (position 1-3만)
            int candlePos = getCandlePosition();
            if (candlePos <= 0) {
                if (candlePos == 0) {
                    addLogThrottled("⏳", "시초가대기", "시초가 동기화 대기 (5초 미만)");
                    lastFilterHit = "시초가대기";
                } else {
                    addLogThrottled("📊", "캔들", "마감 15초 차단");
                    lastFilterHit = "캔들마감";
                }
                return;
            }

            // 4. 오즈 조회
            OddsService.MarketOdds odds = oddsService.getOdds();
            if (odds == null) {
                addLogThrottled("⚠️", "오즈", "오즈 조회 실패");
                lastFilterHit = "오즈실패"; return;
            }

            // ⚡ tokenId 프리파싱 (주문 시 BigInteger 변환 생략)
            orderService.prepareTokenIds(odds.upTokenId(), odds.downTokenId());

            // ⭐ poly_bug 동일: 스프레드 검증
            double spread = odds.upOdds() + odds.downOdds();
            if (spread > MAX_SPREAD) {
                addLogThrottled("📊", "스프레드",
                        String.format("%.1f%% > %.0f%% → 스킵", spread * 100, MAX_SPREAD * 100));
                lastFilterHit = "스프레드"; return;
            }

            // 5. 승률 갱신 (30초마다)
            refreshWinRate();

            // 🔧 FIX: LIVE 모드면 Polymarket 실잔액 동기화 (자동 redeem 대기 포함)
            double balance = balanceService.getVerifiedBalance();
            if (balance < MIN_BALANCE) {
                String extra = balanceService.isRedeemPending() ? " (⏳ redeem 대기중)" : "";
                addLogThrottled("💸", "잔액", String.format("잔액 부족 $%.2f%s", balance, extra));
                lastFilterHit = "잔액부족"; return;
            }

            // ⭐ 모멘텀 일관성 체크 (레짐별 동적 임계값)
            double momentumScore = getMomentumConsistency();
            double absMomentum = Math.abs(momentumScore);
            double momentumMin = getRegimeMomentumMin();
            if (absMomentum < momentumMin) {
                addLogThrottled("📊", "모멘텀",
                        String.format("일관성 %.0f%% < %.0f%% (%s) → 방향 불명확",
                                absMomentum * 100, momentumMin * 100, regime.name()));
                lastFilterHit = "모멘텀"; return;
            }
            // 🔧 FIX #2: 모멘텀이 가격 방향과 반대면 스킵
            boolean priceUp = priceDiffPct > 0;
            boolean momentumUp = momentumScore > 0;
            if (priceUp != momentumUp) {
                addLogThrottled("📊", "모멘텀",
                        String.format("방향 불일치: 가격%s 모멘텀%s → 스킵",
                                priceUp ? "UP" : "DOWN", momentumUp ? "UP" : "DOWN"));
                lastFilterHit = "모멘텀역행"; return;
            }

            // 6. 방향 & 오즈
            String priceDir = priceDiffPct > 0 ? "UP" : "DOWN";
            double fwdMarketOdds = "UP".equals(priceDir) ? odds.upOdds() : odds.downOdds();

            // 🔧 FIX: 오즈 상한 필터 — 시장이 이미 방향 반영한 경우 스킵
            // 55¢+ = 시장이 55%+ 확률로 봄 → 우리 엣지 없음 + 손익비 불리
            if (fwdMarketOdds > 0.60) {
                addLogThrottled("🛡️", "오즈상한",
                        String.format("%s %.0f¢ > 60¢ → 시장 이미 반영, 손익비 불리 → 스킵",
                                priceDir, fwdMarketOdds * 100));
                lastFilterHit = "오즈상한"; return;
            }

            // ⚡ 티어드 조기진입 필터 (latency arbitrage)
            // 98% 승률 봇: 2~15초에 진입 → 오즈 미반영 윈도우 포착
            // 오즈가 싸다 = 시장이 모른다 = 빨리 들어가야 엣지 있다
            int candleElapsed = getCandleElapsedSeconds();
            if (candleElapsed < 40) {
                boolean earlyAllowed = false;
                String earlyReason = "";

                if (absDiff >= 0.10 && fwdMarketOdds <= 0.45) {
                    // ⚡ Tier 1: 15초~ | 큰 움직임 + 매우 싼 오즈 = 시장 완전 미반영
                    earlyAllowed = true;
                    earlyReason = String.format("T1: 움직임%.3f%%≥0.10 + 오즈%.0f¢≤45¢ → %d초 조기진입",
                            absDiff, fwdMarketOdds * 100, candleElapsed);
                } else if (absDiff >= 0.08 && fwdMarketOdds <= 0.50 && candleElapsed >= 30) {
                    // ⚡ Tier 2: 30초~ | 중간 움직임 + 적당 오즈
                    earlyAllowed = true;
                    earlyReason = String.format("T2: 움직임%.3f%%≥0.08 + 오즈%.0f¢≤50¢ → %d초 진입",
                            absDiff, fwdMarketOdds * 100, candleElapsed);
                }

                if (!earlyAllowed) {
                    addLogThrottled("⏳", "조기대기",
                            String.format("%d초 | 움직임%.3f%% 오즈%.0f¢ → 40초 대기 필요",
                                    candleElapsed, absDiff, fwdMarketOdds * 100));
                    lastFilterHit = "조기대기"; return;
                }
                addLog("⚡", "조기진입", earlyReason);
            }

            // ⭐ poly_bug 동일: 시간 보너스
            double timeBonus = getTimeBonus();

            // 7. ⭐ 확률 추정 + EV 계산 (순방향만, 역방향 비활성화)
            // 🔧 FIX #1: downOdds도 전달 (1-upOdds 사용 금지)
            EvCalculator.EvResult fwd = evCalculator.calcForward(
                    priceDiffPct, odds.upOdds(), odds.downOdds(),
                    velocity, momentumScore, timeBonus, balance);

            // 🔧 FIX #4: 단일 갭 기준으로 통일 + 레짐별 조정
            double adaptiveGap = getAdaptiveGap(BASE_FORWARD_GAP) + getRegimeGapAdj();

            if ("HOLD".equals(fwd.direction()) || fwd.gap() < adaptiveGap) {
                double displayOdds = priceDiffPct > 0 ? odds.upOdds() : odds.downOdds();
                String dirLabel = priceDiffPct > 0 ? "Up" : "Dn";
                addLogThrottled("🔍", "분석",
                        String.format("$%,.2f %+.3f%% | %s%.0f¢ | 추정%.0f%% 갭%.1f%%<%.1f%% EV%+.1f%% → HOLD",
                                currentPrice, priceDiffPct, dirLabel, displayOdds * 100,
                                fwd.estimatedProb() * 100, fwd.gap() * 100, adaptiveGap * 100, fwd.ev() * 100));
                lastFilterHit = "EV부족"; return;
            }

            lastFilterHit = "🎯 배팅!";
            // 8. 🎯 순방향 배팅 실행!
            long elapsed = (System.nanoTime() - scanStart) / 1_000_000;
            addLog("🎯", "배팅",
                    String.format("[FWD] %s $%.2f @ %.0f¢ | EV+%.1f%% | 갭%.1f%% | 모멘텀%.0f%%",
                            fwd.direction(), fwd.betAmount(), fwdMarketOdds * 100,
                            fwd.ev() * 100, fwd.gap() * 100, absMomentum * 100));
            executeTrade(fwd, odds, currentPrice, openPrice, priceDiffPct, elapsed);

        } catch (Exception e) {
            if (totalScans % 100 == 0) {
                log.warn("스캔 에러: {}", e.getMessage());
            }
        } finally {
            long scanNs = System.nanoTime() - scanStart;
            lastScanDurationUs = scanNs / 1_000;
            totalScanTimeMs += scanNs / 1_000_000;

            // ⚡ 초당 스캔수 계산 (1초 창)
            scanEpochCounter++;
            long now = System.currentTimeMillis();
            long elapsed = now - scanEpochStart;
            if (elapsed >= 1000) {
                currentScansPerSec = scanEpochCounter * 1000.0 / elapsed;
                scanEpochCounter = 0;
                scanEpochStart = now;
            }
        }
    }

    // =========================================================================
    // ⭐ 캔들 포지션 + 티어드 조기진입
    // 0~4초: ❌ 하드블록 (시초가 동기화 대기 — Chainlink WS ~1초 지연)
    // 5초~: T1/T2 조건 충족 시 조기진입
    // 40초~: 기본 진입 허용
    // 285초~: 마감 차단
    // =========================================================================
    private int getCandlePosition() {
        int elapsed = getCandleElapsedSeconds();

        if (elapsed < 5) return 0;   // 시초가 동기화 대기 (Chainlink ~1초 + 안전마진)
        if (elapsed >= 285) return -1; // 마감 15초 차단 (마켓 정산/교체 구간)
        double pct = (double) elapsed / 300;
        if (pct < 0.30) return 1;
        if (pct < 0.70) return 2;
        return 3;
    }

    /** 현재 5분봉 내 경과 시간 (초) */
    private int getCandleElapsedSeconds() {
        ZonedDateTime nowET = ZonedDateTime.now(ET);
        int minute = nowET.getMinute();
        int second = nowET.getSecond();
        return (minute % 5) * 60 + second;
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
    // ⭐ 가격 속도 추적 (%/초) — EMA 평활 (노이즈 제거)
    // =========================================================================
    private double trackVelocity(double currentPrice) {
        long now = System.currentTimeMillis();
        if (lastPrice <= 0 || lastPriceTime <= 0) {
            lastPrice = currentPrice;
            lastPriceTime = now;
            return 0.0;
        }
        double elapsed = (now - lastPriceTime) / 1000.0;
        if (elapsed < 0.05) return smoothedVelocity; // 50ms 미만이면 스킵

        double rawVel = ((currentPrice - lastPrice) / lastPrice * 100) / elapsed;
        lastPrice = currentPrice;
        lastPriceTime = now;

        // 🔧 FIX #5: EMA 평활 — 100ms 틱 노이즈 제거
        smoothedVelocity = VELOCITY_EMA_ALPHA * rawVel + (1 - VELOCITY_EMA_ALPHA) * smoothedVelocity;
        return smoothedVelocity;
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
    // ⭐ poly_bug 동일: 가격 레인지 추적 (60틱 고저) — 보조 로깅용
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
    // ⭐ CUSUM 필터 (Lopez de Prado, "Advances in Financial ML")
    //
    // 핵심 아이디어:
    //   - 수익률의 누적 이탈을 추적
    //   - S⁺ = max(0, S⁺ + (ret - E[ret]))  ← 상승 누적
    //   - S⁻ = min(0, S⁻ + (ret + E[ret]))  ← 하락 누적
    //   - |S⁺| > h OR |S⁻| > h 일 때 → 유의미한 방향 감지
    //
    // 장점:
    //   - 레인지 필터와 달리, 횡보에서 S값이 0으로 수렴 → 자동 필터
    //   - 한쪽으로 꾸준히 이동해야만 S값이 커짐 → 가짜 신호 감소
    //   - 임계값 h를 ATR 연동 → 변동성 적응
    //
    // BTC 5M 적용:
    //   - E[ret] = 0 (5분 내 기대수익률 ≈ 0)
    //   - h = ATR(14) × CUSUM_ATR_MULT (변동성 적응 임계값)
    //   - 캔들 시작마다 리셋 (각 캔들 독립)
    // =========================================================================
    private void trackCusum(double currentPrice) {
        if (lastCusumPrice <= 0) {
            lastCusumPrice = currentPrice;
            return;
        }

        // 수익률 계산 (%)
        double ret = ((currentPrice - lastCusumPrice) / lastCusumPrice) * 100;
        lastCusumPrice = currentPrice;

        // CUSUM 누적
        cusumPos = Math.max(0, cusumPos + (ret - CUSUM_EXPECTED_RETURN));
        cusumNeg = Math.min(0, cusumNeg + (ret + CUSUM_EXPECTED_RETURN));

        // 임계값 h (ATR 기반 동적)
        double h = getCusumThreshold();

        // 신호 발생: 누적 이탈이 임계값 초과
        if (cusumPos > h || Math.abs(cusumNeg) > h) {
            if (!cusumTriggered) {
                cusumTriggered = true;
                String dir = cusumPos > Math.abs(cusumNeg) ? "▲상승" : "▼하락";
                addLogThrottled("📈", "CUSUM",
                        String.format("신호! S⁺=%.4f S⁻=%.4f h=%.4f → %s 방향 확인",
                                cusumPos, cusumNeg, h, dir));
            }
        }
    }

    /** CUSUM 임계값 h = ATR × 멀티플라이어 (레짐별 자동 조정) */
    private double getCusumThreshold() {
        if (!chainlink.isATRReady()) return CUSUM_FALLBACK_H;
        double[] params = getRegimeParams();
        return chainlink.getATRPct() * params[3]; // params[3] = cusumMult
    }

    /** CUSUM 방향 (양수=상승압력, 음수=하락압력) */
    private double getCusumDirection() {
        return cusumPos > Math.abs(cusumNeg) ? cusumPos : -Math.abs(cusumNeg);
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
            // ⭐ CUSUM 리셋 (새 캔들 시작)
            cusumPos = 0;
            cusumNeg = 0;
            lastCusumPrice = 0;
            cusumTriggered = false;
        }
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 서킷브레이커 (3연패 → 5분 정지)
    // =========================================================================
    private void checkCircuitBreaker() {
        try {
            var recent = tradeRepository.findRecentResolved(10);
            if (recent.size() < 3) return;
            
            // 최신 trade ID 확인
            long latestTradeId = recent.get(0).getId();
            
            // 이미 이 trade 기준으로 서킷 발동한 적 있으면 스킵 (무한루프 방지)
            if (latestTradeId <= circuitTriggeredForTradeId) return;
            
            boolean threeConsecLoss = recent.stream().limit(3)
                    .allMatch(t -> t.getResult() == Trade.TradeResult.LOSE);
            if (threeConsecLoss) {
                circuitBreakerUntil = System.currentTimeMillis() + CIRCUIT_BREAKER_DURATION;
                circuitTriggeredForTradeId = latestTradeId; // 이 trade 기준 발동 기록
                addLog("🔴", "서킷", "3연패 감지 → 5분 정지!");
                log.warn("🔴 서킷브레이커 발동: BTC 5M 3연패 → 5분 정지 (trade #{})", latestTradeId);
            }
        } catch (Exception e) {
            log.debug("서킷브레이커 체크 오류: {}", e.getMessage());
        }
    }



    // =========================================================================
    // ⭐ ATR 기반 동적 임계값 + 변동성 레짐 연동
    // 레짐별 멀티플라이어가 자동 조정됨
    // =========================================================================

    /** 현재 레짐에 맞는 파라미터 배열 반환 */
    private double[] getRegimeParams() {
        return REGIME_PARAMS.getOrDefault(chainlink.getVolatilityRegime(),
                REGIME_PARAMS.get(ChainlinkPriceService.VolRegime.NORMAL));
    }

    /**
     * 동적 최소 변동폭: ATR(14) × entryMult (레짐별)
     * LOW:     ATR × 0.4 → 조용한 시장에서 작은 움직임도 포착
     * NORMAL:  ATR × 0.5 → 기본
     * HIGH:    ATR × 0.6 → 활발한 시장에서 노이즈 필터 강화
     * EXTREME: ATR × 0.7 → 극단 변동에서 보수적
     */
    private double getDynamicMinMove() {
        if (!chainlink.isATRReady()) return FALLBACK_MIN_MOVE;
        double[] params = getRegimeParams();
        double atrBased = chainlink.getATRPct() * params[0]; // params[0] = entryMult
        return Math.max(0.01, Math.min(0.10, atrBased));
    }

    /**
     * 동적 레인지 필터: ATR(14) × rangeMult (레짐별)
     * CUSUM이 주 필터지만, 캔들 내 고저차 보조 체크로 유지
     */
    private double getDynamicRangeThreshold() {
        if (!chainlink.isATRReady()) return FALLBACK_MIN_MOVE * 0.8;
        double[] params = getRegimeParams();
        double atrBased = chainlink.getATRPct() * params[1]; // params[1] = rangeMult
        return Math.max(0.008, Math.min(0.08, atrBased));
    }

    /** 레짐별 최소 모멘텀 일관성 (LOW에서 낮추고, EXTREME에서 높임) */
    private double getRegimeMomentumMin() {
        double[] params = getRegimeParams();
        return params[2]; // params[2] = momentumMin
    }

    /** 레짐별 EV 갭 조정 (LOW에서 공격적, EXTREME에서 방어적) */
    private double getRegimeGapAdj() {
        double[] params = getRegimeParams();
        return params[4]; // params[4] = gapAdj
    }

    // =========================================================================
    // ⭐ poly_bug 동일: 승률 기반 동적 임계값
    // =========================================================================
    // 🔧 FIX: 5분봉 현실 반영 — 승률 기반 동적 임계값
    private double getAdaptiveGap(double baseGap) {
        if (recentWinRate >= 0.65) return baseGap - 0.01; // 2% — 연승 중 공격적
        if (recentWinRate >= 0.55) return baseGap;         // 3% — 기본
        if (recentWinRate >= 0.45) return baseGap + 0.02;  // 5% — 초기/보통
        return baseGap + 0.04;                             // 7% — 연패 중 방어적
    }

    // =========================================================================
    // 배팅 실행
    // =========================================================================
    private void executeTrade(EvCalculator.EvResult ev, OddsService.MarketOdds odds,
                               double currentPrice, double openPrice, double priceDiffPct, long scanMs) {
        boolean isBuyYes = "UP".equals(ev.direction());
        Trade.TradeAction action = isBuyYes ? Trade.TradeAction.BUY_YES : Trade.TradeAction.BUY_NO;
        double mktOdds = isBuyYes ? odds.upOdds() : odds.downOdds();
        String tokenId = isBuyYes ? odds.upTokenId() : odds.downTokenId();

        // 🔧 FIX: 주문 전 실제 필요 USDC 계산 + 잔액 사전 검증
        double tickPrice = Math.round(mktOdds * 100.0) / 100.0;
        if (tickPrice <= 0) tickPrice = 0.50;
        double minUsdcNeeded = 5.0 * tickPrice; // 최소 5토큰 * 가격
        double verifiedBal = balanceService.getVerifiedBalance();
        if (verifiedBal < minUsdcNeeded) {
            addLog("💸", "잔액실패",
                    String.format("실잔액 $%.2f < 필요 $%.2f (최소5tok×%.0f¢) → 스킵",
                            verifiedBal, minUsdcNeeded, tickPrice * 100));
            return;
        }

        // 배팅액도 실잔액 기준으로 조정
        double adjustedBet = Math.min(ev.betAmount(), verifiedBal * 0.9); // 90%까지만 사용
        adjustedBet = Math.max(adjustedBet, minUsdcNeeded); // 최소값 보장

        // 🔧 FIX: 최소주문이 Kelly 의도의 2.5배 초과하면 스킵 (과도한 리스크 방지)
        if (adjustedBet > ev.betAmount() * 2.5 && ev.betAmount() > 0) {
            addLog("🛡️", "리스크",
                    String.format("최소주문 $%.2f > Kelly $%.2f × 2.5 → 과도한 리스크 스킵",
                            adjustedBet, ev.betAmount()));
            return;
        }

        if (adjustedBet > verifiedBal) {
            addLog("💸", "잔액실패", String.format("조정액 $%.2f > 잔액 $%.2f → 스킵", adjustedBet, verifiedBal));
            return;
        }

        totalTrades++;

        // ⚡ FOK 즉시 재시도 루프 (필터 재진입 없이 executeTrade 내부에서 처리)
        // 이유: scan() 전체 필터를 다시 타면 오즈 캐시 갱신으로 오즈상한(57¢)에 걸려 재시도 차단됨
        OrderService.OrderResult order = null;
        int retryCount = 0;
        double retryOdds = mktOdds;

        for (int attempt = 0; attempt <= MAX_FOK_RETRIES; attempt++) {
            order = orderService.placeOrder(tokenId, adjustedBet, retryOdds, "BUY", attempt);

            if (order.success()) break; // ✅ 체결 성공

            retryCount = attempt + 1;

            // ⭐ FOK 실패 DB 기록 (CANCELLED)
            try {
                Trade fokFail = Trade.builder()
                        .coin("BTC").timeframe("5M")
                        .action(action)
                        .result(Trade.TradeResult.CANCELLED)
                        .betAmount(adjustedBet)
                        .odds(retryOdds)
                        .entryPrice(currentPrice)
                        .openPrice(openPrice)
                        .estimatedProb(ev.estimatedProb())
                        .ev(ev.ev())
                        .gap(ev.gap())
                        .priceDiffPct(priceDiffPct)
                        .pnl(0)
                        .balanceAfter(balanceService.getBalance())
                        .marketId(odds.marketId())
                        .strategy("FOK_FAIL")
                        .reason(String.format("FOK실패 #%d/%d | %s", retryCount, MAX_FOK_RETRIES, order.error()))
                        .detail(String.format("retryCount=%d | slippage=+%d틱 | targetOdds=%.0f¢ | momentum=%.2f | error=%s",
                                retryCount, (retryCount * 2) + 1, retryOdds * 100, getMomentumConsistency(), order.error()))
                        .scanToTradeMs(scanMs)
                        .orderStatus("FOK_FAIL")
                        .tokenId(tokenId)
                        .resolvedAt(LocalDateTime.now())
                        .build();
                tradeRepository.save(fokFail);
            } catch (Exception e) {
                log.debug("FOK 실패 DB 저장 실패: {}", e.getMessage());
            }

            if (retryCount >= MAX_FOK_RETRIES) {
                // 3회 소진 → 이 캔들 포기
                lastTradedCandleWindow = getCurrentCandleWindow();
                addLog("❌", "주문실패", String.format("FOK %d회 실패 → 이 캔들 포기: %s", MAX_FOK_RETRIES, order.error()));
                log.warn("❌ FOK {}회 연속 실패 → 캔들 포기", MAX_FOK_RETRIES);
                if (!dryRun) {
                    scanExecutor.schedule(() -> balanceService.refreshIfLive(), 1, TimeUnit.SECONDS);
                }
                totalTrades--;
                return;
            }

            // ⚡ 즉시 재시도: +2틱 올려서 다시 주문 (scan 재진입 없음)
            retryOdds = retryOdds + 0.02;
            if (retryOdds > 0.60) {
                // 60¢ 초과하면 손익비 너무 불리 → 포기
                lastTradedCandleWindow = getCurrentCandleWindow();
                addLog("❌", "주문실패", String.format("재시도 오즈 %.0f¢ > 60¢ → 손익비 불리, 포기", retryOdds * 100));
                log.warn("❌ 재시도 오즈 {}¢ > 60¢ → 포기", String.format("%.0f", retryOdds * 100));
                if (!dryRun) {
                    scanExecutor.schedule(() -> balanceService.refreshIfLive(), 1, TimeUnit.SECONDS);
                }
                totalTrades--;
                return;
            }

            addLog("⚠️", "FOK재시도", String.format("실패 #%d → %.0f¢로 즉시 재시도 (%d/%d)",
                    retryCount, retryOdds * 100, retryCount, MAX_FOK_RETRIES));
            log.info("⚠️ FOK 실패 #{} → {}¢로 즉시 재시도", retryCount, String.format("%.0f", retryOdds * 100));

            // 50ms 대기 (CLOB 서버 부하 방지)
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        // FOK 전부 실패한 경우 (위 루프에서 return됨, 여기는 성공 케이스만 도달)
        if (order == null || !order.success()) {
            totalTrades--;
            return;
        }

        // ✅ 체결 성공 → 캔들 잠금
        lastTradedCandleWindow = getCurrentCandleWindow();

        // 실제 배팅 금액 (최소 5토큰 제약 반영)
        double actualBet = order.actualAmount() > 0 ? order.actualAmount() : adjustedBet;
        balanceService.deductBet(actualBet);

        // LIVE: 주문 후 실잔액 재동기화 (2초 후)
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
                .orderStatus(order.status())
                .orderId(order.orderId())
                .balanceAtBet(balanceService.getLiveBalance()) // 배팅 직후 Polymarket 실잔액
                .tokenId(tokenId) // 배팅한 토큰 ID (CTF redeem용)
                .actualSize(order.actualSize()) // 실제 체결 토큰 수
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
        if (last != null && now - last < 100) return;
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

    /** ⚡ 경량 스캔 메트릭 (500ms 폴링용, DB 조회 없음) */
    public ScanMetrics getScanMetrics() {
        return new ScanMetrics(
                totalScans,
                currentScansPerSec,
                lastScanDurationUs,
                lastFilterHit,
                enabled,
                chainlink.isConnected(),
                chainlink.isWarmedUp(),
                chainlink.getATRPct(),
                getDynamicMinMove(),
                getDynamicRangeThreshold(),
                chainlink.getVolatilityRegime().name(),
                chainlink.getVolatilityRegimeLabel(),
                cusumPos,
                cusumNeg,
                cusumTriggered,
                getCusumThreshold()
        );
    }

    public record ScanMetrics(
            int totalScans,
            double scansPerSec,      // 초당 스캔수
            long lastScanUs,         // 마지막 스캔 소요(µs)
            String lastFilter,       // 마지막 필터
            boolean enabled,
            boolean connected,
            boolean warmedUp,
            double atrPct,           // ATR(14) %
            double dynamicMinMove,   // 현재 동적 최소변동폭
            double dynamicRangeThreshold, // 현재 동적 레인지 임계값
            String regime,           // 변동성 레짐 (LOW/NORMAL/HIGH/EXTREME)
            String regimeLabel,      // 레짐 라벨 (이모지 포함)
            double cusumPos,         // CUSUM S⁺
            double cusumNeg,         // CUSUM S⁻
            boolean cusumTriggered,  // CUSUM 신호 발생 여부
            double cusumThreshold    // CUSUM 임계값 h
    ) {}
}
