# 🎬 AI 쇼츠 제작기

주제만 입력하면 **스크립트 → 음성(TTS) → 자막 → 썸네일 → 영상** 까지 자동으로 생성해주는 AI 도구입니다.

## 📁 프로젝트 구조

```
ai-shorts-maker/
├── backend/
│   ├── main.py                        # FastAPI 서버 진입점
│   ├── requirements.txt               # 패키지 목록
│   ├── .env.example                   # 환경변수 예시
│   ├── routers/
│   │   └── shorts_router.py           # API 엔드포인트 (Spring @RestController 역할)
│   └── services/
│       ├── script_service.py          # AI 스크립트 생성 (Claude API)
│       ├── tts_service.py             # 음성 나레이션 생성 (edge-tts)
│       ├── video_service.py           # 영상 합성 (moviepy + ffmpeg)
│       └── thumbnail_service.py       # 썸네일 생성 (Pillow)
├── frontend/
│   └── index.html                     # 웹 UI
└── outputs/                           # 생성된 파일 저장 위치
    ├── audio/
    ├── videos/
    └── thumbnails/
```

## ⚙️ 설치 방법

### 1. 사전 요구사항
- Python 3.11 이상
- ffmpeg 설치 필요

```bash
# macOS
brew install ffmpeg

# Ubuntu/Debian
sudo apt install ffmpeg

# Windows: https://ffmpeg.org/download.html 에서 다운로드
```

### 2. 패키지 설치

```bash
cd backend
pip install -r requirements.txt
```

### 3. 환경변수 설정

```bash
cp .env.example .env
# .env 파일 열어서 ANTHROPIC_API_KEY 값 입력
```

### 4. 서버 실행

```bash
cd backend
python main.py
```

브라우저에서 `http://localhost:8000` 접속

---

## 🔧 주요 클래스 설명 (수정 가이드)

### `ScriptService` - 스크립트 생성
```python
# 수정 포인트: 프롬프트 튜닝
def generate_script(self, topic, duration_seconds, tone):
    prompt = f"..."  # 여기서 AI 지시사항 변경 가능
```

### `TTSService` - 음성 생성
```python
# 수정 포인트: 음성 종류 추가
VOICES = {
    "여성_기본": "ko-KR-SunHiNeural",
    # 여기에 새 음성 추가 가능
}
```

### `VideoService` - 영상 합성
```python
# 수정 포인트: 자막 스타일 변경
def _create_subtitle_clips(self, script, duration):
    # fontsize, color, 위치(y_ratio) 등 수정
```

### `ThumbnailService` - 썸네일 생성
```python
# 수정 포인트: 색상 테마 추가
THEMES = [
    {"bg": [...], "accent": (...), "text": "white"},
    # 새 테마 추가 가능
]
```

---

## 🔌 API 엔드포인트

| 메서드 | URL | 설명 |
|--------|-----|------|
| POST | `/api/shorts/generate` | 영상 생성 시작 |
| GET | `/api/shorts/status/{job_id}` | 진행 상황 조회 |
| GET | `/api/shorts/script/preview` | 스크립트 미리보기 |
| GET | `/api/shorts/download/video/{job_id}` | 영상 다운로드 |
| GET | `/api/shorts/download/thumbnail/{job_id}` | 썸네일 다운로드 |

---

## 🚀 업그레이드 방향

- [ ] ElevenLabs TTS 연동 (더 자연스러운 음성)
- [ ] Pexels/Pixabay API로 배경 영상 자동 검색
- [ ] DALL-E API로 AI 썸네일 이미지 생성
- [ ] 유튜브 자동 업로드 연동
- [ ] Redis + Celery로 작업 큐 처리 (다중 사용자)
