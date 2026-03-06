package com.aishots.service;

import com.aishots.config.VideoRenderProfile;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 영상 합성 서비스 — 파티클 배경 + 자막 타이밍 + 오디오 노이즈 수정
 *
 * 변경 사항:
 * ① 파티클 애니메이션 배경 — 프레임마다 파티클 위치 업데이트
 * ② 자막 타이밍 수정 — 단어 수 기반 가중 타이밍 (균등 분할 제거)
 * ③ 오디오 노이즈 수정 — 44100Hz 통일, 오디오 먼저 삽입 후 비디오
 * ④ 자막 캐시 제거 — 파티클이 매 프레임 다르므로 캐시 불가
 */
@Slf4j
@Service
public class VideoService {

    private static final int   WIDTH         = 1080;
    private static final int   HEIGHT        = 1920;
    private static final int   CHUNK_SIZE    = 4;
    private static final float SUBTITLE_Y    = 0.78f;
    private static final int   FONT_SIZE     = 72;
    private static final float OUTLINE_WIDTH = 5f;
    private static final int   SAMPLE_RATE   = 44100; // ③ 리샘플링과 통일

    // 파티클 설정
    private static final int   PARTICLE_COUNT      = 80;
    private static final float PARTICLE_SPEED_MIN  = 0.3f;
    private static final float PARTICLE_SPEED_MAX  = 1.2f;
    private static final int   PARTICLE_SIZE_MIN   = 3;
    private static final int   PARTICLE_SIZE_MAX   = 12;

    private static final Font CACHED_FONT = resolveKoreanFont(FONT_SIZE);

    private final TtsService     ttsService;
    private final VideoRenderProfile profile;

    public VideoService(TtsService ttsService, VideoRenderProfile profile) {
        this.ttsService = ttsService;
        this.profile    = profile;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        log.info("VideoService 준비 — 프로파일: {} / 폰트: {}",
                profile.getProfile(), CACHED_FONT.getFontName());
    }

    // ─────────────────────────────────────────────────────────────
    //  파티클 데이터 클래스
    // ─────────────────────────────────────────────────────────────

    private static class Particle {
        float x, y;        // 현재 위치
        float vx, vy;      // 속도
        int   size;        // 크기
        float alpha;       // 투명도
        float alphaDelta;  // 투명도 변화량 (반짝임)
        Color color;       // 색상

        Particle(Random rnd, int[] accentColor) {
            x     = rnd.nextFloat() * WIDTH;
            y     = rnd.nextFloat() * HEIGHT;
            float speed = PARTICLE_SPEED_MIN + rnd.nextFloat() * (PARTICLE_SPEED_MAX - PARTICLE_SPEED_MIN);
            float angle = rnd.nextFloat() * (float)(Math.PI * 2);
            vx    = (float)(Math.cos(angle) * speed);
            vy    = (float)(Math.sin(angle) * speed) - 0.4f; // 위로 살짝 편향
            size  = PARTICLE_SIZE_MIN + rnd.nextInt(PARTICLE_SIZE_MAX - PARTICLE_SIZE_MIN);
            alpha = 0.1f + rnd.nextFloat() * 0.5f;
            alphaDelta = (rnd.nextBoolean() ? 1 : -1) * (0.002f + rnd.nextFloat() * 0.005f);
            // 강조색 기반 + 흰색 혼합
            if (rnd.nextFloat() < 0.6f) {
                color = new Color(accentColor[0], accentColor[1], accentColor[2]);
            } else {
                color = Color.WHITE;
            }
        }

        void update() {
            x += vx;
            y += vy;
            alpha += alphaDelta;
            // 경계 처리: 화면 밖으로 나가면 반대편에서 등장
            if (x < 0)      x = WIDTH;
            if (x > WIDTH)  x = 0;
            if (y < 0)      y = HEIGHT;
            if (y > HEIGHT) y = 0;
            // 투명도 반전
            if (alpha > 0.7f || alpha < 0.05f) alphaDelta = -alphaDelta;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  PUBLIC
    // ─────────────────────────────────────────────────────────────

    public String createShortsVideo(
            String audioPath, String script,
            int bgR, int bgG, int bgB, String filename) throws Exception {

        String videoDir = "outputs/videos";
        Files.createDirectories(Paths.get(videoDir));
        String outputPath = videoDir + "/" + filename + ".mp4";

        double duration    = ttsService.getAudioDuration(audioPath);
        int    fps         = profile.getFps();
        long   totalFrames = (long)(duration * fps);

        log.info("[{}] 시작 — {:.1f}s / {}프레임 / {}fps",
                filename, duration, totalFrames, fps);

        // ② 단어 수 기반 가중 자막 타이밍
        List<String> chunks      = buildSubtitleChunks(script);
        double[]     chunkTimes  = buildChunkTimings(chunks, duration);

        // 강조색 (배경색에서 보색 계산)
        int[] accent = computeAccentColor(bgR, bgG, bgB);

        // 파티클 초기화
        Random   rnd       = new Random(42); // 시드 고정 → 재현 가능
        Particle[] particles = new Particle[PARTICLE_COUNT];
        for (int i = 0; i < PARTICLE_COUNT; i++)
            particles[i] = new Particle(rnd, accent);

        FFmpegFrameRecorder recorder = buildRecorder(outputPath, fps);
        try {
            recorder.start();

            // ③ 오디오 먼저 삽입 (타임스탬프 기준점 확립)
            injectAudio(recorder, audioPath);

            // 비디오 렌더링
            renderFrames(recorder, bgR, bgG, bgB, accent,
                    particles, chunks, chunkTimes,
                    totalFrames, fps, filename);

        } finally {
            try { recorder.stop();    } catch (Exception e) { log.warn("stop: {}",    e.getMessage()); }
            try { recorder.release(); } catch (Exception e) { log.warn("release: {}", e.getMessage()); }
        }

        log.info("✅ [{}] 완료: {}", filename, outputPath);
        return outputPath;
    }

    // ─────────────────────────────────────────────────────────────
    //  프레임 렌더링
    // ─────────────────────────────────────────────────────────────

    private void renderFrames(
            FFmpegFrameRecorder recorder,
            int bgR, int bgG, int bgB, int[] accent,
            Particle[] particles,
            List<String> chunks, double[] chunkTimes,
            long totalFrames, int fps, String jobId) throws Exception {

        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage buffer = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);

        long lastLog = 0;

        for (long frameIdx = 0; frameIdx < totalFrames; frameIdx++) {
            double currentTime = (double) frameIdx / fps;
            String subtitle    = getSubtitleByTime(chunks, chunkTimes, currentTime);

            // 파티클 업데이트
            for (Particle p : particles) p.update();

            // 프레임 렌더링
            renderFrame(buffer, bgR, bgG, bgB, accent, particles, subtitle, frameIdx, fps);

            recorder.setTimestamp((long)(currentTime * 1_000_000));
            recorder.record(converter.convert(buffer), avutil.AV_PIX_FMT_BGR24);

            if (frameIdx - lastLog >= fps * 5L) {
                log.debug("[{}] {}% ({}/{})", jobId,
                        String.format("%.0f", (double) frameIdx / totalFrames * 100),
                        frameIdx, totalFrames);
                lastLog = frameIdx;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  단일 프레임 렌더링
    // ─────────────────────────────────────────────────────────────

    private void renderFrame(
            BufferedImage buffer,
            int bgR, int bgG, int bgB, int[] accent,
            Particle[] particles,
            String subtitle,
            long frameIdx, int fps) {

        Graphics2D g = buffer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_SPEED);

        // ── 1. 그라데이션 배경 ──────────────────────────────────
        drawGradientBackground(g, bgR, bgG, bgB);

        // ── 2. 파티클 ───────────────────────────────────────────
        drawParticles(g, particles);

        // ── 3. 하단 자막 영역 반투명 바 ─────────────────────────
        if (subtitle != null && !subtitle.isBlank()) {
            drawSubtitleBar(g, accent);
            drawSubtitle(g, subtitle);
        }

        g.dispose();
    }

    // ─────────────────────────────────────────────────────────────
    //  배경 그라데이션
    // ─────────────────────────────────────────────────────────────

    private void drawGradientBackground(Graphics2D g, int r, int gr, int b) {
        // 3단 그라데이션: 상단 밝음 → 중간 → 하단 어두움
        Color top    = new Color(Math.min(r + 30, 255), Math.min(gr + 30, 255), Math.min(b + 30, 255));
        Color mid    = new Color(r, gr, b);
        Color bottom = new Color(Math.max(r - 50, 0), Math.max(gr - 50, 0), Math.max(b - 50, 0));

        // 상단 → 중간
        g.setPaint(new GradientPaint(0, 0, top, 0, HEIGHT / 2, mid));
        g.fillRect(0, 0, WIDTH, HEIGHT / 2);

        // 중간 → 하단
        g.setPaint(new GradientPaint(0, HEIGHT / 2, mid, 0, HEIGHT, bottom));
        g.fillRect(0, HEIGHT / 2, WIDTH, HEIGHT / 2);
    }

    // ─────────────────────────────────────────────────────────────
    //  파티클 렌더링
    // ─────────────────────────────────────────────────────────────

    private void drawParticles(Graphics2D g, Particle[] particles) {
        for (Particle p : particles) {
            Color c = new Color(
                    p.color.getRed(),
                    p.color.getGreen(),
                    p.color.getBlue(),
                    (int)(p.alpha * 255)
            );
            g.setColor(c);

            // 큰 파티클은 원, 작은 파티클은 점
            if (p.size >= 8) {
                // 글로우 효과 (2겹)
                g.setColor(new Color(
                        p.color.getRed(), p.color.getGreen(), p.color.getBlue(),
                        (int)(p.alpha * 80)));
                g.fillOval((int)p.x - p.size, (int)p.y - p.size,
                        p.size * 2, p.size * 2);
                // 중심
                g.setColor(c);
                g.fillOval((int)p.x - p.size / 2, (int)p.y - p.size / 2,
                        p.size, p.size);
            } else {
                g.fillOval((int)p.x - p.size / 2, (int)p.y - p.size / 2,
                        p.size, p.size);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  자막 바 + 자막
    // ─────────────────────────────────────────────────────────────

    private void drawSubtitleBar(Graphics2D g, int[] accent) {
        int barHeight = 180;
        int barY      = HEIGHT - barHeight - 60;

        // 반투명 블러 바
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(40, barY - 20, WIDTH - 80, barHeight + 40, 30, 30);

        // 상단 강조선
        g.setColor(new Color(accent[0], accent[1], accent[2], 200));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(80, barY - 18, WIDTH - 80, barY - 18);
    }

    private void drawSubtitle(Graphics2D g, String text) {
        g.setFont(CACHED_FONT);
        FontMetrics  fm    = g.getFontMetrics();
        List<String> lines = wrapText(fm, text, WIDTH - 160);
        int lh     = FONT_SIZE + 16;
        int baseY  = HEIGHT - 120 - (lines.size() * lh) / 2;

        Stroke outlineStroke = new BasicStroke(OUTLINE_WIDTH,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Stroke prev = g.getStroke();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int    x    = (WIDTH - fm.stringWidth(line)) / 2;
            int    y    = baseY + (i + 1) * lh;

            TextLayout layout  = new TextLayout(line, CACHED_FONT, g.getFontRenderContext());
            Shape      outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));

            // 외곽선
            g.setColor(new Color(0, 0, 0, 230));
            g.setStroke(outlineStroke);
            g.draw(outline);

            // 본문
            g.setStroke(prev);
            g.setColor(Color.WHITE);
            g.fill(outline);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  오디오 삽입 — 순차 삽입 (노이즈 방지)
    // ─────────────────────────────────────────────────────────────

    private void injectAudio(FFmpegFrameRecorder recorder, String audioPath) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audioPath)) {
            grabber.setAudioChannels(1);
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.start();
            Frame f;
            while ((f = grabber.grabSamples()) != null) {
                recorder.record(f);
            }
            log.debug("오디오 삽입 완료");
        } catch (Exception e) {
            log.warn("오디오 삽입 오류: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ② 자막 타이밍 — 단어 수 기반 가중치
    // ─────────────────────────────────────────────────────────────

    /**
     * 각 청크의 단어 수에 비례해서 시간을 배분합니다.
     * 기존 균등 분할 대비 실제 발화 속도에 가까운 타이밍.
     *
     * 예) 청크A 2단어, 청크B 4단어 → A는 1/3, B는 2/3 시간 배분
     */
    private double[] buildChunkTimings(List<String> chunks, double totalDuration) {
        if (chunks.isEmpty()) return new double[0];

        int[] wordCounts = chunks.stream()
                .mapToInt(c -> c.split("\\s+").length)
                .toArray();
        int totalWords = Arrays.stream(wordCounts).sum();

        double[] timings = new double[chunks.size() + 1]; // [시작시간, ..., 종료시간]
        timings[0] = 0.0;
        for (int i = 0; i < chunks.size(); i++) {
            double weight = totalWords > 0
                    ? (double) wordCounts[i] / totalWords
                    : 1.0 / chunks.size();
            timings[i + 1] = timings[i] + totalDuration * weight;
        }
        return timings;
    }

    private String getSubtitleByTime(List<String> chunks, double[] timings, double currentTime) {
        if (chunks.isEmpty() || timings.length == 0) return "";
        for (int i = 0; i < chunks.size(); i++) {
            if (currentTime >= timings[i] && currentTime < timings[i + 1]) {
                return chunks.get(i);
            }
        }
        return chunks.get(chunks.size() - 1); // 마지막 청크 유지
    }

    // ─────────────────────────────────────────────────────────────
    //  보색 계산 (배경색 기반 파티클 강조색)
    // ─────────────────────────────────────────────────────────────

    private int[] computeAccentColor(int r, int g, int b) {
        // HSB 변환 후 색조 180도 회전 (보색)
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        hsb[0] = (hsb[0] + 0.5f) % 1.0f; // 보색
        hsb[1] = Math.max(hsb[1], 0.6f);  // 채도 최소 60%
        hsb[2] = Math.max(hsb[2], 0.8f);  // 밝기 최소 80%
        Color accent = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
        return new int[]{accent.getRed(), accent.getGreen(), accent.getBlue()};
    }

    // ─────────────────────────────────────────────────────────────
    //  FFmpeg 레코더
    // ─────────────────────────────────────────────────────────────

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
        r.setSampleRate(SAMPLE_RATE); // ③ 44100Hz 통일
        r.setAudioBitrate(128000);
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    //  유틸
    // ─────────────────────────────────────────────────────────────

    private List<String> buildSubtitleChunks(String script) {
        if (script == null || script.isBlank()) return List.of();
        String[] words = script.split("\\s+");
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < words.length; i += CHUNK_SIZE)
            chunks.add(String.join(" ",
                    Arrays.copyOfRange(words, i, Math.min(i + CHUNK_SIZE, words.length))));
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
        for (String n : new String[]{
                "Noto Sans KR", "NanumGothic", "NanumBarunGothic",
                "맑은 고딕", "Apple SD Gothic Neo"}) {
            Font f = new Font(n, Font.BOLD, size);
            if (f.canDisplay('가')) return f;
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, size);
    }
}