package com.aishots.service;

import com.aishots.dto.ScriptData;
import com.aishots.exception.ShortsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * AI 스크립트 생성 서비스 (Ollama)
 *
 * [안정성] WebClient 응답 null 방어 처리
 * [보안]  예외 메시지에 내부 정보 포함하지 않도록 ShortsException 사용
 */
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

    public ScriptData generateScript(String topic, int durationSeconds, String tone) {
        int wordCount = durationSeconds * 3;

        String prompt = String.format("""
                You are a professional YouTube Shorts script writer.
                Write a %d-second shorts script on the following topic.
        
                Topic: %s
                Tone: %s
                Target word count: approximately %d words
        
                Respond ONLY in the following JSON format. Do not include any other text:
        
                {
                    "title": "An engaging video title that drives clicks (under 60 chars)",
                    "hook": "A powerful opening line that grabs viewers in the first 3 seconds (1-2 sentences)",
                    "script": "Full narration script. Write as if speaking naturally.",
                    "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
                    "thumbnailPrompt": "English prompt for thumbnail image generation"
                }
                """, durationSeconds, topic, tone, wordCount);

        String rawResponse = callOllama(prompt);
        return parseScriptJson(rawResponse);
    }

    private String callOllama(String prompt) {
        Map<String, Object> body = Map.of("model", model, "prompt", prompt, "stream", false);

        try {
            // [안정성] response가 null인 경우 명시적으로 처리
            String response = webClient.post()
                    .uri(ollamaUrl)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (response == null || response.isBlank()) {
                throw new ShortsException("AI 서버로부터 응답을 받지 못했습니다. Ollama가 실행 중인지 확인해주세요.");
            }

            JsonNode root = objectMapper.readTree(response);
            String text = root.path("response").asText();
            if (text.isBlank()) {
                throw new ShortsException("AI 스크립트 생성에 실패했습니다. 잠시 후 다시 시도해주세요.");
            }
            return text;

        } catch (ShortsException e) {
            throw e; // 비즈니스 예외는 그대로 전파
        } catch (WebClientResponseException e) {
            // [보안] HTTP 상태/바디 같은 내부 정보 노출 방지
            log.error("Ollama HTTP 오류: {} {}", e.getStatusCode(), e.getMessage());
            throw new ShortsException("AI 서버 연결에 실패했습니다. Ollama가 실행 중인지 확인해주세요.");
        } catch (Exception e) {
            log.error("Ollama 호출 실패", e);
            throw new ShortsException("AI 스크립트 생성에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private ScriptData parseScriptJson(String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.contains("```")) {
                String[] parts = cleaned.split("```");
                for (String part : parts) {
                    String t = part.trim();
                    if (t.startsWith("json")) { cleaned = t.substring(4).trim(); break; }
                    else if (t.startsWith("{"))  { cleaned = t; break; }
                }
            }
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) cleaned = cleaned.substring(s, e + 1);
            return objectMapper.readValue(cleaned, ScriptData.class);
        } catch (Exception e) {
            // [보안] 원본 AI 응답 내용을 로그에만 기록, 클라이언트에는 미노출
            log.error("스크립트 JSON 파싱 실패. 원본 일부: {}",
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            throw new ShortsException("스크립트 형식 파싱에 실패했습니다. 다시 시도해주세요.");
        }
    }
}
