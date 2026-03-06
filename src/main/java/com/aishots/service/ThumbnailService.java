package com.aishots.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 썸네일 생성 서비스 — 고품질 최적화 버전
 *
 * 최적화 목록:
 * ① 폰트 static 캐시          JVM 기동 시 1회 탐색, 매 호출 탐색 루프 제거
 * ② JPEG 품질 95 명시         ImageIO 기본 75 → 95로 올려 선명도 향상
 * ③ 고품질 렌더링 힌트 통일   BICUBIC 보간, VALUE_RENDER_QUALITY 전체 적용
 * ④ TextLayout 외곽선         VideoService와 동일 방식으로 선명한 외곽선
 * ⑤ 멀티레이어 글로우 효과    단순 원 → Gaussian 근사 스택 글로우로 품질 향상
 */
@Slf4j
@Service
public class ThumbnailService {

    private static final int WIDTH  = 1080;
    private static final int HEIGHT = 1920;

    // 색상 테마
    private static final int[][][] THEMES = {
        {{15,15,35},  {50,10,80},   {180,100,255}},
        {{10,30,60},  {5,80,120},   {0,200,255}},
        {{40,10,10},  {120,20,20},  {255,80,80}},
        {{10,40,20},  {20,100,50},  {80,255,120}},
        {{50,30,5},   {120,70,10},  {255,180,0}},
    };

    // ① 폰트 static 캐시
    private static final Font FONT_HOOK  = resolveKoreanFont(100, Font.BOLD);
    private static final Font FONT_TITLE = resolveKoreanFont(68,  Font.PLAIN);

    @Value("${output.thumbnail.dir}")
    private String outputDir;

    private final Random random = new Random();

    public String createThumbnail(String title, String hookText, String filename) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String outputPath = outputDir + "/" + filename + ".jpg";

        int[][] theme   = THEMES[random.nextInt(THEMES.length)];
        int[]   bgStart = theme[0], bgEnd = theme[1], accent = theme[2];

        // ③ 고품질 렌더링 힌트 전체 적용
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        applyQualityHints(g);

        drawGradientBackground(g, bgStart, bgEnd);
        drawGlowEffect(g, accent);                              // ⑤ 스택 글로우
        drawTextOutlined(g, hookText,  FONT_HOOK,  accent, (int)(HEIGHT * 0.35));
        drawTextOutlined(g, title,     FONT_TITLE, null,   (int)(HEIGHT * 0.58));
        drawBottomBar(g, accent);

        g.dispose();

        // ② JPEG 품질 95 명시
        writeJpeg(image, outputPath, 0.95f);

        log.info("✅ 썸네일 생성 완료: {}", outputPath);
        return outputPath;
    }

    // ─────────────────────────────────────────────────────────────
    //  렌더링
    // ─────────────────────────────────────────────────────────────

    private void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,       RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,     RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,      RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,   RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private void drawGradientBackground(Graphics2D g, int[] s, int[] e) {
        g.setPaint(new GradientPaint(
                0, 0,      new Color(s[0], s[1], s[2]),
                0, HEIGHT, new Color(e[0], e[1], e[2])));
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    /**
     * ⑤ 스택 글로우 효과 — 여러 겹의 반투명 원을 겹쳐 Gaussian blur 근사
     * 성능: 추가 루프 3회지만 썸네일은 1회성이므로 부담 없음
     */
    private void drawGlowEffect(Graphics2D g, int[] accent) {
        int r = accent[0], gr = accent[1], b = accent[2];
        // 우상단 대형 글로우 (3겹 스택)
        for (int layer = 0; layer < 3; layer++) {
            int alpha = 8 + layer * 7;
            int expand = layer * 40;
            g.setColor(new Color(r, gr, b, alpha));
            g.fill(new Ellipse2D.Float(500 - expand, -300 - expand,
                    1000 + expand * 2, 1000 + expand * 2));
        }
        // 좌하단 글로우
        for (int layer = 0; layer < 2; layer++) {
            int alpha = 10 + layer * 8;
            int expand = layer * 30;
            g.setColor(new Color(r, gr, b, alpha));
            g.fill(new Ellipse2D.Float(-300 - expand, 1200 - expand,
                    800 + expand * 2, 800 + expand * 2));
        }
        // 중앙 작은 글로우 (포인트)
        g.setColor(new Color(r, gr, b, 12));
        g.fill(new Ellipse2D.Float(150, 650, 700, 700));
    }

    /**
     * ④ TextLayout 외곽선 방식으로 텍스트 렌더링
     * accent != null 이면 강조색, null이면 흰색
     */
    private void drawTextOutlined(Graphics2D g, String text, Font font,
                                   int[] accent, int centerY) {
        g.setFont(font);
        FontMetrics  fm    = g.getFontMetrics(font);
        List<String> lines = wrapText(fm, text, WIDTH - 100, font);
        int lh     = font.getSize() + 22;
        int startY = centerY - (lines.size() * lh) / 2;

        Color fillColor = (accent != null)
                ? new Color(accent[0], accent[1], accent[2])
                : Color.WHITE;

        Stroke outlineStroke = new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Stroke prev = g.getStroke();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int    x    = (WIDTH - fm.stringWidth(line)) / 2;
            int    y    = startY + (i + 1) * lh;

            // 그림자 (오프셋 4px)
            g.setColor(new Color(0, 0, 0, 160));
            TextLayout shadow = new TextLayout(line, font, g.getFontRenderContext());
            g.fill(shadow.getOutline(AffineTransform.getTranslateInstance(x + 4, y + 4)));

            // 외곽선
            TextLayout layout  = new TextLayout(line, font, g.getFontRenderContext());
            Shape      outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));
            g.setColor(new Color(0, 0, 0, 200));
            g.setStroke(outlineStroke);
            g.draw(outline);

            // 본문
            g.setStroke(prev);
            g.setColor(fillColor);
            g.fill(outline);
        }
    }

    private void drawBottomBar(Graphics2D g, int[] accent) {
        int barY = HEIGHT - 90;
        g.setColor(new Color(accent[0], accent[1], accent[2], 50));
        g.fillRect(0, barY, WIDTH, HEIGHT - barY);
        g.setColor(new Color(accent[0], accent[1], accent[2], 180));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(60, barY + 3, WIDTH - 60, barY + 3);
    }

    // ─────────────────────────────────────────────────────────────
    //  JPEG 저장 (② 품질 명시)
    // ─────────────────────────────────────────────────────────────

    private void writeJpeg(BufferedImage image, String path, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality); // 0.0~1.0, 기본 0.75 → 0.95

        try (FileImageOutputStream out = new FileImageOutputStream(new File(path))) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  유틸
    // ─────────────────────────────────────────────────────────────

    private List<String> wrapText(FontMetrics fm, String text, int maxWidth, Font font) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String test = cur.isEmpty() ? w : cur + " " + w;
            if (fm.stringWidth(test) <= maxWidth) cur = new StringBuilder(test);
            else { if (!cur.isEmpty()) lines.add(cur.toString()); cur = new StringBuilder(w); }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    /** ① 폰트 static 캐시 */
    private static Font resolveKoreanFont(int size, int style) {
        for (String name : new String[]{"Noto Sans KR", "NanumGothic", "NanumBarunGothic",
                "맑은 고딕", "Apple SD Gothic Neo"}) {
            Font f = new Font(name, style, size);
            if (f.canDisplay('가')) return f;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
