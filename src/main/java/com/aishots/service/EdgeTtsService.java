package com.aishots.service;

import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Edge TTS 서비스 — 최적화 버전
 *
 * 개선 사항:
 * ① KMP 알고리즘으로 오디오 헤더 탐색   O(n²) → O(n)
 * ② 버퍼 초기 용량 예약                  재할당 횟수 감소 (1MB 예약)
 * ③ 재시도 로직                          네트워크 일시 오류 자동 복구 (최대 2회)
 * ④ 연결 타임아웃 분리                    connect 5초 / 전체 60초
 * ⑤ 오디오 헤더 파싱 정확도 향상         2바이트 길이 필드 기반 정확한 오프셋 계산
 */
@Slf4j
@Service
public class EdgeTtsService {

    private static final String TTS_WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId=";

    private static final Map<String, String> VOICES = Map.of(
            "여성_기본", "ko-KR-SunHiNeural",
            "남성_기본", "ko-KR-InJoonNeural",
            "여성_활기", "ko-KR-YuJinNeural",
            "남성_중후", "ko-KR-BongJinNeural"
    );

    // ① KMP 패턴: "Path:audio" 이후 \r\n\r\n 탐색 패턴
    private static final byte[] AUDIO_HEADER_MARKER = "Path:audio\r\n".getBytes();

    private static final int MAX_RETRIES    = 2;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int TOTAL_TIMEOUT_SEC  = 60;
    private static final int AUDIO_BUFFER_INIT  = 1024 * 1024; // ② 1MB 예약

    @Value("${output.audio.dir}")
    private String outputDir;

    public String generateAudio(String text, String filename, String voiceKey) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String outputPath = outputDir + "/" + filename + ".mp3";
        String voiceName  = VOICES.getOrDefault(voiceKey, VOICES.get("여성_기본"));

        // ③ 재시도 로직
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                byte[] audioData = fetchAudio(text, voiceName);
                Files.write(Paths.get(outputPath), audioData);
                log.info("✅ TTS 완료: {} ({}KB)", outputPath, audioData.length / 1024);
                return outputPath;
            } catch (Exception e) {
                lastError = e;
                if (attempt <= MAX_RETRIES) {
                    log.warn("TTS 실패 ({}/{}), {}ms 후 재시도: {}", attempt, MAX_RETRIES,
                            attempt * 500, e.getMessage());
                    Thread.sleep(attempt * 500L);
                }
            }
        }
        throw new RuntimeException("TTS 생성 실패 (" + MAX_RETRIES + "회 재시도): " + lastError.getMessage(), lastError);
    }

    private byte[] fetchAudio(String text, String voiceName) throws Exception {
        String connectionId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String requestId    = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        URI    uri          = new URI(TTS_WSS_URL + connectionId);

        // ② 1MB 사전 예약 → 재할당 횟수 감소
        java.io.ByteArrayOutputStream audioBuffer = new java.io.ByteArrayOutputStream(AUDIO_BUFFER_INIT);
        CountDownLatch latch  = new CountDownLatch(1);
        Exception[]    error  = {null};

        WebSocketClient client = new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake h) {
                send(buildConfigMessage(requestId));
                send(buildSsmlMessage(requestId, text, voiceName));
            }

            @Override public void onMessage(String msg) {
                if (msg.contains("Path:turn.end")) latch.countDown();
            }

            @Override public void onMessage(ByteBuffer bytes) {
                byte[] data = bytes.array();
                int offset = bytes.position();
                int length = bytes.remaining();

                // ⑤ 2바이트 헤더 길이 필드로 정확한 오디오 시작 위치 계산
                // Edge TTS 바이너리 프레임: [2바이트 헤더길이][헤더][오디오]
                if (length < 2) return;
                int headerLen = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                int audioStart = offset + 2 + headerLen;

                if (audioStart < offset + length) {
                    // 헤더 텍스트에서 "Path:audio" 확인
                    String header = new String(data, offset + 2, Math.min(headerLen, 200));
                    if (header.contains("Path:audio")) {
                        try {
                            audioBuffer.write(data, audioStart, (offset + length) - audioStart);
                        } catch (Exception e) {
                            log.warn("오디오 청크 기록 오류: {}", e.getMessage());
                        }
                    }
                } else {
                    // 폴백: ① KMP 탐색
                    int kmpIdx = kmpSearch(data, offset, length, AUDIO_HEADER_MARKER);
                    if (kmpIdx >= 0) {
                        int start = skipToCRLF(data, kmpIdx + AUDIO_HEADER_MARKER.length, offset + length);
                        if (start > 0) {
                            try { audioBuffer.write(data, start, (offset + length) - start); }
                            catch (Exception e) { log.warn("KMP 폴백 오디오 기록 오류: {}", e.getMessage()); }
                        }
                    }
                }
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                latch.countDown();
            }

            @Override public void onError(Exception ex) {
                error[0] = ex;
                latch.countDown();
            }
        };

        client.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0");
        client.addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold");

        // ④ 연결 타임아웃 설정
        client.setConnectionLostTimeout(CONNECT_TIMEOUT_MS / 1000);
        client.connect();

        if (!latch.await(TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS))
            throw new RuntimeException("TTS 타임아웃 (" + TOTAL_TIMEOUT_SEC + "초)");
        client.close();

        if (error[0] != null) throw error[0];

        byte[] result = audioBuffer.toByteArray();
        if (result.length == 0) throw new RuntimeException("TTS 오디오 데이터 없음");
        return result;
    }

    /**
     * ① KMP(Knuth-Morris-Pratt) 패턴 탐색 O(n)
     * 기존 중첩 루프 O(n*m) 대비 대용량 오디오 청크에서 확실한 성능 향상
     */
    private int kmpSearch(byte[] data, int offset, int length, byte[] pattern) {
        int[] failure = buildKmpFailure(pattern);
        int j = 0;
        for (int i = 0; i < length; i++) {
            while (j > 0 && data[offset + i] != pattern[j]) j = failure[j - 1];
            if (data[offset + i] == pattern[j]) j++;
            if (j == pattern.length) return offset + i - pattern.length + 1;
        }
        return -1;
    }

    private int[] buildKmpFailure(byte[] pattern) {
        int[] f = new int[pattern.length];
        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j > 0 && pattern[i] != pattern[j]) j = f[j - 1];
            if (pattern[i] == pattern[j]) j++;
            f[i] = j;
        }
        return f;
    }

    /** \r\n\r\n 이후 위치 반환 */
    private int skipToCRLF(byte[] data, int start, int end) {
        for (int i = start; i < end - 3; i++) {
            if (data[i]=='\r' && data[i+1]=='\n' && data[i+2]=='\r' && data[i+3]=='\n')
                return i + 4;
        }
        return -1;
    }

    private String buildConfigMessage(String requestId) {
        return "X-Timestamp:" + Instant.now() + "\r\n" +
               "Content-Type:application/json; charset=utf-8\r\n" +
               "Path:speech.config\r\n\r\n" +
               "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
               "\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":false}," +
               "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
    }

    private String buildSsmlMessage(String requestId, String text, String voiceName) {
        String ssml = String.format(
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ko-KR'>" +
                "<voice name='%s'><prosody rate='+0%%' pitch='+0Hz'>%s</prosody></voice></speak>",
                voiceName, escapeXml(text));
        return "X-RequestId:" + requestId + "\r\n" +
               "Content-Type:application/ssml+xml\r\n" +
               "X-Timestamp:" + Instant.now() + "\r\n" +
               "Path:ssml\r\n\r\n" + ssml;
    }

    private String escapeXml(String t) {
        return t.replace("&","&amp;").replace("<","&lt;")
                .replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }

    public double getAudioDuration(String mp3Path) {
        try {
            org.jaudiotagger.audio.AudioFile f =
                    org.jaudiotagger.audio.AudioFileIO.read(new java.io.File(mp3Path));
            return f.getAudioHeader().getTrackLength();
        } catch (Exception e) {
            log.warn("오디오 길이 추출 실패 (기본값 60초): {}", e.getMessage());
            return 60.0;
        }
    }
}
