package com.aishots.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptData {
    private String title;
    private String hook;
    private String script;
    private String emotion;
    private List<String> hashtags;
    // 문장별 SD 이미지 프롬프트 (나레이션 문장 수와 1:1 대응)
    private List<String> imagePrompts;
    // AnimateDiff용 (기존 유지)
    private List<String> videoPrompts;
}
