package com.aishots.controller;

import com.aishots.dto.JobStatus;
import com.aishots.dto.ScriptData;
import com.aishots.dto.ShortsRequest;
import com.aishots.exception.ShortsException;
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
    private final ScriptService scriptService;

    @Value("${output.video.dir}")
    private String videoDir;

    @Value("${output.thumbnail.dir}")
    private String thumbnailDir;

    // ── 페이지 ──────────────────────────────────────────────

    @GetMapping("/")
    public String index() {
        return "index";
    }

    // ── API ─────────────────────────────────────────────────

    /**
     * 영상 생성 시작
     *
     * [보안] @Valid로 DTO 검증 → GlobalExceptionHandler가 400 응답 처리
     * [보안] tone/voice 화이트리스트 검증 (프롬프트 인젝션 방지)
     * [보안] jobId를 full UUID로 변경 (8자리 단축 시 충돌 및 추측 가능)
     */
    @PostMapping("/api/shorts/generate")
    @ResponseBody
    public ResponseEntity<?> generate(@Valid @RequestBody ShortsRequest request) {
        // tone / voice 화이트리스트 검증
        if (!ShortsRequest.ALLOWED_TONES.contains(request.getTone())) {
            throw new ShortsException("허용되지 않는 말투입니다.");
        }
        if (!ShortsRequest.ALLOWED_VOICES.contains(request.getVoice())) {
            throw new ShortsException("허용되지 않는 음성입니다.");
        }

        // [보안] full UUID 사용 — 8자리 단축은 brute-force 추측 가능
        String jobId = UUID.randomUUID().toString();
        shortsGenerationService.initJob(jobId);
        shortsGenerationService.generateShorts(jobId, request);

        return ResponseEntity.ok(Map.of("jobId", jobId, "message", "영상 생성이 시작되었습니다."));
    }

    /**
     * 작업 상태 조회
     *
     * [보안] jobId PathUtils 검증으로 Path Traversal 방지
     */
    @GetMapping("/api/shorts/status/{jobId}")
    @ResponseBody
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        PathUtils.validateId(jobId); // [보안] 형식 검증
        JobStatus status = shortsGenerationService.getStatus(jobId);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status);
    }

    /**
     * 스크립트 미리보기
     *
     * [보안] topic 길이 제한 + 예외는 GlobalExceptionHandler 위임
     *        → 내부 오류 메시지 클라이언트 노출 차단
     */
    @GetMapping("/api/shorts/script/preview")
    @ResponseBody
    public ResponseEntity<?> previewScript(
            @RequestParam @jakarta.validation.constraints.Size(min = 2, max = 100) String topic,
            @RequestParam(defaultValue = "60") @jakarta.validation.constraints.Min(30) @jakarta.validation.constraints.Max(90) int durationSeconds
    ) {
        ScriptData script = scriptService.generateScript(topic, durationSeconds, "친근하고 흥미롭게");
        return ResponseEntity.ok(Map.of("success", true, "data", script));
    }

    /**
     * 영상 다운로드
     *
     * [보안] jobId → PathUtils.validateId() → safeResolve()
     *        baseDir 밖으로 절대 나갈 수 없도록 경계 검증
     */
    @GetMapping("/api/shorts/download/video/{jobId}")
    public ResponseEntity<Resource> downloadVideo(@PathVariable String jobId) {
        PathUtils.validateId(jobId);
        Path filePath = PathUtils.safeResolve(Paths.get(videoDir).toAbsolutePath(), jobId + ".mp4");
        return serveFile(filePath.toFile(), jobId + ".mp4", "video/mp4");
    }

    /**
     * 썸네일 다운로드
     *
     * [보안] 동일한 Path Traversal 방어 적용
     */
    @GetMapping("/api/shorts/download/thumbnail/{jobId}")
    public ResponseEntity<Resource> downloadThumbnail(@PathVariable String jobId) {
        PathUtils.validateId(jobId);
        Path filePath = PathUtils.safeResolve(Paths.get(thumbnailDir).toAbsolutePath(), jobId + ".jpg");
        return serveFile(filePath.toFile(), jobId + ".jpg", "image/jpeg");
    }

    // ── 내부 유틸 ────────────────────────────────────────────

    private ResponseEntity<Resource> serveFile(File file, String downloadName, String mediaType) {
        if (!file.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadName + "\"")
                .body(new FileSystemResource(file));
    }
}
