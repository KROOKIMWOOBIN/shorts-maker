package com.aishots.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;

/**
 * TTS 서비스 — Piper TTS 로컬 구현
 * piper/ 디렉터리의 바이너리 + 모델 파일로 완전 오프라인 동작
 */
@Slf4j
@Service
public class TtsService {

    private static final String PIPER_MODEL      = "piper/en_US-lessac-medium.onnx";
    private static final String PIPER_MODEL_JSON = "piper/en_US-lessac-medium.onnx.json";
    private static final String PIPER_BIN        = resolvePiperBinary();

    @Value("${output.audio.dir}")
    private String outputDir;

    public String generateAudio(String text, String filename, String voiceKey) throws Exception {
        Files.createDirectories(Paths.get(outputDir));
        String outputPath = outputDir + "/" + filename + ".wav";

        if (!Files.exists(Paths.get(PIPER_MODEL))) {
            throw new RuntimeException(
                "Piper model not found: " + PIPER_MODEL +
                "\nDownload from: https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/"
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
        pb.directory(new File(PIPER_BIN).getAbsoluteFile().getParentFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
            writer.write(text);
            writer.flush();
        }

        boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Piper TTS timeout (60s)");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String err = new String(process.getInputStream().readAllBytes(), "UTF-8");
            log.error("Piper error (exitCode={}, 0x{:08X}): {}",
                    exitCode, exitCode & 0xFFFFFFFFL,
                    err.isBlank() ? "(no output)" : err);
            throw new RuntimeException("Piper TTS failed (exitCode=" + exitCode + ")");
        }

        if (!Files.exists(Paths.get(outputPath)) || Files.size(Paths.get(outputPath)) == 0)
            throw new RuntimeException("Piper output file missing: " + outputPath);

        // 리샘플링: 22050Hz → 44100Hz (노이즈 방지)
        String resampled = resampleWav(outputPath);
        log.info("✅ TTS: {} ({}KB)", resampled, Files.size(Paths.get(resampled)) / 1024);
        return resampled;
    }

    public double getAudioDuration(String audioPath) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new File(audioPath));
            long  frames    = ais.getFrameLength();
            float frameRate = ais.getFormat().getFrameRate();
            ais.close();
            return frames / (double) frameRate;
        } catch (Exception e) {
            log.warn("Audio duration extraction failed (default 60s): {}", e.getMessage());
            return 60.0;
        }
    }

    private String resampleWav(String inputPath) throws Exception {
        String outputPath = inputPath.replace(".wav", "_44k.wav");
        AudioInputStream src = AudioSystem.getAudioInputStream(new File(inputPath));
        AudioFormat targetFmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44100, 16, 1, 2, 44100, false);
        AudioInputStream resampled = AudioSystem.getAudioInputStream(targetFmt, src);
        AudioSystem.write(resampled, AudioFileFormat.Type.WAVE, new File(outputPath));
        src.close();
        resampled.close();
        return outputPath;
    }

    private static String resolvePiperBinary() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "piper/piper.exe";
        return "piper/piper";
    }
}
