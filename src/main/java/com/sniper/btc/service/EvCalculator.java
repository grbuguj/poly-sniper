package com.sniper.btc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ⚡ EV 계산기 — poly_bug ExpectedValueCalculator 정합 버전
 *
 * poly_bug 검증 로직:
 * 1. 확률 추정: 변동폭 구간별 baseProb + 속도·모멘텀·시간 보너스
 * 2. 순방향 EV: (추정확률 / 시장오즈) - 1, 오즈 20-80% 클램프
 * 3. Kelly 배팅: EV 비례 동적 사이즈 (2-12%)
 * 4. 역방향: 시장 과잉반응 시 반대 배팅 (오즈 5-95% 클램프, Kelly 15-25%)
 */
@Slf4j
@Service
public class EvCalculator {

    @Value("${sniper.min-bet:1.0}")
    private double minBet;

    @Value("${sniper.max-bet:10.0}")
    private double maxBet;

    // poly_bug 동일: 순방향 오즈 범위
    private static final double FWD_MIN_ODDS = 0.20;
    private static final double FWD_MAX_ODDS = 0.80;
    private static final double MAX_EV = 0.80;
    private static final double FWD_THRESHOLD = 0.08; // poly_bug: 8%

    // ⭐ poly_bug 동일: 역방향 오즈 범위 (싼 오즈가 핵심)
    private static final double REV_MIN_ODDS = 0.05;
    private static final double REV_MAX_ODDS = 0.95;
    private static final double REV_THRESHOLD = 0.15; // 역방향은 15% (더 보수적)

    public record EvResult(
            String direction,    // UP / DOWN / HOLD
            double ev,
            double estimatedProb,
            double gap,
            double betAmount,
            String strategy,     // FWD
            String reason
    ) {}

    /**
     * 순방향 EV 계산 — poly_bug estimateProbFromPriceMove + calculateMomentum 통합
     *
     * @param priceDiffPct  시초가 대비 변동률
     * @param upOdds        Up 오즈
     * @param velocity      가격 변동 속도 (%/초)
     * @param momentumScore 모멘텀 일관성 (-1~+1)
     * @param timeBonus     캔들 진행도 보너스
     * @param balance       현재 잔액
     */
    public EvResult calcForward(double priceDiffPct, double upOdds,
                                 double velocity, double momentumScore,
                                 double timeBonus, double balance) {
        boolean isUp = priceDiffPct > 0;
        double absDiff = Math.abs(priceDiffPct);

        // ⭐ poly_bug 동일: 확률 추정 (구간별 baseProb)
        // 🔧 FIX: signed priceDiffPct 전달 (velocity 방향 불일치 페널티용)
        double baseProb = estimateProb(priceDiffPct, velocity, momentumScore, timeBonus);

        // 오즈 클램프
        double clampedUp = clamp(upOdds, FWD_MIN_ODDS, FWD_MAX_ODDS);
        double targetOdds = isUp ? clampedUp : (1.0 - clampedUp);

        // EV 계산
        double ev = Math.min((baseProb / targetOdds) - 1.0, MAX_EV);
        double gap = baseProb - targetOdds;

        if (ev <= FWD_THRESHOLD) {
            return new EvResult("HOLD", ev, baseProb, gap, 0, "FWD",
                    String.format("FWD EV%.1f%% ≤ 임계%.0f%%", ev * 100, FWD_THRESHOLD * 100));
        }

        double bet = calcBetSize(balance, ev, targetOdds);
        String dir = isUp ? "UP" : "DOWN";

        return new EvResult(dir, ev, baseProb, gap, bet, "FWD",
                String.format("FWD %s | 가격%+.3f%% | 추정%.0f%% vs 오즈%.0f%% | EV+%.1f%%",
                        dir, priceDiffPct, baseProb * 100, targetOdds * 100, ev * 100));
    }

    /**
     * ⭐ poly_bug estimateProbFromPriceMove 동일 구현 (5M 전용)
     * @param changePct signed 가격 변동률 (양수=UP, 음수=DOWN)
     */
    private double estimateProb(double changePct, double velocity, double momentumScore, double timeBonus) {
        double absPct = Math.abs(changePct);

        // 5M 타임프레임 보너스
        double tfBonus = 0.05;

        // 속도 보너스
        double velocityBonus = 0.0;
        double absVelocity = Math.abs(velocity);
        if (absVelocity >= 0.05)      velocityBonus = 0.06;
        else if (absVelocity >= 0.02) velocityBonus = 0.04;
        else if (absVelocity >= 0.01) velocityBonus = 0.02;

        // 🔧 FIX: poly_bug 동일 — 속도 역방향이면 -0.02로 덮어쓰기
        // 가격은 올라가는데 속도는 하락 중 (또는 그 반대) = 반전 징후
        if ((changePct > 0 && velocity < 0) || (changePct < 0 && velocity > 0)) {
            velocityBonus = -0.02;
        }

        // 모멘텀 일관성 보너스
        double momentumBonus = 0.0;
        double absMomentum = Math.abs(momentumScore);
        if (absMomentum >= 0.8) momentumBonus = 0.04;
        else if (absMomentum >= 0.6) momentumBonus = 0.02;
        else if (absMomentum < 0.3) momentumBonus = -0.02;

        double bonus = tfBonus + timeBonus + velocityBonus + momentumBonus;

        // ⭐ poly_bug 동일: 구간별 기본 확률
        double baseProb;
        if (absPct >= 1.0)       baseProb = 0.85;
        else if (absPct >= 0.7)  baseProb = 0.80;
        else if (absPct >= 0.5)  baseProb = 0.73;
        else if (absPct >= 0.35) baseProb = 0.66;
        else if (absPct >= 0.25) baseProb = 0.61;
        else if (absPct >= 0.15) baseProb = 0.57;
        else if (absPct >= 0.10) baseProb = 0.54;
        else if (absPct >= 0.08) baseProb = 0.52;
        else                     baseProb = 0.51;

        return clamp(baseProb + bonus, 0.50, 0.92);
    }

    /**
     * ⭐ poly_bug calcBetSize 동일: Kelly Criterion (EV 비례)
     */
    double calcBetSize(double balance, double ev, double marketOdds) {
        if (ev <= 0) return 0;
        marketOdds = clamp(marketOdds, FWD_MIN_ODDS, FWD_MAX_ODDS);

        double payout = 1.0 / marketOdds;
        double kellyFraction = ev / (payout - 1.0);

        double kellyMultiplier;
        if (ev >= 1.0)      kellyMultiplier = 0.35;
        else if (ev >= 0.5) kellyMultiplier = 0.30;
        else if (ev >= 0.3) kellyMultiplier = 0.25;
        else                kellyMultiplier = 0.20;

        double safeFraction = kellyFraction * kellyMultiplier;
        safeFraction = clamp(safeFraction, 0.02, 0.12);

        double bet = balance * safeFraction;
        return clamp(bet, minBet, maxBet);
    }

    /**
     * ⭐ 역방향 EV 계산 — 시장 과잉반응, 반대쪽 저평가
     *
     * 핵심: 가격이 크게 올랐는데 시장이 DOWN 오즈를 너무 낮게 책정 → DOWN 배팅
     * 예: BTC +0.5% → 시장 DOWN 12¢ → 추정 반전확률 27% → EV = (27/12)-1 = +125%
     *
     * @param priceDiffPct     시초가 대비 변동률 (순방향 기준)
     * @param fwdMarketOdds    순방향 오즈 (이게 높으면 = 역방향이 싸다)
     * @param reverseMarketOdds 역방향 오즈 (실제 배팅할 쪽)
     * @param fwdEstimatedProb 순방향 추정 확률
     * @param balance          현재 잔액
     */
    public EvResult calcReverse(double priceDiffPct, double fwdMarketOdds,
                                 double reverseMarketOdds, double fwdEstimatedProb,
                                 double balance) {
        // 역방향 추정 확률 = 1 - 순방향 확률
        double reverseEstProb = 1.0 - fwdEstimatedProb;
        // poly_bug 동일: 역방향 확률 15-60% 클램프 (뻥튀기 방지)
        reverseEstProb = clamp(reverseEstProb, 0.15, 0.60);

        // 역방향 오즈 클램프 (5-95% — 싼 오즈의 진짜 가치 계산)
        double clampedRevOdds = clamp(reverseMarketOdds, REV_MIN_ODDS, REV_MAX_ODDS);

        double ev = Math.min((reverseEstProb / clampedRevOdds) - 1.0, MAX_EV);
        double gap = reverseEstProb - clampedRevOdds;

        // 역방향은 가격 반대
        boolean priceIsUp = priceDiffPct > 0;
        String revDir = priceIsUp ? "DOWN" : "UP";

        if (ev <= REV_THRESHOLD) {
            return new EvResult("HOLD", ev, reverseEstProb, gap, 0, "REV",
                    String.format("REV EV%.1f%% ≤ 임계%.0f%%", ev * 100, REV_THRESHOLD * 100));
        }

        double bet = calcReverseBetSize(balance, ev, clampedRevOdds);

        return new EvResult(revDir, ev, reverseEstProb, gap, bet, "REV",
                String.format("REV %s | 가격%+.3f%% | 추정%.0f%% vs 오즈%.0f¢ | EV+%.1f%%",
                        revDir, priceDiffPct, reverseEstProb * 100, reverseMarketOdds * 100, ev * 100));
    }

    /**
     * ⭐ poly_bug 동일: 역방향 전용 배팅 사이즈 (더 보수적)
     * Kelly 15-25%, 잔액 2-8%
     */
    private double calcReverseBetSize(double balance, double ev, double marketOdds) {
        if (ev <= 0) return 0;
        marketOdds = clamp(marketOdds, REV_MIN_ODDS, REV_MAX_ODDS);

        double payout = 1.0 / marketOdds;
        double kellyFraction = ev / (payout - 1.0);

        double kellyMultiplier;
        if (ev >= 1.5)      kellyMultiplier = 0.25;
        else if (ev >= 0.8) kellyMultiplier = 0.20;
        else                kellyMultiplier = 0.15;

        double safeFraction = kellyFraction * kellyMultiplier;
        safeFraction = clamp(safeFraction, 0.02, 0.08); // 2-8% (순방향보다 보수적)

        double bet = balance * safeFraction;
        return clamp(bet, minBet, maxBet);
    }

    /**
     * 순방향 확률 추정값을 외부에서 접근 (역방향 계산용)
     */
    public double estimateProbPublic(double changePct, double velocity, double momentumScore, double timeBonus) {
        return estimateProb(changePct, velocity, momentumScore, timeBonus);
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
