"""
AI 스크립트 생성 서비스
- Ollama 로컬 모델 사용 (완전 무료, 인터넷 불필요)
- 기본 모델: gemma3
"""
import requests
import json

class ScriptService:
    def __init__(self):
        self.api_url = "http://localhost:11434/api/generate"
        self.model = "gemma3"

    def generate_script(self, topic: str, duration_seconds: int = 60, tone: str = "친근하고 흥미롭게") -> dict:
        word_count = duration_seconds * 3

        prompt = f"""
당신은 유튜브 쇼츠 전문 스크립트 작가입니다.
다음 주제로 {duration_seconds}초 분량의 쇼츠 스크립트를 작성해주세요.

주제: {topic}
톤: {tone}
목표 단어 수: 약 {word_count}자 (한국어 기준)

반드시 아래 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요:

{{
    "title": "클릭을 유도하는 흥미로운 영상 제목 (30자 이내)",
    "hook": "처음 3초 안에 시청자를 사로잡는 강렬한 첫 문장 (1-2문장)",
    "script": "전체 나레이션 스크립트. 자연스럽게 말하는 것처럼 작성.",
    "hashtags": ["#관련태그1", "#관련태그2", "#관련태그3", "#관련태그4", "#관련태그5"],
    "thumbnail_prompt": "썸네일 이미지 생성을 위한 영어 프롬프트"
}}
"""

        response = requests.post(
            self.api_url,
            json={
                "model": self.model,
                "prompt": prompt,
                "stream": False
            },
            timeout=120
        )

        response.raise_for_status()
        raw_text = response.json()["response"].strip()

        # JSON 파싱 (```json 블록 처리)
        if "```" in raw_text:
            raw_text = raw_text.split("```")[1]
            if raw_text.startswith("json"):
                raw_text = raw_text[4:]

        return json.loads(raw_text.strip())

    def generate_thumbnail_text(self, title: str) -> str:
        response = requests.post(
            self.api_url,
            json={
                "model": self.model,
                "prompt": f"다음 영상 제목을 썸네일에 들어갈 짧고 강렬한 문구로 바꿔주세요 (10자 이내, 텍스트만 출력): {title}",
                "stream": False
            },
            timeout=60
        )

        response.raise_for_status()
        return response.json()["response"].strip()
