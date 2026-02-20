package com.sniper.btc.service;

import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 잔액 추적 (DRY-RUN + LIVE 공용)
 *
 * LIVE 모드: Polymarket API에서 실제 USDC 잔액 조회
 * DRY-RUN:  initial-balance + DB 거래 내역으로 시뮬레이션
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final TradeRepository tradeRepository;
    private final OrderService orderService;

    @Value("${sniper.initial-balance:50.0}")
    private double configInitialBalance;

    @Value("${sniper.dry-run:true}")
    private boolean dryRun;

    private final AtomicReference<Double> balance = new AtomicReference<>(0.0);
    
    // Polymarket 실잔액 (주기적 동기화)
    private final AtomicReference<Double> liveBalance = new AtomicReference<>(-1.0);
    private volatile long lastLiveSyncMs = 0;

    // LIVE 모드: 폴리마켓 실제 시작 잔액 (최초 1회만 설정)
    private volatile double initialBalance;
    private volatile boolean initialBalanceCaptured = false;
    
    private final ScheduledExecutorService syncExecutor = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        if (!dryRun && orderService.isLive()) {
            captureInitialBalance();
            syncFromPolymarket();
            // 🔄 10초마다 Polymarket 실잔액 자동 동기화
            syncExecutor.scheduleAtFixedRate(this::periodicLiveSync, 10, 10, TimeUnit.SECONDS);
            log.info("🔄 Polymarket 잔액 자동 동기화 시작 (10초 간격)");
        } else {
            initialBalance = configInitialBalance;
            recalcFromDb();
        }
    }

    /**
     * LIVE 모드: 폴리마켓 실제 잔액을 시작 자금으로 설정
     *
     * 🔧 FIX: PnL 역산 제거 — CANCELLED 트레이드가 실제 손실을 반영 못해서 틀어짐
     *   → 재시작 시 현재 Polymarket 잔액을 시작 자금으로 사용
     *   → 이전 세션 기록은 초기화 버튼으로 정리
     */
    private void captureInitialBalance() {
        if (initialBalanceCaptured) return;
        double live = orderService.fetchLiveBalance();
        if (live < 0) {
            initialBalance = configInitialBalance;
            log.warn("⚠️ 폴리마켓 잔액 조회 실패 → 설정값 ${} 사용", fmt(configInitialBalance));
            return;
        }
        initialBalance = live;
        initialBalanceCaptured = true;
        log.info("💰 LIVE 시작 자금 설정: ${} (현재 Polymarket 잔액 기준)", fmt(initialBalance));
    }

    /**
     * Polymarket API에서 실제 잔액 동기화 (LIVE 모드)
     */
    public void syncFromPolymarket() {
        double live = orderService.fetchLiveBalance();
        if (live >= 0) {
            balance.set(live);
            liveBalance.set(live);
            lastLiveSyncMs = System.currentTimeMillis();
            log.info("💰 LIVE 잔액 동기화: ${} (Polymarket 실잔액)", fmt(live));
        } else {
            log.warn("⚠️ Polymarket 잔액 조회 실패 → DB 기반 복원 fallback");
            recalcFromDb();
        }
    }

    /**
     * 🔄 주기적 Polymarket 실잔액 동기화 (10초마다)
     */
    private void periodicLiveSync() {
        try {
            double live = orderService.fetchLiveBalance();
            if (live >= 0) {
                double prev = liveBalance.getAndSet(live);
                lastLiveSyncMs = System.currentTimeMillis();
                balance.set(live);
                
                // 잔액 변동 시만 로그
                if (Math.abs(live - prev) > 0.01) {
                    log.info("🔄 잔액 동기화: ${} → ${} ({}${})",
                            fmt(prev), fmt(live),
                            live > prev ? "+" : "",
                            fmt(live - prev));
                }
            }
        } catch (Exception e) {
            log.debug("잔액 동기화 실패: {}", e.getMessage());
        }
    }

    /**
     * Polymarket 실잔액 (대시보드 표시용)
     */
    public double getLiveBalance() {
        double lb = liveBalance.get();
        return lb >= 0 ? lb : balance.get();
    }

    /**
     * 마지막 동기화 시간 (ms)
     */
    public long getLastLiveSyncMs() {
        return lastLiveSyncMs;
    }

    /**
     * DB 거래 내역 기반 잔액 계산 (DRY-RUN 또는 fallback)
     */
    public void recalcFromDb() {
        // LIVE 모드면 폴리마켓에서 실잔액 조회
        if (!dryRun && orderService.isLive()) {
            syncFromPolymarket();
            return;
        }
        double bal = initialBalance;
        List<Trade> all = tradeRepository.findAll();
        for (Trade t : all) {
            if (t.getAction() == Trade.TradeAction.HOLD) continue;
            bal -= t.getBetAmount();
            if (t.getResult() == Trade.TradeResult.WIN) {
                bal += t.getBetAmount() / t.getOdds();
            }
        }
        balance.set(bal);
        log.info("💰 잔액 복원: ${} (초기 ${}, 거래 {}건)", fmt(bal), fmt(initialBalance), all.size());
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        syncExecutor.shutdownNow();
    }

    public double getBalance() {
        return balance.get();
    }

    public double getInitialBalance() {
        return initialBalance;
    }

    public void deductBet(double amount) {
        balance.updateAndGet(b -> b - amount);
    }

    public void addWinnings(double betAmount, double odds) {
        double payout = betAmount / odds;
        balance.updateAndGet(b -> b + payout);
    }

    /**
     * 배팅 전 실잔액 재동기화 (LIVE 모드)
     * 내부 추적 잔액과 실제 잔액 괴리 방지
     */
    public void refreshIfLive() {
        if (!dryRun && orderService.isLive()) {
            syncFromPolymarket();
        }
    }

    /**
     * 리셋 시 LIVE 초기 잔액 재설정
     * DB 전부 삭제 후 현재 폴리마켓 잔액을 새 시작점으로
     */
    public void resetInitialBalance() {
        initialBalanceCaptured = false;
        if (!dryRun && orderService.isLive()) {
            double liveBalance = orderService.fetchLiveBalance();
            if (liveBalance >= 0) {
                initialBalance = liveBalance;
                balance.set(liveBalance);
                initialBalanceCaptured = true;
                log.info("🔄 LIVE 시작 자금 리셋: ${}", fmt(liveBalance));
            }
        } else {
            initialBalance = configInitialBalance;
            balance.set(configInitialBalance);
        }
    }

    // =========================================================================
    // 🔧 FIX: Redeem 대기 — WIN 후 Polymarket 잔액이 실제로 증가할 때까지 폴링
    // =========================================================================
    private volatile boolean redeemPolling = false;
    private volatile long redeemPollStartTime = 0;
    private volatile double redeemExpectedBalance = 0;

    // API 호출 스로틀링: 일반 5초, redeem 대기중 10초
    private volatile long lastVerifiedAt = 0;
    private volatile double lastVerifiedBalance = 0;

    /**
     * WIN 판정 후 호출 — Polymarket 자동 redeem 반영 대기
     * @param expectedPayout 예상 수령액 (betAmount / odds)
     */
    public void startRedeemPolling(double expectedPayout) {
        if (dryRun || !orderService.isLive()) return;
        double currentBalance = fetchCurrentBalance();
        redeemExpectedBalance = currentBalance + expectedPayout * 0.8; // 80% 도달 시 성공 간주
        redeemPollStartTime = System.currentTimeMillis();
        redeemPolling = true;
        log.info("⏳ Redeem 대기 시작: 현재 ${} → 목표 ${} (payout ${})",
                fmt(currentBalance), fmt(redeemExpectedBalance), fmt(expectedPayout));
    }

    /**
     * 배팅 전 호출 — redeem 완료 여부 확인 + 잔액 동기화
     * @return 사용 가능한 실제 잔액 (redeem 미완료면 대기 후 재조회)
     */
    public double getVerifiedBalance() {
        if (dryRun || !orderService.isLive()) return balance.get();

        // 스로틀링: 일반 5초, redeem 대기중 10초
        long now = System.currentTimeMillis();
        long cacheTtl = redeemPolling ? 10_000 : 5_000;
        if (now - lastVerifiedAt < cacheTtl && lastVerifiedBalance > 0) {
            return lastVerifiedBalance;
        }

        // redeem 폴링 중이면 확인
        if (redeemPolling) {
            long elapsed = System.currentTimeMillis() - redeemPollStartTime;
            double currentLive = fetchCurrentBalance();
            lastVerifiedAt = now;

            if (currentLive >= redeemExpectedBalance) {
                // ✅ Redeem 완료!
                redeemPolling = false;
                balance.set(currentLive);
                lastVerifiedBalance = currentLive;
                log.info("✅ Redeem 완료 감지: ${} ({}초 소요)", fmt(currentLive), elapsed / 1000);
                return currentLive;
            }

            if (elapsed > 180_000) { // 3분 초과 → 타임아웃
                redeemPolling = false;
                balance.set(currentLive);
                lastVerifiedBalance = currentLive;
                log.warn("⏰ Redeem 대기 타임아웃 (3분): 현재 ${}", fmt(currentLive));
                return currentLive;
            }

            // 아직 대기 중 — 현재 잔액 반환 (부족할 수 있음)
            balance.set(currentLive);
            lastVerifiedBalance = currentLive;
            log.debug("⏳ Redeem 대기 중: ${} < 목표 ${} ({}초)",
                    fmt(currentLive), fmt(redeemExpectedBalance), elapsed / 1000);
            return currentLive;
        }

        // 일반 동기화
        syncFromPolymarket();
        lastVerifiedAt = now;
        lastVerifiedBalance = balance.get();
        return balance.get();
    }

    public boolean isRedeemPending() {
        return redeemPolling;
    }

    private double fetchCurrentBalance() {
        double live = orderService.fetchLiveBalance();
        return live >= 0 ? live : balance.get();
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
