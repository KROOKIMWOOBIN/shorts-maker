# 🎬 AI 쇼츠 제작기 — 순수 Java 버전

Python 의존성 **0개**. 100% Java + Spring Boot로 동작합니다.

---

## 📦 Python → Java 라이브러리 대체 표

| Python | Java 대체 | 비고 |
|--------|-----------|------|
| `moviepy` | `JavaCV 1.5.10` (FFmpeg 바인딩) | 영상 합성, 인코딩 |
| `Pillow` | `Java2D` (JDK 내장) | 썸네일 생성 |
| `edge-tts` | `EdgeTtsService` (WebSocket 직접 구현) | Microsoft TTS 프로토콜 |
| `mutagen` | `JAudioTagger 3.0.1` | MP3 메타데이터 |
| `FastAPI` | `Spring Boot 3.2` | 웹 서버 |
| `Pydantic` | `Lombok @Data` | DTO |
| `asyncio` | `@Async + @EnableAsync` | 비동기 처리 |
| `dict (job_status)` | `ConcurrentHashMap` | 작업 상태 저장 |

---

## 📁 프로젝트 구조

```
ai-shorts-pure-java/
├── build.gradle
├── settings.gradle
└── src/main/
    ├── java/com/aishots/
    │   ├── AiShortsApplication.java
    │   ├── config/AppConfig.java
    │   ├── controller/ShortsController.java
    │   ├── dto/
    │   │   ├── ShortsRequest.java
    │   │   ├── ScriptData.java
    │   │   └── JobStatus.java
    │   └── service/
    │       ├── ScriptService.java          # Ollama HTTP 호출
    │       ├── EdgeTtsService.java         # MS Edge TTS WebSocket 구현
    │       ├── ThumbnailService.java       # Java2D 썸네일 생성
    │       ├── VideoService.java           # JavaCV/FFmpeg 영상 합성
    │       └── ShortsGenerationService.java
    ├── resources/application.properties
    └── webapp/WEB-INF/views/index.jsp
```

---

## 🚀 실행 방법

### 사전 요구사항
- Java 17 이상
- Gradle (또는 `./gradlew` 사용)
- Ollama 설치 및 실행

### 실행

**터미널 1 — Ollama 서버**
```bash
ollama serve
ollama pull gemma3  # 최초 1회
```

**터미널 2 — Spring Boot**
```bash
./gradlew bootRun
```

**브라우저**
```
http://localhost:8080
```

---

## ⚙️ 설정 변경

`src/main/resources/application.properties`

```properties
# AI 모델 변경
ollama.model=gemma3        # llama3.2, mistral 등

# 포트 변경
server.port=8080

# 출력 경로 변경
output.video.dir=outputs/videos
```

---

## 🔧 클래스 수정 가이드

### TTS 음성 추가 (`EdgeTtsService.java`)
```java
static {
    VOICES.put("여성_기본", "ko-KR-SunHiNeural");
    VOICES.put("남성_기본", "ko-KR-InJoonNeural");
    // 여기에 추가
}
```

### 썸네일 테마 추가 (`ThumbnailService.java`)
```java
private static final int[][][] THEMES = {
    {{15,15,35}, {50,10,80},  {180,100,255}},  // 퍼플
    // 새 테마: {배경시작RGB}, {배경끝RGB}, {강조색RGB}
};
```

### 자막 위치/스타일 변경 (`VideoService.java`)
```java
// 자막 Y 위치 (0.0 ~ 1.0)
int subtitleY = (int)(HEIGHT * 0.72);

// 글자 크기
int fontSize = 72;
```

---

## ⚠️ JavaCV 용량 주의

`javacv-platform` 의존성은 모든 플랫폼용 FFmpeg 바이너리를 포함하여 **약 1.5GB**입니다.
빌드 시간이 처음에 오래 걸릴 수 있습니다.

플랫폼을 특정하면 용량을 줄일 수 있습니다:
```gradle
// build.gradle - macOS M1/M2만 사용하는 경우
implementation 'org.bytedeco:javacv:1.5.10'
implementation 'org.bytedeco:ffmpeg:6.1.1-1.5.10:macosx-arm64'
```
