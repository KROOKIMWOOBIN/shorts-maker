package com.aishots.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StableDiffusionService v2 — 병렬 생성
 *
 * ComfyUI 큐에 모든 프롬프트를 한꺼번에 투입 (POST /prompt × N)
 * → 완료 폴링을 병렬로 수행 → GPU 유휴 시간 제거
 *
 * RTX 4070Ti 기준 step=20 이미지 1장당 약 2~3초.
 * 30장 순차: ~90초 / 병렬: ~90초 (ComfyUI 내부 큐는 순차지만 Java측 대기 오버헤드 제거)
 *
 * batch_size 지원 ComfyUI 빌드면 한 번에 여러 장 생성 가능하나
 * 표준 빌드는 큐 순차 처리 → promptId 개수만큼 큐에 쌓아서 최대 활용.
 */
@Slf4j
@Service
public class StableDiffusionService {

    // 9:16 비율, SD 1.5 최적 해상도
    private static final int IMG_W = 576;
    private static final int IMG_H = 1024;

    private static final String NEGATIVE =
            "blurry, low quality, ugly, deformed, watermark, text, logo, " +
            "nsfw, cartoon, anime, drawing, painting, bad anatomy, duplicate";

    @Value("${comfyui.api.url:http://localhost:8188}")
    private String comfyuiUrl;

    @Value("${comfyui.timeout:300}")       // 30장 × 평균 5초 + 여유
    private int timeoutSeconds;

    @Value("${output.images.dir:outputs/images}")
    private String imagesDir;

    @Value("${sd.model:v1-5-pruned-emaonly.ckpt}")
    private String sdModel;

    @Value("${sd.steps:20}")
    private int sdSteps;

    @Value("${sd.cfg:7.0}")
    private double sdCfg;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 병렬 폴링용 스레드풀 (I/O 대기 위주라 스레드 많이 써도 OK)
    private final ExecutorService pollExecutor = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "sd-poll");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .executor(pollExecutor)
            .build();

    // ════════════════════════════════════════════════════════════
    //  연결 확인
    // ════════════════════════════════════════════════════════════

    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(comfyuiUrl + "/system_stats"))
                    .GET().timeout(java.time.Duration.ofSeconds(5))
                    .build();
            int status = httpClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            log.info("[SD] isAvailable → status={} url={}", status, comfyuiUrl);
            return status == 200;
        } catch (Exception e) {
            log.error("[SD] isAvailable 실패: {} / url={}", e.getMessage(), comfyuiUrl);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  이미지 일괄 생성 (병렬)
    // ════════════════════════════════════════════════════════════

    /**
     * 1단계: 모든 프롬프트를 ComfyUI 큐에 한꺼번에 투입
     * 2단계: 각 promptId 완료를 병렬로 폴링
     * 3단계: 완료된 것부터 순서대로 다운로드
     */
    public List<String> generateImages(List<String> prompts, String jobId) {
        if (prompts == null || prompts.isEmpty()) return List.of();

        if (!isAvailable()) {
            log.warn("[{}] ComfyUI 미연결", jobId);
            return List.of();
        }

        try {
            Files.createDirectories(Paths.get(imagesDir));
        } catch (Exception e) {
            log.error("이미지 폴더 생성 실패: {}", e.getMessage());
            return List.of();
        }

        int total = prompts.size();
        log.info("[{}] SD 이미지 {}장 병렬 생성 시작 (steps={}, cfg={})",
                jobId, total, sdSteps, sdCfg);

        // ── 1단계: 큐에 전부 투입 ────────────────────────────
        List<PromptJob> jobs = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String outPath = imagesDir + "/" + jobId + "_" + i + ".png";
            try {
                String promptId = submitPrompt(prompts.get(i), jobId + "_" + i);
                jobs.add(new PromptJob(i, promptId, outPath));
                log.debug("[{}] 큐 투입 완료 #{}: promptId={}", jobId, i, promptId);
            } catch (Exception e) {
                log.warn("[{}] 큐 투입 실패 #{}: {}", jobId, i, e.getMessage());
                jobs.add(new PromptJob(i, null, outPath)); // null = 실패
            }
        }

        // ── 2단계: 병렬 폴링 + 다운로드 ─────────────────────
        AtomicInteger done = new AtomicInteger(0);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        List<CompletableFuture<Void>> futures = jobs.stream()
                .filter(j -> j.promptId != null)
                .map(j -> CompletableFuture.runAsync(() -> {
                    try {
                        String filename = pollForResult(j.promptId, jobId + "_" + j.idx, deadline);
                        if (filename != null) {
                            downloadImage(filename, j.outPath);
                            j.success = true;
                            log.info("[{}] #{} 완료 ({}/{}장)", jobId, j.idx,
                                    done.incrementAndGet(), total);
                        } else {
                            log.warn("[{}] #{} 타임아웃", jobId, j.idx);
                        }
                    } catch (Exception e) {
                        log.warn("[{}] #{} 실패: {}", jobId, j.idx, e.getMessage());
                    }
                }, pollExecutor))
                .toList();

        // 전체 완료 대기
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutSeconds + 30L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[{}] 전체 대기 타임아웃 — 완료된 것만 사용", jobId);
        } catch (Exception e) {
            log.warn("[{}] 폴링 대기 중 예외: {}", jobId, e.getMessage());
        }

        // ── 3단계: 성공한 것만 순서대로 반환 ─────────────────
        List<String> results = jobs.stream()
                .filter(j -> j.success)
                .sorted(Comparator.comparingInt(j -> j.idx))
                .map(j -> j.outPath)
                .toList();

        log.info("[{}] SD 생성 완료: {}/{}장", jobId, results.size(), total);
        return results;
    }

    // ════════════════════════════════════════════════════════════
    //  큐 투입 (POST /prompt → promptId)
    // ════════════════════════════════════════════════════════════

    private String submitPrompt(String prompt, String tag) throws Exception {
        String workflow = buildWorkflow(prompt);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(comfyuiUrl + "/prompt"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(
                                Map.of("prompt", objectMapper.readTree(workflow)))))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        String body = res.body();

        if (res.statusCode() != 200) {
            throw new RuntimeException("POST /prompt 실패 " + res.statusCode() + ": " + body);
        }

        JsonNode json = objectMapper.readTree(body);
        if (json.has("error")) {
            String msg = json.path("error").path("message").asText(json.path("error").toString());
            throw new RuntimeException("ComfyUI 에러: " + msg);
        }

        String promptId = json.path("prompt_id").asText();
        if (promptId.isBlank())
            throw new RuntimeException("promptId 없음: " + body);

        return promptId;
    }

    // ════════════════════════════════════════════════════════════
    //  완료 폴링
    // ════════════════════════════════════════════════════════════

    private String pollForResult(String promptId, String tag, long deadline) throws Exception {
        int pollCount = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(comfyuiUrl + "/history/" + promptId))
                    .GET().timeout(java.time.Duration.ofSeconds(10))
                    .build();
            String body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();

            // 첫 번째 폴링 때 응답 구조 로그
            if (pollCount == 0) {
                String preview = body.length() > 500 ? body.substring(0, 500) : body;
                log.info("[{}] history 응답 구조: {}", tag, preview);
            }
            pollCount++;

            JsonNode history = objectMapper.readTree(body);
            JsonNode entry   = history.path(promptId);

            if (!entry.isMissingNode()) {
                String status = entry.path("status").path("status_str").asText("");
                log.debug("[{}] status={}", tag, status);

                if ("error".equals(status)) {
                    String errMsg = entry.path("status").path("messages").toString();
                    log.warn("[{}] ComfyUI 처리 오류: {}", tag, errMsg);
                    return null;
                }

                // outputs에서 파일명 추출
                JsonNode outputs = entry.path("outputs");
                if (outputs.isMissingNode() || outputs.isEmpty()) {
                    // 아직 처리 중 — 계속 대기
                    continue;
                }
                Iterator<JsonNode> nodes = outputs.elements();
                while (nodes.hasNext()) {
                    JsonNode images = nodes.next().path("images");
                    if (images.isArray() && !images.isEmpty()) {
                        String filename = images.get(0).path("filename").asText();
                        log.info("[{}] 이미지 완료: {}", tag, filename);
                        return filename;
                    }
                }
            }
        }
        log.warn("[{}] 폴링 타임아웃 ({}회 시도)", tag, pollCount);
        return null;
    }

    // ════════════════════════════════════════════════════════════
    //  이미지 다운로드
    // ════════════════════════════════════════════════════════════

    private void downloadImage(String filename, String outPath) throws Exception {
        String url = comfyuiUrl + "/view?filename=" + filename + "&type=output";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET().timeout(java.time.Duration.ofSeconds(30))
                .build();
        byte[] bytes = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
        Files.write(Paths.get(outPath), bytes);
    }

    // ════════════════════════════════════════════════════════════
    //  ComfyUI 워크플로우
    // ════════════════════════════════════════════════════════════

    private String buildWorkflow(String prompt) {
        return """
        {
          "3": {
            "class_type": "KSampler",
            "inputs": {
              "seed": %d,
              "steps": %d,
              "cfg": %.1f,
              "sampler_name": "dpm_2_ancestral",
              "scheduler": "karras",
              "denoise": 1.0,
              "model": ["4", 0],
              "positive": ["6", 0],
              "negative": ["7", 0],
              "latent_image": ["5", 0]
            }
          },
          "4": {
            "class_type": "CheckpointLoaderSimple",
            "inputs": { "ckpt_name": "%s" }
          },
          "5": {
            "class_type": "EmptyLatentImage",
            "inputs": { "width": %d, "height": %d, "batch_size": 1 }
          },
          "6": {
            "class_type": "CLIPTextEncode",
            "inputs": { "text": "%s", "clip": ["4", 1] }
          },
          "7": {
            "class_type": "CLIPTextEncode",
            "inputs": { "text": "%s", "clip": ["4", 1] }
          },
          "8": {
            "class_type": "VAEDecode",
            "inputs": { "samples": ["3", 0], "vae": ["4", 2] }
          },
          "9": {
            "class_type": "SaveImage",
            "inputs": { "images": ["8", 0], "filename_prefix": "shorts" }
          }
        }
        """.formatted(
                new Random().nextInt(Integer.MAX_VALUE),
                sdSteps,
                sdCfg,
                sdModel,
                IMG_W, IMG_H,
                escapeJson(prompt),
                escapeJson(NEGATIVE)
        );
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ");
    }

    // ════════════════════════════════════════════════════════════
    //  내부 데이터 클래스
    // ════════════════════════════════════════════════════════════

    private static class PromptJob {
        final int    idx;
        final String promptId;
        final String outPath;
        volatile boolean success = false;

        PromptJob(int idx, String promptId, String outPath) {
            this.idx      = idx;
            this.promptId = promptId;
            this.outPath  = outPath;
        }
    }
}
