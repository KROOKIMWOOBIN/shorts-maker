package com.aishots.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AppConfig {

    @Value("${ollama.timeout:120}")
    private int ollamaTimeoutSeconds;

    /**
     * WebClient — Ollama HTTP 클라이언트
     *
     * 개선:
     * - ConnectionProvider: 연결 풀 (최대 10, pending 50) → 재사용으로 핸드셰이크 생략
     * - WriteTimeout 추가: 느린 쓰기 시 hang 방지
     * - maxInMemorySize 32MB: 대용량 Ollama 응답 처리
     * - UNKNOWN_PROPERTIES 무시: JSON 파싱 오류 방지
     */
    @Bean
    public WebClient webClient() {
        // 연결 풀 설정 (Ollama는 로컬 단일 서버이므로 소규모)
        ConnectionProvider provider = ConnectionProvider.builder("ollama-pool")
                .maxConnections(10)
                .pendingAcquireMaxCount(50)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(ollamaTimeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    /**
     * ObjectMapper — JSON 파서
     * FAIL_ON_UNKNOWN_PROPERTIES=false: Ollama 응답에 모르는 필드 있어도 파싱 성공
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * @Async 전용 스레드풀
     *
     * - corePoolSize 2: 동시 영상 생성 최대 2개
     * - maxPoolSize 4: 피크 트래픽 대응
     * - queueCapacity 10: 대기 요청
     * - 저사양 환경에서 컨텍스트 스위칭 최소화
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, Math.min(2, cores)));
        exec.setMaxPoolSize(Math.max(2, Math.min(4, cores)));
        exec.setQueueCapacity(10);
        exec.setThreadNamePrefix("shorts-gen-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(120);
        exec.initialize();
        return exec;
    }
}
