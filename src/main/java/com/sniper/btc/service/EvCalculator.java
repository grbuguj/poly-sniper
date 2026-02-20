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

    @Value("${sniper.max-bet:50.0}")
    private double maxBet;

    @Value("${sniper.initial-balance:80.0}")
    private double initialBalance;

    // poly_bug 동일: 순방향 오즈 범위
    private static final double FWD_MIN_ODDS = 0.20;
    private static final double FWD_MAX_ODDS = 0.80;
    private static final double MAX_EV = 0.80;
    private static final double FWD_THRESHOLD = 0.05; // 🔧 FIX: 0.08→0.05 (5분봉 현실 반영)

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
     * @param downOdds      Down 오즈 (실제 CLOB 가격)
     * @param velocity      가격 변동 속도 (%/초, EMA 평활)
     * @param momentumScore 모멘텀 일관성 (-1~+1)
     * @param timeBonus     캔들 진행도 보너스
     * @param balance       현재 잔액
     */
    public EvResult calcForward(double priceDiffPct, double upOdds, double downOdds,
                                 double velocity, double momentumScore,
                                 double timeBonus, double balance) {
        boolean isUp = priceDiffPct > 0;

        // 🔧 FIX #1: 실제 CLOB 오즈를 그대로 사용 (1-upOdds 금지)
        double targetOdds = isUp
                ? clamp(upOdds, FWD_MIN_ODDS, FWD_MAX_ODDS)
                : clamp(downOdds, FWD_MIN_ODDS, FWD_MAX_ODDS);

        // 🔧 FIX #2: 모멘텀 방향 검증 — 배팅 방향과 반대면 확률 페널티
        double directedMomentum = isUp ? momentumScore : -momentumScore;
        // directedMomentum > 0 이면 배팅 방향과 모멘텀 일치

        // 확률 추정
        double baseProb = estimateProb(priceDiffPct, velocity, directedMomentum, timeBonus);

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
     * ⭐ 확률 추정 (5M 전용)
     * @param changePct signed 가격 변동률 (양수=UP, 음수=DOWN)
     * @param velocity  EMA 평활된 속도 (%/초)
     * @param directedMomentum 배팅 방향 기준 모멘텀 (+면 일치, -면 역행)
     * @param timeBonus 캔들 진행도 보너스
     */
    private double estimateProb(double changePct, double velocity, double directedMomentum, double timeBonus) {
        double absPct = Math.abs(changePct);

        // 🔧 FIX #3: tfBonus 제거 — 구간별 baseProb에 이미 반영됨
        // 무조건 +5%는 확률을 과대추정하는 주범이었음

        // 속도 보너스
        double velocityBonus = 0.0;
        double absVelocity = Math.abs(velocity);
        if (absVelocity >= 0.05)      velocityBonus = 0.04;
        else if (absVelocity >= 0.02) velocityBonus = 0.02;
        else if (absVelocity >= 0.01) velocityBonus = 0.01;

        // 속도 역방향이면 페널티
        if ((changePct > 0 && velocity < 0) || (changePct < 0 && velocity > 0)) {
            velocityBonus = -0.03;
        }

        // 🔧 FIX #2: 모멘텀 방향 반영 (directedMomentum은 이미 배팅 방향 기준)
        // +면 모멘텀이 배팅과 같은 방향, -면 역행
        double momentumBonus = 0.0;
        if (directedMomentum >= 0.8)       momentumBonus = 0.04;
        else if (directedMomentum >= 0.6)  momentumBonus = 0.02;
        else if (directedMomentum >= 0.3)  momentumBonus = 0.0;
        else if (directedMomentum >= 0.0)  momentumBonus = -0.02;  // 약한 동의
        else if (directedMomentum >= -0.3) momentumBonus = -0.03;  // 약한 역행
        else                               momentumBonus = -0.05;  // 강한 역행 → 큰 페널티

        double bonus = timeBonus + velocityBonus + momentumBonus;
        // 🔧 FIX: 보너스 총합 상한 — 과대추정 방지
        // 실제 시장 EV는 1-5%, 보너스가 baseProb을 6%+ 올리면 가짜 엣지 발생
        bonus = Math.max(-0.05, Math.min(0.04, bonus));

        // ⭐ 구간별 기본 확률 — 30일 BTC 5분봉 백테스트 보정 (2분차 진입, -5pp 보수적)
        // 원본 vs 실측: 0.10%에서 54% vs 80.4% → 26pp 과소추정이었음
        double baseProb;
        if (absPct >= 1.0)       baseProb = 0.92;  // 실측~100% → 보수적 92%
        else if (absPct >= 0.7)  baseProb = 0.90;  // 실측 94.7%
        else if (absPct >= 0.5)  baseProb = 0.88;  // 실측 96.4% → -5pp 안전마진 충분
        else if (absPct >= 0.35) baseProb = 0.86;  // 실측 94.8%
        else if (absPct >= 0.25) baseProb = 0.83;  // 실측 90.4%
        else if (absPct >= 0.15) baseProb = 0.79;  // 실측 86.9%
        else if (absPct >= 0.10) baseProb = 0.73;  // 실측 80.4%
        else if (absPct >= 0.08) baseProb = 0.67;  // 실측 74.3%
        else if (absPct >= 0.05) baseProb = 0.63;  // 실측 72.8%
        else if (absPct >= 0.03) baseProb = 0.58;  // 실측 68.6%
        else                     baseProb = 0.53;  // 실측 59.2%

        return clamp(baseProb + bonus, 0.50, 0.92);
    }

    /**
     * ⭐ Kelly Criterion + 단계적 사이징
     * 잔액이 시드 이하일 때 보수적 → 성장할수록 공격적
     * 시드이하: 2% | ~2배: 3% | ~5배: 4% | 5배+: 5%
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

        // ⭐ 단계적 Kelly: 잔액/시드 비율에 따라 상한 조정
        double ratio = balance / initialBalance;
        double maxFraction;
        if (ratio < 1.0)      maxFraction = 0.02;  // 시드 이하: 생존 모드
        else if (ratio < 2.0) maxFraction = 0.03;  // ~2배: 안정 모드
        else if (ratio < 5.0) maxFraction = 0.04;  // ~5배: 성장 모드
        else                  maxFraction = 0.05;  // 5배+: 공격 모드

        safeFraction = clamp(safeFraction, 0.02, maxFraction);

        double bet = balance * safeFraction;
        // maxBet: 유동성 한계 $50 (Kelly가 리스크 제어, 고정캡은 유동성 보호)
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
    public double estimateProbPublic(double changePct, double velocity, double directedMomentum, double timeBonus) {
        return estimateProb(changePct, velocity, directedMomentum, timeBonus);
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
