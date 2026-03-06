package com.aishots.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * 실행 환경 하드웨어 자동 감지 → 렌더링 프로파일 자동 선택
 *
 * 저사양 (RAM < 2GB 또는 CPU < 2코어) : FAST   - ultrafast, crf=30, 2Mbps
 * 중간   (RAM 2~4GB, CPU 2~3코어)      : BALANCED - superfast, crf=26, 3Mbps
 * 고사양 (RAM > 4GB, CPU 4코어+)       : QUALITY  - veryfast,  crf=23, 4Mbps
 */
@Slf4j
@Getter
@Component
public class VideoRenderProfile {

    public enum Profile { FAST, BALANCED, QUALITY }

    private final Profile profile;
    private final int     threads;
    private final String  preset;
    private final String  crf;
    private final int     bitrate;
    private final int     fps;
    private final int     renderWorkers;

    public VideoRenderProfile() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxRamMb = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getMax() / (1024 * 1024);

        if (cpuCores <= 2 || maxRamMb < 1024) {
            profile       = Profile.FAST;
            preset        = "ultrafast";
            crf           = "30";
            bitrate       = 2_000_000;
            fps           = 24;             // 30→24fps: 렌더링 20% 감소
            renderWorkers = 1;
        } else if (cpuCores <= 4 || maxRamMb < 3072) {
            profile       = Profile.BALANCED;
            preset        = "superfast";
            crf           = "26";
            bitrate       = 3_000_000;
            fps           = 30;
            renderWorkers = Math.min(2, cpuCores - 1);
        } else {
            profile       = Profile.QUALITY;
            preset        = "veryfast";
            crf           = "23";
            bitrate       = 4_000_000;
            fps           = 30;
            renderWorkers = Math.min(4, cpuCores - 1);
        }

        threads = cpuCores;

        log.info("=== VideoRenderProfile ===");
        log.info("  CPU: {}코어 / 힙: {}MB → 프로파일: {}", cpuCores, maxRamMb, profile);
        log.info("  preset={} crf={} bitrate={}Mbps fps={} workers={}",
                preset, crf, bitrate / 1_000_000.0, fps, renderWorkers);
    }
}
