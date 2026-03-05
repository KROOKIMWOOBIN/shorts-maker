"""
영상 생성 서비스
- moviepy로 영상 합성
- 자막 자동 삽입
- 배경영상 + 나레이션 + 자막 = 최종 쇼츠 영상
"""
import os
import textwrap
from moviepy.editor import (
    VideoFileClip, AudioFileClip, TextClip,
    CompositeVideoClip, ColorClip, concatenate_videoclips
)
from moviepy.video.fx import resize


class VideoService:
    """
    쇼츠 영상 합성 서비스
    
    입력: 배경영상 + 오디오 + 스크립트
    출력: 자막이 삽입된 최종 쇼츠 영상 (9:16 비율, 1080x1920)
    """

    # 쇼츠 표준 해상도
    WIDTH = 1080
    HEIGHT = 1920

    def __init__(self):
        self.output_dir = "outputs/videos"
        os.makedirs(self.output_dir, exist_ok=True)

    def create_shorts_video(
        self,
        audio_path: str,
        script: str,
        background_video_path: str = None,
        background_color: tuple = (15, 15, 25),  # 기본 다크 배경
        filename: str = "shorts_output"
    ) -> str:
        """
        쇼츠 영상을 생성합니다.
        
        Args:
            audio_path: 나레이션 오디오 경로
            script: 자막으로 사용할 스크립트 텍스트
            background_video_path: 배경 영상 경로 (없으면 단색 배경 사용)
            background_color: 단색 배경 RGB 색상
            filename: 출력 파일명
            
        Returns:
            생성된 영상 파일 경로
        """
        output_path = os.path.join(self.output_dir, f"{filename}.mp4")

        # 1. 오디오 로드
        audio = AudioFileClip(audio_path)
        duration = audio.duration

        # 2. 배경 클립 생성
        if background_video_path and os.path.exists(background_video_path):
            background = self._prepare_background_video(background_video_path, duration)
        else:
            background = ColorClip(
                size=(self.WIDTH, self.HEIGHT),
                color=background_color,
                duration=duration
            )

        # 3. 자막 생성 및 오버레이
        subtitle_clips = self._create_subtitle_clips(script, duration)

        # 4. 최종 합성
        final_clips = [background] + subtitle_clips
        final_video = CompositeVideoClip(final_clips, size=(self.WIDTH, self.HEIGHT))
        final_video = final_video.set_audio(audio)

        # 5. 렌더링
        final_video.write_videofile(
            output_path,
            fps=30,
            codec="libx264",
            audio_codec="aac",
            threads=4,
            preset="fast",
            logger=None
        )

        print(f"✅ 영상 생성 완료: {output_path}")
        return output_path

    def _prepare_background_video(self, video_path: str, target_duration: float):
        """배경 영상을 쇼츠 비율(9:16)로 크롭하고 길이 조정"""
        clip = VideoFileClip(video_path)
        
        # 9:16 비율로 크롭
        target_ratio = self.WIDTH / self.HEIGHT
        current_ratio = clip.w / clip.h

        if current_ratio > target_ratio:
            # 좌우 크롭
            new_width = int(clip.h * target_ratio)
            x_center = clip.w // 2
            clip = clip.crop(x1=x_center - new_width // 2, x2=x_center + new_width // 2)
        else:
            # 상하 크롭
            new_height = int(clip.w / target_ratio)
            y_center = clip.h // 2
            clip = clip.crop(y1=y_center - new_height // 2, y2=y_center + new_height // 2)

        clip = clip.resize((self.WIDTH, self.HEIGHT))

        # 오디오 길이에 맞게 반복 또는 자르기
        if clip.duration < target_duration:
            repeats = int(target_duration / clip.duration) + 1
            clip = concatenate_videoclips([clip] * repeats)

        return clip.subclip(0, target_duration).without_audio()

    def _create_subtitle_clips(self, script: str, total_duration: float) -> list:
        """
        스크립트를 자막 클립 리스트로 변환
        - 3~5단어 단위로 분할해 순차적으로 표시
        """
        words = script.split()
        
        # 3~4단어씩 묶어서 자막 청크 생성
        chunk_size = 4
        chunks = [" ".join(words[i:i+chunk_size]) for i in range(0, len(words), chunk_size)]
        
        if not chunks:
            return []

        time_per_chunk = total_duration / len(chunks)
        subtitle_clips = []

        for i, chunk in enumerate(chunks):
            start_time = i * time_per_chunk
            
            try:
                # 자막 텍스트 클립
                txt_clip = (
                    TextClip(
                        chunk,
                        fontsize=72,
                        color="white",
                        font="NanumGothic-Bold",  # 한국어 폰트 (없으면 아래 폰트 시도)
                        stroke_color="black",
                        stroke_width=3,
                        method="caption",
                        size=(self.WIDTH - 100, None),
                        align="center"
                    )
                    .set_position(("center", self.HEIGHT * 0.72))
                    .set_start(start_time)
                    .set_duration(time_per_chunk)
                    .crossfadein(0.1)
                )
                subtitle_clips.append(txt_clip)
            except Exception:
                # 폰트 없을 시 기본 폰트 사용
                txt_clip = (
                    TextClip(
                        chunk,
                        fontsize=72,
                        color="white",
                        stroke_color="black",
                        stroke_width=3,
                        method="caption",
                        size=(self.WIDTH - 100, None),
                        align="center"
                    )
                    .set_position(("center", self.HEIGHT * 0.72))
                    .set_start(start_time)
                    .set_duration(time_per_chunk)
                )
                subtitle_clips.append(txt_clip)

        return subtitle_clips
