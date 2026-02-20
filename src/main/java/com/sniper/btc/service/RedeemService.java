package com.sniper.btc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;

/**
 * Polymarket CTF Redeem Service
 *
 * WIN 판정 후 자동으로 포지션을 USDC로 전환.
 * Python poly-web3 패키지를 사이드카로 사용 (Builder Relayer API).
 *
 * 흐름:
 *   1. ResultChecker에서 WIN 감지
 *   2. RedeemService.redeemAsync(conditionId) 호출
 *   3. Python scripts/redeem.py 실행 (ProcessBuilder)
 *   4. Relayer가 Proxy wallet을 통해 CTF.redeemPositions 실행
 *   5. USDC가 Proxy wallet으로 입금
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedeemService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // CLOB API credentials (기존 것 재사용)
    @Value("${polymarket.private-key:}")
    private String privateKey;

    @Value("${polymarket.api-key:}")
    private String apiKey;

    @Value("${polymarket.api-secret:}")
    private String apiSecret;

    @Value("${polymarket.passphrase:}")
    private String passphrase;

    @Value("${polymarket.funder:}")
    private String proxyAddress;

    // Builder credentials (새로 추가)
    @Value("${polymarket.builder.api-key:}")
    private String builderApiKey;

    @Value("${polymarket.builder.secret:}")
    private String builderSecret;

    @Value("${polymarket.builder.passphrase:}")
    private String builderPassphrase;

    @Value("${sniper.dry-run:true}")
    private boolean dryRun;

    // Python 경로
    private static final String PYTHON_BIN = ".venv-redeem/bin/python3";
    private static final String REDEEM_SCRIPT = "scripts/redeem.py";

    /**
     * 비동기 Redeem 실행 (WIN 판정 후 호출)
     */
    public CompletableFuture<RedeemResult> redeemAsync(String conditionId, boolean negRisk) {
        return CompletableFuture.supplyAsync(() -> redeem(conditionId, negRisk), executor);
    }

    /**
     * 동기 Redeem 실행
     */
    public RedeemResult redeem(String conditionId, boolean negRisk) {
        if (dryRun) {
            log.info("🏷️ [DRY-RUN] Redeem 스킵: conditionId={}", shortId(conditionId));
            return new RedeemResult("DRY_RUN", "", "Dry run mode - redeem skipped");
        }

        if (!isConfigured()) {
            log.warn("⚠️ Builder credentials 미설정 → Redeem 불가");
            return new RedeemResult("ERROR", "", "Builder credentials not configured");
        }

        if (conditionId == null || conditionId.isBlank() || "unknown".equals(conditionId)) {
            log.warn("⚠️ conditionId 없음 → Redeem 불가");
            return new RedeemResult("ERROR", "", "No conditionId available");
        }

        log.info("🔄 Redeem 시작: conditionId={}", shortId(conditionId));

        try {
            // Python 스크립트 경로 확인
            Path projectDir = findProjectDir();
            Path pythonPath = projectDir.resolve(PYTHON_BIN);
            Path scriptPath = projectDir.resolve(REDEEM_SCRIPT);

            if (!pythonPath.toFile().exists()) {
                log.error("❌ Python venv 미설치: {}", pythonPath);
                return new RedeemResult("ERROR", "", "Python venv not found: " + pythonPath);
            }

            // ProcessBuilder 구성
            ProcessBuilder pb = new ProcessBuilder(
                    pythonPath.toString(),
                    scriptPath.toString(),
                    conditionId
            );
            if (negRisk) {
                pb.command().add("--neg-risk");
            }

            // 환경변수 설정
            pb.environment().put("POLY_PRIVATE_KEY", privateKey);
            pb.environment().put("POLY_API_KEY", apiKey);
            pb.environment().put("POLY_API_SECRET", apiSecret);
            pb.environment().put("POLY_PASSPHRASE", passphrase);
            pb.environment().put("POLY_PROXY_ADDRESS", proxyAddress);
            pb.environment().put("BUILDER_API_KEY", builderApiKey);
            pb.environment().put("BUILDER_SECRET", builderSecret);
            pb.environment().put("BUILDER_PASSPHRASE", builderPassphrase);

            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            // 실행 (30초 타임아웃)
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("⏰ Redeem 타임아웃 (30초)");
                return new RedeemResult("TIMEOUT", "", "Redeem script timed out");
            }

            // JSON 파싱
            String rawOutput = output.toString().trim();
            if (rawOutput.isEmpty()) {
                log.warn("⚠️ Redeem 출력 없음 (exit={})", process.exitValue());
                return new RedeemResult("ERROR", "", "No output from redeem script");
            }

            // 마지막 JSON 줄 찾기 (Python 로그가 섞일 수 있음)
            String jsonLine = extractLastJson(rawOutput);
            JsonNode result = objectMapper.readTree(jsonLine);

            String status = result.path("status").asText("ERROR");
            String txId = result.path("tx_id").asText("");
            String txHash = result.path("tx_hash").asText("");
            String message = result.path("message").asText("");

            switch (status) {
                case "SUCCESS":
                    log.info("✅ Redeem 성공! txId={}, txHash={}", txId, shortHash(txHash));
                    return new RedeemResult("SUCCESS", txHash, message);
                case "NOT_RESOLVED":
                    log.info("⏳ 아직 미정산: {}", shortId(conditionId));
                    return new RedeemResult("NOT_RESOLVED", "", message);
                case "NO_BALANCE":
                    log.info("📭 Redeem 잔액 없음: {}", shortId(conditionId));
                    return new RedeemResult("NO_BALANCE", "", message);
                default:
                    log.warn("❌ Redeem 실패: {} - {}", status, message);
                    String tb = result.path("traceback").asText("");
                    if (!tb.isEmpty()) log.debug("Traceback:\n{}", tb);
                    return new RedeemResult("ERROR", "", message);
            }

        } catch (Exception e) {
            log.error("❌ Redeem 예외: {}", e.getMessage());
            return new RedeemResult("ERROR", "", e.getMessage());
        }
    }

    /**
     * Builder credentials 설정 여부
     */
    public boolean isConfigured() {
        return builderApiKey != null && !builderApiKey.isBlank()
                && builderSecret != null && !builderSecret.isBlank()
                && builderPassphrase != null && !builderPassphrase.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }

    /**
     * 프로젝트 루트 디렉토리 찾기
     */
    private Path findProjectDir() {
        // 1. 현재 작업 디렉토리에서 scripts/redeem.py 탐색
        Path cwd = Paths.get(System.getProperty("user.dir"));
        if (cwd.resolve(REDEEM_SCRIPT).toFile().exists()) return cwd;

        // 2. 클래스패스 기반 추정
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.contains("poly-sniper")) {
            Path candidate = Paths.get(classpath.split("poly-sniper")[0] + "poly-sniper");
            if (candidate.resolve(REDEEM_SCRIPT).toFile().exists()) return candidate;
        }

        // 3. 고정 경로 fallback
        Path fallback = Paths.get(System.getProperty("user.home"), "IdeaProjects/poly-sniper");
        if (fallback.resolve(REDEEM_SCRIPT).toFile().exists()) return fallback;

        return cwd; // 최후의 수단
    }

    /**
     * 출력에서 마지막 JSON 추출 (Python 로그 무시)
     */
    private String extractLastJson(String output) {
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        return output; // JSON 못 찾으면 전체 반환
    }

    private String shortId(String id) {
        if (id == null || id.length() < 10) return id;
        return id.substring(0, 10) + "...";
    }

    private String shortHash(String hash) {
        if (hash == null || hash.length() < 12) return hash;
        return hash.substring(0, 12) + "...";
    }

    /**
     * Redeem 결과
     */
    public record RedeemResult(String status, String txHash, String message) {
        public boolean isSuccess() { return "SUCCESS".equals(status); }
        public boolean isNotResolved() { return "NOT_RESOLVED".equals(status); }
        public boolean isNoBalance() { return "NO_BALANCE".equals(status); }
    }
}
