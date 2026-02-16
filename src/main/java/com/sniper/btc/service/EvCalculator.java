package com.sniper.btc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ⚡ EV 계산기 — 순수 수학, AI 없음
 *
 * poly_bug의 ExpectedValueCalculator에서 핵심만 추출
 * 5M BTC 승률 76% 기반 Kelly 동적 배팅
 *
 * 순방향: 가격 방향과 같은쪽 배팅 (모멘텀)
 * 역방향: 오즈가 과잉반응했을 때 반대쪽 배팅 (평균회귀)
 */
@Slf4j
@Service
public class EvCalculator {

    @Value("${sniper.min-ev:0.10}")
    private double minEv;

    @Value("${sniper.min-bet:1.0}")
    private double minBet;

    @Value("${sniper.max-bet:10.0}")
    private double maxBet;

    // 순방향 오즈 범위
    private static final double FWD_MIN_ODDS = 0.20;
    private static final double FWD_MAX_ODDS = 0.80;

    // 역방향 오즈 범위
    private static final double REV_MIN_ODDS = 0.05;
    private static final double REV_MAX_ODDS = 0.95;

    private static final double MAX_EV = 0.80;

    public record EvResult(
            String direction,    // UP / DOWN / HOLD
            double ev,
            double estimatedProb,
            double gap,
            double betAmount,
            String strategy,     // FWD / REV
            String reason
    ) {}

    /**
     * 순방향 EV 계산 (가격 모멘텀 추종)
     *
     * @param priceDiffPct  시초가 대비 변동률 (양수=상승, 음수=하락)
     * @param upOdds        Up 오즈
     * @param winRate       최근 승률
     * @param balance       현재 잔액
     */
    public EvResult calcForward(double priceDiffPct, double upOdds, double winRate, double balance) {
        // 가격 방향 결정
        boolean isUp = priceDiffPct > 0;
        double absDiff = Math.abs(priceDiffPct);

        // 변동폭 기반 추정 확률 (5M BTC 76% 승률 + 변동폭 보정)
        double baseProb = 0.55 + Math.min(absDiff * 2.5, 0.30); // 변동폭 클수록 확률 상승
        baseProb = clamp(baseProb, 0.50, 0.85);

        // 오즈 클램프
        double clampedUp = clamp(upOdds, FWD_MIN_ODDS, FWD_MAX_ODDS);
        double clampedDown = 1.0 - clampedUp;

        // EV 계산
        double targetOdds = isUp ? clampedUp : clampedDown;
        double ev = Math.min((baseProb / targetOdds) - 1.0, MAX_EV);
        double gap = baseProb - targetOdds;

        if (ev < minEv || gap < 0.03) {
            return new EvResult("HOLD", ev, baseProb, gap, 0,
                    "FWD", String.format("FWD EV부족 %.1f%% (임계%.1f%%)", ev * 100, minEv * 100));
        }

        double bet = calcKellyBet(ev, balance, winRate);
        String dir = isUp ? "UP" : "DOWN";

        return new EvResult(dir, ev, baseProb, gap, bet, "FWD",
                String.format("FWD %s | 가격%+.3f%% | 추정%.0f%% vs 오즈%.0f%% | EV%+.1f%%",
                        dir, priceDiffPct, baseProb * 100, targetOdds * 100, ev * 100));
    }

    /**
     * 역방향 EV 계산 (오즈 과잉반응 역이용)
     *
     * @param priceDiffPct  시초가 대비 변동률
     * @param upOdds        Up 오즈
     * @param winRate       최근 승률
     * @param balance       현재 잔액
     */
    public EvResult calcReverse(double priceDiffPct, double upOdds, double winRate, double balance) {
        boolean priceUp = priceDiffPct > 0;

        // 역방향 = 가격 반대쪽의 오즈가 너무 싸면 매수
        double reverseOdds = priceUp ? (1.0 - upOdds) : upOdds;
        double clampedReverse = clamp(reverseOdds, REV_MIN_ODDS, REV_MAX_ODDS);

        // 역방향 추정 확률: 변동폭 작으면 확률 높음 (평균회귀)
        double absDiff = Math.abs(priceDiffPct);
        double reverseProbBase = 0.45 - Math.min(absDiff * 1.5, 0.15);
        reverseProbBase = clamp(reverseProbBase, 0.25, 0.50);

        // 오즈가 매우 싸면 (< 0.15) 추가 보정
        if (clampedReverse < 0.15) {
            reverseProbBase += 0.10;
        }

        double ev = Math.min((reverseProbBase / clampedReverse) - 1.0, MAX_EV);
        double gap = reverseProbBase - clampedReverse;

        if (ev < minEv || gap < 0.06) {
            return new EvResult("HOLD", ev, reverseProbBase, gap, 0,
                    "REV", String.format("REV EV부족 %.1f%%", ev * 100));
        }

        double bet = calcKellyBet(ev, balance, winRate) * 0.7; // 역방향은 70% 사이즈
        bet = clamp(bet, minBet, maxBet);
        String dir = priceUp ? "DOWN" : "UP"; // 가격 반대

        return new EvResult(dir, ev, reverseProbBase, gap, bet, "REV",
                String.format("REV %s | 가격%+.3f%% | 추정%.0f%% vs 오즈%.0f%% | EV%+.1f%%",
                        dir, priceDiffPct, reverseProbBase * 100, clampedReverse * 100, ev * 100));
    }

    /**
     * Kelly Criterion 동적 배팅 사이즈
     * EV 크기에 비례, 잔액의 2~12%
     */
    private double calcKellyBet(double ev, double balance, double winRate) {
        double kellyFraction;
        if (ev >= 0.50) {
            kellyFraction = 0.08 + (ev - 0.50) * 0.13; // 8-12%
        } else if (ev >= 0.30) {
            kellyFraction = 0.04 + (ev - 0.30) * 0.20; // 4-8%
        } else {
            kellyFraction = 0.02 + (ev - minEv) * 0.10;  // 2-4%
        }

        // 승률 보정
        if (winRate > 0.70) kellyFraction *= 1.15;
        if (winRate < 0.50) kellyFraction *= 0.70;

        kellyFraction = clamp(kellyFraction, 0.02, 0.12);
        double bet = balance * kellyFraction;
        return clamp(bet, minBet, maxBet);
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
