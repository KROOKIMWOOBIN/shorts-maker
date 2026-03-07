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
        
                CRITICAL OUTPUT RULES:
                        - Output ONLY raw JSON. No markdown. No ```json. No explanation.
                        - script field: plain sentences only. NO asterisks, NO timestamps, NO stage directions, NO formatting.
                        - Start your response with { and end with }
                
                        {
                            "title": "Clickbait title under 60 chars",
                            "hook": "1-2 shocking opening sentences",
                            "script": "Plain narration text only. No formatting. No symbols. Just words.",
                            "emotion": "SHOCKING",
                            "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
                            "thumbnailPrompt": "Vivid image description"
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
            ScriptData data = objectMapper.readValue(cleaned, ScriptData.class);
            // 마크다운/타임스탬프/특수문자 제거
            if (data.getScript() != null) {
                String cleanScript = data.getScript()
                        .replaceAll("\\*\\*.*?\\*\\*", "")         // **굵게** 제거
                        .replaceAll("\\(\\d+-\\d+\\s*seconds?\\)", "") // (0-10 seconds) 제거
                        .replaceAll("\\*", "")                      // * 제거
                        .replaceAll("#(?!\\w)", "")                 // 단독 # 제거
                        .replaceAll("\\[.*?\\]", "")                // [stage direction] 제거
                        .replaceAll("\\s{2,}", " ")                 // 연속 공백 정리
                        .trim();
                data.setScript(cleanScript);
            }
            return data;
        } catch (Exception e) {
            // [보안] 원본 AI 응답 내용을 로그에만 기록, 클라이언트에는 미노출
            log.error("스크립트 JSON 파싱 실패. 원본 일부: {}",
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            throw new ShortsException("스크립트 형식 파싱에 실패했습니다. 다시 시도해주세요.");
        }
    }
}
