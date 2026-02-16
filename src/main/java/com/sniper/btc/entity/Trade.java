package com.sniper.btc.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // BTC 고정이지만 확장성 유지
    @Builder.Default
    private String coin = "BTC";

    @Builder.Default
    private String timeframe = "5M";

    @Enumerated(EnumType.STRING)
    private TradeAction action;  // BUY_YES, BUY_NO, HOLD

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TradeResult result = TradeResult.PENDING;

    private double betAmount;
    private double odds;           // 진입 오즈
    private double entryPrice;     // Chainlink 진입가
    private double openPrice;      // 5M 캔들 시초가
    private double exitPrice;      // 판정 종가

    private double estimatedProb;  // 추정 확률
    private double ev;             // 기댓값
    private double gap;            // 오즈 갭
    private double priceDiffPct;   // 가격 변동률

    private double pnl;            // 손익
    private double balanceAfter;   // 배팅 후 잔액

    private String marketId;       // Polymarket market ID
    private String reason;         // 배팅 근거 요약

    @Column(length = 2000)
    private String detail;         // 상세 로그

    private String strategy;       // FWD / REV

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;

    // === 속도 추적 ===
    private Long scanToTradeMs;    // 스캔→배팅 소요시간(ms)

    public enum TradeAction { BUY_YES, BUY_NO, HOLD }
    public enum TradeResult { PENDING, WIN, LOSE, CANCELLED }
}
