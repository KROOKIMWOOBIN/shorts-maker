"""
TTS (Text-to-Speech) 서비스
- edge-tts 사용 (무료, 한국어 지원 우수)
- ElevenLabs로 교체 가능한 구조
"""
import asyncio
import os
import edge_tts


class TTSService:
    """
    음성 나레이션 생성 서비스
    
    기본: edge-tts (Microsoft, 무료)
    업그레이드: ElevenLabs API (유료, 더 자연스러움)
    """
    
    # 사용 가능한 한국어 음성 목록
    VOICES = {
        "여성_기본": "ko-KR-SunHiNeural",      # 밝고 친근한 여성 목소리
        "남성_기본": "ko-KR-InJoonNeural",      # 안정적인 남성 목소리
        "여성_활기": "ko-KR-YuJinNeural",       # 활기찬 여성 목소리
    }

    def __init__(self, voice_key: str = "여성_기본"):
        self.voice = self.VOICES.get(voice_key, self.VOICES["여성_기본"])
        self.output_dir = "outputs/audio"
        os.makedirs(self.output_dir, exist_ok=True)

    async def generate_audio(self, text: str, filename: str, rate: str = "+0%", volume: str = "+0%") -> str:
        """
        텍스트를 음성 파일(mp3)로 변환합니다.
        
        Args:
            text: 변환할 텍스트 (나레이션 스크립트)
            filename: 저장할 파일명 (확장자 제외)
            rate: 말하기 속도 (예: "+10%" 빠르게, "-10%" 느리게)
            volume: 볼륨 (예: "+10%")
            
        Returns:
            생성된 오디오 파일 경로
        """
        output_path = os.path.join(self.output_dir, f"{filename}.mp3")
        
        communicate = edge_tts.Communicate(
            text=text,
            voice=self.voice,
            rate=rate,
            volume=volume
        )
        
        await communicate.save(output_path)
        print(f"✅ 음성 생성 완료: {output_path}")
        return output_path

    def generate_audio_sync(self, text: str, filename: str, rate: str = "+0%") -> str:
        """동기 버전 (FastAPI 일반 엔드포인트에서 사용)"""
        return asyncio.run(self.generate_audio(text, filename, rate))

    def get_audio_duration(self, audio_path: str) -> float:
        """오디오 파일의 재생 시간(초) 반환"""
        try:
            from mutagen.mp3 import MP3
            audio = MP3(audio_path)
            return audio.info.length
        except Exception:
            # mutagen 없을 경우 추정값 반환
            return 60.0

    # -------------------------------------------------------
    # ElevenLabs 연동 (추후 교체용 - 주석 해제해서 사용)
    # -------------------------------------------------------
    # def generate_audio_elevenlabs(self, text: str, filename: str) -> str:
    #     from elevenlabs import generate, save
    #     audio = generate(
    #         text=text,
    #         voice="Rachel",  # 원하는 목소리로 변경
    #         model="eleven_multilingual_v2"
    #     )
    #     output_path = os.path.join(self.output_dir, f"{filename}.mp3")
    #     save(audio, output_path)
    #     return output_path
