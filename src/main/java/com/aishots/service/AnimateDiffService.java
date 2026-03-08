package com.aishots.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * AnimateDiff 영상 클립 생성 서비스
 * ComfyUI (localhost:8188) API 호출 → 텍스트 프롬프트 → 영상 클립
 *
 * 사전 준비:
 * 1. ComfyUI 설치 및 실행 (python main.py --listen 0.0.0.0 --port 8188)
 * 2. ComfyUI/models/checkpoints/v1-5-pruned-emaonly.ckpt
 * 3. ComfyUI/models/animatediff_models/mm_sd_v15_v2.ckpt
 * 4. ComfyUI custom_nodes: ComfyUI-AnimateDiff-Evolved, ComfyUI-VideoHelperSuite
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnimateDiffService {

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    @Value("${comfyui.api.url:http://localhost:8188}")
    private String comfyUrl;

    @Value("${comfyui.timeout:300}")
    private int timeoutSeconds;

    @Value("${output.clips.dir:outputs/clips}")
    private String clipsDir;

    // ComfyUI 연결 가능 여부 캐시 (매 요청마다 ping 안 하도록)
    private Boolean comfyAvailable = null;

    // ════════════════════════════════════════════════════════════
    //  PUBLIC
    // ════════════════════════════════════════════════════════════

    /**
     * 프롬프트 리스트 → 영상 클립 파일 경로 리스트 반환
     * ComfyUI 미실행 시 빈 리스트 반환 (Java2D 씬으로 자동 폴백)
     */
    public List<String> generateClips(List<String> prompts, String jobId) {
        if (!isComfyAvailable()) {
            log.warn("[{}] ComfyUI 미연결 — Java2D 씬으로 폴백", jobId);
            return List.of();
        }

        List<String> clipPaths = new ArrayList<>();
        try {
            Files.createDirectories(Paths.get(clipsDir));
        } catch (Exception e) {
            log.error("클립 디렉터리 생성 실패: {}", e.getMessage());
            return List.of();
        }

        for (int i = 0; i < prompts.size(); i++) {
            String prompt = prompts.get(i);
            log.info("[{}] 클립 {}/{} 생성: {}...", jobId, i + 1, prompts.size(),
                    prompt.length() > 50 ? prompt.substring(0, 50) : prompt);
            try {
                String path = generateSingleClip(prompt, jobId + "_clip" + i);
                clipPaths.add(path);
                log.info("[{}] 클립 {}/{} 완료", jobId, i + 1, prompts.size());
            } catch (Exception e) {
                log.warn("[{}] 클립 {} 실패 (스킵): {}", jobId, i + 1, e.getMessage());
            }
        }
        return clipPaths;
    }

    public boolean isComfyAvailable() {
        if (comfyAvailable != null) return comfyAvailable;
        try {
            String resp = webClient.get()
                    .uri(comfyUrl + "/system_stats")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            comfyAvailable = (resp != null && !resp.isBlank());
        } catch (Exception e) {
            comfyAvailable = false;
        }
        log.info("ComfyUI 연결 상태: {}", comfyAvailable ? "✅ 연결됨" : "❌ 미연결");
        return comfyAvailable;
    }

    // ════════════════════════════════════════════════════════════
    //  클립 생성
    // ════════════════════════════════════════════════════════════

    private String generateSingleClip(String prompt, String filename) throws Exception {
        String workflow  = buildWorkflow(prompt, filename);
        String promptId  = submitPrompt(workflow);
        return pollUntilDone(promptId, filename);
    }

    private String submitPrompt(String workflowJson) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("prompt", objectMapper.readTree(workflowJson));

        String response = webClient.post()
                .uri(comfyUrl + "/prompt")
                .header("Content-Type", "application/json")
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        if (response == null || response.isBlank())
            throw new RuntimeException("ComfyUI /prompt 응답 없음");

        JsonNode root = objectMapper.readTree(response);
        String promptId = root.path("prompt_id").asText();
        if (promptId.isBlank()) throw new RuntimeException("prompt_id 없음: " + response);
        return promptId;
    }

    private String pollUntilDone(String promptId, String filename) throws Exception {
        long   deadline    = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        String outputPath  = clipsDir + "/" + filename + ".mp4";

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2500);

            String historyJson = webClient.get()
                    .uri(comfyUrl + "/history/" + promptId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (historyJson == null || historyJson.equals("{}")) continue;

            JsonNode history = objectMapper.readTree(historyJson);
            JsonNode record  = history.path(promptId);
            if (record.isMissingNode()) continue;

            // 에러 체크
            JsonNode error = record.path("error");
            if (!error.isMissingNode()) throw new RuntimeException("ComfyUI 오류: " + error);

            JsonNode outputs = record.path("outputs");
            if (outputs.isMissingNode()) continue;

            // 출력 파일 탐색
            for (JsonNode node : outputs) {
                for (String key : new String[]{"gifs", "videos", "images"}) {
                    JsonNode files = node.path(key);
                    if (!files.isMissingNode() && files.isArray() && files.size() > 0) {
                        String fname     = files.get(0).path("filename").asText();
                        String subfolder = files.get(0).path("subfolder").asText("");
                        downloadFile(fname, subfolder, outputPath);
                        return outputPath;
                    }
                }
            }
        }
        throw new RuntimeException("클립 생성 타임아웃 (" + timeoutSeconds + "s): " + promptId);
    }

    private void downloadFile(String fname, String subfolder, String outputPath) throws Exception {
        String url = comfyUrl + "/view?filename=" + fname
                + "&subfolder=" + subfolder + "&type=output";
        byte[] bytes = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(60))
                .block();

        if (bytes == null || bytes.length == 0)
            throw new RuntimeException("클립 파일 다운로드 실패: " + fname);

        Files.write(Paths.get(outputPath), bytes);
        log.info("클립 저장: {} ({}KB)", outputPath, bytes.length / 1024);
    }

    // ════════════════════════════════════════════════════════════
    //  ComfyUI 워크플로우 JSON
    //  SD 1.5 + AnimateDiff v2 + VHS VideoCombine
    //  출력: 512x896 (9:16 쇼츠 비율), 16프레임, 8fps = 2초 클립
    // ════════════════════════════════════════════════════════════

    private String buildWorkflow(String prompt, String filename) {
        String neg = "ugly, blurry, low quality, watermark, text, logo, nsfw, "
                   + "deformed, bad anatomy, extra limbs, mutation, worst quality";
        int seed   = new Random().nextInt(Integer.MAX_VALUE);

        return """
            {
              "1": {
                "class_type": "CheckpointLoaderSimple",
                "inputs": { "ckpt_name": "v1-5-pruned-emaonly.ckpt" }
              },
              "2": {
                "class_type": "CLIPTextEncode",
                "inputs": { "text": "%s, masterpiece, best quality, 8k, cinematic", "clip": ["1", 1] }
              },
              "3": {
                "class_type": "CLIPTextEncode",
                "inputs": { "text": "%s", "clip": ["1", 1] }
              },
              "4": {
                "class_type": "ADE_AnimateDiffLoaderWithContext",
                "inputs": {
                  "model": ["1", 0],
                  "motion_model": "mm_sd_v15_v2.ckpt",
                  "beta_schedule": "sqrt_linear (AnimateDiff)",
                  "motion_scale": 1.0,
                  "apply_v2_models_properly": true
                }
              },
              "5": {
                "class_type": "EmptyLatentImage",
                "inputs": { "width": 512, "height": 896, "batch_size": 16 }
              },
              "6": {
                "class_type": "KSampler",
                "inputs": {
                  "model": ["4", 0],
                  "positive": ["2", 0],
                  "negative": ["3", 0],
                  "latent_image": ["5", 0],
                  "seed": %d,
                  "steps": 20,
                  "cfg": 7.5,
                  "sampler_name": "euler_ancestral",
                  "scheduler": "linear",
                  "denoise": 1.0
                }
              },
              "7": {
                "class_type": "VAEDecode",
                "inputs": { "samples": ["6", 0], "vae": ["1", 2] }
              },
              "8": {
                "class_type": "VHS_VideoCombine",
                "inputs": {
                  "images": ["7", 0],
                  "frame_rate": 8,
                  "loop_count": 0,
                  "filename_prefix": "%s",
                  "format": "video/h264-mp4",
                  "pingpong": false,
                  "save_output": true
                }
              }
            }
            """.formatted(
                escapeJson(prompt),
                escapeJson(neg),
                seed,
                filename
        );
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
