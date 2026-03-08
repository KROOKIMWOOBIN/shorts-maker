package com.aishots.service;

import java.awt.*;

/**
 * 감정 톤 — 스크립트 분석 기반 색상/스타일 자동 결정
 */
public enum EmotionTone {

    SHOCKING  (new Color(220, 50,  20),  new Color(255, 120, 0),   new Color(255, 200, 0)),
    INSPIRING (new Color(180, 140, 0),   new Color(255, 200, 0),   new Color(255, 240, 120)),
    SCARY     (new Color(80,  0,   120), new Color(140, 0,  80),   new Color(220, 100, 255)),
    HAPPY     (new Color(0,   140, 120), new Color(0,   200, 160), new Color(120, 255, 220)),
    SERIOUS   (new Color(20,  40,  80),  new Color(40,  80,  140), new Color(100, 160, 255)),
    NEUTRAL   (new Color(30,  30,  50),  new Color(60,  60,  90),  new Color(160, 160, 220));

    public final Color primary;
    public final Color secondary;
    public final Color accent;

    EmotionTone(Color primary, Color secondary, Color accent) {
        this.primary   = primary;
        this.secondary = secondary;
        this.accent    = accent;
    }

    /** 스크립트/톤 텍스트로 감정 자동 감지 */
    public static EmotionTone fromText(String tone, String script) {
        String t = (tone   == null ? "" : tone.toLowerCase());
        String s = (script == null ? "" : script.toLowerCase());
        String combined = t + " " + s;

        if (containsAny(combined, "shocking","surprising","unbelievable","incredible","wtf","crazy","insane","mind-blowing","충격","놀라"))
            return SHOCKING;
        if (containsAny(combined, "inspiring","motivat","success","achieve","dream","hope","believe","overcome","영감","동기"))
            return INSPIRING;
        if (containsAny(combined, "scary","horror","terrifying","dark","mysterious","creepy","haunting","공포","무서"))
            return SCARY;
        if (containsAny(combined, "happy","fun","joy","laugh","amazing","wonderful","love","positive","행복","즐거"))
            return HAPPY;
        if (containsAny(combined, "serious","important","critical","warning","danger","alert","심각","중요","경고"))
            return SERIOUS;

        return NEUTRAL;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }
}
