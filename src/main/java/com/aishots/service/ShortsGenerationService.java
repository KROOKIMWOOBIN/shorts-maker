package com.aishots.service;

import com.aishots.dto.JobStatus;
import com.aishots.dto.ScriptData;
import com.aishots.dto.ShortsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortsGenerationService {

    private final ScriptService           scriptService;
    private final TtsService              ttsService;
    private final VideoService            videoService;
    private final BgmGeneratorService     bgmGeneratorService;
    private final StableDiffusionService  sdService;

    private final Map<String, JobStatus> jobStatusMap   = new ConcurrentHashMap<>();
    private final Map<String, Instant>   jobCompletedAt = new ConcurrentHashMap<>();

    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "parallel-gen");
        t.setDaemon(true);
        return t;
    });

    public void initJob(String jobId) {
        jobStatusMap.put(jobId, JobStatus.builder()
                .jobId(jobId).status(JobStatus.Status.PENDING)
                .progress(0).message("Preparing...").build());
    }

    public JobStatus getStatus(String jobId) {
        return jobStatusMap.get(jobId);
    }

    @Async
    public void generateShorts(String jobId, ShortsRequest request) {
        long start = System.currentTimeMillis();
        try {
            // Step 1: 스크립트 + 이미지 프롬프트 생성
            updateStatus(jobId, 10, "🤖 Generating script...");
            ScriptData script = scriptService.generateScript(
                    request.getTopic(), request.getDurationSeconds(), request.getTone());
            log.info("[{}] 스크립트 완료: {}, imagePrompts={}개",
                    jobId, script.getTitle(),
                    script.getImagePrompts() != null ? script.getImagePrompts().size() : 0);

            // Step 2: TTS + BGM 병렬 생성
            updateStatus(jobId, 20, "🎙️ Generating voice & BGM...");
            BgmStyle bgmStyle = BgmStyle.fromValue(request.getBgmStyle());

            CompletableFuture<String> ttsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.generateAudio(script.getScript(), jobId, request.getVoice());
                } catch (Exception e) { throw new CompletionException(e); }
            }, parallelExecutor);

            CompletableFuture<String> bgmFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    if (bgmStyle == BgmStyle.NONE) return null;
                    return bgmGeneratorService.generateBgm(bgmStyle, request.getDurationSeconds(), jobId);
                } catch (Exception e) {
                    log.warn("[{}] BGM 생성 실패 (스킵): {}", jobId, e.getMessage());
                    return null;
                }
            }, parallelExecutor);

            String audioPath, bgmPath;
            try {
                audioPath = ttsFuture.get(90, TimeUnit.SECONDS);
                bgmPath   = bgmFuture.get(30, TimeUnit.SECONDS);
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                throw (cause instanceof Exception ex) ? ex : new RuntimeException(cause);
            } catch (TimeoutException e) {
                ttsFuture.cancel(true);
                bgmFuture.cancel(true);
                throw new RuntimeException("TTS or BGM generation timed out");
            }

            // Step 3: SD 이미지 생성
            updateStatus(jobId, 45, "🎨 Generating images with Stable Diffusion...");
            List<String> imagePaths = List.of();
            if (script.getImagePrompts() != null && !script.getImagePrompts().isEmpty()) {
                imagePaths = sdService.generateImages(script.getImagePrompts(), jobId);
            }
            if (imagePaths.isEmpty()) {
                log.info("[{}] SD 이미지 없음 — Java2D 씬 폴백", jobId);
                updateStatus(jobId, 55, "🎨 Rendering scenes (Java2D fallback)...");
            } else {
                log.info("[{}] SD 이미지 {}장 생성 완료", jobId, imagePaths.size());
            }

            // Step 4: 영상 합성
            updateStatus(jobId, 70, "🎬 Rendering video...");
            List<Integer> bg = request.getBackgroundColor();
            videoService.createShortsVideo(
                    audioPath,
                    script.getScript(),
                    script.getHook(),
                    request.getTopic(),
                    imagePaths,   // SD 이미지 경로 (없으면 빈 리스트 → 폴백)
                    bgmPath,
                    bg.get(0), bg.get(1), bg.get(2),
                    jobId);

            long total = System.currentTimeMillis() - start;
            jobStatusMap.put(jobId, JobStatus.builder()
                    .jobId(jobId).status(JobStatus.Status.COMPLETED)
                    .progress(100)
                    .message("✅ Video ready! (" + (total / 1000) + "s)")
                    .script(script)
                    .videoUrl("/videos/" + jobId + ".mp4")
                    .build());
            jobCompletedAt.put(jobId, Instant.now());
            log.info("[{}] 전체 완료: {}ms", jobId, total);

        } catch (Exception e) {
            log.error("[{}] 생성 실패: {}", jobId, e.getMessage(), e);
            jobStatusMap.put(jobId, JobStatus.builder()
                    .jobId(jobId).status(JobStatus.Status.ERROR)
                    .progress(0).message("Video generation failed. Please try again.")
                    .build());
            jobCompletedAt.put(jobId, Instant.now());
        }
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void evictStaleJobs() {
        Instant threshold = Instant.now().minusSeconds(3600);
        jobCompletedAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(threshold)) {
                jobStatusMap.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void updateStatus(String jobId, int progress, String message) {
        jobStatusMap.put(jobId, JobStatus.builder()
                .jobId(jobId).status(JobStatus.Status.PROCESSING)
                .progress(progress).message(message).build());
    }
}
