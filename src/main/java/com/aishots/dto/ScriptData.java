package com.aishots.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptData {
    // 한국어
    private String title;
    private String hook;
    private String script;
    private String emotion;
    private List<String> hashtags;

    // 영어 버전
    private String titleEn;
    private String hookEn;
    private String scriptEn;
    private List<String> hashtagsEn;

    // SD 이미지 프롬프트
    private List<String> imagePrompts;
}
