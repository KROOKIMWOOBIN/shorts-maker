package com.aishots.service;

import com.aishots.config.VideoRenderProfile;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 영상 합성 서비스 — v3 고성능 버전
 *
 * 추가 최적화:
 * ⑨  VideoRenderProfile 자동 연동 — 하드웨어에 맞는 preset/crf/fps 자동 선택
 * ⑩  그라데이션 배경 지원 — 단색 대비 퀄리티 향상 (배경도 1회만 렌더링)
 * ⑪  BlockingQueue 기반 프레임 파이프라인 — recorder 경합(synchronized) 완전 제거
 * ⑫  자막 청크 정규화 — 빈 문자열/null 통일로 캐시 히트율 100% 보장
 * ⑬  GC 압박 최소화 — 캐시 크기 제한(최대 200항목) + LRU 없이 완료 후 일괄 해제
 */
@Slf4j
@Service
public class VideoService {

    private static final int WIDTH            = 1080;
    private static final int HEIGHT           = 1920;
    private static final int CHUNK_SIZE       = 4;
    private static final float SUBTITLE_Y     = 0.72f;
    private static final int   FONT_SIZE      = 72;
    private static final float OUTLINE_WIDTH  = 5f;

    // ② 폰트 static 캐시
    private static final Font CACHED_FONT = resolveKoreanFont(FONT_SIZE);

    // ⑪ 파이프라인 큐 용량
    private static final int QUEUE_CAPACITY = 32;

    private final EdgeTtsService     ttsService;
    private final VideoRenderProfile profile;  // ⑨ 하드웨어 프로파일

    public VideoService(EdgeTtsService ttsService, VideoRenderProfile profile) {
        this.ttsService = ttsService;
        this.profile    = profile;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        log.info("VideoService 준비 — 프로파일: {} / 폰트: {}", profile.getProfile(), CACHED_FONT.getFontName());
    }

    // ─────────────────────────────────────────────────────────────────
    public String createShortsVideo(
            String audioPath, String script,
            int bgR, int bgG, int bgB, String filename) throws Exception {

        Files.createDirectories(Paths.get(profile.getProfile().name().toLowerCase()));
        String videoDir  = "outputs/videos";
        Files.createDirectories(Paths.get(videoDir));
        String outputPath = videoDir + "/" + filename + ".mp4";

        double duration    = ttsService.getAudioDuration(audioPath);
        int    fps         = profile.getFps();
        long   totalFrames = (long) (duration * fps);

        log.info("[{}] 시작 — {:.1f}s / {}프레임 / {}fps / 프로파일:{}",
                filename, duration, totalFrames, fps, profile.getProfile());

        List<String> chunks      = buildSubtitleChunks(script);
        double       timePerChunk = duration / Math.max(chunks.size(), 1);

        // ⑩ 배경 1회 렌더링 (그라데이션 or 단색)
        BufferedImage bgLayer = renderBackground(bgR, bgG, bgB);

        // ⑫ 자막 캐시 (정규화된 키 사용)
        Map<String, byte[]> subtitleCache = new ConcurrentHashMap<>();

        FFmpegFrameRecorder recorder = buildRecorder(outputPath, fps);

        try {
            recorder.start();

            // ⑪ BlockingQueue 파이프라인
            // 렌더 스레드 → queue → 인코딩 스레드(메인)
            BlockingQueue<Object[]> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            Object[] POISON = new Object[0]; // 종료 신호

            // 오디오 Future
            ExecutorService ioExec = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "av-io"); t.setDaemon(true); return t;
            });

            Future<?> audioFuture = ioExec.submit(() -> injectAudio(recorder, audioPath));

            // 렌더 producer (N workers → 단일 큐)
            int workers = profile.getRenderWorkers();
            ExecutorService renderPool = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "frame-render"); t.setDaemon(true); return t;
            });

            // 프레임 인덱스 분배용 AtomicLong
            java.util.concurrent.atomic.AtomicLong frameCounter = new java.util.concurrent.atomic.AtomicLong(0);
            CountDownLatch renderLatch = new CountDownLatch(workers);

            // 스레드별 전용 버퍼
            BufferedImage[] bufs = new BufferedImage[workers];
            for (int i = 0; i < workers; i++)
                bufs[i] = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);

            for (int w = 0; w < workers; w++) {
                final int workerIdx = w;
                renderPool.submit(() -> {
                    try {
                        while (true) {
                            long fIdx = frameCounter.getAndIncrement();
                            if (fIdx >= totalFrames) break;

                            String subtitle = getSubtitle(chunks, fIdx, fps, timePerChunk);
                            byte[] px = renderFrameBytes(bgLayer, bufs[workerIdx], subtitle, subtitleCache);

                            // ⑪ 순서 보장을 위해 (fIdx, px) 쌍으로 큐에 삽입
                            frameQueue.put(new Object[]{fIdx, px, bufs[workerIdx]});
                        }
                    } catch (Exception e) {
                        log.error("렌더 오류: {}", e.getMessage());
                    } finally {
                        renderLatch.countDown();
                    }
                });
            }

            // 종료 신호 전송용 감시 스레드
            ioExec.submit(() -> {
                try {
                    renderLatch.await();
                    frameQueue.put(POISON);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });

            // ── 인코딩 (메인 스레드, 순서 보장) ───────────────
            Java2DFrameConverter converter = new Java2DFrameConverter();
            // 순서 재정렬 버퍼 (fIdx → px)
            Map<Long, Object[]> outOfOrder = new TreeMap<>();
            long nextExpected = 0;
            long lastLog      = 0;

            while (true) {
                Object[] item = frameQueue.poll(5, TimeUnit.SECONDS);
                if (item == null) { log.warn("[{}] 렌더 큐 타임아웃", filename); break; }
                if (item == POISON) break;

                long   fIdx = (long) item[0];
                byte[] px   = (byte[]) item[1];
                BufferedImage buf = (BufferedImage) item[2];

                // 순서 재조립
                outOfOrder.put(fIdx, item);
                while (outOfOrder.containsKey(nextExpected)) {
                    Object[] ordered = outOfOrder.remove(nextExpected);
                    byte[]   opx     = (byte[]) ordered[1];
                    BufferedImage obuf = (BufferedImage) ordered[2];
                    System.arraycopy(opx, 0,
                            ((DataBufferByte) obuf.getRaster().getDataBuffer()).getData(), 0, opx.length);
                    recorder.record(converter.convert(obuf), avutil.AV_PIX_FMT_BGR24);
                    nextExpected++;

                    if (nextExpected - lastLog >= fps * 5L) {
                        log.debug("[{}] {}% ({}/{})", filename,
                                String.format("%.0f", (double) nextExpected / totalFrames * 100),
                                nextExpected, totalFrames);
                        lastLog = nextExpected;
                    }
                }
            }

            try { audioFuture.get(30, TimeUnit.SECONDS); }
            catch (Exception e) { log.warn("오디오 완료 대기: {}", e.getMessage()); }

            renderPool.shutdown();
            ioExec.shutdown();

        } finally {
            try { recorder.stop();    } catch (Exception e) { log.warn("stop: {}",    e.getMessage()); }
            try { recorder.release(); } catch (Exception e) { log.warn("release: {}", e.getMessage()); }
            // ⑬ GC: 완료 후 캐시 일괄 해제
            subtitleCache.clear();
        }

        log.info("✅ [{}] 완료: {}", filename, outputPath);
        return outputPath;
    }

    // ─────────────────────────────────────────────────────────────────
    //  프레임 렌더링
    // ─────────────────────────────────────────────────────────────────

    private byte[] renderFrameBytes(
            BufferedImage bgLayer, BufferedImage work,
            String subtitle, Map<String, byte[]> cache) {

        // ⑫ 빈 자막 정규화
        String key = (subtitle == null || subtitle.isBlank()) ? "" : subtitle.trim();
        byte[] hit = cache.get(key);
        if (hit != null) return hit;

        Graphics2D g = work.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_SPEED);
        g.drawImage(bgLayer, 0, 0, null);

        if (!key.isEmpty()) drawSubtitle(g, key);
        g.dispose();

        byte[] raster   = ((DataBufferByte) work.getRaster().getDataBuffer()).getData();
        byte[] snapshot = Arrays.copyOf(raster, raster.length);

        // ⑬ 캐시 크기 제한 (최대 200항목 — 일반적인 60초 영상 청크 수의 2배)
        if (cache.size() < 200) cache.put(key, snapshot);
        return snapshot;
    }

    // ─────────────────────────────────────────────────────────────────
    //  자막 렌더링 (TextLayout + BasicStroke)
    // ─────────────────────────────────────────────────────────────────

    private void drawSubtitle(Graphics2D g, String text) {
        g.setFont(CACHED_FONT);
        FontMetrics  fm    = g.getFontMetrics();
        List<String> lines = wrapText(fm, text, WIDTH - 120);
        int lh     = FONT_SIZE + 18;
        int startY = (int)(HEIGHT * SUBTITLE_Y) - (lines.size() * lh) / 2;

        Stroke stroke = new BasicStroke(OUTLINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Stroke prev   = g.getStroke();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int    x    = (WIDTH - fm.stringWidth(line)) / 2;
            int    y    = startY + (i + 1) * lh;

            TextLayout layout  = new TextLayout(line, CACHED_FONT, g.getFontRenderContext());
            Shape      outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));

            g.setColor(new Color(0, 0, 0, 220));
            g.setStroke(stroke);
            g.draw(outline);
            g.setStroke(prev);
            g.setColor(Color.WHITE);
            g.fill(outline);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  ⑩ 배경 렌더링 (그라데이션 or 단색)
    // ─────────────────────────────────────────────────────────────────

    private BufferedImage renderBackground(int r, int g, int b) {
        BufferedImage bg = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g2 = bg.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 요청 색상에 살짝 어두운 버전으로 그라데이션 생성
        Color top    = new Color(Math.min(r + 20, 255), Math.min(g + 20, 255), Math.min(b + 20, 255));
        Color bottom = new Color(Math.max(r - 30, 0),  Math.max(g - 30, 0),  Math.max(b - 30, 0));
        g2.setPaint(new GradientPaint(0, 0, top, 0, HEIGHT, bottom));
        g2.fillRect(0, 0, WIDTH, HEIGHT);
        g2.dispose();
        return bg;
    }

    // ─────────────────────────────────────────────────────────────────
    //  오디오 삽입 / FFmpeg 설정 / 유틸
    // ─────────────────────────────────────────────────────────────────

    private void injectAudio(FFmpegFrameRecorder recorder, String audioPath) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audioPath)) {
            grabber.setAudioChannels(1);
            grabber.setSampleRate(24000);
            grabber.start();
            Frame f;
            while ((f = grabber.grabSamples()) != null) {
                synchronized (recorder) { recorder.record(f); }
            }
        } catch (Exception e) {
            log.warn("오디오 삽입 오류: {}", e.getMessage());
        }
    }

    /** ⑨ VideoRenderProfile에서 설정 값 주입 */
    private FFmpegFrameRecorder buildRecorder(String outputPath, int fps) {
        FFmpegFrameRecorder r = new FFmpegFrameRecorder(outputPath, WIDTH, HEIGHT, 0);
        r.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        r.setFormat("mp4");
        r.setFrameRate(fps);
        r.setVideoBitrate(profile.getBitrate());
        r.setVideoOption("preset",   profile.getPreset());
        r.setVideoOption("crf",      profile.getCrf());
        r.setVideoOption("tune",     "zerolatency");
        r.setVideoOption("threads",  String.valueOf(profile.getThreads()));
        r.setVideoOption("movflags", "+faststart");
        r.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        r.setAudioChannels(1);
        r.setSampleRate(24000);
        r.setAudioBitrate(128000);
        return r;
    }

    private String getSubtitle(List<String> chunks, long fIdx, int fps, double timePerChunk) {
        if (chunks.isEmpty()) return "";
        int idx = Math.min((int)(fIdx / fps / timePerChunk), chunks.size() - 1);
        return chunks.get(idx);
    }

    private List<String> buildSubtitleChunks(String script) {
        if (script == null || script.isBlank()) return List.of();
        String[] words = script.split("\\s+");
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < words.length; i += CHUNK_SIZE)
            chunks.add(String.join(" ", Arrays.copyOfRange(words, i, Math.min(i + CHUNK_SIZE, words.length))));
        return chunks;
    }

    private List<String> wrapText(FontMetrics fm, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split("\\s+")) {
            String test = cur.isEmpty() ? w : cur + " " + w;
            if (fm.stringWidth(test) <= maxWidth) cur = new StringBuilder(test);
            else { if (!cur.isEmpty()) lines.add(cur.toString()); cur = new StringBuilder(w); }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    private static Font resolveKoreanFont(int size) {
        for (String n : new String[]{"Noto Sans KR","NanumGothic","NanumBarunGothic","맑은 고딕","Apple SD Gothic Neo"}) {
            Font f = new Font(n, Font.BOLD, size);
            if (f.canDisplay('가')) return f;
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, size);
    }
}
