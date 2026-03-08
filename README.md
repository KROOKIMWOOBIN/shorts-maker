# 🎬 Shorts Maker

AI 기반 유튜브 쇼츠 자동 생성기  
주제 입력 → AI 스크립트 → AI 영상 클립 → TTS → BGM → 완성된 쇼츠

---

## 📋 전체 파이프라인

```
주제 입력
    ↓
Ollama (gemma3) → 스크립트 + 영상 프롬프트 4개 생성
    ↓
[병렬] Piper TTS → 나레이션 WAV
[병렬] BgmGenerator → 스타일별 BGM WAV (Java 자체 생성)
    ↓
AnimateDiff (ComfyUI) → AI 영상 클립 생성
※ ComfyUI 미실행 시 Java2D 씬으로 자동 폴백
    ↓
JavaCV (FFmpeg) → 클립 + 자막 + 오디오 합성
    ↓
완성된 쇼츠 MP4 (1080x1920)
```

---

## 🖥️ 시스템 요구사항

| 항목 | 최소 | 권장 |
|---|---|---|
| OS | Windows 10 / macOS 12 / Ubuntu 20.04 | Windows 11 |
| Java | 17 | 17 |
| RAM | 8GB | 16GB+ |
| GPU | 없어도 동작 (폴백) | RTX 3060+ (AnimateDiff용) |
| 저장공간 | 10GB | 20GB+ |

---

## ⚙️ 사전 준비

### 1. Java 17 설치
```bash
# Windows: https://adoptium.net 에서 Java 17 다운로드
java -version  # 확인
```

### 2. Ollama 설치 및 모델 다운로드
```bash
# https://ollama.com 에서 설치
ollama pull gemma3
ollama serve  # 백그라운드 실행
```

### 3. Piper TTS 설정
```
프로젝트루트/piper/ 폴더 생성 후:
- Windows: piper.exe
- Mac/Linux: piper (실행권한 chmod +x piper)
- en_US-lessac-medium.onnx
- en_US-lessac-medium.onnx.json
- 기타 DLL (Windows)

다운로드: https://github.com/rhasspy/piper/releases
모델: https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/
```

### 4. ComfyUI + AnimateDiff 설치 (선택, AI 영상 클립용)
```bash
git clone https://github.com/comfyanonymous/ComfyUI
cd ComfyUI
pip install -r requirements.txt

# AnimateDiff + VideoHelper 플러그인
cd custom_nodes
git clone https://github.com/Kosinkadink/ComfyUI-AnimateDiff-Evolved
git clone https://github.com/Kosinkadink/ComfyUI-VideoHelperSuite

# 모델 파일 배치
# ComfyUI/models/checkpoints/ → v1-5-pruned-emaonly.ckpt
#   다운로드: https://huggingface.co/runwayml/stable-diffusion-v1-5
# ComfyUI/models/animatediff_models/ → mm_sd_v15_v2.ckpt
#   다운로드: https://huggingface.co/guoyww/animatediff

# 실행 (항상 백그라운드로)
python main.py --listen 0.0.0.0 --port 8188
```

> ⚠️ ComfyUI 없이도 동작합니다. Java2D 씬으로 자동 폴백됩니다.

---

## 🚀 실행

```bash
# 프로젝트 루트에서
./gradlew bootRun

# 또는 빌드 후 실행
./gradlew build
java -jar build/libs/shorts-maker-1.0.0.war
```

브라우저에서 `http://localhost:8080` 접속

---

## 📁 프로젝트 구조

```
shorts-maker/
├── src/main/java/com/aishots/
│   ├── config/
│   │   ├── AppConfig.java              # WebClient, ObjectMapper, ThreadPool
│   │   └── VideoRenderProfile.java     # GPU 자동 감지 (FAST/BALANCED/QUALITY)
│   ├── controller/
│   │   └── ShortsController.java       # REST API + 페이지
│   ├── dto/
│   │   ├── JobStatus.java              # 작업 상태
│   │   ├── ScriptData.java             # AI 생성 스크립트 + 영상 프롬프트
│   │   └── ShortsRequest.java          # 생성 요청 (topic, tone, bgmStyle 등)
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   └── ShortsException.java
│   └── service/
│       ├── AnimateDiffService.java     # ComfyUI API → AI 영상 클립 생성
│       ├── BgmGeneratorService.java    # Java Sound API → BGM 자체 생성
│       ├── BgmStyle.java               # BGM 스타일 enum
│       ├── EmotionTone.java            # 감정 기반 색상 시스템
│       ├── SceneRenderer.java          # Java2D 씬 렌더러 (폴백용)
│       ├── SceneType.java              # 씬 타입 (SPACE/CITY/NATURE 등)
│       ├── ScriptService.java          # Ollama 스크립트 생성
│       ├── ShortsGenerationService.java # 전체 파이프라인 조율
│       ├── TtsService.java             # Piper TTS
│       └── VideoService.java           # FFmpeg 영상 합성
├── src/main/resources/
│   └── application.properties
├── src/main/webapp/WEB-INF/views/
│   └── index.jsp                       # UI
├── piper/                              # Piper TTS 바이너리 + 모델
├── outputs/                            # 생성된 파일들 (자동 생성)
│   ├── audio/
│   ├── videos/
│   ├── clips/
│   └── bgm_generated/
└── build.gradle
```

---

## 🎵 BGM 스타일

UI에서 선택 가능. 외부 파일 없이 Java로 직접 생성.

| 스타일 | 특징 |
|---|---|
| Upbeat & Energetic | 140bpm, 밝은 C장조, 킥 드럼 |
| Calm & Relaxing | 70bpm, 사인파, 아르페지오 |
| Mysterious & Dark | 90bpm, 프리지안 스케일, 저음 드론 |
| Inspiring & Epic | 110bpm, 5도 화음, 크레셴도 |
| Cute & Playful | 130bpm, 고음 스타카토, 글로켄슈필 |
| No BGM | BGM 없음 |

---

## 🎨 씬 자동 매핑

주제 키워드에 따라 배경 씬 자동 선택 (ComfyUI 미연결 시)

| 키워드 | 씬 |
|---|---|
| space, galaxy, universe | 🌌 SPACE |
| city, urban, building | 🌃 CITY |
| nature, forest, mountain | 🌿 NATURE |
| ocean, sea, water | 🌊 OCEAN |
| fire, energy, explosion | 🔥 FIRE |
| tech, ai, computer, code | 💻 TECH |
| dark, mystery, horror | 🌑 DARK |
| 그 외 | ✨ ABSTRACT (감정 색상) |

---

## 🔌 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | /api/shorts/generate | 영상 생성 시작 |
| GET | /api/shorts/status/{jobId} | 작업 상태 조회 |
| GET | /api/shorts/download/video/{jobId} | 영상 다운로드 |
| GET | /api/comfyui/status | ComfyUI 연결 상태 |
| GET | /api/shorts/script/preview?topic= | 스크립트 미리보기 |

### 요청 예시
```json
POST /api/shorts/generate
{
  "topic": "black holes",
  "durationSeconds": 60,
  "tone": "shocking and mind-blowing",
  "voice": "en_US",
  "bgmStyle": "MYSTERIOUS",
  "backgroundColor": [5, 5, 20]
}
```

---

## ⚠️ 자주 발생하는 문제

### Piper TTS exitCode=-1073740791
→ piper 폴더를 프로젝트 루트에 배치했는지 확인  
→ DLL 파일들이 piper/ 폴더 안에 모두 있는지 확인

### Ollama 응답 없음
→ `ollama serve` 실행 여부 확인  
→ `http://localhost:11434` 접속 확인

### ComfyUI 미연결 경고
→ 정상 동작 — Java2D 씬으로 자동 폴백됨  
→ AI 클립 원하면 ComfyUI 설치 후 실행

### 스크립트 JSON 파싱 실패
→ Ollama 모델이 JSON을 제대로 출력 안 할 때  
→ 다시 시도하거나 `ollama pull gemma3` 재설치
