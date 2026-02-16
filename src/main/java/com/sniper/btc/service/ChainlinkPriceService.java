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
            .pingInterval(5, TimeUnit.SECONDS)
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

    private WebSocket webSocket;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean connected = false;

    @PostConstruct
    public void connect() {
        connectWebSocket();
        // 재연결 감시 (15초마다)
        reconnectExecutor.scheduleAtFixedRate(() -> {
            if (!connected) {
                log.warn("🔄 Chainlink WS 재연결 시도...");
                connectWebSocket();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void disconnect() {
        if (webSocket != null) webSocket.close(1000, "shutdown");
        reconnectExecutor.shutdownNow();
    }

    private void connectWebSocket() {
        Request request = new Request.Builder().url(RTDS_WS_URL).build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                log.info("✅ Chainlink WS 연결 성공: {}", RTDS_WS_URL);

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
                log.error("❌ Chainlink WS 끊김: {}", t.getMessage());
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

            // 새 캔들 시초가
            last5mBoundary = boundary;
            current5mOpen = findClosestPrice(boundary);
            if (current5mOpen <= 0) current5mOpen = price;
            log.info("📌 새 5M 캔들 시초가: ${} (boundary={})", String.format("%.2f", current5mOpen), boundary);

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
}
