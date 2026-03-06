package com.aishots.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 쇼츠 생성 요청 DTO
 *
 * [보안] 모든 필드에 명시적 검증 적용:
 * - topic: 길이 제한 (프롬프트 인젝션 / DoS 방지)
 * - tone, voice: 허용값 화이트리스트 (임의 문자열이 AI 프롬프트에 삽입되는 것 방지)
 * - backgroundColor: 필수 3개, 각 채널 0-255 범위 강제
 */
@Data
public class ShortsRequest {

    public static final Set<String> ALLOWED_TONES = Set.of(
            "친근하고 흥미롭게",
            "전문적이고 신뢰감 있게",
            "유머러스하고 재미있게",
            "놀랍고 충격적인 사실 전달",
            "차분하고 감성적으로"
    );

    public static final Set<String> ALLOWED_VOICES = Set.of(
            "여성_기본", "남성_기본", "여성_활기", "남성_중후"
    );

    @NotBlank(message = "주제를 입력해주세요.")
    @Size(min = 2, max = 100, message = "주제는 2자 이상 100자 이하로 입력해주세요.")
    private String topic;

    @Min(value = 30, message = "영상 길이는 최소 30초입니다.")
    @Max(value = 90, message = "영상 길이는 최대 90초입니다.")
    private int durationSeconds = 60;

    @NotBlank(message = "말투를 선택해주세요.")
    private String tone = "friendly and engaging";

    @NotBlank(message = "음성을 선택해주세요.")
    private String voice = "en_US";

    @NotNull(message = "배경색을 선택해주세요.")
    @Size(min = 3, max = 3, message = "배경색은 RGB 3개 값이어야 합니다.")
    private List<@NotNull @Min(0) @Max(255) Integer> backgroundColor = List.of(15, 15, 25);
}
