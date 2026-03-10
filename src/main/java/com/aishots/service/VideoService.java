package com.aishots.service;

import com.aishots.config.VideoRenderProfile;
import com.aishots.exception.ShortsException;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * VideoService v8
 *
 * - 자막(단어 팝업) 제거
 * - CTA 아웃트로 제거
 * - 인트로 훅 3초 + SD 이미지 켄번스로만 구성
 * - 영상 끝은 페이드아웃으로 깔끔하게 마무리
 */
@Slf4j
@Service
public class VideoService {

    private static final int  W           = 1080;
    private static final int  H           = 1920;
    private static final int  SAMPLE_RATE = 44100;
    private static final int  FADE_FRAMES = 18;  // 0.6초 페이드


    private final TtsService         ttsService;
    private final VideoRenderProfile profile;

    public VideoService(TtsService ttsService, VideoRenderProfile profile) {
        this.ttsService = ttsService;
        this.profile    = profile;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        log.info("VideoService v8 — 프로파일: {}", profile.getProfile());
    }

    // ════════════════════════════════════════════════════════════
    //  PUBLIC
    // ════════════════════════════════════════════════════════════

    public String createShortsVideo(
            String audioPath,
            String script,
            String hookText,
            String topic,
            List<String> imagePaths,
            String bgmPath,
            int bgR, int bgG, int bgB,
            String filename) throws Exception {

        if (imagePaths == null || imagePaths.isEmpty()) {
            throw new ShortsException(
                    "ComfyUI is not connected. Please start ComfyUI at localhost:8188 and try again.");
        }

        String videoDir = "outputs/videos";
        Files.createDirectories(Paths.get(videoDir));
        String outputPath = videoDir + "/" + filename + ".mp4";

        double duration    = ttsService.getAudioDuration(audioPath);
        int    fps         = profile.getFps();
        long   totalFrames = (long)(duration * fps);

        EmotionTone emotion = EmotionTone.fromText(script, script);
        log.info("[{}] 감정={} {}초 {}fps images={}장",
                filename, emotion, (int)duration, fps, imagePaths.size());

        List<BufferedImage> images = loadImages(imagePaths);
        if (images.isEmpty()) {
            throw new ShortsException("Failed to load generated images. Please check ComfyUI output.");
        }

        // 인트로 이후 구간에 이미지 균등 배분
        double[] imageTimes = buildImageTimes(images.size(), duration);

        FFmpegFrameRecorder recorder = buildRecorder(outputPath, fps);
        try {
            recorder.start();
            injectAudio(recorder, audioPath);
            if (bgmPath != null) injectBgm(recorder, bgmPath, duration);
            renderFrames(recorder, images, imageTimes,
                    totalFrames, fps, duration, filename);
        } finally {
            try { recorder.stop();    } catch (Exception e) { log.warn("stop: {}",    e.getMessage()); }
            try { recorder.release(); } catch (Exception e) { log.warn("release: {}", e.getMessage()); }
        }

        log.info("✅ [{}] 완료: {}", filename, outputPath);
        return outputPath;
    }

    // ════════════════════════════════════════════════════════════
    //  이미지 로드
    // ════════════════════════════════════════════════════════════

    private List<BufferedImage> loadImages(List<String> paths) {
        List<BufferedImage> images = new ArrayList<>();
        for (String path : paths) {
            try {
                BufferedImage src = ImageIO.read(new File(path));
                if (src != null) images.add(resizeCover(src, W, H));
            } catch (Exception e) {
                log.warn("이미지 로드 실패 (스킵): {}", path);
            }
        }
        return images;
    }

    private BufferedImage resizeCover(BufferedImage src, int tw, int th) {
        double scale = Math.max((double) tw / src.getWidth(), (double) th / src.getHeight());
        int sw = (int)(src.getWidth() * scale), sh = (int)(src.getHeight() * scale);
        int dx = (tw - sw) / 2, dy = (th - sh) / 2;
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, dx, dy, sw, sh, null);
        g.dispose();
        return out;
    }

    // ════════════════════════════════════════════════════════════
    //  이미지 타이밍 — 인트로 이후 전체 구간에 균등 배분
    // ════════════════════════════════════════════════════════════

    private double[] buildImageTimes(int count, double duration) {
        double interval = Math.max(duration, 1.0) / count;
        double[] times  = new double[count + 1];
        for (int i = 0; i <= count; i++) times[i] = interval * i;
        return times;
    }

    private int getImageIndex(double t, double[] times, int count) {
        if (count == 0 || t < times[0]) return -1;
        if (t >= times[count]) return count - 1;
        for (int i = 0; i < count; i++)
            if (t >= times[i] && t < times[i + 1]) return i;
        return count - 1;
    }

    // ════════════════════════════════════════════════════════════
    //  메인 프레임 루프
    // ════════════════════════════════════════════════════════════

    private void renderFrames(
            FFmpegFrameRecorder recorder,
            List<BufferedImage> images, double[] imageTimes,
            long totalFrames, int fps, double duration,
            String jobId) throws Exception {

        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage buffer = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        long lastLog = 0;

        for (long fi = 0; fi < totalFrames; fi++) {
            double t = (double) fi / fps;
            Graphics2D g = buffer.createGraphics();
            applyHints(g);

            // ── 배경: 첫 프레임부터 SD 이미지 켄번스 ─────────
            int idx = getImageIndex(t, imageTimes, images.size());
            if (idx >= 0) {
                drawImageKenBurns(g, images.get(idx), t,
                        imageTimes[idx], imageTimes[idx + 1], idx);
            } else {
                g.drawImage(images.get(0), 0, 0, null);
            }

            // ── 페이드인 / 페이드아웃 ─────────────────────────
            float fade = computeFade(fi, totalFrames);
            if (fade < 1f) {
                g.setColor(new Color(0, 0, 0, (int)((1f - fade) * 255)));
                g.fillRect(0, 0, W, H);
            }

            g.dispose();
            recorder.setTimestamp((long)(t * 1_000_000));
            recorder.record(converter.convert(buffer), avutil.AV_PIX_FMT_BGR24);

            if (fi - lastLog >= fps * 5L) {
                log.debug("[{}] {}%", jobId,
                        String.format("%.0f", (double) fi / totalFrames * 100));
                lastLog = fi;
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  이미지 효과
    // ════════════════════════════════════════════════════════════

    private void drawImageKenBurns(Graphics2D g, BufferedImage img,
                                    double t, double tStart, double tEnd, int idx) {
        double segLen = Math.max(tEnd - tStart, 0.001);
        double localT = Math.min(1.0, (t - tStart) / segLen);
        double ease   = localT < 0.5
                ? 2 * localT * localT
                : 1 - Math.pow(-2 * localT + 2, 2) / 2;

        boolean zoomIn = (idx % 2 == 0);
        double zoom  = zoomIn ? 1.00 + 0.08 * ease : 1.08 - 0.08 * ease;
        double panX  = zoomIn ? 0.02 - 0.04 * ease : -0.02 + 0.04 * ease;

        double scale  = Math.max((double) W / img.getWidth(),
                                 (double) H / img.getHeight()) * zoom;
        int scaledW   = (int)(img.getWidth()  * scale);
        int scaledH   = (int)(img.getHeight() * scale);
        int drawX     = (int)((W - scaledW) / 2.0 + scaledW * panX);
        int drawY     = (H - scaledH) / 2;

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, drawX, drawY, scaledW, scaledH, null);
    }

    private BufferedImage darken(BufferedImage src, float factor) {
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setColor(new Color(0, 0, 0, (int)((1f - factor) * 255)));
        g.fillRect(0, 0, W, H);
        g.dispose();
        return out;
    }

    // ════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════
    //  텍스트 렌더링
    // ════════════════════════════════════════════════════════════

    private void drawTextShadowed(Graphics2D g, String text, Font font,
                                   Color fill, Color shadow, int centerY, int maxWidth) {
        FontMetrics  fm    = g.getFontMetrics(font);
        List<String> lines = wrapText(fm, text, maxWidth);
        int lh     = font.getSize() + 18;
        int startY = centerY - (lines.size() * lh) / 2;

        Stroke thick = new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Stroke thin  = new BasicStroke(2f);
        Stroke prev  = g.getStroke();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int x = (W - fm.stringWidth(line)) / 2;
            int y = startY + (i + 1) * lh;
            TextLayout tl      = new TextLayout(line, font, g.getFontRenderContext());
            Shape      outline = tl.getOutline(AffineTransform.getTranslateInstance(x, y));

            g.setColor(new Color(0, 0, 0, 180));
            g.fill(tl.getOutline(AffineTransform.getTranslateInstance(x + 5, y + 5)));
            g.setColor(new Color(0, 0, 0, 230)); g.setStroke(thick); g.draw(outline);
            g.setColor(new Color(shadow.getRed(), shadow.getGreen(), shadow.getBlue(), 160));
            g.setStroke(thin);
            g.draw(tl.getOutline(AffineTransform.getTranslateInstance(x + 1, y + 1)));
            g.setStroke(prev);
            g.setColor(fill); g.fill(outline);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  오디오
    // ════════════════════════════════════════════════════════════

    private void injectAudio(FFmpegFrameRecorder recorder, String audioPath) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audioPath)) {
            grabber.setAudioChannels(1);
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.start();
            Frame f;
            while ((f = grabber.grabSamples()) != null) recorder.record(f);
        } catch (Exception e) { log.warn("오디오 삽입 오류: {}", e.getMessage()); }
    }

    private void injectBgm(FFmpegFrameRecorder recorder, String bgmPath, double duration) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(bgmPath)) {
            grabber.setAudioChannels(2);
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.start();
            Frame  f;
            double played = 0;
            while (played < duration && (f = grabber.grabSamples()) != null) {
                if (f.samples != null) {
                    for (java.nio.Buffer buf : f.samples) {
                        if (buf instanceof java.nio.ShortBuffer sb) {
                            sb.rewind();
                            while (sb.hasRemaining()) {
                                int pos = sb.position();
                                sb.put(pos, (short)(sb.get(pos) * 0.22f));
                                sb.position(pos + 1);
                            }
                            sb.rewind();
                        }
                    }
                }
                recorder.record(f);
                played += f.samples != null ? (double) f.samples[0].limit() / SAMPLE_RATE : 0;
            }
        } catch (Exception e) { log.warn("BGM 삽입 오류: {}", e.getMessage()); }
    }

    // ════════════════════════════════════════════════════════════
    //  FFmpeg 레코더
    // ════════════════════════════════════════════════════════════

    private FFmpegFrameRecorder buildRecorder(String outputPath, int fps) {
        FFmpegFrameRecorder r = new FFmpegFrameRecorder(outputPath, W, H, 0);
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
        r.setAudioChannels(2);
        r.setSampleRate(SAMPLE_RATE);
        r.setAudioBitrate(192000);
        return r;
    }

    // ════════════════════════════════════════════════════════════
    //  유틸
    // ════════════════════════════════════════════════════════════

    private float computeFade(long fi, long totalFrames) {
        if (fi < FADE_FRAMES) return (float) fi / FADE_FRAMES;
        if (fi > totalFrames - FADE_FRAMES) return (float)(totalFrames - fi) / FADE_FRAMES;
        return 1f;
    }

    private void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private List<String> wrapText(FontMetrics fm, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur  = new StringBuilder();
        for (String w : text.split("\\s+")) {
            String test = cur.isEmpty() ? w : cur + " " + w;
            if (fm.stringWidth(test) <= maxWidth) cur = new StringBuilder(test);
            else { if (!cur.isEmpty()) lines.add(cur.toString()); cur = new StringBuilder(w); }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    private static Font resolveFont(int size, int style) {
        for (String n : new String[]{
                "Noto Sans KR", "NanumGothic", "맑은 고딕",
                "Arial Black", "Impact", Font.SANS_SERIF}) {
            Font f = new Font(n, style, size);
            if (f.canDisplay('A')) return f;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
