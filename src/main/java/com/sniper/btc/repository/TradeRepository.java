package com.sniper.btc.repository;

import com.sniper.btc.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByResultOrderByCreatedAtDesc(Trade.TradeResult result);

    @Query("SELECT t FROM Trade t WHERE t.result <> 'PENDING' ORDER BY t.createdAt DESC")
    List<Trade> findResolvedDesc();

    @Query("SELECT t FROM Trade t ORDER BY t.createdAt DESC")
    List<Trade> findAllDesc();

    // 최근 N건 승률 계산용
    @Query(value = "SELECT * FROM trades WHERE result IN ('WIN','LOSE') ORDER BY created_at DESC LIMIT ?1",
            nativeQuery = true)
    List<Trade> findRecentResolved(int limit);

    // 오늘 배팅 수
    @Query("SELECT COUNT(t) FROM Trade t WHERE t.action <> 'HOLD' AND t.createdAt > CURRENT_DATE")
    long countTodayTrades();
}
