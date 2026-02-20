package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.*;

/**
 * ⚡ BTC 전용 Chainlink 가격 서비스
 *
 * 폴리마켓 RTDS WebSocket → btc/usd Chainlink 가격 수신
 * 구독 형식: poly_bug와 동일 (검증된 프로토콜)
 */
@Slf4j
@Service
public class ChainlinkPriceService {

    private static final String RTDS_WS_URL = "wss://ws-live-data.polymarket.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();

    // BTC 실시간 가격
    private volatile double latestPrice = 0.0;
    private volatile long priceTimestamp = 0;

    // 가격 링 버퍼 (최근 600개 = 약 10분)
    private final Deque<double[]> priceRingBuffer = new ConcurrentLinkedDeque<>();
    private static final int RING_BUFFER_SIZE = 600;

    // 5M 캔들 시초가
    private volatile double current5mOpen = 0.0;
    private volatile long last5mBoundary = 0;

    // 5M 캔들 종가 캐시: boundaryTsSec → closePrice
    private final Map<Long, Double> closeSnapshots = new ConcurrentHashMap<>();

    // ⭐ ATR(14) 계산용 5분봉 OHLC 추적
    private volatile double candleHigh = 0;
    private volatile double candleLow = Double.MAX_VALUE;
    private volatile double prevCandleClose = 0;
    private final Deque<Double> trueRanges = new ConcurrentLinkedDeque<>();
    private static final int ATR_PERIOD = 14;
    private volatile double currentATR = 0.0;    // ATR(14) as % of price
    private volatile double currentATRRaw = 0.0; // ATR(14) in $ terms

    private volatile WebSocket webSocket;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean connected = false;
    private volatile boolean reconnecting = false; // 중복 재연결 방지
    private volatile int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_DELAY = 60; // 최대 60초

    // ⭐ 워밍업: 첫 5M 경계 전환 전까지 시초가 부정확 → 배팅 금지
    private volatile boolean warmedUp = false;

    @PostConstruct
    public void connect() {
        connectWebSocket();
        // 재연결 감시 (10초마다) — 좀비 연결 감지 포함
        reconnectExecutor.scheduleAtFixedRate(() -> {
            try {
                if (reconnecting) return; // 재연결 대기 중이면 스킵
                long ageMs = getPriceAgeMs();
                if (!connected || ageMs > 30_000) {
                    // 연결 끊김 or 30초 이상 데이터 없음 (좀비 감지)
                    if (ageMs > 30_000 && connected) {
                        log.warn("🧟 좀비 연결 감지! 마지막 데이터 {}초 전 → 강제 재연결", ageMs / 1000);
                        connected = false;
                    }
                    forceReconnect();
                } else {
                    reconnectAttempts = 0; // 정상이면 카운터 리셋
                }
            } catch (Exception e) {
                log.error("재연결 감시 에러: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void disconnect() {
        closeExistingSocket();
        reconnectExecutor.shutdownNow();
    }

    private void closeExistingSocket() {
        WebSocket old = webSocket;
        if (old != null) {
            try {
                old.close(1000, "reconnect");
            } catch (Exception ignored) {}
            webSocket = null;
        }
    }

    private void forceReconnect() {
        if (reconnecting) return; // 이미 재연결 진행 중
        reconnecting = true;
        
        int delay = Math.min((int) Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
        // 429 방지: 최소 5초
        delay = Math.max(delay, 5);
        reconnectAttempts++;
        log.warn("🔄 Chainlink WS 재연결 시도 #{} ({}초 후)", reconnectAttempts, delay);
        
        closeExistingSocket();
        
        reconnectExecutor.schedule(() -> {
            reconnecting = false;
            connectWebSocket();
        }, delay, TimeUnit.SECONDS);
    }

    private void connectWebSocket() {
        Request request = new Request.Builder().url(RTDS_WS_URL).build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                reconnecting = false;
                reconnectAttempts = 0;
                log.info("✅ Chainlink WS 연결 성공: {} (시도 리셋)", RTDS_WS_URL);

                // ⭐ poly_bug와 동일한 구독 형식 (검증됨)
                String subscribeMsg = """
                    {
                      "action": "subscribe",
                      "subscriptions": [
                        {
                          "topic": "crypto_prices_chainlink",
                          "type": "*",
                          "filters": ""
                        }
                      ]
                    }
                    """;
                ws.send(subscribeMsg);
                log.info("📡 Chainlink BTC/USD 구독 요청 전송");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonNode msg = objectMapper.readTree(text);
                    String topic = msg.path("topic").asText("");

                    if (!"crypto_prices_chainlink".equals(topic)) return;

                    // ⭐ poly_bug와 동일한 파싱 (검증됨)
                    JsonNode payload = msg.path("payload");
                    String symbol = payload.path("symbol").asText("").toLowerCase();

                    // BTC만 처리
                    if (!"btc/usd".equals(symbol)) return;

                    double value = payload.path("value").asDouble(0);
                    long timestamp = payload.path("timestamp").asLong(0);

                    if (value <= 0) return;

                    // epoch seconds 변환
                    long tsSec = timestamp > 1_000_000_000_000L ? timestamp / 1000 : timestamp;

                    latestPrice = value;
                    priceTimestamp = System.currentTimeMillis();

                    // ⭐ ATR용 캔들 고/저 추적
                    if (value > candleHigh) candleHigh = value;
                    if (value < candleLow) candleLow = value;

                    // 링 버퍼 추가
                    priceRingBuffer.addLast(new double[]{tsSec, value});
                    while (priceRingBuffer.size() > RING_BUFFER_SIZE) {
                        priceRingBuffer.pollFirst();
                    }

                    // 5M 캔들 경계 체크
                    update5mBoundary(tsSec, value);

                } catch (Exception e) {
                    // 무시 (속도 우선)
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                log.error("❌ Chainlink WS 끊김: {} → 자동 재연결 대기", t.getMessage());
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                connected = false;
                log.warn("⚠️ Chainlink WS 닫히는 중: {} {} → 자동 재연결 대기", code, reason);
                ws.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
                log.warn("⚠️ Chainlink WS 닫힘: {} {}", code, reason);
            }
        });
    }

    /**
     * 5M 캔들 경계 감지 & 시초가/종가 스냅샷
     */
    private void update5mBoundary(long chainlinkTsSec, double price) {
        long boundary = (chainlinkTsSec / 300) * 300;

        if (last5mBoundary == 0) {
            last5mBoundary = boundary;
            current5mOpen = findClosestPrice(boundary);
            if (current5mOpen <= 0) current5mOpen = price;
            log.info("📌 5M 초기 시초가 설정: ${}", String.format("%.2f", current5mOpen));
            return;
        }

        if (boundary != last5mBoundary) {
            // 이전 캔들 종가
            double closePrice = findClosestPriceBefore(boundary);
            if (closePrice > 0) {
                closeSnapshots.put(boundary, closePrice);
            }

            // ⭐ ATR 계산: True Range = max(H-L, |H-prevClose|, |L-prevClose|)
            if (candleHigh > 0 && candleLow < Double.MAX_VALUE && closePrice > 0) {
                double tr;
                if (prevCandleClose > 0) {
                    tr = Math.max(candleHigh - candleLow,
                            Math.max(Math.abs(candleHigh - prevCandleClose),
                                     Math.abs(candleLow - prevCandleClose)));
                } else {
                    tr = candleHigh - candleLow;
                }
                trueRanges.addLast(tr);
                while (trueRanges.size() > ATR_PERIOD) trueRanges.pollFirst();

                // ATR = EMA of True Ranges
                if (trueRanges.size() >= 3) {
                    double atrRaw = 0;
                    double multiplier = 2.0 / (trueRanges.size() + 1);
                    boolean first = true;
                    for (double trVal : trueRanges) {
                        if (first) { atrRaw = trVal; first = false; }
                        else { atrRaw = (trVal - atrRaw) * multiplier + atrRaw; }
                    }
                    currentATRRaw = atrRaw;
                    currentATR = (atrRaw / closePrice) * 100; // %로 변환
                    log.info("📊 ATR({}): ${} = {}% (TR개수: {})",
                            trueRanges.size(), String.format("%.2f", atrRaw),
                            String.format("%.4f", currentATR), trueRanges.size());
                }
                prevCandleClose = closePrice;
            }

            // ⭐ 새 캔들 고/저 리셋
            candleHigh = 0;
            candleLow = Double.MAX_VALUE;

            // 새 캔들 시초가
            last5mBoundary = boundary;
            current5mOpen = findClosestPrice(boundary);
            if (current5mOpen <= 0) current5mOpen = price;

            // ⭐ 첫 경계 전환 = 워밍업 완료 (이 시초가부터 정확)
            if (!warmedUp) {
                warmedUp = true;
                log.info("✅ 워밍업 완료! 첫 정확한 5M 시초가: ${} (boundary={})", 
                        String.format("%.2f", current5mOpen), boundary);
            } else {
                log.info("📌 새 5M 캔들 시초가: ${} (boundary={})", String.format("%.2f", current5mOpen), boundary);
            }

            // 오래된 종가 정리
            long cutoff = boundary - 3600;
            closeSnapshots.entrySet().removeIf(e -> e.getKey() < cutoff);
        }
    }

    private double findClosestPrice(long targetTsSec) {
        double closest = 0;
        long minDiff = Long.MAX_VALUE;
        for (double[] entry : priceRingBuffer) {
            long diff = Math.abs((long) entry[0] - targetTsSec);
            if (diff < minDiff) {
                minDiff = diff;
                closest = entry[1];
            }
        }
        return closest;
    }

    private double findClosestPriceBefore(long boundaryTsSec) {
        double best = 0;
        long bestTs = 0;
        for (double[] entry : priceRingBuffer) {
            if (entry[0] < boundaryTsSec && entry[0] > bestTs) {
                bestTs = (long) entry[0];
                best = entry[1];
            }
        }
        return best;
    }

    // === Public API ===

    public double getPrice() { return latestPrice; }

    public long getPriceAgeMs() {
        return priceTimestamp > 0 ? System.currentTimeMillis() - priceTimestamp : Long.MAX_VALUE;
    }

    public double get5mOpen() { return current5mOpen; }

    public Double getCloseAt(long boundaryTsSec) { return closeSnapshots.get(boundaryTsSec); }

    public boolean isConnected() { return connected && getPriceAgeMs() < 10_000; }

    public boolean isWarmedUp() { return warmedUp; }

    /** ATR(14) as percentage (e.g., 0.08 = 0.08%) — 0이면 아직 워밍업 중 */
    public double getATRPct() { return currentATR; }

    /** ATR(14) in USD (e.g., 53.2 = $53.2) */
    public double getATRRaw() { return currentATRRaw; }

    /** ATR 워밍업 완료 여부 (최소 3개 캔들 필요) */
    public boolean isATRReady() { return trueRanges.size() >= 3; }

    // =========================================================================
    // ⭐ 변동성 레짐 감지 (ATR 수준 → 4단계 자동 분류)
    // 논문: K-means clustering → 간소화 버전 (ATR 구간별 고정 경계)
    // BTC 5M ATR 실측 기준:
    //   - LOW:     < 0.04%  (새벽/주말, 거래량 부족)
    //   - NORMAL:  0.04-0.10% (일반 장중)
    //   - HIGH:    0.10-0.18% (미국장 오픈, 뉴스)
    //   - EXTREME: > 0.18% (FOMC, CPI, 급등락)
    // =========================================================================
    public enum VolRegime { LOW, NORMAL, HIGH, EXTREME }

    public VolRegime getVolatilityRegime() {
        if (!isATRReady()) return VolRegime.NORMAL; // 워밍업 중 기본값
        double atr = currentATR;
        if (atr < 0.04) return VolRegime.LOW;
        if (atr < 0.10) return VolRegime.NORMAL;
        if (atr < 0.18) return VolRegime.HIGH;
        return VolRegime.EXTREME;
    }

    /** 레짐 문자열 (대시보드용) */
    public String getVolatilityRegimeLabel() {
        return switch (getVolatilityRegime()) {
            case LOW -> "🟢 LOW";
            case NORMAL -> "🔵 NORMAL";
            case HIGH -> "🟠 HIGH";
            case EXTREME -> "🔴 EXTREME";
        };
    }
}
