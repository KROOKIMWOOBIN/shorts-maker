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

/**
 * 쇼츠 생성 파이프라인 — 병렬화 최적화 버전
 *
 * 기존 순차 파이프라인:
 *   스크립트(~30s) → TTS(~15s) → 썸네일(~2s) → 영상(~60s)  합계 ~107s
 *
 * 개선된 병렬 파이프라인:
 *   스크립트(~30s) → [TTS(~15s) ∥ 썸네일(~2s)] → 영상(~60s)  합계 ~90s
 *
 * TTS와 썸네일은 스크립트 데이터만 있으면 동시에 실행 가능.
 * 썸네일이 TTS보다 훨씬 빠르므로 실제 절약량 = TTS 시간의 대부분.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortsGenerationService {

    private final ScriptService    scriptService;
    private final TtsService ttsService;
    private final ThumbnailService thumbnailService;
    private final VideoService     videoService;

    private final Map<String, JobStatus> jobStatusMap  = new ConcurrentHashMap<>();
    private final Map<String, Instant>   jobCompletedAt = new ConcurrentHashMap<>();

    // TTS + 썸네일 병렬화용 전용 executor (최대 2 작업 동시)
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "parallel-generator");
        t.setDaemon(true);
        return t;
    });

    public void initJob(String jobId) {
        jobStatusMap.put(jobId, JobStatus.builder()
                .jobId(jobId).status(JobStatus.Status.PENDING)
                .progress(0).message("대기 중...").build());
    }

    public JobStatus getStatus(String jobId) {
        return jobStatusMap.get(jobId);
    }

    @Async
    public void generateShorts(String jobId, ShortsRequest request) {
        long pipelineStart = System.currentTimeMillis();
        try {
            // ── Step 1: 스크립트 생성 ──────────────────────────
            updateStatus(jobId, 10, "🤖 AI 스크립트 생성 중...");
            long t0 = System.currentTimeMillis();
            ScriptData script = scriptService.generateScript(
                    request.getTopic(), request.getDurationSeconds(), request.getTone());
            log.info("[{}] 스크립트 완료: {}ms", jobId, System.currentTimeMillis() - t0);

            // ── Step 2+3: TTS & 썸네일 병렬 실행 ──────────────
            updateStatus(jobId, 30, "🎙️ 음성 + 🎨 썸네일 동시 생성 중...");

            // TTS: 비동기 제출
            CompletableFuture<String> ttsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    long t = System.currentTimeMillis();
                    String path = ttsService.generateAudio(script.getScript(), jobId, request.getVoice());
                    log.info("[{}] TTS 완료: {}ms", jobId, System.currentTimeMillis() - t);
                    return path;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, parallelExecutor);

            // 썸네일: 비동기 제출
            CompletableFuture<String> thumbFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    long t = System.currentTimeMillis();
                    String path = thumbnailService.createThumbnail(
                            script.getTitle(), script.getHook(), jobId);
                    log.info("[{}] 썸네일 완료: {}ms", jobId, System.currentTimeMillis() - t);
                    return path;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, parallelExecutor);

            // 둘 다 완료될 때까지 대기
            String audioPath;
            try {
                audioPath = ttsFuture.get(90, TimeUnit.SECONDS);
                thumbFuture.get(30, TimeUnit.SECONDS); // 썸네일은 짧음
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                throw (cause instanceof Exception ex) ? ex : new RuntimeException(cause);
            } catch (TimeoutException e) {
                ttsFuture.cancel(true);
                thumbFuture.cancel(true);
                throw new RuntimeException("TTS 또는 썸네일 생성 시간 초과");
            }

            // ── Step 4: 영상 합성 ──────────────────────────────
            updateStatus(jobId, 65, "🎬 영상 합성 중...");
            long t1 = System.currentTimeMillis();
            List<Integer> bg = request.getBackgroundColor();
            videoService.createShortsVideo(
                    audioPath, script.getScript(),
                    bg.get(0), bg.get(1), bg.get(2), jobId);
            log.info("[{}] 영상 합성 완료: {}ms", jobId, System.currentTimeMillis() - t1);

            // ── 완료 ───────────────────────────────────────────
            long total = System.currentTimeMillis() - pipelineStart;
            jobStatusMap.put(jobId, JobStatus.builder()
                    .jobId(jobId).status(JobStatus.Status.COMPLETED)
                    .progress(100).message("✅ 영상 생성 완료! (" + (total / 1000) + "초)")
                    .script(script)
                    .videoUrl("/videos/" + jobId + ".mp4")
                    .thumbnailUrl("/thumbnails/" + jobId + ".jpg")
                    .build());
            jobCompletedAt.put(jobId, Instant.now());
            log.info("[{}] 전체 파이프라인 완료: {}ms", jobId, total);

        } catch (Exception e) {
            log.error("[{}] 생성 실패: {}", jobId, e.getMessage(), e);
            jobStatusMap.put(jobId, JobStatus.builder()
                    .jobId(jobId).status(JobStatus.Status.ERROR)
                    .progress(0).message("영상 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
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
                log.debug("만료 작업 제거: {}", entry.getKey());
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
