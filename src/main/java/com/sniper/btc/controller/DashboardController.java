package com.sniper.btc.controller;

import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import com.sniper.btc.service.BalanceService;
import com.sniper.btc.service.ChainlinkPriceService;
import com.sniper.btc.service.OddsService;
import com.sniper.btc.service.OrderService;
import com.sniper.btc.service.SniperScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final TradeRepository tradeRepository;
    private final BalanceService balanceService;
    private final ChainlinkPriceService chainlink;
    private final SniperScanner sniperScanner;
    private final OddsService oddsService;
    private final OrderService orderService;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> apiStats() {
        SniperScanner.SniperStats stats = sniperScanner.getStats();
        List<Trade> all = tradeRepository.findAllDesc();

        // === DB 기반 통계 (하드코딩 아님) ===
        long totalBets = all.stream().filter(t -> t.getAction() != Trade.TradeAction.HOLD).count();
        long winCount = all.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        long loseCount = all.stream().filter(t -> t.getResult() == Trade.TradeResult.LOSE).count();
        long pendingCount = all.stream().filter(t -> t.getResult() == Trade.TradeResult.PENDING).count();
        long resolvedCount = winCount + loseCount;
        double winRate = resolvedCount > 0 ? (double) winCount / resolvedCount : 0;
        double totalPnl = all.stream().mapToDouble(Trade::getPnl).sum();

        // === 뱅크롤 통계 ===
        double initialBalance = balanceService.getInitialBalance();
        double balance = balanceService.getBalance();
        double pendingBetAmount = all.stream()
                .filter(t -> t.getResult() == Trade.TradeResult.PENDING && t.getAction() != Trade.TradeAction.HOLD)
                .mapToDouble(Trade::getBetAmount).sum();
        double totalAssets = balance + pendingBetAmount;
        double roi = initialBalance > 0 ? ((totalAssets - initialBalance) / initialBalance) * 100 : 0;

        // === 이퀄리티 커브 + 최고/최저 ===
        double btcPrice = chainlink.getPrice();
        double btcOpen = chainlink.get5mOpen();
        double priceDiff = btcOpen > 0 ? ((btcPrice - btcOpen) / btcOpen * 100) : 0;

        List<Map<String, Object>> equityCurve = new ArrayList<>();
        List<Trade> resolved = new ArrayList<>(all);
        Collections.reverse(resolved);
        double cumBalance = initialBalance;
        double peakBalance = initialBalance;
        double troughBalance = initialBalance;
        for (Trade t : resolved) {
            if (t.getAction() == Trade.TradeAction.HOLD) continue;
            if (t.getResult() == Trade.TradeResult.PENDING) continue;
            cumBalance += t.getPnl();
            peakBalance = Math.max(peakBalance, cumBalance);
            troughBalance = Math.min(troughBalance, cumBalance);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", t.getCreatedAt() != null ? t.getCreatedAt().format(DATETIME_FMT) : "");
            point.put("epoch", t.getCreatedAt() != null
                    ? t.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() : 0);
            point.put("pnl", cumBalance - initialBalance);
            point.put("balance", cumBalance);
            point.put("result", t.getResult().name());
            equityCurve.add(point);
        }

        // 트레이드 테이블
        List<Map<String, Object>> trades = new ArrayList<>();
        for (Trade t : all) {
            if (t.getAction() == Trade.TradeAction.HOLD) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", t.getCreatedAt() != null ? t.getCreatedAt().format(TIME_FMT) : "");
            row.put("strategy", t.getStrategy());
            row.put("action", t.getAction().name());
            row.put("betAmount", t.getBetAmount());
            row.put("odds", t.getOdds());
            row.put("openPrice", t.getOpenPrice());
            row.put("entryPrice", t.getEntryPrice());
            row.put("exitPrice", t.getExitPrice());
            row.put("ev", t.getEv());
            row.put("result", t.getResult().name());
            row.put("pnl", t.getPnl());
            row.put("scanMs", t.getScanToTradeMs());
            trades.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // 가격
        result.put("btcPrice", btcPrice);
        result.put("btcOpen", btcOpen);
        result.put("priceDiff", priceDiff);
        result.put("chainlinkConnected", chainlink.isConnected());
        // 뱅크롤
        result.put("initialBalance", initialBalance);
        result.put("balance", balance);
        result.put("totalAssets", totalAssets);
        result.put("pendingBetAmount", pendingBetAmount);
        result.put("roi", roi);
        result.put("totalPnl", totalPnl);
        // 성적 (DB 기반)
        result.put("winRate", winRate);
        result.put("winCount", winCount);
        result.put("loseCount", loseCount);
        result.put("pendingCount", pendingCount);
        result.put("totalBets", totalBets);
        // 스캔 (메모리 기반 — 런타임 전용)
        result.put("totalScans", stats.totalScans());
        result.put("avgScanMs", stats.avgScanMs());
        result.put("dryRun", stats.dryRun());
        result.put("warmedUp", chainlink.isWarmedUp());
        result.put("enabled", stats.enabled());
        // 이퀄리티 커브
        result.put("peakBalance", peakBalance);
        result.put("troughBalance", troughBalance);
        // 데이터
        result.put("logs", sniperScanner.getRecentLogs(50));
        result.put("trades", trades);
        result.put("equityCurve", equityCurve);

        return result;
    }

    @GetMapping("/api/test/balance")
    @ResponseBody
    public Map<String, Object> testBalance() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            double liveBalance = orderService.fetchLiveBalance();
            result.put("success", liveBalance >= 0);
            result.put("balance", liveBalance);
            result.put("isLive", orderService.isLive());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/test/order")
    @ResponseBody
    public Map<String, Object> testOrder() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 현재 활성 마켓의 오즈 조회
            var odds = oddsService.getOdds();
            if (odds == null) {
                result.put("success", false);
                result.put("error", "No active market / odds not available");
                return result;
            }
            // $1 최소 배팅 테스트 (Up 토큰)
            OrderService.OrderResult order = orderService.placeOrder(
                    odds.upTokenId(), 1.0, odds.upOdds(), "BUY");
            result.put("success", order.success());
            result.put("orderId", order.orderId());
            result.put("error", order.error());
            result.put("tokenId", odds.upTokenId().substring(0, 12) + "...");
            result.put("odds", odds.upOdds());
            result.put("requestedAmount", 1.0);
            result.put("actualAmount", order.actualAmount());
            result.put("actualSize", order.actualSize());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/api/toggle")
    @ResponseBody
    public Map<String, Object> toggleSniper() {
        boolean newState = !sniperScanner.isEnabled();
        sniperScanner.setEnabled(newState);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", newState);
        result.put("mode", sniperScanner.getStats().dryRun() ? "DRY-RUN" : "LIVE");
        return result;
    }

    @PostMapping("/api/reset")
    @ResponseBody
    @Transactional
    public Map<String, Object> resetAll() {
        long count = tradeRepository.count();
        tradeRepository.deleteAll();
        sniperScanner.resetStats();
        balanceService.resetInitialBalance();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("deleted", count);
        result.put("balance", balanceService.getBalance());
        return result;
    }
}
