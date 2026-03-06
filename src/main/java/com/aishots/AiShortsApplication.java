package com.aishots;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync          // @Async 활성화 (AppConfig.taskExecutor Bean 사용)
@EnableScheduling     // @Scheduled 활성화 (만료 작업 자동 제거)
public class AiShortsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiShortsApplication.class, args);
    }
}
