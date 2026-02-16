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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final TradeRepository tradeRepository;

    @Value("${sniper.initial-balance:50.0}")
    private double initialBalance;

    private final AtomicReference<Double> balance = new AtomicReference<>(0.0);

    @PostConstruct
    public void init() {
        recalcFromDb();
    }

    public void recalcFromDb() {
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

    public void deductBet(double amount) {
        balance.updateAndGet(b -> b - amount);
    }

    public void addWinnings(double betAmount, double odds) {
        double payout = betAmount / odds;
        balance.updateAndGet(b -> b + payout);
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
