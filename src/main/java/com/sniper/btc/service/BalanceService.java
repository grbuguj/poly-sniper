package com.sniper.btc.service;

import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
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

    // LIVE 모드: 폴리마켓 실제 시작 잔액 (최초 1회만 설정)
    private volatile double initialBalance;
    private volatile boolean initialBalanceCaptured = false;

    @PostConstruct
    public void init() {
        if (!dryRun && orderService.isLive()) {
            captureInitialBalance();
            syncFromPolymarket();
        } else {
            initialBalance = configInitialBalance;
            recalcFromDb();
        }
    }

    /**
     * LIVE 모드: 폴리마켓 실제 잔액을 시작 자금으로 설정
     * DB에 기존 거래가 있으면 (PnL 총합)을 역산해서 시작 잔액 추정
     */
    private void captureInitialBalance() {
        if (initialBalanceCaptured) return;
        double liveBalance = orderService.fetchLiveBalance();
        if (liveBalance < 0) {
            initialBalance = configInitialBalance;
            log.warn("⚠️ 폴리마켓 잔액 조회 실패 → 설정값 ${} 사용", fmt(configInitialBalance));
            return;
        }
        // DB 거래 내역의 PnL 총합을 역산해서 시작 잔액 추정
        double totalPnl = tradeRepository.findAll().stream()
                .filter(t -> t.getAction() != Trade.TradeAction.HOLD)
                .mapToDouble(Trade::getPnl).sum();
        initialBalance = liveBalance - totalPnl;
        initialBalanceCaptured = true;
        log.info("💰 LIVE 시작 자금 설정: ${} (현재 ${}, PnL ${})",
                fmt(initialBalance), fmt(liveBalance), fmt(totalPnl));
    }

    /**
     * Polymarket API에서 실제 잔액 동기화 (LIVE 모드)
     */
    public void syncFromPolymarket() {
        double liveBalance = orderService.fetchLiveBalance();
        if (liveBalance >= 0) {
            balance.set(liveBalance);
            log.info("💰 LIVE 잔액 동기화: ${} (Polymarket 실잔액)", fmt(liveBalance));
        } else {
            log.warn("⚠️ Polymarket 잔액 조회 실패 → DB 기반 복원 fallback");
            recalcFromDb();
        }
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

    private String fmt(double v) { return String.format("%.2f", v); }
}
