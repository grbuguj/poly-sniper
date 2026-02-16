package com.sniper.btc.controller;

import com.sniper.btc.entity.Trade;
import com.sniper.btc.repository.TradeRepository;
import com.sniper.btc.service.BalanceService;
import com.sniper.btc.service.ChainlinkPriceService;
import com.sniper.btc.service.SniperScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final TradeRepository tradeRepository;
    private final BalanceService balanceService;
    private final ChainlinkPriceService chainlink;
    private final SniperScanner sniperScanner;

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Trade> trades = tradeRepository.findAllDesc();
        SniperScanner.SniperStats stats = sniperScanner.getStats();

        long winCount = trades.stream().filter(t -> t.getResult() == Trade.TradeResult.WIN).count();
        long loseCount = trades.stream().filter(t -> t.getResult() == Trade.TradeResult.LOSE).count();
        long pendingCount = trades.stream().filter(t -> t.getResult() == Trade.TradeResult.PENDING).count();
        double totalPnl = trades.stream().mapToDouble(Trade::getPnl).sum();

        model.addAttribute("trades", trades);
        model.addAttribute("stats", stats);
        model.addAttribute("balance", balanceService.getBalance());
        model.addAttribute("btcPrice", chainlink.getPrice());
        model.addAttribute("btcOpen", chainlink.get5mOpen());
        model.addAttribute("winCount", winCount);
        model.addAttribute("loseCount", loseCount);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("totalPnl", totalPnl);
        model.addAttribute("totalTrades", trades.size());

        return "dashboard";
    }

    /**
     * AJAX 폴링용 API
     */
    @GetMapping("/api/stats")
    @ResponseBody
    public Map<String, Object> apiStats() {
        SniperScanner.SniperStats stats = sniperScanner.getStats();
        List<Trade> recent = tradeRepository.findAllDesc();

        return Map.of(
                "stats", stats,
                "balance", balanceService.getBalance(),
                "btcPrice", chainlink.getPrice(),
                "btcOpen", chainlink.get5mOpen(),
                "chainlinkConnected", chainlink.isConnected(),
                "recentTrades", recent.stream().limit(20).toList()
        );
    }
}
