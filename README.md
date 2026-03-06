# 🎬 AI Shorts Maker

> Spring Boot 기반 YouTube Shorts 자동 생성기  
> AI 스크립트 → TTS 음성 → 썸네일 → 영상 합성까지 완전 자동화

---

## 📋 목차

- [소개](#소개)
- [기술 스택](#기술-스택)
- [프로젝트 구조](#프로젝트-구조)
- [사전 준비](#사전-준비)
- [설치 및 실행](#설치-및-실행)
- [설정](#설정)
- [API](#api)
- [성능 최적화](#성능-최적화)

---

## 소개

주제 하나만 입력하면 AI가 스크립트를 작성하고, TTS로 음성을 생성하고,
썸네일과 영상을 자동으로 만들어주는 YouTube Shorts 제작 도구입니다.

```
주제 입력
  → Ollama (AI 스크립트 생성)
  → Piper TTS (음성 생성)  ─┐ 병렬
  → Java2D (썸네일 생성)   ─┘
  → JavaCV/FFmpeg (영상 합성)
  → MP4 출력
```

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 백엔드 | Spring Boot 3.2, Java 17 |
| 영상 합성 | JavaCV 1.5.10 / FFmpeg 6.1.1 |
| AI 스크립트 | Ollama (gemma3) |
| TTS | Piper TTS (로컬, 오프라인) |
| 썸네일 | Java2D |
| 빌드 | Gradle |
| 뷰 | JSP |

---

## 프로젝트 구조

```
shorts-maker/
├── piper/                              # Piper TTS 바이너리 + 모델
│   ├── piper.exe                       # Windows 바이너리
│   ├── en_US-lessac-medium.onnx        # 영어 음성 모델
│   ├── en_US-lessac-medium.onnx.json   # 모델 설정
│   ├── onnxruntime.dll
│   ├── piper_phonemize.dll
│   ├── espeak-ng.dll
│   ├── onnxruntime_providers_shared.dll
│   ├── libtashkeel_model.ort
│   └── espeak-ng-data/
├── outputs/                            # 생성 결과물 (자동 생성)
│   ├── audio/                          # WAV 음성 파일
│   ├── videos/                         # MP4 영상 파일
│   └── thumbnails/                     # JPG 썸네일 파일
├── src/main/java/com/aishots/
│   ├── AiShortsApplication.java        # 애플리케이션 진입점
│   ├── config/
│   │   ├── AppConfig.java              # WebClient, Executor 설정
│   │   └── VideoRenderProfile.java     # 하드웨어 자동 감지 프로파일
│   ├── controller/
│   │   └── ShortsController.java       # REST API 컨트롤러
│   ├── dto/
│   │   ├── ShortsRequest.java          # 요청 DTO
│   │   ├── ScriptData.java             # 스크립트 데이터 DTO
│   │   └── JobStatus.java              # 작업 상태 DTO
│   ├── exception/
│   │   ├── ShortsException.java        # 비즈니스 예외
│   │   └── GlobalExceptionHandler.java # 전역 예외 처리
│   ├── service/
│   │   ├── ShortsGenerationService.java # 파이프라인 오케스트레이터
│   │   ├── ScriptService.java           # Ollama AI 스크립트 생성
│   │   ├── TtsService.java              # Piper TTS 음성 생성
│   │   ├── ThumbnailService.java        # Java2D 썸네일 생성
│   │   └── VideoService.java            # JavaCV 영상 합성
│   └── util/
│       └── PathUtils.java               # 경로 보안 유틸
├── src/main/resources/
│   └── application.properties
├── src/main/webapp/WEB-INF/views/
│   └── index.jsp
└── build.gradle
```

---

## 사전 준비

### 1. Java 17+

```bash
java -version
```

### 2. Ollama 설치 및 모델 다운로드

```bash
# Ollama 설치: https://ollama.com
ollama pull gemma3
ollama serve
```

### 3. Piper TTS 바이너리 + 모델

**바이너리 다운로드** (OS에 맞게 선택)

| OS | 파일 |
|----|------|
| Windows | `piper_windows_amd64.zip` |
| Linux x86 | `piper_linux_x86_64.tar.gz` |
| Mac Intel | `piper_macos_x64.tar.gz` |
| Mac M1/M2 | `piper_macos_aarch64.tar.gz` |

```
https://github.com/rhasspy/piper/releases/tag/2023.11.14-2
```

압축 해제 후 `piper/` 폴더에 전체 파일 복사.

**영어 음성 모델 다운로드**

```
https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/en_US-lessac-medium.onnx
https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json
```

두 파일을 `piper/` 폴더에 저장.

**Linux/Mac 실행 권한 부여**

```bash
chmod +x piper/piper
```

**동작 확인**

```bash
# Windows
echo Hello this is a test | piper\piper.exe --model piper\en_US-lessac-medium.onnx --output_file test.wav

# Linux/Mac
echo "Hello this is a test" | ./piper/piper --model piper/en_US-lessac-medium.onnx --output_file test.wav
```

---

## 설치 및 실행

```bash
# 1. 빌드
./gradlew build

# 2. 실행
./gradlew bootRun
```

브라우저에서 접속:

```
http://localhost:8080
```

---

## 설정

`src/main/resources/application.properties`

```properties
# Ollama
ollama.api.url=http://localhost:11434/api/generate
ollama.model=gemma3
ollama.timeout=120

# 출력 경로
output.audio.dir=outputs/audio
output.video.dir=outputs/videos
output.thumbnail.dir=outputs/thumbnails
```

---

## API

### 영상 생성 요청

```http
POST /api/shorts/generate
Content-Type: application/json

{
  "topic": "The history of space exploration",
  "durationSeconds": 60,
  "tone": "friendly and engaging",
  "voice": "en_US",
  "backgroundColor": [20, 20, 40]
}
```

### 생성 상태 조회

```http
GET /api/shorts/status/{jobId}
```

응답:

```json
{
  "jobId": "uuid",
  "status": "COMPLETED",
  "progress": 100,
  "message": "✅ Video generated! (87s)",
  "videoUrl": "/videos/uuid.mp4",
  "thumbnailUrl": "/thumbnails/uuid.jpg"
}
```

### 상태값

| status | 설명 |
|--------|------|
| `PENDING` | 대기 중 |
| `PROCESSING` | 생성 중 |
| `COMPLETED` | 완료 |
| `ERROR` | 오류 |

---

## 성능 최적화

### 하드웨어 자동 감지 (VideoRenderProfile)

CPU 코어 수와 힙 메모리를 감지해서 자동으로 렌더링 프로파일을 선택합니다.

| 프로파일 | 조건 | preset | CRF | FPS |
|----------|------|--------|-----|-----|
| FAST | 2코어 이하 또는 RAM 1GB 미만 | ultrafast | 30 | 24 |
| BALANCED | 4코어 이하 또는 RAM 3GB 미만 | superfast | 26 | 30 |
| QUALITY | 4코어 초과, RAM 4GB 이상 | veryfast | 23 | 30 |

### 병렬 파이프라인

TTS 음성 생성과 썸네일 생성을 동시에 실행해서 전체 생성 시간을 단축합니다.

```
스크립트 생성 → [TTS 음성 생성 ∥ 썸네일 생성] → 영상 합성
```

### 주요 최적화 목록

| 항목 | 내용 |
|------|------|
| 폰트 캐시 | JVM 기동 시 1회 탐색, static final 캐싱 |
| 자막 프레임 캐시 | 동일 자막 청크 내 프레임 재사용 (~95% 렌더링 감소) |
| TextLayout 외곽선 | 48번 drawString → 1번 draw(Shape) |
| JPEG 품질 95 | 썸네일 기본값 75 → 95 명시 |
| OS별 javacv | 전 플랫폼 1.5GB → 현재 OS만 ~300MB |
| G1GC | 저사양 환경 GC pause 최소화 |
| WAV 리샘플링 | Piper 22050Hz → 44100Hz 변환으로 노이즈 제거 |
| 파티클 배경 | 80개 파티클 + 보색 강조 + 3단 그라데이션 |
| 자막 타이밍 | 균등 분할 → 단어 수 비례 가중 타이밍 |
