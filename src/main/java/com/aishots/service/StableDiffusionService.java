package com.aishots.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * StableDiffusionService
 *
 * ComfyUI API (localhost:8188) 를 사용해 txt2img 이미지 생성.
 * ComfyUI 미연결 시 빈 리스트 반환 → VideoService에서 Java2D 폴백.
 *
 * 워크플로우:
 *  1. POST /prompt  → promptId 받음
 *  2. GET  /history/{promptId} 폴링 → 완료 대기
 *  3. GET  /view?filename=... → PNG 다운로드
 */
@Slf4j
@Service
public class StableDiffusionService {

    private static final int IMG_W = 576;   // SD 1.5 최적 (9:16 비율)
    private static final int IMG_H = 1024;

    // 네거티브 프롬프트 (SD 공통)
    private static final String NEGATIVE =
            "blurry, low quality, ugly, deformed, watermark, text, logo, " +
            "nsfw, cartoon, anime, drawing, painting, bad anatomy";

    @Value("${comfyui.api.url:http://localhost:8188}")
    private String comfyuiUrl;

    @Value("${comfyui.timeout:120}")
    private int timeoutSeconds;

    @Value("${output.images.dir:outputs/images}")
    private String imagesDir;

    @Value("${sd.model:v1-5-pruned-emaonly.ckpt}")
    private String sdModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient   httpClient   = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    // ── 연결 확인 ────────────────────────────────────────────

    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(comfyuiUrl + "/system_stats"))
                    .GET().timeout(java.time.Duration.ofSeconds(3))
                    .build();
            int status = httpClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 이미지 일괄 생성 ──────────────────────────────────────

    /**
     * @param prompts      문장별 SD 프롬프트 리스트
     * @param jobId        작업 ID (파일명 접두사)
     * @return 생성된 이미지 파일 경로 리스트 (ComfyUI 미연결 시 빈 리스트)
     */
    public List<String> generateImages(List<String> prompts, String jobId) {
        if (prompts == null || prompts.isEmpty()) return List.of();

        if (!isAvailable()) {
            log.warn("[{}] ComfyUI 미연결 — 이미지 생성 스킵, Java2D 폴백 사용", jobId);
            return List.of();
        }

        try {
            Files.createDirectories(Paths.get(imagesDir));
        } catch (Exception e) {
            log.error("이미지 출력 폴더 생성 실패: {}", e.getMessage());
            return List.of();
        }

        List<String> results = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            String prompt = prompts.get(i);
            String outPath = imagesDir + "/" + jobId + "_" + i + ".png";
            try {
                String imagePath = generateSingle(prompt, outPath, jobId + "_" + i);
                if (imagePath != null) results.add(imagePath);
            } catch (Exception e) {
                log.warn("[{}] 이미지 {} 생성 실패 (스킵): {}", jobId, i, e.getMessage());
            }
        }

        log.info("[{}] 이미지 생성 완료: {}/{}장", jobId, results.size(), prompts.size());
        return results;
    }

    // ── 단일 이미지 생성 ──────────────────────────────────────

    private String generateSingle(String prompt, String outPath, String tag) throws Exception {
        // 1. 워크플로우 JSON 구성
        String workflow = buildWorkflow(prompt);

        // 2. POST /prompt
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(comfyuiUrl + "/prompt"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("prompt", objectMapper.readTree(workflow)))))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

        HttpResponse<String> postRes = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        if (postRes.statusCode() != 200) {
            throw new RuntimeException("ComfyUI POST /prompt 실패: " + postRes.statusCode());
        }

        String promptId = objectMapper.readTree(postRes.body()).path("prompt_id").asText();
        if (promptId.isBlank()) throw new RuntimeException("promptId 없음");

        // 3. 완료 대기 폴링 (최대 timeoutSeconds)
        String filename = pollForResult(promptId, tag);
        if (filename == null) throw new RuntimeException("이미지 생성 타임아웃");

        // 4. 이미지 다운로드
        downloadImage(filename, outPath);
        log.info("[{}] 이미지 저장: {}", tag, outPath);
        return outPath;
    }

    private String pollForResult(String promptId, String tag) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(comfyuiUrl + "/history/" + promptId))
                    .GET().timeout(java.time.Duration.ofSeconds(10))
                    .build();
            String body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
            JsonNode history = objectMapper.readTree(body);
            JsonNode entry   = history.path(promptId);
            if (!entry.isMissingNode()) {
                // outputs 안에서 이미지 파일명 추출
                JsonNode outputs = entry.path("outputs");
                Iterator<JsonNode> nodes = outputs.elements();
                while (nodes.hasNext()) {
                    JsonNode node = nodes.next();
                    JsonNode images = node.path("images");
                    if (images.isArray() && images.size() > 0) {
                        return images.get(0).path("filename").asText();
                    }
                }
            }
        }
        return null;
    }

    private void downloadImage(String filename, String outPath) throws Exception {
        String url = comfyuiUrl + "/view?filename=" + filename + "&type=output";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET().timeout(java.time.Duration.ofSeconds(30))
                .build();
        byte[] bytes = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray()).body();
        Files.write(Paths.get(outPath), bytes);
    }

    // ── ComfyUI 워크플로우 JSON ───────────────────────────────

    /**
     * SD 1.5 기본 txt2img 워크플로우
     * KSampler → VAEDecode → SaveImage
     */
    private String buildWorkflow(String prompt) {
        return """
        {
          "3": {
            "class_type": "KSampler",
            "inputs": {
              "seed": %d,
              "steps": 20,
              "cfg": 7.5,
              "sampler_name": "euler_ancestral",
              "scheduler": "normal",
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
}
