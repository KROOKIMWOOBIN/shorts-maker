"""
쇼츠 생성 API 라우터
- Spring의 @RestController와 동일한 역할
"""
from fastapi import APIRouter, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse
from pydantic import BaseModel
from typing import Optional
import uuid
import os
import asyncio

from services.script_service import ScriptService
from services.tts_service import TTSService
from services.video_service import VideoService
from services.thumbnail_service import ThumbnailService

router = APIRouter()

# 서비스 인스턴스 (Spring의 @Service @Autowired와 유사)
script_service = ScriptService()
video_service = VideoService()
thumbnail_service = ThumbnailService()

# 진행 상태 저장소 (실제 서비스는 Redis 사용 권장)
job_status = {}


# DTO (Spring의 Request DTO와 동일)
class ShortsRequest(BaseModel):
    topic: str                          # 영상 주제
    duration_seconds: int = 60          # 영상 길이 (초)
    tone: str = "친근하고 흥미롭게"       # 말투/톤
    voice: str = "여성_기본"             # TTS 음성
    background_color: list = [15, 15, 25]  # RGB 배경색


class ShortsResponse(BaseModel):
    job_id: str
    message: str


@router.post("/generate", response_model=ShortsResponse)
async def generate_shorts(request: ShortsRequest, background_tasks: BackgroundTasks):
    """
    쇼츠 영상 생성 시작
    - 비동기로 처리 후 job_id 반환
    - 클라이언트는 /status/{job_id}로 진행 상황 폴링
    """
    job_id = str(uuid.uuid4())[:8]
    job_status[job_id] = {"status": "pending", "progress": 0, "message": "대기 중..."}

    # 백그라운드에서 영상 생성 실행
    background_tasks.add_task(_generate_shorts_task, job_id, request)

    return ShortsResponse(job_id=job_id, message="영상 생성이 시작되었습니다.")


@router.get("/status/{job_id}")
async def get_status(job_id: str):
    """작업 진행 상황 조회"""
    if job_id not in job_status:
        raise HTTPException(status_code=404, detail="작업을 찾을 수 없습니다.")
    return job_status[job_id]


@router.get("/download/video/{job_id}")
async def download_video(job_id: str):
    """생성된 영상 다운로드"""
    if job_id not in job_status:
        raise HTTPException(status_code=404, detail="작업을 찾을 수 없습니다.")

    status = job_status[job_id]
    if status.get("status") != "completed":
        raise HTTPException(status_code=400, detail="영상이 아직 생성되지 않았습니다.")

    video_path = status.get("video_path")
    if not video_path or not os.path.exists(video_path):
        raise HTTPException(status_code=404, detail="영상 파일을 찾을 수 없습니다.")

    return FileResponse(video_path, media_type="video/mp4", filename=f"shorts_{job_id}.mp4")


@router.get("/download/thumbnail/{job_id}")
async def download_thumbnail(job_id: str):
    """생성된 썸네일 다운로드"""
    if job_id not in job_status:
        raise HTTPException(status_code=404, detail="작업을 찾을 수 없습니다.")

    thumbnail_path = job_status[job_id].get("thumbnail_path")
    if not thumbnail_path or not os.path.exists(thumbnail_path):
        raise HTTPException(status_code=404, detail="썸네일 파일을 찾을 수 없습니다.")

    return FileResponse(thumbnail_path, media_type="image/jpeg", filename=f"thumbnail_{job_id}.jpg")


@router.get("/script/preview")
async def preview_script(topic: str, duration_seconds: int = 60):
    """영상 생성 없이 스크립트만 미리 보기"""
    try:
        script_data = script_service.generate_script(topic, duration_seconds)
        return {"success": True, "data": script_data}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# --------------------------------------------------
# 내부 비동기 작업 함수
# --------------------------------------------------
async def _generate_shorts_task(job_id: str, request: ShortsRequest):
    """실제 영상 생성 파이프라인 (백그라운드 실행)"""
    try:
        # Step 1: 스크립트 생성
        _update_status(job_id, "processing", 10, "🤖 AI 스크립트 생성 중...")
        script_data = script_service.generate_script(
            topic=request.topic,
            duration_seconds=request.duration_seconds,
            tone=request.tone
        )

        # Step 2: TTS 음성 생성
        _update_status(job_id, "processing", 35, "🎙️ 음성 나레이션 생성 중...")
        tts_service = TTSService(voice_key=request.voice)
        audio_path = await tts_service.generate_audio(
            text=script_data["script"],
            filename=job_id
        )

        # Step 3: 썸네일 생성
        _update_status(job_id, "processing", 55, "🎨 썸네일 생성 중...")
        thumbnail_path = thumbnail_service.create_thumbnail(
            title=script_data["title"],
            hook_text=script_data["hook"],
            filename=job_id
        )

        # Step 4: 영상 합성
        _update_status(job_id, "processing", 70, "🎬 영상 합성 중... (가장 오래 걸립니다)")
        video_path = video_service.create_shorts_video(
            audio_path=audio_path,
            script=script_data["script"],
            background_color=tuple(request.background_color),
            filename=job_id
        )

        # 완료
        job_status[job_id] = {
            "status": "completed",
            "progress": 100,
            "message": "✅ 영상 생성 완료!",
            "script": script_data,
            "video_path": video_path,
            "thumbnail_path": thumbnail_path,
            "video_url": f"/outputs/videos/{job_id}.mp4",
            "thumbnail_url": f"/outputs/thumbnails/{job_id}.jpg",
        }

    except Exception as e:
        job_status[job_id] = {
            "status": "error",
            "progress": 0,
            "message": f"❌ 오류 발생: {str(e)}"
        }
        print(f"Error in job {job_id}: {e}")


def _update_status(job_id: str, status: str, progress: int, message: str):
    job_status[job_id] = {**job_status.get(job_id, {}), "status": status, "progress": progress, "message": message}
