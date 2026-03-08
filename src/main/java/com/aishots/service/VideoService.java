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
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * VideoService v5
 *
 * 변경사항:
 * ① ThumbnailService 의존성 완전 제거
 * ② bgmPath 파라미터 추가 (BgmGeneratorService에서 생성된 WAV)
 * ③ clipPaths 파라미터 추가 (AnimateDiff 클립 → 프레임 추출)
 * ④ AI 클립 없으면 Java2D 씬으로 자동 폴백
 */
@Slf4j
@Service
public class VideoService {

    private static final int   W           = 1080;
    private static final int   H           = 1920;
    private static final int   SAMPLE_RATE = 44100;
    private static final int   INTRO_SECS  = 3;
    private static final int   OUTRO_SECS  = 3;
    private static final int   FADE_FRAMES = 12;
    private static final float SUBTITLE_Y  = 0.72f;

    private static final int    WORDS_PER_CHUNK = 3;

    private static final Font FONT_WORD  = resolveFont(100, Font.BOLD);
    private static final Font FONT_HOOK  = resolveFont(96,  Font.BOLD);
    private static final Font FONT_CTA   = resolveFont(72,  Font.BOLD);
    private static final Font FONT_LABEL = resolveFont(38,  Font.PLAIN);

    private final TtsService         ttsService;
    private final VideoRenderProfile profile;

    public VideoService(TtsService ttsService, VideoRenderProfile profile) {
        this.ttsService = ttsService;
        this.profile    = profile;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        log.info("VideoService v5 — 프로파일: {}", profile.getProfile());
    }

    // ════════════════════════════════════════════════════════════
    //  PUBLIC
    // ════════════════════════════════════════════════════════════

    public String createShortsVideo(
            String audioPath,
            String script,
            String hookText,
            String topic,
            List<String> clipPaths,   // AI 생성 클립 (없으면 빈 리스트)
            String bgmPath,           // 생성된 BGM WAV (없으면 null)
            int bgR, int bgG, int bgB,
            String filename) throws Exception {

        String videoDir = "outputs/videos";
        Files.createDirectories(Paths.get(videoDir));
        String outputPath = videoDir + "/" + filename + ".mp4";

        double duration    = ttsService.getAudioDuration(audioPath);
        int    fps         = profile.getFps();
        long   totalFrames = (long)(duration * fps);

        SceneType   scene   = SceneType.fromTopic(topic != null ? topic : script);
        EmotionTone emotion = EmotionTone.fromText(script, script);
        log.info("[{}] 씬={} 감정={} {}초 {}fps", filename, scene, emotion, (int)duration, fps);

        // AI 클립 → 프레임 추출
        List<BufferedImage> clipFrames = extractFramesFromClips(clipPaths);
        log.info("[{}] AI 클립 프레임: {}장", filename, clipFrames.size());

        List<String> words     = buildWords(script);
        double[]     wordTimes = buildWordTimings(words, duration);
        Random       rnd       = new Random(42);

        FFmpegFrameRecorder recorder = buildRecorder(outputPath, fps);
        try {
            recorder.start();
            injectAudio(recorder, audioPath);
            if (bgmPath != null) injectBgm(recorder, bgmPath, duration);
            renderFrames(recorder, scene, emotion,
                    hookText, words, wordTimes,
                    totalFrames, fps, duration, rnd, filename,
                    clipFrames);
        } finally {
            try { recorder.stop();    } catch (Exception e) { log.warn("stop: {}",    e.getMessage()); }
            try { recorder.release(); } catch (Exception e) { log.warn("release: {}", e.getMessage()); }
        }

        log.info("✅ [{}] 완료: {}", filename, outputPath);
        return outputPath;
    }

    // ════════════════════════════════════════════════════════════
    //  AI 클립 → 프레임 추출
    // ════════════════════════════════════════════════════════════

    private List<BufferedImage> extractFramesFromClips(List<String> clipPaths) {
        List<BufferedImage> frames = new ArrayList<>();
        if (clipPaths == null || clipPaths.isEmpty()) return frames;

        Java2DFrameConverter converter = new Java2DFrameConverter();
        for (String clipPath : clipPaths) {
            if (!Files.exists(Paths.get(clipPath))) continue;
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clipPath)) {
                grabber.start();
                Frame frame;
                int count = 0;
                while ((frame = grabber.grabImage()) != null && count < 32) {
                    BufferedImage img = converter.convert(frame);
                    if (img != null) {
                        frames.add(deepCopy(img));
                        count++;
                    }
                }
                log.debug("클립 프레임 {}장 추출: {}", count, clipPath);
            } catch (Exception e) {
                log.warn("클립 프레임 추출 실패: {}", e.getMessage());
            }
        }
        return frames;
    }

    private BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        copy.createGraphics().drawImage(src, 0, 0, null);
        return copy;
    }

    // ════════════════════════════════════════════════════════════
    //  프레임 루프
    // ════════════════════════════════════════════════════════════

    private void renderFrames(
            FFmpegFrameRecorder recorder,
            SceneType scene, EmotionTone emotion,
            String hookText,
            List<String> words, double[] wordTimes,
            long totalFrames, int fps, double duration,
            Random rnd, String jobId,
            List<BufferedImage> clipFrames) throws Exception {

        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage buffer = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        long lastLog = 0;

        for (long fi = 0; fi < totalFrames; fi++) {
            double t = (double) fi / fps;

            Graphics2D g = buffer.createGraphics();
            applyHints(g);

            // 1. 배경: AI 클립 or Java2D 씬
            if (!clipFrames.isEmpty()) {
                drawClipFrame(g, clipFrames, t, duration);
            } else {
                drawScene(g, scene, emotion, t, rnd);
            }

            // 2. 진행 바
            drawProgressBar(g, t, duration, emotion);

            // 3. 콘텐츠
            if (t < INTRO_SECS) {
                drawHookIntro(g, hookText, emotion, t);
            } else if (t > duration - OUTRO_SECS) {
                drawCta(g, emotion, t, duration);
            } else {
                String word = getCurrentWord(words, wordTimes, t);
                if (word != null && !word.isBlank())
                    drawWordPopup(g, word, emotion, fi, fps);
            }

            // 4. 페이드
            float fade = computeFade(fi, totalFrames);
            if (fade < 1f) {
                g.setColor(new Color(0, 0, 0, (int)((1f - fade) * 255)));
                g.fillRect(0, 0, W, H);
            }

            g.dispose();
            recorder.setTimestamp((long)(t * 1_000_000));
            recorder.record(converter.convert(buffer), avutil.AV_PIX_FMT_BGR24);

            if (fi - lastLog >= fps * 5L) {
                log.debug("[{}] {}%", jobId, String.format("%.0f", (double) fi / totalFrames * 100));
                lastLog = fi;
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AI 클립 프레임 렌더링 (켄번스 효과 + 하단 그라데이션)
    // ════════════════════════════════════════════════════════════

    private void drawClipFrame(Graphics2D g, List<BufferedImage> frames,
                                double t, double duration) {
        int    total   = frames.size();
        double perFrame = duration / total;
        int    idx     = Math.min((int)(t / perFrame), total - 1);
        double localT  = (t % perFrame) / perFrame;

        // 켄번스: 짝수=줌인+우패닝, 홀수=줌아웃+좌패닝
        double zoomS = (idx % 2 == 0) ? 1.00 : 1.10;
        double zoomE = (idx % 2 == 0) ? 1.10 : 1.00;
        double panS  = (idx % 2 == 0) ? 0.00 : -0.03;
        double panE  = (idx % 2 == 0) ? -0.03 : 0.00;

        double ease = localT < 0.5
                ? 2 * localT * localT
                : 1 - Math.pow(-2 * localT + 2, 2) / 2;

        double zoom = zoomS + (zoomE - zoomS) * ease;
        double panX = panS  + (panE  - panS)  * ease;

        drawImageKenBurns(g, frames.get(idx), zoom, panX);

        // 크로스페이드 (마지막 15%)
        if (localT > 0.85 && idx + 1 < total) {
            float alpha = (float)((localT - 0.85) / 0.15);
            Composite orig = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            drawImageKenBurns(g, frames.get(idx + 1), 1.0, 0.0);
            g.setComposite(orig);
        }

        // 하단 그라데이션 (자막 가독성)
        g.setPaint(new GradientPaint(0, H * 0.50f, new Color(0, 0, 0, 0),
                0, H, new Color(0, 0, 0, 210)));
        g.fillRect(0, (int)(H * 0.50f), W, H);
    }

    private void drawImageKenBurns(Graphics2D g, BufferedImage img, double zoom, double panX) {
        int srcW   = img.getWidth();
        int srcH   = img.getHeight();
        double scale = Math.max((double) W / srcW, (double) H / srcH) * zoom;
        int scaledW  = (int)(srcW * scale);
        int scaledH  = (int)(srcH * scale);
        int drawX    = (int)((W - scaledW) / 2.0 + scaledW * panX);
        int drawY    = (H - scaledH) / 2;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, drawX, drawY, scaledW, scaledH, null);
    }

    // ════════════════════════════════════════════════════════════
    //  씬 라우터 (AI 클립 없을 때 폴백)
    // ════════════════════════════════════════════════════════════

    private void drawScene(Graphics2D g, SceneType scene, EmotionTone emotion,
                            double t, Random rnd) {
        switch (scene) {
            case SPACE  -> SceneRenderer.drawSpace(g, t, rnd);
            case CITY   -> SceneRenderer.drawCity(g, t);
            case NATURE -> SceneRenderer.drawNature(g, t);
            case OCEAN  -> SceneRenderer.drawOcean(g, t);
            case FIRE   -> SceneRenderer.drawFire(g, t);
            case TECH   -> SceneRenderer.drawTech(g, t);
            case DARK   -> SceneRenderer.drawDark(g, t);
            default     -> SceneRenderer.drawAbstract(g, t, emotion);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  후킹 인트로
    // ════════════════════════════════════════════════════════════

    private void drawHookIntro(Graphics2D g, String hookText, EmotionTone emotion, double t) {
        Color acc = emotion.accent;
        double slideProgress = Math.min(1.0, t / 0.4);
        int blockX = (int)((1 - slideProgress) * -W);

        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(blockX + 30, H / 2 - 350, W - 60, 580, 40, 40);

        g.setColor(acc);
        g.setStroke(new BasicStroke(5f));
        g.drawLine(blockX + 70, H / 2 - 345, W - 70, H / 2 - 345);

        String label = getLabelByEmotion(emotion);
        g.setFont(FONT_LABEL);
        FontMetrics lfm = g.getFontMetrics();
        int lw = lfm.stringWidth(label) + 30;
        g.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), 200));
        g.fillRoundRect((W - lw) / 2, H / 2 - 310, lw, 44, 22, 22);
        g.setColor(Color.BLACK);
        g.drawString(label, (W - lfm.stringWidth(label)) / 2, H / 2 - 278);

        if (hookText != null && t > 0.5) {
            String display = hookText.substring(0,
                    Math.min(hookText.length(), (int)((t - 0.5) / 0.05)));
            if (!display.isBlank()) {
                float scale = (float)Math.min(1.0, (t - 0.5) / 0.3 * 1.05);
                AffineTransform orig = g.getTransform();
                g.translate(W / 2.0, H / 2.0 - 80);
                g.scale(Math.max(0.3f, scale), Math.max(0.3f, scale));
                g.translate(-W / 2.0, -(H / 2.0 - 80));
                drawTextShadowed(g, display, FONT_HOOK, Color.WHITE, acc, H / 2 - 80, W - 100);
                g.setTransform(orig);
            }
            if ((int)(t * 2) % 2 == 0 && display.length() < hookText.length()) {
                g.setColor(acc);
                g.fillRect(W / 2, H / 2 - 120, 4, 96);
            }
        }

        if (t > 1.8) {
            float alpha = (float)(0.5 + 0.5 * Math.sin((t - 1.8) * Math.PI * 3));
            g.setFont(FONT_LABEL);
            g.setColor(new Color(255, 255, 255, (int)(alpha * 200)));
            String hint = "▼  Keep watching  ▼";
            FontMetrics hfm = g.getFontMetrics();
            g.drawString(hint, (W - hfm.stringWidth(hint)) / 2, H / 2 + 260);
        }
    }

    private String getLabelByEmotion(EmotionTone e) {
        return switch (e) {
            case SHOCKING  -> "🔥 SHOCKING FACT";
            case INSPIRING -> "⭐ INSPIRING";
            case SCARY     -> "😱 YOU WON'T BELIEVE THIS";
            case HAPPY     -> "✨ AMAZING FACT";
            case SERIOUS   -> "⚠️ IMPORTANT";
            default        -> "💡 DID YOU KNOW?";
        };
    }

    // ════════════════════════════════════════════════════════════
    //  단어 팝업 자막
    // ════════════════════════════════════════════════════════════

    private static final Color[] HIGHLIGHT_COLORS = {
        new Color(255, 220, 0),    // 노랑
        Color.WHITE,
        new Color(0, 255, 200),    // 시안
        Color.WHITE,
        new Color(255, 120, 120),  // 분홍
        Color.WHITE,
    };

    private void drawWordPopup(Graphics2D g, String word, EmotionTone emotion,
                                long fi, int fps) {
        Color  acc      = emotion.accent;
        double localT   = (fi % Math.max(1, fps / 3.0)) / Math.max(1, fps / 3.0);
        float  popScale = (float)(1.0 + Math.sin(localT * Math.PI) * 0.12);
        int    centerY  = (int)(H * SUBTITLE_Y);
        int    barH     = 260;
        int    barY     = centerY - barH / 2;

        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(25, barY, W - 50, barH, 35, 35);
        g.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), 200));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(70, barY + 6, W - 70, barY + 6);
        g.drawLine(70, barY + barH - 6, W - 70, barY + barH - 6);

        AffineTransform orig = g.getTransform();
        g.translate(W / 2.0, centerY);
        g.scale(popScale, popScale);
        g.translate(-W / 2.0, -centerY);

        boolean isNumeric  = word.matches(".*\\d.*");
        int     chunkIdx   = (int)(fi / Math.max(1, fps / 3.0));
        Color   fillColor  = isNumeric
                ? new Color(255, 220, 0)
                : HIGHLIGHT_COLORS[chunkIdx % HIGHLIGHT_COLORS.length];
        Color shadowColor  = isNumeric ? new Color(180, 100, 0) : acc;

        drawTextShadowed(g, word.toUpperCase(), FONT_WORD, fillColor, shadowColor, centerY, W - 80);
        g.setTransform(orig);
    }

    // ════════════════════════════════════════════════════════════
    //  CTA 아웃트로
    // ════════════════════════════════════════════════════════════

    private void drawCta(Graphics2D g, EmotionTone emotion, double t, double duration) {
        Color acc   = emotion.accent;
        float alpha = (float)Math.min(1.0, (t - (duration - OUTRO_SECS)) / 0.5);

        g.setColor(new Color(0, 0, 0, (int)(alpha * 200)));
        g.fillRoundRect(30, H / 2 - 400, W - 60, 700, 50, 50);
        g.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int)(alpha * 180)));
        g.setStroke(new BasicStroke(4f));
        g.drawRoundRect(30, H / 2 - 400, W - 60, 700, 50, 50);

        AffineTransform orig = g.getTransform();
        g.translate(0, (int)((1 - alpha) * 50));

        drawCtaButton(g, "🔔  FOLLOW FOR MORE", acc, W / 2, H / 2 - 200, (int)(alpha * 255));
        drawCtaButton(g, "❤️  LIKE IF YOU LEARNED",
                new Color(255, 80, 80), W / 2, H / 2, (int)(alpha * 255));

        g.setFont(FONT_LABEL);
        g.setColor(new Color(200, 200, 200, (int)(alpha * 180)));
        String tag = "Share this with someone who needs it!";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(tag, (W - fm.stringWidth(tag)) / 2, H / 2 + 220);
        g.setTransform(orig);
    }

    private void drawCtaButton(Graphics2D g, String text, Color c, int cx, int cy, int alpha) {
        g.setFont(FONT_CTA);
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(text) + 60;
        int bh = 72 + 30;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha / 3));
        g.fillRoundRect(cx - bw / 2, cy - bh / 2, bw, bh, 30, 30);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(cx - bw / 2, cy - bh / 2, bw, bh, 30, 30);
        g.setColor(new Color(255, 255, 255, alpha));
        g.drawString(text, cx - fm.stringWidth(text) / 2, cy + 24);
    }

    // ════════════════════════════════════════════════════════════
    //  진행 바
    // ════════════════════════════════════════════════════════════

    private void drawProgressBar(Graphics2D g, double t, double duration, EmotionTone emotion) {
        int barH = 6, barY = H - barH - 20;
        Color acc = emotion.accent;
        g.setColor(new Color(255, 255, 255, 30));
        g.fillRoundRect(0, barY, W, barH, barH, barH);
        int pw = (int)(W * Math.min(1.0, t / duration));
        if (pw > 0) {
            g.setPaint(new GradientPaint(0, 0,
                    new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), 200),
                    pw, 0, new Color(255, 255, 255, 220)));
            g.fillRoundRect(0, barY, pw, barH, barH, barH);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  텍스트 렌더링
    // ════════════════════════════════════════════════════════════

    private void drawTextShadowed(Graphics2D g, String text, Font font,
                                   Color fill, Color shadow, int centerY, int maxWidth) {
        FontMetrics  fm    = g.getFontMetrics(font);
        List<String> lines = wrapText(fm, text, maxWidth);
        int lh     = font.getSize() + 18;
        int startY = centerY - (lines.size() * lh) / 2;

        Stroke outStroke  = new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Stroke thinStroke = new BasicStroke(2f);
        Stroke prev       = g.getStroke();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int x = (W - fm.stringWidth(line)) / 2;
            int y = startY + (i + 1) * lh;

            TextLayout layout  = new TextLayout(line, font, g.getFontRenderContext());
            Shape      outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));

            g.setColor(new Color(0, 0, 0, 180));
            g.fill(layout.getOutline(AffineTransform.getTranslateInstance(x + 5, y + 5)));
            g.setColor(new Color(0, 0, 0, 230));
            g.setStroke(outStroke);
            g.draw(outline);
            g.setColor(new Color(shadow.getRed(), shadow.getGreen(), shadow.getBlue(), 160));
            g.setStroke(thinStroke);
            g.draw(layout.getOutline(AffineTransform.getTranslateInstance(x + 1, y + 1)));
            g.setStroke(prev);
            g.setColor(fill);
            g.fill(outline);
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
                                sb.put(pos, (short)(sb.get(pos) * 0.22f)); // 22% 볼륨
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
    //  자막 타이밍
    // ════════════════════════════════════════════════════════════

    private List<String> buildWords(String script) {
        if (script == null || script.isBlank()) return List.of();
        String[] raw    = script.trim().split("\\s+");
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < raw.length; i += WORDS_PER_CHUNK) {
            String chunk = String.join(" ",
                    Arrays.copyOfRange(raw, i, Math.min(i + WORDS_PER_CHUNK, raw.length)));
            chunks.add(chunk);
        }
        return chunks;
    }

    private double[] buildWordTimings(List<String> words, double duration) {
        if (words.isEmpty()) return new double[0];
        double available = Math.max(duration - INTRO_SECS - OUTRO_SECS, 1.0);
        int    totalChars = words.stream().mapToInt(String::length).sum();
        double[] times   = new double[words.size() + 1];
        times[0] = INTRO_SECS;
        for (int i = 0; i < words.size(); i++) {
            double w = totalChars > 0
                    ? (double) words.get(i).length() / totalChars
                    : 1.0 / words.size();
            times[i + 1] = times[i] + available * w;
        }
        return times;
    }

    private String getCurrentWord(List<String> words, double[] times, double t) {
        if (words.isEmpty() || times.length == 0) return "";
        for (int i = 0; i < words.size(); i++)
            if (t >= times[i] && t < times[i + 1]) return words.get(i);
        return words.get(words.size() - 1);
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
        StringBuilder cur = new StringBuilder();
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
