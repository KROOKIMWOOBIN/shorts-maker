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

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptService {

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.timeout}")
    private int timeoutSeconds;

    public ScriptData generateScript(String topic, int durationSeconds, String tone) {
        int wordCount = durationSeconds * 2;

        String prompt = String.format("""
                You are a world-class YouTube Shorts script writer.
                Your scripts go viral because they hook viewers instantly and keep them watching.

                Write a %d-second YouTube Shorts script on:
                Topic: %s
                Tone: %s
                Target word count: ~%d words

                RULES:
                - Hook must be shocking, surprising, or deeply curiosity-inducing
                - Script must feel like a real person talking, not an essay
                - Every sentence must make the viewer want to hear the next one
                - End with a cliffhanger or revelation that makes them want more
                - Include at least one surprising statistic or fact with a number
                - First sentence must contain a number or percentage
                - Use "you" and "your" to make it personal
                - MAX 10 words per sentence
                - NO filler phrases: "In conclusion", "As we can see", "It's worth noting"

                CRITICAL OUTPUT RULES:
                - Output ONLY raw JSON. No markdown. No ```json. No explanation.
                - script field: plain sentences only. NO asterisks, NO timestamps, NO stage directions.
                - Start your response with { and end with }

                {
                    "title": "Clickbait title under 60 chars that creates massive curiosity",
                    "hook": "1-2 shocking opening sentences with a number or fact",
                    "script": "Full plain narration. Conversational. No formatting. No symbols.",
                    "emotion": "SHOCKING",
                    "videoPrompts": [
                        "cinematic wide shot of [specific visual scene 1], 4k, photorealistic, smooth motion",
                        "dramatic close-up of [specific visual scene 2], golden hour, cinematic",
                        "atmospheric shot of [specific visual scene 3], moody lighting, depth of field",
                        "epic wide angle of [specific visual scene 4], sweeping camera movement"
                    ],
                    "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
                    "thumbnailPrompt": "Vivid split image description for thumbnail"
                }
                """, durationSeconds, topic, tone, wordCount);

        String rawResponse = callOllama(prompt);
        return parseAndClean(rawResponse);
    }

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
            log.error("Ollama call failed", e);
            throw new ShortsException("Script generation failed. Please try again.");
        }
    }

    private ScriptData parseAndClean(String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.contains("```")) {
                String[] parts = cleaned.split("```");
                for (String part : parts) {
                    String t = part.trim();
                    if (t.startsWith("json")) { cleaned = t.substring(4).trim(); break; }
                    else if (t.startsWith("{")) { cleaned = t; break; }
                }
            }
            int s = cleaned.indexOf('{'), e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) cleaned = cleaned.substring(s, e + 1);

            ScriptData data = objectMapper.readValue(cleaned, ScriptData.class);

            // 스크립트 마크다운/타임스탬프 제거
            if (data.getScript() != null) {
                String clean = data.getScript()
                        .replaceAll("\\*\\*.*?\\*\\*", "")
                        .replaceAll("\\(\\d+-\\d+\\s*seconds?\\)", "")
                        .replaceAll("\\*", "")
                        .replaceAll("#(?!\\w)", "")
                        .replaceAll("\\[.*?\\]", "")
                        .replaceAll("\\s{2,}", " ")
                        .trim();
                data.setScript(clean);
            }
            return data;
        } catch (Exception e) {
            log.error("Script JSON parse failed. Raw: {}",
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            throw new ShortsException("Script format error. Please try again.");
        }
    }
}
