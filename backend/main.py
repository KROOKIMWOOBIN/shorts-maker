"""
AI 쇼츠 제작기 - FastAPI 메인 서버
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from dotenv import load_dotenv
import uvicorn
import os

load_dotenv()

from routers import shorts_router

app = FastAPI(title="AI 쇼츠 제작기", version="1.0.0")

# CORS 설정 (프론트엔드 연동)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 라우터 등록
app.include_router(shorts_router.router, prefix="/api/shorts", tags=["shorts"])

# 생성된 파일 서빙
os.makedirs("outputs", exist_ok=True)
app.mount("/outputs", StaticFiles(directory="outputs"), name="outputs")

# 프론트엔드 서빙
app.mount("/static", StaticFiles(directory="../frontend"), name="static")

@app.get("/")
async def root():
    return FileResponse("../frontend/index.html")

@app.get("/health")
async def health():
    return {"status": "ok", "message": "AI 쇼츠 제작기 서버 정상 동작 중"}

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
