# 🎬 AI 쇼츠 제작기

주제만 입력하면 **스크립트 → 음성(TTS) → 자막 → 썸네일 → 영상** 까지 자동으로 생성해주는 AI 도구입니다.

> Powered by Ollama (gemma3) + FastAPI + edge-tts + moviepy

---

## 📋 목차

1. [사전 요구사항](#1-사전-요구사항)
2. [설치 방법](#2-설치-방법)
3. [실행 방법](#3-실행-방법)
4. [사용 방법](#4-사용-방법)
5. [프로젝트 구조](#5-프로젝트-구조)
6. [클래스 수정 가이드](#6-클래스-수정-가이드)
7. [자주 겪는 오류 해결](#7-자주-겪는-오류-해결)
8. [API 엔드포인트](#8-api-엔드포인트)

---

## 1. 사전 요구사항

| 항목 | 권장 버전 | 비고 |
|------|----------|------|
| Python | **3.11.x** | 3.12 이상 비권장 — moviepy 호환 문제 |
| ffmpeg | 최신 | 영상 처리 필수 |
| Ollama | 최신 | AI 로컬 모델 실행 (무료) |

> ⚠️ **Python 버전 주의**: Python 3.12 이상(특히 3.14)은 `moviepy` 등 일부 패키지가 호환되지 않습니다. 반드시 **3.11**을 사용하세요.

---

## 2. 설치 방법

### Step 1 — Python 3.11 설치

**macOS**
```bash
brew install python@3.11
```

**Windows**
- https://www.python.org/downloads/release/python-3119/ 에서 다운로드

**Ubuntu / Debian**
```bash
sudo apt install python3.11 python3.11-pip
```

---

### Step 2 — ffmpeg 설치

**macOS**
```bash
brew install ffmpeg
```

**Windows**
- https://ffmpeg.org/download.html 에서 다운로드 후 환경변수 PATH에 추가

**Ubuntu / Debian**
```bash
sudo apt install ffmpeg
```

---

### Step 3 — Ollama 설치 및 모델 다운로드

**macOS / Linux**
```bash
brew install ollama
# 또는 공식 사이트: https://ollama.com/download
```

**Windows**
- https://ollama.com/download 에서 설치 파일 다운로드

**모델 다운로드 (약 5GB, 최초 1회만)**
```bash
# 터미널 1: Ollama 서버 시작
ollama serve

# 터미널 2: 모델 다운로드
ollama pull gemma3
```

---

### Step 4 — Python 패키지 설치

```bash
cd ai-shorts-maker/backend

python3.11 -m pip install fastapi uvicorn python-multipart moviepy==1.0.3 ffmpeg-python Pillow mutagen python-dotenv edge-tts requests
```

> ⚠️ **moviepy 버전 주의**: `moviepy==1.0.3` 으로 고정 설치하세요. 최신 버전(2.x)은 경로가 변경되어 호환되지 않습니다.

> ⚠️ **pip 주의**: `pip3` 대신 `python3.11 -m pip` 을 사용하세요. `pip3`가 다른 Python 환경을 바라볼 수 있습니다.

---

## 3. 실행 방법

**터미널 3개를 동시에 열어야 합니다.**

**터미널 1 — Ollama 서버 (항상 켜둬야 함)**
```bash
ollama serve
```

**터미널 2 — FastAPI 백엔드 서버**
```bash
cd ai-shorts-maker/backend
python3.11 main.py
```

**터미널 3 — 브라우저 접속**
```
http://localhost:8000
```

> ⚠️ `ollama serve` 창을 닫으면 AI 스크립트 생성이 작동하지 않습니다.

---

## 4. 사용 방법

1. 브라우저에서 `http://localhost:8000` 접속
2. **영상 주제** 입력 (예: "고양이가 물을 싫어하는 이유")
3. 영상 길이, 말투, 음성, 배경색 선택
4. **AI 쇼츠 영상 생성하기** 클릭
5. 생성 완료 후 영상 및 썸네일 다운로드

> 💡 **스크립트 미리보기** 버튼으로 영상 생성 전 내용을 먼저 확인할 수 있습니다.

---

## 5. 프로젝트 구조

```
ai-shorts-maker/
├── backend/
│   ├── main.py                      # FastAPI 서버 진입점
│   ├── requirements.txt             # 패키지 목록
│   ├── .env.example                 # 환경변수 예시
│   ├── routers/
│   │   └── shorts_router.py         # API 엔드포인트 (Spring @RestController 역할)
│   └── services/
│       ├── script_service.py        # AI 스크립트 생성 (Ollama gemma3)
│       ├── tts_service.py           # 음성 나레이션 생성 (edge-tts, 무료)
│       ├── video_service.py         # 영상 합성 + 자막 삽입 (moviepy)
│       └── thumbnail_service.py    # 썸네일 자동 생성 (Pillow)
├── frontend/
│   └── index.html                   # 웹 UI
└── outputs/                         # 생성된 파일 저장
    ├── audio/
    ├── videos/
    └── thumbnails/
```

---

## 6. 클래스 수정 가이드

### AI 모델 변경 (`script_service.py`)

```python
def __init__(self):
    self.model = "gemma3"  # 원하는 Ollama 모델로 변경
    # 다른 모델: "llama3.2", "mistral", "qwen2.5"
```

### TTS 음성 추가 (`tts_service.py`)

```python
VOICES = {
    "여성_기본": "ko-KR-SunHiNeural",
    "남성_기본": "ko-KR-InJoonNeural",
    # 여기에 새 음성 추가
}
```

### 자막 스타일 변경 (`video_service.py`)

```python
TextClip(
    chunk,
    fontsize=72,       # 글자 크기
    color="white",     # 글자 색상
    stroke_width=3,    # 외곽선 두께
)
.set_position(("center", self.HEIGHT * 0.72))  # 자막 위치 (0.0 ~ 1.0)
```

### 썸네일 테마 추가 (`thumbnail_service.py`)

```python
THEMES = [
    {"bg": [(15,15,35), (50,10,80)], "accent": (180,100,255), "text": "white"},
    # 새 테마: bg는 그라디언트 시작/끝 RGB, accent는 강조색
]
```

---

## 7. 자주 겪는 오류 해결

### `ModuleNotFoundError: No module named 'fastapi'`
`python3`와 `pip3`가 다른 환경을 바라보는 문제입니다.
```bash
python3.11 -m pip install fastapi uvicorn ...
```

### `ModuleNotFoundError: No module named 'moviepy.editor'`
`moviepy` 2.x 버전이 설치된 경우입니다.
```bash
python3.11 -m pip uninstall moviepy -y
python3.11 -m pip install moviepy==1.0.3
```

### `404 Not Found: http://localhost:11434/api/generate`
Ollama 서버가 실행되지 않은 상태입니다.
```bash
# 별도 터미널에서 실행 후 켜둬야 함
ollama serve
```

### `Address already in use`
이전에 실행한 서버가 아직 살아있는 경우입니다.
```bash
lsof -ti:8000 | xargs kill -9
python3.11 main.py
```

### 스크립트 생성 후 JSON 파싱 오류
Ollama 모델이 JSON 형식을 제대로 지키지 않은 경우입니다.
```python
# script_service.py 에서 모델 변경
self.model = "llama3.2"  # gemma3 대신 시도
```

---

## 8. API 엔드포인트

| 메서드 | URL | 설명 |
|--------|-----|------|
| POST | `/api/shorts/generate` | 영상 생성 시작 |
| GET | `/api/shorts/status/{job_id}` | 진행 상황 조회 |
| GET | `/api/shorts/script/preview` | 스크립트 미리보기 |
| GET | `/api/shorts/download/video/{job_id}` | 영상 다운로드 |
| GET | `/api/shorts/download/thumbnail/{job_id}` | 썸네일 다운로드 |

---

## 9. 업그레이드 방향

- [ ] ElevenLabs TTS 연동 (더 자연스러운 음성)
- [ ] Pexels/Pixabay API로 배경 영상 자동 검색
- [ ] DALL-E API로 AI 썸네일 이미지 생성
- [ ] 유튜브 자동 업로드 연동
- [ ] Redis + Celery로 작업 큐 처리 (다중 사용자)
