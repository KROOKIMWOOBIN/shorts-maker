package com.aishots.service;

/**
 * 장면 타입 — 주제 키워드 기반 자동 매핑
 * 각 타입마다 완전히 다른 배경 씬을 Java2D로 렌더링
 */
public enum SceneType {
    SPACE,      // 우주: 별 + 행성 + 성운
    CITY,       // 도시: 스카이라인 실루엣 + 네온
    NATURE,     // 자연: 산 레이어 + 안개
    ABSTRACT,   // 추상: 기하학 도형 + 글로우
    DARK,       // 어두움: 번개 + 깊은 배경
    OCEAN,      // 바다: 웨이브 레이어
    FIRE,       // 에너지: 불꽃 파티클
    TECH;       // 기술: 그리드 + 데이터 스트림

    /** 주제/스크립트 키워드로 씬 자동 결정 */
    public static SceneType fromTopic(String topic) {
        if (topic == null) return ABSTRACT;
        String t = topic.toLowerCase();

        if (containsAny(t, "space","star","galaxy","planet","universe","cosmos","nasa","astronaut","rocket"))
            return SPACE;
        if (containsAny(t, "city","urban","building","architecture","street","downtown","skyscraper"))
            return CITY;
        if (containsAny(t, "nature","forest","mountain","ocean","sea","river","tree","plant","animal","wildlife"))
            return NATURE;
        if (containsAny(t, "ocean","sea","water","wave","marine","fish","deep","underwater"))
            return OCEAN;
        if (containsAny(t, "fire","energy","power","explosion","heat","passion","war","battle","fight"))
            return FIRE;
        if (containsAny(t, "tech","ai","computer","code","software","digital","internet","robot","cyber","data","hack"))
            return TECH;
        if (containsAny(t, "dark","mystery","horror","scary","ghost","death","shadow","night","secret"))
            return DARK;

        return ABSTRACT;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) if (text.contains(k)) return true;
        return false;
    }
}
