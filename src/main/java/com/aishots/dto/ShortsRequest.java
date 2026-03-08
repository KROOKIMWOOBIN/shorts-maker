package com.aishots.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class ShortsRequest {

    public static final Set<String> ALLOWED_TONES = Set.of(
            "friendly and engaging",
            "professional and trustworthy",
            "shocking and mind-blowing",
            "inspiring and motivational",
            "mysterious and scary"
    );

    public static final Set<String> ALLOWED_VOICES = Set.of("en_US");

    public static final Set<String> ALLOWED_BGM_STYLES = Set.of(
            "UPBEAT", "CALM", "MYSTERIOUS", "INSPIRING", "CUTE", "NONE"
    );

    @NotBlank(message = "Topic is required.")
    @Size(min = 2, max = 100, message = "Topic must be 2-100 characters.")
    private String topic;

    @Min(value = 30, message = "Minimum duration is 30 seconds.")
    @Max(value = 90, message = "Maximum duration is 90 seconds.")
    private int durationSeconds = 60;

    @NotBlank(message = "Tone is required.")
    private String tone = "shocking and mind-blowing";

    @NotBlank(message = "Voice is required.")
    private String voice = "en_US";

    // BGM 스타일 (기본: UPBEAT)
    private String bgmStyle = "UPBEAT";

    @NotNull(message = "Background color is required.")
    @Size(min = 3, max = 3, message = "Background color must have 3 RGB values.")
    private List<@NotNull @Min(0) @Max(255) Integer> backgroundColor = List.of(5, 5, 20);
}
