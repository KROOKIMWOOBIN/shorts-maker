package com.aishots.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.file.*;

/**
 * TTS 서비스 — Piper TTS 로컬 구현
 * piper/ 디렉터리의 바이너리 + 모델 파일로 완전 오프라인 동작
 *
 * 실행 구조:
 *   echo "텍스트" | piper --model ko_KR-kss-medium.onnx --output_file output.wav
 */
@Slf4j
@Service
public class EdgeTtsService {

    // piper 바이너리 및 모델 경로 (프로젝트 루트 기준)
    private static final String PIPER_DIR        = "piper";
    private static final String PIPER_MODEL      = "piper/piper-kss-korean.onnx";
    private static final String PIPER_MODEL_JSON = "piper/piper-kss-korean.onnx.json";

    // OS별 바이너리 이름 자동 감지
    private static final String PIPER_BIN = resolvePiperBinary();

    @Value("${output.audio.dir}")
    private String outputDir;

    public String generateAudio(String text, String filename, String voiceKey) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String outputPath = outputDir + "/" + filename + ".wav";

        // 모델 파일 존재 확인
        if (!Files.exists(Paths.get(PIPER_MODEL))) {
            throw new RuntimeException(
                    "Piper 모델 파일 없음: " + PIPER_MODEL +
                            "\npiper/ 폴더에 ko_KR-kss-medium.onnx 파일을 넣어주세요." +
                            "\n다운로드: https://huggingface.co/rhasspy/piper-voices/tree/main/ko/ko_KR/kss/medium"
            );
        }

        String piperExe   = new File(PIPER_BIN).getAbsolutePath();
        String modelPath  = new File(PIPER_MODEL).getAbsolutePath();
        String configPath = new File(PIPER_MODEL_JSON).getAbsolutePath();
        String outPath    = new File(outputPath).getAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder(
                piperExe,
                "--model",       modelPath,
                "--config",      configPath,
                "--output_file", outPath
        );
        // DLL 로딩을 위해 작업 디렉터리를 piper/ 폴더로 설정
        pb.directory(new File(PIPER_BIN).getAbsoluteFile().getParentFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 텍스트를 stdin으로 전달
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
            writer.write(text);
            writer.flush();
        }

        // 프로세스 완료 대기 (최대 60초)
        boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Piper TTS 타임아웃 (60초)");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorOutput = new String(process.getInputStream().readAllBytes(), "UTF-8");
            log.error("Piper 오류 (exitCode={}, hex={}): {}",
                    exitCode,
                    String.format("0x%08X", exitCode & 0xFFFFFFFFL),
                    errorOutput.isBlank() ? "(출력 없음)" : errorOutput);
            throw new RuntimeException("Piper TTS 실패 (exitCode=" + exitCode + ")");
        }

        if (!Files.exists(Paths.get(outputPath)) || Files.size(Paths.get(outputPath)) == 0) {
            throw new RuntimeException("Piper TTS 출력 파일 없음: " + outputPath);
        }

        log.info("✅ TTS 완료: {} ({}KB)", outputPath,
                Files.size(Paths.get(outputPath)) / 1024);
        return outputPath;
    }

    public double getAudioDuration(String audioPath) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new File(audioPath));
            long frames     = ais.getFrameLength();
            float frameRate = ais.getFormat().getFrameRate();
            ais.close();
            return frames / (double) frameRate;
        } catch (Exception e) {
            log.warn("오디오 길이 추출 실패 (기본값 60초): {}", e.getMessage());
            return 60.0;
        }
    }

    // OS 자동 감지로 바이너리 경로 결정
    private static String resolvePiperBinary() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))   return "piper/piper.exe";
        if (os.contains("mac"))   return "piper/piper";
        return "piper/piper"; // Linux
    }
}