package com.aishots.service;

import com.aishots.dto.ScriptData;
import com.aishots.exception.ShortsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.timeout}")
    private int timeoutSeconds;

    // ── 스크립트 + 이미지 프롬프트 동시 생성 ──────────────────

    private static final int IMAGE_COUNT = 10;

    public ScriptData generateScript(String topic, int durationSeconds, String tone) {
        durationSeconds   = Math.min(durationSeconds, 60);
        int wordCount     = durationSeconds * 2;
        int sentenceCount = Math.max(4, Math.min(12, durationSeconds / 5));
        int imageCount    = IMAGE_COUNT;

        String prompt = String.format("""
                당신은 세계 최고의 유튜브 쇼츠 스크립트 작가입니다.

                다음 주제로 %d초짜리 유튜브 쇼츠 스크립트를 한국어로 작성하세요:
                주제: %s
                톤: %s
                목표 단어 수: 약 %d 단어
                문장 수: 정확히 %d개

                규칙:
                - 훅은 충격적이고 강렬한 호기심을 유발해야 함
                - 실제 사람이 말하는 것처럼 자연스럽게
                - 매 문장이 다음 문장을 듣고 싶게 만들어야 함
                - 마지막은 반전이나 클리프행어로 끝내기
                - 숫자가 포함된 놀라운 통계나 사실 최소 1개 포함
                - script 필드는 반드시 하나의 평문 문자열 (배열 금지)
                - sentences 필드는 정확히 %d개의 문장 배열
                - imagePrompts 필드는 정확히 %d개의 영어 Stable Diffusion 프롬프트 배열
                  형식: "subject, scene details, lighting, style, 4k, photorealistic"
                  전체 내러티브에 걸쳐 시각적으로 다양하게 분배
                  각 이미지는 시각적으로 완전히 달라야 함 (씬 반복 금지)
                  imagePrompts는 반드시 영어로 작성

                반드시 아래 JSON만 응답 (마크다운, 추가 텍스트 금지):
                {
                    "title": "60자 이내 클릭베이트 제목 (한국어)",
                    "hook": "즉시 주목을 끄는 첫 1~2문장 (한국어)",
                    "script": "전체 나레이션을 하나의 평문 문자열로 (한국어)",
                    "sentences": ["문장1", "문장2", ...],
                    "emotion": "SHOCKING, INSPIRING, SCARY, HAPPY, SERIOUS, NEUTRAL 중 하나",
                    "hashtags": ["#태그1", "#태그2", "#태그3", "#태그4", "#태그5"],
                    "titleEn": "Same title translated to English",
                    "hookEn": "Same hook translated to English",
                    "scriptEn": "Full narration translated to English as ONE plain string",
                    "hashtagsEn": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
                    "imagePrompts": ["SD prompt 1 in English", "SD prompt 2 in English", ...]
                }
                """, durationSeconds, topic, tone, wordCount, sentenceCount, sentenceCount, imageCount);

        String rawResponse = callOllama(prompt);
        ScriptData data = parseScript(rawResponse);

        // imagePrompts가 없으면 script에서 자동 분리 후 fallback 프롬프트 생성
        if (data.getImagePrompts() == null || data.getImagePrompts().isEmpty()) {
            log.warn("imagePrompts 없음 — script에서 fallback 생성");
            data.setImagePrompts(buildFallbackPrompts(data.getScript(), topic, sentenceCount));
        }

        log.info("스크립트 완료: title={}, imagePrompts={}개",
                data.getTitle(),
                data.getImagePrompts() != null ? data.getImagePrompts().size() : 0);
        return data;
    }

    // ── Ollama 호출 ───────────────────────────────────────────

    private String callOllama(String prompt) {
        Map<String, Object> body = Map.of("model", model, "prompt", prompt, "stream", false);
        try {
            String response = webClient.post()
                    .uri(ollamaUrl)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.isBlank())
                throw new ShortsException("No response from AI. Is Ollama running?");

            JsonNode root = objectMapper.readTree(response);
            String text = root.path("response").asText();
            if (text.isBlank())
                throw new ShortsException("AI returned empty script. Please try again.");
            return text;

        } catch (ShortsException e) {
            throw e;
        } catch (WebClientResponseException e) {
            log.error("Ollama HTTP error: {} {}", e.getStatusCode(), e.getMessage());
            throw new ShortsException("AI server connection failed. Is Ollama running?");
        } catch (Exception e) {
            log.error("Ollama call failed: {}", e.getMessage());
            throw new ShortsException("Script generation failed. Please try again.");
        }
    }

    // ── 파싱 ─────────────────────────────────────────────────

    private ScriptData parseScript(String raw) {
        try {
            String cleaned = extractJson(raw);
            JsonNode root  = objectMapper.readTree(cleaned);

            // script 필드가 배열이면 문자열로 병합
            JsonNode scriptNode = root.path("script");
            if (scriptNode.isArray()) {
                log.warn("script 필드가 배열 — 문자열로 병합");
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : scriptNode) {
                    String text = item.isTextual() ? item.asText()
                            : item.isObject() ? firstNonEmpty(item, "text", "narration", "content")
                            : item.asText();
                    if (!text.isBlank()) sb.append(text.trim()).append(" ");
                }
                ((ObjectNode) root).put("script", sb.toString().trim());
                cleaned = objectMapper.writeValueAsString(root);
            }

            ScriptData data = objectMapper.readValue(cleaned, ScriptData.class);

            // 마크다운 기호 제거
            if (data.getScript() != null) {
                data.setScript(data.getScript()
                        .replaceAll("\\*\\*.*?\\*\\*", "")
                        .replaceAll("\\(\\d+-\\d+\\s*seconds?\\)", "")
                        .replaceAll("[\\*#]", "")
                        .replaceAll("\\[.*?\\]", "")
                        .replaceAll("\\s{2,}", " ")
                        .trim());
            }

            return data;

        } catch (ShortsException e) {
            throw e;
        } catch (Exception ex) {
            log.error("Script parse error: {}", ex.getMessage());
            log.error("Script raw:\n{}", raw);
            throw new ShortsException("Script format error. Please try again.");
        }
    }

    // ── Fallback 프롬프트 생성 ────────────────────────────────

    /**
     * imagePrompts가 없을 때 script를 문장으로 분리하고
     * 각 문장을 기반으로 기본 SD 프롬프트를 생성
     */
    private List<String> buildFallbackPrompts(String script, String topic, int count) {
        if (script == null || script.isBlank()) {
            return Collections.nCopies(count,
                    topic + ", cinematic, 4k, photorealistic, dramatic lighting");
        }

        String[] sentences = script.split("(?<=[.!?])\\s+");
        List<String> prompts = new ArrayList<>();
        String style = ", cinematic photography, dramatic lighting, 4k, photorealistic, high detail";

        for (int i = 0; i < count; i++) {
            String sentence = i < sentences.length ? sentences[i] : topic;
            // 문장에서 핵심 명사/동사만 추출해 SD 프롬프트로 변환
            String base = sentence
                    .replaceAll("[^a-zA-Z0-9 ]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (base.length() > 80) base = base.substring(0, 80);
            prompts.add(base + style);
        }
        return prompts;
    }

    // ── 유틸 ─────────────────────────────────────────────────

    private String extractJson(String raw) {
        String s = raw.trim();
        if (s.contains("```")) {
            String[] parts = s.split("```");
            for (String part : parts) {
                String t = part.trim();
                if (t.startsWith("json")) { s = t.substring(4).trim(); break; }
                if (t.startsWith("{"))    { s = t; break; }
            }
        }
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        return s;
    }

    private String firstNonEmpty(JsonNode node, String... keys) {
        for (String key : keys) {
            String val = node.path(key).asText("").trim();
            if (!val.isBlank()) return val;
        }
        return "";
    }
}
