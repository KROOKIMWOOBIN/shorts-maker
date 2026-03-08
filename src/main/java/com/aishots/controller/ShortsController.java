package com.aishots.controller;

import com.aishots.dto.JobStatus;
import com.aishots.dto.ScriptData;
import com.aishots.dto.ShortsRequest;
import com.aishots.exception.ShortsException;
import com.aishots.service.AnimateDiffService;
import com.aishots.service.ScriptService;
import com.aishots.service.ShortsGenerationService;
import com.aishots.util.PathUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ShortsController {

    private final ShortsGenerationService shortsGenerationService;
    private final ScriptService           scriptService;
    private final AnimateDiffService      animateDiffService;

    @Value("${output.video.dir}")
    private String videoDir;

    // ── 페이지 ──────────────────────────────────────────────────

    @GetMapping("/")
    public String index() { return "index"; }

    // ── API ─────────────────────────────────────────────────────

    @PostMapping("/api/shorts/generate")
    @ResponseBody
    public ResponseEntity<?> generate(@Valid @RequestBody ShortsRequest request) {
        if (!ShortsRequest.ALLOWED_TONES.contains(request.getTone()))
            throw new ShortsException("Invalid tone. Allowed: " + ShortsRequest.ALLOWED_TONES);
        if (!ShortsRequest.ALLOWED_VOICES.contains(request.getVoice()))
            throw new ShortsException("Invalid voice.");
        if (!ShortsRequest.ALLOWED_BGM_STYLES.contains(
                request.getBgmStyle() != null ? request.getBgmStyle().toUpperCase() : "NONE"))
            throw new ShortsException("Invalid BGM style. Allowed: " + ShortsRequest.ALLOWED_BGM_STYLES);

        // bgmStyle 대문자 정규화
        request.setBgmStyle(request.getBgmStyle() != null
                ? request.getBgmStyle().toUpperCase() : "NONE");

        String jobId = UUID.randomUUID().toString();
        shortsGenerationService.initJob(jobId);
        shortsGenerationService.generateShorts(jobId, request);

        return ResponseEntity.ok(Map.of("jobId", jobId, "message", "Video generation started."));
    }

    @GetMapping("/api/shorts/status/{jobId}")
    @ResponseBody
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        PathUtils.validateId(jobId);
        JobStatus status = shortsGenerationService.getStatus(jobId);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status);
    }

    @GetMapping("/api/shorts/script/preview")
    @ResponseBody
    public ResponseEntity<?> previewScript(
            @RequestParam @jakarta.validation.constraints.Size(min = 2, max = 100) String topic,
            @RequestParam(defaultValue = "60") int durationSeconds) {
        ScriptData script = scriptService.generateScript(topic, durationSeconds, "shocking and mind-blowing");
        return ResponseEntity.ok(Map.of("success", true, "data", script));
    }

    @GetMapping("/api/shorts/download/video/{jobId}")
    public ResponseEntity<Resource> downloadVideo(@PathVariable String jobId) {
        PathUtils.validateId(jobId);
        Path filePath = PathUtils.safeResolve(Paths.get(videoDir).toAbsolutePath(), jobId + ".mp4");
        return serveFile(filePath.toFile(), jobId + ".mp4", "video/mp4");
    }

    /** ComfyUI 연결 상태 확인 API */
    @GetMapping("/api/comfyui/status")
    @ResponseBody
    public ResponseEntity<?> comfyStatus() {
        boolean available = animateDiffService.isComfyAvailable();
        return ResponseEntity.ok(Map.of(
                "available", available,
                "message", available ? "ComfyUI connected ✅" : "ComfyUI not running ❌"
        ));
    }

    // ── 내부 유틸 ────────────────────────────────────────────────

    private ResponseEntity<Resource> serveFile(File file, String downloadName, String mediaType) {
        if (!file.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadName + "\"")
                .body(new FileSystemResource(file));
    }
}
