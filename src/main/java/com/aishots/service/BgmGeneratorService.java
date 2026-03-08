package com.aishots.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;

/**
 * BGM 생성 서비스 — Java Sound API 순수 구현
 * 외부 파일 없이 주파수/파형 합성으로 BGM 직접 생성
 *
 * 스타일별 특성:
 * UPBEAT     : 빠른 템포(140bpm), 밝은 화음, 타악기 비트
 * CALM       : 느린 템포(70bpm), 사인파, 낮은 볼륨
 * MYSTERIOUS : 중간 템포(90bpm), 불협화음, 저음 드론
 * INSPIRING  : 점층적 템포(110bpm), 5도 화음, 크레셴도
 * CUTE       : 빠른 템포(130bpm), 고음 멜로디, 스타카토
 */
@Slf4j
@Service
public class BgmGeneratorService {

    private static final int   SAMPLE_RATE = 44100;
    private static final int   CHANNELS    = 2;
    private static final int   BIT_DEPTH   = 16;
    private static final float MASTER_VOL  = 0.25f; // 나레이션 방해 안 하도록 25%

    @Value("${output.bgm.generated.dir:outputs/bgm_generated}")
    private String outputDir;

    // ════════════════════════════════════════════════════════════
    //  PUBLIC
    // ════════════════════════════════════════════════════════════

    /**
     * BGM 생성 — WAV 파일 경로 반환
     * @param style   선택된 BGM 스타일
     * @param duration 영상 길이 (초)
     * @param jobId   작업 ID (파일명에 사용)
     */
    public String generateBgm(BgmStyle style, double duration, String jobId) throws Exception {
        if (style == BgmStyle.NONE) return null;

        Files.createDirectories(Paths.get(outputDir));
        String outputPath = outputDir + "/" + jobId + "_bgm.wav";

        int totalSamples = (int)(SAMPLE_RATE * duration);
        byte[] audioData = new byte[totalSamples * CHANNELS * (BIT_DEPTH / 8)];

        // 스타일별 BGM 생성
        switch (style) {
            case UPBEAT     -> generateUpbeat(audioData, totalSamples, duration);
            case CALM       -> generateCalm(audioData, totalSamples, duration);
            case MYSTERIOUS -> generateMysterious(audioData, totalSamples, duration);
            case INSPIRING  -> generateInspiring(audioData, totalSamples, duration);
            case CUTE       -> generateCute(audioData, totalSamples, duration);
        }

        writeWav(audioData, outputPath);
        log.info("✅ BGM 생성 완료: {} ({}스타일, {}초)", outputPath, style, (int)duration);
        return outputPath;
    }

    // ════════════════════════════════════════════════════════════
    //  스타일별 BGM 생성
    // ════════════════════════════════════════════════════════════

    /** UPBEAT: 140bpm, 밝은 C장조 화음, 킥 드럼 비트 */
    private void generateUpbeat(byte[] data, int totalSamples, double duration) {
        double bpm      = 140;
        double beatSec  = 60.0 / bpm;

        // C장조 스케일 멜로디 패턴
        double[] melody = {261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25};
        int melodyLen   = melody.length;

        for (int i = 0; i < totalSamples; i++) {
            double t        = (double) i / SAMPLE_RATE;
            double beatPos  = (t % beatSec) / beatSec; // 0~1 within beat
            int    beatNum  = (int)(t / beatSec);

            // 멜로디 (사각파 + 사인파 믹스)
            double noteFreq = melody[beatNum % melodyLen];
            double mel      = 0.3 * Math.sin(2 * Math.PI * noteFreq * t)
                            + 0.15 * Math.sin(2 * Math.PI * noteFreq * 2 * t); // 옥타브 배음

            // 킥 드럼 (비트 1, 3)
            double kick = 0;
            if (beatPos < 0.08 && (beatNum % 4 == 0 || beatNum % 4 == 2)) {
                kick = 0.4 * Math.sin(2 * Math.PI * 60 * beatPos * 12) * (1 - beatPos * 12);
            }

            // 하이햇 (8분음표마다)
            double hihat = 0;
            double hihatPos = (t % (beatSec / 2)) / (beatSec / 2);
            if (hihatPos < 0.05) {
                hihat = 0.08 * (Math.random() * 2 - 1) * (1 - hihatPos * 20);
            }

            // 베이스 라인
            double bass = 0.2 * Math.sin(2 * Math.PI * 65.41 * t); // C2

            // 페이드인/아웃
            double fade = computeFade(t, duration);
            double sample = (mel + kick + hihat + bass) * MASTER_VOL * fade;

            writeSample(data, i, sample);
        }
    }

    /** CALM: 70bpm, 부드러운 사인파, 낮은 볼륨, 느린 아르페지오 */
    private void generateCalm(byte[] data, int totalSamples, double duration) {
        // Am 펜타토닉 스케일
        double[] notes  = {220.00, 246.94, 261.63, 293.66, 329.63, 369.99, 392.00};
        double bpm      = 70;
        double beatSec  = 60.0 / bpm;

        for (int i = 0; i < totalSamples; i++) {
            double t       = (double) i / SAMPLE_RATE;
            int    beatNum = (int)(t / beatSec);

            // 아르페지오 멜로디 (부드러운 사인파)
            double noteFreq = notes[beatNum % notes.length];
            double env      = Math.exp(-((t % beatSec) / beatSec) * 3); // 디케이 엔벨로프
            double mel      = 0.25 * Math.sin(2 * Math.PI * noteFreq * t) * env;

            // 패드 (지속음, 낮은 볼륨)
            double pad = 0.1 * Math.sin(2 * Math.PI * 110.0 * t)   // A2
                       + 0.08 * Math.sin(2 * Math.PI * 164.81 * t); // E3

            // 느린 LFO 모듈레이션
            double lfo    = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.1 * t);
            double fade   = computeFade(t, duration);
            double sample = (mel + pad) * lfo * MASTER_VOL * fade;

            writeSample(data, i, sample);
        }
    }

    /** MYSTERIOUS: 90bpm, 반음계, 저음 드론, 불협화음 */
    private void generateMysterious(byte[] data, int totalSamples, double duration) {
        // 프리지안 스케일 (어두운 느낌)
        double[] scale  = {196.00, 207.65, 233.08, 261.63, 293.66, 311.13, 349.23, 392.00};
        double bpm      = 90;
        double beatSec  = 60.0 / bpm;

        for (int i = 0; i < totalSamples; i++) {
            double t       = (double) i / SAMPLE_RATE;
            int    beatNum = (int)(t / (beatSec * 2)); // 2박 단위

            // 멜로디 (사각파 — 어두운 톤)
            double noteFreq = scale[beatNum % scale.length];
            double square   = Math.signum(Math.sin(2 * Math.PI * noteFreq * t));
            double mel      = 0.12 * square;

            // 저음 드론 (지속)
            double drone = 0.15 * Math.sin(2 * Math.PI * 55.0 * t)  // A1
                         + 0.1  * Math.sin(2 * Math.PI * 58.27 * t); // 반음 — 불협화음

            // 트레몰로 효과
            double tremolo = 0.5 + 0.5 * Math.sin(2 * Math.PI * 6.0 * t);

            // 저음 타악기
            double perc = 0;
            double percPos = (t % (beatSec * 2)) / (beatSec * 2);
            if (percPos < 0.1) {
                perc = 0.2 * Math.sin(2 * Math.PI * 80 * percPos * 10) * (1 - percPos * 10);
            }

            double fade   = computeFade(t, duration);
            double sample = (mel * tremolo + drone + perc) * MASTER_VOL * fade;

            writeSample(data, i, sample);
        }
    }

    /** INSPIRING: 110bpm, 5도 화음, 크레셴도, 웅장한 느낌 */
    private void generateInspiring(byte[] data, int totalSamples, double duration) {
        // G장조 스케일
        double[] scale  = {196.00, 220.00, 246.94, 261.63, 293.66, 329.63, 369.99, 392.00};
        double bpm      = 110;
        double beatSec  = 60.0 / bpm;

        for (int i = 0; i < totalSamples; i++) {
            double t        = (double) i / SAMPLE_RATE;
            int    beatNum  = (int)(t / beatSec);

            // 점층적 볼륨 (크레셴도)
            double crescendo = 0.3 + 0.7 * Math.min(1.0, t / (duration * 0.6));

            // 5도 화음
            double root  = scale[beatNum % scale.length];
            double fifth = root * 1.5; // 완전 5도
            double chord = 0.2 * Math.sin(2 * Math.PI * root  * t)
                         + 0.15 * Math.sin(2 * Math.PI * fifth * t)
                         + 0.1  * Math.sin(2 * Math.PI * root  * 2 * t); // 옥타브

            // 스트링 패드 효과 (여러 배음)
            double strings = 0;
            for (int h = 1; h <= 4; h++) {
                strings += (0.08 / h) * Math.sin(2 * Math.PI * 196.0 * h * t);
            }

            // 심벌 (4박마다)
            double cymbal = 0;
            double cymPos = (t % (beatSec * 4)) / (beatSec * 4);
            if (cymPos < 0.3) {
                cymbal = 0.06 * (Math.random() * 2 - 1) * Math.exp(-cymPos * 10);
            }

            double fade   = computeFade(t, duration);
            double sample = (chord + strings + cymbal) * crescendo * MASTER_VOL * fade;

            writeSample(data, i, sample);
        }
    }

    /** CUTE: 130bpm, 고음 멜로디, 스타카토, 밝은 C장조 */
    private void generateCute(byte[] data, int totalSamples, double duration) {
        // C장조 고음 멜로디 패턴
        double[] melody = {
            523.25, 587.33, 659.25, 698.46, // C5 D5 E5 F5
            783.99, 880.00, 987.77, 1046.50  // G5 A5 B5 C6
        };
        double[] rhythm = {1, 0, 1, 1, 0, 1, 0, 1}; // 스타카토 리듬 패턴
        double bpm      = 130;
        double beatSec  = 60.0 / bpm;

        for (int i = 0; i < totalSamples; i++) {
            double t        = (double) i / SAMPLE_RATE;
            int    beatNum  = (int)(t / beatSec);
            double beatPos  = (t % beatSec) / beatSec;

            // 스타카토 (음표 앞 50%만 소리)
            boolean active = beatPos < 0.5 && rhythm[beatNum % rhythm.length] == 1;

            double noteFreq = melody[beatNum % melody.length];
            // 빠른 어택 엔벨로프
            double env = active
                    ? Math.min(1.0, beatPos * 20) * Math.exp(-beatPos * 5)
                    : 0;

            double mel = 0.25 * Math.sin(2 * Math.PI * noteFreq * t) * env;

            // 글로켄슈필 효과 (배음 추가)
            double bell = 0.1 * Math.sin(2 * Math.PI * noteFreq * 2.756 * t) * env; // 배음

            // 삼각파 베이스 (귀여운 느낌)
            double triPhase = (t * 130 % 1.0);
            double tri = (triPhase < 0.5 ? 4 * triPhase - 1 : 3 - 4 * triPhase) * 0.1;

            double fade   = computeFade(t, duration);
            double sample = (mel + bell + tri) * MASTER_VOL * fade;

            writeSample(data, i, sample);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  유틸
    // ════════════════════════════════════════════════════════════

    private void writeSample(byte[] data, int sampleIndex, double value) {
        // 클리핑 방지
        value = Math.max(-1.0, Math.min(1.0, value));
        short pcm = (short)(value * Short.MAX_VALUE);

        int byteIndex = sampleIndex * CHANNELS * 2;
        if (byteIndex + 3 >= data.length) return;

        // 리틀 엔디안, 스테레오 (L=R)
        data[byteIndex]     = (byte)(pcm & 0xFF);
        data[byteIndex + 1] = (byte)((pcm >> 8) & 0xFF);
        data[byteIndex + 2] = (byte)(pcm & 0xFF);
        data[byteIndex + 3] = (byte)((pcm >> 8) & 0xFF);
    }

    private double computeFade(double t, double duration) {
        double fadeIn  = Math.min(1.0, t / 1.5);           // 1.5초 페이드인
        double fadeOut = Math.min(1.0, (duration - t) / 1.5); // 1.5초 페이드아웃
        return Math.min(fadeIn, fadeOut);
    }

    private void writeWav(byte[] audioData, String outputPath) throws Exception {
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                SAMPLE_RATE, BIT_DEPTH, CHANNELS,
                CHANNELS * (BIT_DEPTH / 8), SAMPLE_RATE, false);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
             AudioInputStream ais = new AudioInputStream(
                     bais, format, audioData.length / format.getFrameSize())) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
        }
    }
}
