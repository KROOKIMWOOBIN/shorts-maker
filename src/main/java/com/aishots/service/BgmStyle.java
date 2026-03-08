package com.aishots.service;

/**
 * BGM 스타일 — UI에서 사용자가 선택
 * Java Sound API로 완전 생성 (외부 파일 없음)
 */
public enum BgmStyle {

    UPBEAT      ("Upbeat & Energetic",   "신나는 비트, 빠른 템포"),
    CALM        ("Calm & Relaxing",      "잔잔한 배경음, 느린 템포"),
    MYSTERIOUS  ("Mysterious & Dark",    "긴장감, 낮은 톤"),
    INSPIRING   ("Inspiring & Epic",     "웅장한 느낌, 점점 고조"),
    CUTE        ("Cute & Playful",       "밝고 귀여운 멜로디"),
    NONE        ("No BGM",               "BGM 없음");

    public final String label;
    public final String description;

    BgmStyle(String label, String description) {
        this.label       = label;
        this.description = description;
    }

    public static BgmStyle fromValue(String value) {
        if (value == null) return NONE;
        for (BgmStyle s : values()) {
            if (s.name().equalsIgnoreCase(value) || s.label.equalsIgnoreCase(value))
                return s;
        }
        return NONE;
    }
}
