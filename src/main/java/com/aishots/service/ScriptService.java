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

    public ScriptData generateScript(String topic, int durationSeconds, String tone) {
        int wordCount     = durationSeconds * 2;
        // 문장 수 = 대략 초 / 5 (한 문장당 약 5초)
        int sentenceCount = Math.max(4, Math.min(10, durationSeconds / 5));

        String prompt = String.format("""
                You are a world-class YouTube Shorts script writer.

                Write a %d-second YouTube Shorts script on:
                Topic: %s
                Tone: %s
                Target word count: ~%d words
                Number of sentences: exactly %d sentences

                RULES:
                - Hook must be shocking, surprising, or deeply curiosity-inducing
                - Script must feel like a real person talking, not an essay
                - Every sentence must make the viewer want to hear the next one
                - End with a cliffhanger or revelation
                - Include at least one surprising statistic or fact with a number
                - script field must be ONE plain string (all sentences joined)
                - sentences field must be an array of exactly %d strings (split the script into sentences)
                - imagePrompts field must be an array of exactly %d strings
                  Each imagePrompt: vivid, cinematic Stable Diffusion prompt for that sentence
                  Format: "subject, scene details, lighting, style, 4k, photorealistic"
                  Must visually match the sentence content
                - IMPORTANT: script field must be a plain STRING, not an array

                Respond ONLY with this exact JSON (no markdown, no extra text):
                {
                    "title": "Clickbait title under 60 chars",
                    "hook": "First 1-2 sentences that GRAB attention immediately",
                    "script": "Full narration as ONE plain string.",
                    "sentences": ["sentence 1", "sentence 2", ...],
                    "emotion": "one of: SHOCKING, INSPIRING, SCARY, HAPPY, SERIOUS, NEUTRAL",
                    "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
                    "imagePrompts": ["SD prompt for sentence 1", "SD prompt for sentence 2", ...]
                }
                """, durationSeconds, topic, tone, wordCount, sentenceCount, sentenceCount, sentenceCount);

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
