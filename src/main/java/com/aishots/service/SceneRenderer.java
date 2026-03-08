package com.aishots.service;

import java.awt.*;
import java.awt.geom.*;
import java.util.Random;

/**
 * 씬별 배경 렌더러 — Java2D 순수 구현
 * 각 씬은 시간(t)과 프레임(fi)을 받아 완전히 다른 배경을 그림
 */
public class SceneRenderer {

    private static final int W = 1080;
    private static final int H = 1920;

    // ─── SPACE ───────────────────────────────────────────────────

    public static void drawSpace(Graphics2D g, double t, Random rnd) {
        // 딥 스페이스 그라데이션
        g.setPaint(new GradientPaint(0, 0, new Color(2, 2, 15),
                0, H, new Color(8, 0, 25)));
        g.fillRect(0, 0, W, H);

        // 성운 (3겹 글로우)
        drawNebula(g, 200, 400, 600, new Color(80, 0, 150), 0.06f);
        drawNebula(g, 700, 1200, 500, new Color(0, 50, 150), 0.05f);
        drawNebula(g, 400, 1600, 400, new Color(150, 0, 80), 0.04f);

        // 별 (시드 고정으로 재현 가능)
        Random starRnd = new Random(777);
        for (int i = 0; i < 200; i++) {
            int sx = starRnd.nextInt(W);
            int sy = starRnd.nextInt(H);
            float brightness = 0.3f + starRnd.nextFloat() * 0.7f;
            // 별 반짝임 (sin 기반)
            float twinkle = (float)(0.5 + 0.5 * Math.sin(t * (1.5 + i * 0.1) + i));
            int alpha = (int)(brightness * twinkle * 255);
            int size  = starRnd.nextFloat() < 0.9f ? 2 : 4;
            g.setColor(new Color(255, 255, 255, alpha));
            g.fillOval(sx - size / 2, sy - size / 2, size, size);
        }

        // 행성 (천천히 회전)
        double px = 750 + Math.sin(t * 0.1) * 20;
        double py = 600 + Math.cos(t * 0.08) * 15;
        drawPlanet(g, (int)px, (int)py, 120, new Color(60, 20, 100), new Color(120, 60, 180));

        // 소행성 궤적
        for (int i = 0; i < 5; i++) {
            double angle = t * 0.3 + i * Math.PI * 2 / 5;
            int ax = (int)(W / 2 + Math.cos(angle) * (300 + i * 40));
            int ay = (int)(H / 2 + Math.sin(angle) * 150);
            g.setColor(new Color(200, 200, 255, 60));
            g.fillOval(ax, ay, 3, 3);
        }
    }

    private static void drawNebula(Graphics2D g, int cx, int cy, int r, Color c, float alpha) {
        for (int layer = 4; layer >= 1; layer--) {
            int expand = layer * r / 3;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                    (int)(alpha * 255 / layer)));
            g.fill(new Ellipse2D.Float(cx - expand, cy - expand,
                    expand * 2, expand * 2));
        }
    }

    private static void drawPlanet(Graphics2D g, int cx, int cy, int r,
                                    Color dark, Color light) {
        // 행성 본체
        g.setPaint(new RadialGradientPaint(
                cx - r / 3, cy - r / 3, r * 1.2f,
                new float[]{0f, 1f},
                new Color[]{light, dark}));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        // 링
        g.setColor(new Color(light.getRed(), light.getGreen(), light.getBlue(), 60));
        g.setStroke(new BasicStroke(8f));
        g.draw(new Ellipse2D.Float(cx - r * 1.6f, cy - r * 0.3f, r * 3.2f, r * 0.6f));
    }

    // ─── CITY ────────────────────────────────────────────────────

    public static void drawCity(Graphics2D g, double t) {
        // 야간 하늘 그라데이션
        g.setPaint(new GradientPaint(0, 0, new Color(5, 5, 20),
                0, H * 0.7f, new Color(15, 10, 40)));
        g.fillRect(0, 0, W, H);

        // 지평선 글로우
        g.setPaint(new GradientPaint(0, (int)(H * 0.55), new Color(255, 80, 20, 0),
                0, (int)(H * 0.70), new Color(255, 60, 10, 80)));
        g.fillRect(0, (int)(H * 0.55), W, (int)(H * 0.15));

        // 도시 실루엣 (3레이어)
        drawSkyline(g, H, 0.72f, 0.55f, new Color(8, 5, 20), t, 0.0f);    // 원경
        drawSkyline(g, H, 0.65f, 0.45f, new Color(12, 8, 28), t, 0.3f);   // 중경
        drawSkyline(g, H, 0.58f, 0.38f, new Color(5, 3, 15), t, 0.6f);    // 근경

        // 네온 창문
        Random winRnd = new Random(123);
        for (int i = 0; i < 120; i++) {
            int wx = winRnd.nextInt(W);
            int wy = (int)(H * 0.45) + winRnd.nextInt((int)(H * 0.25));
            float flicker = (float)(0.4 + 0.6 * Math.sin(t * (2 + i * 0.3) + i));
            Color[] neons = {
                new Color(0, 200, 255), new Color(255, 50, 150),
                new Color(255, 200, 0), new Color(100, 255, 100)
            };
            Color nc = neons[i % neons.length];
            g.setColor(new Color(nc.getRed(), nc.getGreen(), nc.getBlue(),
                    (int)(flicker * 180)));
            g.fillRect(wx, wy, 4, 6);
        }

        // 도로 반사광
        g.setPaint(new GradientPaint(0, (int)(H * 0.78), new Color(255, 80, 20, 40),
                0, H, new Color(0, 0, 0, 0)));
        g.fillRect(0, (int)(H * 0.78), W, (int)(H * 0.22));
    }

    private static void drawSkyline(Graphics2D g, int h, float horizonY,
                                     float heightRatio, Color c, double t, double phase) {
        Random rnd = new Random((long)(horizonY * 1000));
        int baseY = (int)(h * horizonY);
        int x = 0;
        g.setColor(c);
        while (x < W + 80) {
            int bw = 40 + rnd.nextInt(80);
            int bh = (int)(h * heightRatio * (0.3 + rnd.nextFloat() * 0.7));
            // 패럴랙스 스크롤
            int scrollX = (int)(Math.sin(t * 0.02 + phase) * 5);
            g.fillRect(x + scrollX, baseY - bh, bw - 2, bh + (h - baseY));
            x += bw + rnd.nextInt(20);
        }
    }

    // ─── NATURE ──────────────────────────────────────────────────

    public static void drawNature(Graphics2D g, double t) {
        // 황혼 하늘
        g.setPaint(new GradientPaint(0, 0, new Color(20, 30, 60),
                0, (int)(H * 0.5), new Color(180, 80, 20)));
        g.fillRect(0, 0, W, (int)(H * 0.5));

        // 태양/달
        double sunX = W / 2.0 + Math.sin(t * 0.05) * 100;
        double sunY = H * 0.25;
        drawGlow(g, (int)sunX, (int)sunY, 80, new Color(255, 180, 50), 0.15f);
        g.setPaint(new RadialGradientPaint((float)sunX, (float)sunY, 80f,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 220, 100), new Color(255, 120, 0, 0)}));
        g.fill(new Ellipse2D.Double(sunX - 80, sunY - 80, 160, 160));

        // 산 실루엣 (3레이어)
        drawMountain(g, H, 0.62f, new Color(60, 40, 80, 200), t, 1.0f);
        drawMountain(g, H, 0.55f, new Color(40, 25, 55, 230), t, 0.5f);
        drawMountain(g, H, 0.48f, new Color(20, 12, 30, 255), t, 0.2f);

        // 안개 레이어
        for (int layer = 0; layer < 3; layer++) {
            float fogY = H * (0.55f + layer * 0.06f);
            float drift = (float)(Math.sin(t * 0.2 + layer) * 30);
            g.setPaint(new GradientPaint(0, fogY,
                    new Color(200, 180, 220, 0),
                    0, fogY + 80, new Color(180, 160, 200, 40 + layer * 15)));
            g.fillRect((int)drift, (int)fogY, W, 120);
        }

        // 지면
        g.setPaint(new GradientPaint(0, (int)(H * 0.75), new Color(10, 20, 10),
                0, H, new Color(5, 10, 5)));
        g.fillRect(0, (int)(H * 0.75), W, (int)(H * 0.25));
    }

    private static void drawMountain(Graphics2D g, int h, float peakY,
                                      Color c, double t, double parallax) {
        g.setColor(c);
        int baseY = (int)(h * (peakY + 0.18));
        int scroll = (int)(Math.sin(t * 0.03) * parallax * 8);
        GeneralPath path = new GeneralPath();
        path.moveTo(scroll - 50, baseY);
        Random rnd = new Random((long)(peakY * 1000));
        int x = scroll - 50;
        while (x < W + 100) {
            int pw = 120 + rnd.nextInt(200);
            int ph = (int)(h * (0.08 + rnd.nextFloat() * 0.12));
            path.lineTo(x + pw / 2, baseY - ph);
            path.lineTo(x + pw, baseY);
            x += pw;
        }
        path.lineTo(W + 100, h);
        path.lineTo(scroll - 50, h);
        path.closePath();
        g.fill(path);
    }

    // ─── OCEAN ───────────────────────────────────────────────────

    public static void drawOcean(Graphics2D g, double t) {
        // 심해 그라데이션
        g.setPaint(new GradientPaint(0, 0, new Color(0, 10, 40),
                0, H, new Color(0, 30, 80)));
        g.fillRect(0, 0, W, H);

        // 수면 반사광
        g.setPaint(new GradientPaint(0, (int)(H * 0.3), new Color(0, 100, 180, 0),
                0, (int)(H * 0.5), new Color(0, 150, 220, 60)));
        g.fillRect(0, (int)(H * 0.3), W, (int)(H * 0.2));

        // 웨이브 레이어 (5겹)
        for (int layer = 0; layer < 5; layer++) {
            drawWaveLayer(g, t, layer);
        }

        // 버블
        Random bRnd = new Random(456);
        for (int i = 0; i < 40; i++) {
            double bx = bRnd.nextInt(W) + Math.sin(t + i) * 10;
            double by = ((H - (t * 30 * (0.5 + i * 0.02))) % H + H) % H;
            int br = 2 + bRnd.nextInt(8);
            g.setColor(new Color(150, 220, 255, 40 + bRnd.nextInt(60)));
            g.drawOval((int)bx - br, (int)by - br, br * 2, br * 2);
        }

        // 빛 줄기 (수중 광선)
        for (int i = 0; i < 5; i++) {
            double angle = -0.3 + i * 0.15 + Math.sin(t * 0.2 + i) * 0.05;
            drawLightRay(g, W / 2 + i * 120 - 240, 0, angle, H, 30 + i * 5);
        }
    }

    private static void drawWaveLayer(Graphics2D g, double t, int layer) {
        float baseY = H * (0.35f + layer * 0.08f);
        float amp   = 30 + layer * 15;
        float speed = 0.8f - layer * 0.1f;
        float alpha = 40 + layer * 20;

        GeneralPath wave = new GeneralPath();
        wave.moveTo(0, H);
        wave.lineTo(0, baseY);
        for (int x = 0; x <= W; x += 4) {
            double y = baseY + Math.sin((x * 0.008 + t * speed)) * amp
                    + Math.sin((x * 0.015 + t * speed * 0.7)) * (amp * 0.4);
            wave.lineTo(x, y);
        }
        wave.lineTo(W, H);
        wave.closePath();
        g.setColor(new Color(0, 80 + layer * 20, 160 + layer * 10, (int)alpha));
        g.fill(wave);
    }

    private static void drawLightRay(Graphics2D g, int x, int y,
                                      double angle, int length, int alpha) {
        AffineTransform orig = g.getTransform();
        g.translate(x, y);
        g.rotate(angle);
        g.setPaint(new GradientPaint(0, 0, new Color(150, 220, 255, alpha),
                0, length, new Color(150, 220, 255, 0)));
        g.fillRect(-15, 0, 30, length);
        g.setTransform(orig);
    }

    // ─── FIRE / ENERGY ───────────────────────────────────────────

    public static void drawFire(Graphics2D g, double t) {
        // 어두운 배경
        g.setPaint(new GradientPaint(0, 0, new Color(5, 0, 0),
                0, H, new Color(20, 5, 0)));
        g.fillRect(0, 0, W, H);

        // 불꽃 글로우 (하단에서 위로)
        for (int layer = 5; layer >= 1; layer--) {
            int gy = (int)(H * (0.6 + layer * 0.06));
            int gh = (int)(H * 0.15 * layer);
            Color fc = layer < 3
                    ? new Color(255, 200, 50, 30 * layer)
                    : new Color(255, 80, 10, 20 * layer);
            g.setPaint(new GradientPaint(0, gy, fc, 0, gy - gh, new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), 0)));
            g.fillRect(0, gy - gh, W, gh);
        }

        // 불꽃 파티클
        Random fRnd = new Random(888);
        for (int i = 0; i < 80; i++) {
            double age  = (t * (0.5 + fRnd.nextFloat() * 1.5) + i * 0.3) % 3.0;
            double life = age / 3.0;
            double fx   = W * 0.1 + fRnd.nextInt((int)(W * 0.8))
                        + Math.sin(t * 2 + i) * 30;
            double fy   = H * 0.9 - life * H * 0.6;
            int    fs   = (int)((1 - life) * 12) + 2;
            float  fa   = (float)(1 - life);
            Color  fc   = life < 0.4
                    ? new Color(255, (int)(200 * (1 - life * 2)), 20, (int)(fa * 200))
                    : new Color(255, 50, 0, (int)(fa * 100));
            g.setColor(fc);
            g.fillOval((int)fx - fs / 2, (int)fy - fs / 2, fs, fs);
        }

        // 중앙 에너지 코어
        double pulseR = 80 + Math.sin(t * 3) * 20;
        drawGlow(g, W / 2, (int)(H * 0.75), (int)pulseR, new Color(255, 150, 0), 0.2f);
    }

    // ─── TECH ────────────────────────────────────────────────────

    public static void drawTech(Graphics2D g, double t) {
        // 검정 배경
        g.setColor(new Color(2, 5, 10));
        g.fillRect(0, 0, W, H);

        // 그리드
        g.setStroke(new BasicStroke(0.5f));
        int gridSize = 80;
        for (int x = 0; x < W; x += gridSize) {
            int alpha = 15 + (int)(Math.sin(t + x * 0.01) * 8);
            g.setColor(new Color(0, 150, 255, Math.max(5, alpha)));
            g.drawLine(x, 0, x, H);
        }
        for (int y = 0; y < H; y += gridSize) {
            int alpha = 15 + (int)(Math.sin(t * 0.8 + y * 0.005) * 8);
            g.setColor(new Color(0, 150, 255, Math.max(5, alpha)));
            g.drawLine(0, y, W, y);
        }

        // 데이터 스트림 (Matrix 스타일)
        Random dRnd = new Random(999);
        for (int col = 0; col < 14; col++) {
            int colX = col * (W / 14) + 20;
            int streamLen = 8 + dRnd.nextInt(12);
            double streamY = (t * 60 * (0.5 + dRnd.nextFloat())) % (H + streamLen * 28);
            for (int row = 0; row < streamLen; row++) {
                float brightness = 1f - (float)row / streamLen;
                Color dc = row == 0
                        ? new Color(180, 255, 180, 230)
                        : new Color(0, (int)(180 * brightness), 0, (int)(150 * brightness));
                g.setColor(dc);
                g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
                char ch = (char)('!' + dRnd.nextInt(93));
                g.drawString(String.valueOf(ch), colX, (int)(streamY - row * 28));
            }
        }

        // 회로 기판 패턴
        drawCircuitPattern(g, t);

        // 중앙 홀로그램 원
        double hR = 150 + Math.sin(t * 1.5) * 20;
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0, 200, 255, 60));
        g.draw(new Ellipse2D.Double(W / 2.0 - hR, H * 0.45 - hR, hR * 2, hR * 2));
        g.setColor(new Color(0, 200, 255, 30));
        g.draw(new Ellipse2D.Double(W / 2.0 - hR * 1.3, H * 0.45 - hR * 1.3,
                hR * 2.6, hR * 2.6));
    }

    private static void drawCircuitPattern(Graphics2D g, double t) {
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(0, 150, 255, 25));
        // 수평/수직 라인 패턴
        int[][] nodes = {{100,300},{300,300},{300,500},{700,500},{700,300},{900,300},
                         {200,900},{200,700},{500,700},{500,1100},{800,1100},{800,700}};
        for (int i = 0; i < nodes.length - 1; i++) {
            if (i % 3 == 2) continue;
            g.drawLine(nodes[i][0], nodes[i][1], nodes[i+1][0], nodes[i+1][1]);
            // 노드 점
            g.setColor(new Color(0, 200, 255, (int)(30 + Math.sin(t * 2 + i) * 20)));
            g.fillOval(nodes[i][0] - 4, nodes[i][1] - 4, 8, 8);
        }
    }

    // ─── DARK ────────────────────────────────────────────────────

    public static void drawDark(Graphics2D g, double t) {
        g.setPaint(new GradientPaint(0, 0, new Color(3, 0, 8),
                0, H, new Color(10, 0, 20)));
        g.fillRect(0, 0, W, H);

        // 안개
        Random fogRnd = new Random(321);
        for (int i = 0; i < 8; i++) {
            double fx = fogRnd.nextInt(W) + Math.sin(t * 0.15 + i) * 40;
            double fy = fogRnd.nextInt(H) + Math.cos(t * 0.1 + i) * 30;
            int fr = 100 + fogRnd.nextInt(200);
            g.setColor(new Color(60, 0, 80, 12));
            g.fill(new Ellipse2D.Double(fx - fr, fy - fr, fr * 2, fr * 2));
        }

        // 번개 (가끔)
        if (Math.sin(t * 1.7) > 0.85) {
            drawLightning(g, W / 3 + (int)(Math.random() * W / 3),
                    0, W / 2, H / 2, 8);
        }

        // 별처럼 흩어진 보라 파티클
        Random pRnd = new Random(654);
        for (int i = 0; i < 60; i++) {
            int px = pRnd.nextInt(W);
            int py = pRnd.nextInt(H);
            float fa = (float)(0.1 + 0.3 * Math.sin(t * (0.5 + i * 0.1) + i));
            g.setColor(new Color(150, 0, 200, (int)(fa * 255)));
            g.fillOval(px - 1, py - 1, 3, 3);
        }
    }

    private static void drawLightning(Graphics2D g, int x1, int y1,
                                       int x2, int y2, int branches) {
        if (branches <= 0) return;
        g.setStroke(new BasicStroke(branches * 0.5f));
        g.setColor(new Color(200, 150, 255, 80 * branches / 8));
        g.drawLine(x1, y1, x2, y2);
        if (branches > 2) {
            int mx = (x1 + x2) / 2 + (int)(Math.random() * 80 - 40);
            int my = (y1 + y2) / 2 + (int)(Math.random() * 40 - 20);
            drawLightning(g, x1, y1, mx, my, branches - 1);
            drawLightning(g, mx, my, x2, y2, branches - 1);
        }
    }

    // ─── ABSTRACT ────────────────────────────────────────────────

    public static void drawAbstract(Graphics2D g, double t, EmotionTone emotion) {
        // 감정 기반 그라데이션
        Color c1 = emotion.primary;
        Color c2 = emotion.secondary;
        g.setPaint(new GradientPaint(0, 0,
                new Color(c1.getRed() / 4, c1.getGreen() / 4, c1.getBlue() / 4),
                0, H,
                new Color(c2.getRed() / 4, c2.getGreen() / 4, c2.getBlue() / 4)));
        g.fillRect(0, 0, W, H);

        // 회전하는 기하학 도형
        Color acc = emotion.accent;
        for (int i = 0; i < 6; i++) {
            double angle = t * (0.2 + i * 0.05) + i * Math.PI / 3;
            int cx = (int)(W / 2 + Math.cos(angle) * (200 + i * 40));
            int cy = (int)(H / 2 + Math.sin(angle) * (300 + i * 30));
            int r  = 40 + i * 20;
            int alpha = 20 + i * 8;
            g.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), alpha));
            g.setStroke(new BasicStroke(2f));
            drawHexagon(g, cx, cy, r, angle);
        }

        // 동심원 펄스
        double pulseR = (t * 80) % 400;
        for (int ring = 0; ring < 3; ring++) {
            double r = pulseR + ring * 120;
            int alpha = (int)(80 * (1 - r / 400));
            if (alpha > 0) {
                g.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), alpha));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Ellipse2D.Double(W / 2.0 - r, H / 2.0 - r, r * 2, r * 2));
            }
        }
    }

    private static void drawHexagon(Graphics2D g, int cx, int cy, int r, double rot) {
        GeneralPath hex = new GeneralPath();
        for (int i = 0; i < 6; i++) {
            double a = rot + i * Math.PI / 3;
            double x = cx + r * Math.cos(a);
            double y = cy + r * Math.sin(a);
            if (i == 0) hex.moveTo(x, y);
            else hex.lineTo(x, y);
        }
        hex.closePath();
        g.draw(hex);
    }

    // ─── 공통 유틸 ───────────────────────────────────────────────

    private static void drawGlow(Graphics2D g, int cx, int cy, int r, Color c, float alpha) {
        for (int layer = 4; layer >= 1; layer--) {
            int expand = layer * r / 2;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                    (int)(alpha * 255 / (layer * 1.5))));
            g.fill(new Ellipse2D.Float(cx - expand, cy - expand, expand * 2, expand * 2));
        }
    }
}
