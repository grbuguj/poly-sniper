package com.sniper.btc.service;

import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 5M 배팅 결과 자동 판정
 *
 * 30초마다 실행, PENDING 중 5분 경과한 배팅을 Chainlink 종가로 판정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultChecker {

    private final TradeRepository tradeRepository;
    private final ChainlinkPriceService chainlink;
    private final BalanceService balanceService;
    private final SniperScanner sniperScanner;

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void checkPending() {
        List<Trade> pending = tradeRepository.findByResultOrderByCreatedAtDesc(Trade.TradeResult.PENDING);
        if (pending.isEmpty()) return;

        for (Trade trade : pending) {
            if (trade.getAction() == Trade.TradeAction.HOLD) continue;

            // 5분 + 30초 여유 경과 확인
            long minutesSince = ChronoUnit.MINUTES.between(trade.getCreatedAt(), LocalDateTime.now());
            if (minutesSince < 5) continue;

            // Chainlink 종가 조회
            double closePrice = resolveClosePrice(trade);
            if (closePrice <= 0) {
                // 7분 이상 지나면 현재가로 판정
                if (minutesSince >= 7) {
                    closePrice = chainlink.getPrice();
                    if (closePrice <= 0) continue;
                    log.warn("⚠️ 종가 미수신 → 현재가 fallback: ${}", fmt(closePrice));
                } else {
                    continue; // 아직 대기
                }
            }

            // 판정: 시초가 vs 종가
            boolean priceWentUp = closePrice > trade.getOpenPrice();
            boolean betOnUp = trade.getAction() == Trade.TradeAction.BUY_YES;
            boolean win = (betOnUp == priceWentUp);

            // 동가 처리 (시초가 = 종가)
            if (Math.abs(closePrice - trade.getOpenPrice()) < 0.01) {
                // Polymarket은 동가 = DOWN 승리
                win = !betOnUp;
            }

            trade.setResult(win ? Trade.TradeResult.WIN : Trade.TradeResult.LOSE);
            trade.setExitPrice(closePrice);
            trade.setResolvedAt(LocalDateTime.now());

            if (win) {
                double payout = trade.getBetAmount() / trade.getOdds();
                double pnl = payout - trade.getBetAmount();
                trade.setPnl(pnl);
                balanceService.addWinnings(trade.getBetAmount(), trade.getOdds());
                sniperScanner.recordWin();
                log.info("✅ WIN | {} @ ${} → ${} | +${} | 잔액 ${}",
                        trade.getAction(), fmt(trade.getOpenPrice()), fmt(closePrice),
                        fmt(pnl), fmt(balanceService.getBalance()));
            } else {
                trade.setPnl(-trade.getBetAmount());
                sniperScanner.recordLoss();
                log.info("❌ LOSE | {} @ ${} → ${} | -${} | 잔액 ${}",
                        trade.getAction(), fmt(trade.getOpenPrice()), fmt(closePrice),
                        fmt(trade.getBetAmount()), fmt(balanceService.getBalance()));
            }

            trade.setBalanceAfter(balanceService.getBalance());
            tradeRepository.save(trade);
        }
    }

    /**
     * Chainlink 캔들 종가 조회
     */
    private double resolveClosePrice(Trade trade) {
        // 배팅 시점의 5M 캔들 경계 계산
        LocalDateTime created = trade.getCreatedAt();
        int min = created.getMinute();
        int slot = (min / 5) * 5;

        // 다음 5M 경계 = 종가 시점
        LocalDateTime candleEnd = created.withMinute(slot).withSecond(0).plusMinutes(5);
        long boundaryEpoch = candleEnd.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();

        Double chainlinkClose = chainlink.getCloseAt(boundaryEpoch);
        if (chainlinkClose != null && chainlinkClose > 0) {
            return chainlinkClose;
        }

        // UTC 기준으로도 시도
        long boundaryUtc = candleEnd.atZone(java.time.ZoneId.of("UTC")).toEpochSecond();
        chainlinkClose = chainlink.getCloseAt(boundaryUtc);
        if (chainlinkClose != null && chainlinkClose > 0) {
            return chainlinkClose;
        }

        return 0;
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
