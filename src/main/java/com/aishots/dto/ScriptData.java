package com.aishots.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptData {
    private String       title;
    private String       hook;
    private String       script;
    private String       emotion;
    private List<String> videoPrompts;   // AnimateDiff 클립 프롬프트 (4개)
    private List<String> hashtags;
}
