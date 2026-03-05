"""
썸네일 자동 생성 서비스
- Pillow로 텍스트 + 배경 이미지 합성
- YouTube 쇼츠 최적 비율 (9:16, 1080x1920)
"""
import os
import random
from PIL import Image, ImageDraw, ImageFont, ImageFilter


class ThumbnailService():
    """
    썸네일 생성 서비스
    
    텍스트 기반 썸네일을 자동으로 생성합니다.
    추후 DALL-E / Stable Diffusion API 연동으로 AI 이미지 생성 가능.
    """

    WIDTH = 1080
    HEIGHT = 1920

    # 썸네일 컬러 테마 모음
    THEMES = [
        {"bg": [(15, 15, 35), (50, 10, 80)],   "accent": (180, 100, 255), "text": "white"},
        {"bg": [(10, 30, 60), (5, 80, 120)],    "accent": (0, 200, 255),   "text": "white"},
        {"bg": [(40, 10, 10), (120, 20, 20)],   "accent": (255, 80, 80),   "text": "white"},
        {"bg": [(10, 40, 20), (20, 100, 50)],   "accent": (80, 255, 120),  "text": "white"},
        {"bg": [(50, 30, 5), (120, 70, 10)],    "accent": (255, 180, 0),   "text": "white"},
    ]

    def __init__(self):
        self.output_dir = "outputs/thumbnails"
        os.makedirs(self.output_dir, exist_ok=True)

    def create_thumbnail(self, title: str, hook_text: str, filename: str, theme_index: int = None) -> str:
        """
        썸네일을 생성합니다.
        
        Args:
            title: 영상 제목
            hook_text: 강렬한 후킹 문구 (크게 표시)
            filename: 저장 파일명
            theme_index: 색상 테마 인덱스 (None이면 랜덤)
            
        Returns:
            생성된 썸네일 경로
        """
        output_path = os.path.join(self.output_dir, f"{filename}.jpg")
        theme = self.THEMES[theme_index if theme_index is not None else random.randint(0, len(self.THEMES) - 1)]

        # 1. 그라디언트 배경 생성
        image = self._create_gradient_background(theme["bg"])
        draw = ImageDraw.Draw(image)

        # 2. 장식 요소 추가 (원형 글로우 효과)
        self._add_decorative_elements(image, draw, theme["accent"])

        # 3. 후킹 텍스트 (중앙 상단, 크게)
        self._draw_text_with_shadow(
            draw=draw,
            text=hook_text,
            y_ratio=0.35,
            font_size=110,
            color=theme["accent"],
            max_width=self.WIDTH - 100
        )

        # 4. 제목 텍스트 (중앙)
        self._draw_text_with_shadow(
            draw=draw,
            text=title,
            y_ratio=0.55,
            font_size=72,
            color=theme["text"],
            max_width=self.WIDTH - 120
        )

        # 5. 하단 브랜드 바
        self._add_bottom_bar(draw, theme["accent"])

        # 6. 저장
        image = image.filter(ImageFilter.SHARPEN)
        image.save(output_path, "JPEG", quality=95)
        print(f"✅ 썸네일 생성 완료: {output_path}")
        return output_path

    def _create_gradient_background(self, colors: list) -> Image:
        """수직 그라디언트 배경 생성"""
        image = Image.new("RGB", (self.WIDTH, self.HEIGHT))
        draw = ImageDraw.Draw(image)

        r1, g1, b1 = colors[0]
        r2, g2, b2 = colors[1]

        for y in range(self.HEIGHT):
            ratio = y / self.HEIGHT
            r = int(r1 + (r2 - r1) * ratio)
            g = int(g1 + (g2 - g1) * ratio)
            b = int(b1 + (b2 - b1) * ratio)
            draw.line([(0, y), (self.WIDTH, y)], fill=(r, g, b))

        return image

    def _add_decorative_elements(self, image: Image, draw: ImageDraw, accent_color: tuple):
        """글로우 원 등 장식 요소 추가"""
        # 반투명 큰 원 (우측 상단)
        overlay = Image.new("RGBA", (self.WIDTH, self.HEIGHT), (0, 0, 0, 0))
        overlay_draw = ImageDraw.Draw(overlay)
        r, g, b = accent_color
        overlay_draw.ellipse(
            [600, -200, 1300, 600],
            fill=(r, g, b, 25)
        )
        overlay_draw.ellipse(
            [-200, 1200, 500, 1900],
            fill=(r, g, b, 20)
        )
        image.paste(Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB"))

    def _draw_text_with_shadow(self, draw: ImageDraw, text: str, y_ratio: float,
                                font_size: int, color, max_width: int):
        """그림자 효과가 있는 텍스트 렌더링"""
        # 텍스트 줄 바꿈 처리
        wrapped = self._wrap_text(text, max_width, font_size)
        lines = wrapped.split("\n")
        line_height = font_size + 15

        total_height = len(lines) * line_height
        start_y = int(self.HEIGHT * y_ratio) - total_height // 2

        for i, line in enumerate(lines):
            y = start_y + i * line_height
            # 그림자
            draw.text((self.WIDTH // 2 + 4, y + 4), line, fill=(0, 0, 0, 180), anchor="mt")
            # 본문
            draw.text((self.WIDTH // 2, y), line, fill=color, anchor="mt")

    def _wrap_text(self, text: str, max_width: int, font_size: int) -> str:
        """텍스트 줄 바꿈 (글자 수 기반 추정)"""
        chars_per_line = max(1, max_width // (font_size // 2))
        words = text.split()
        lines = []
        current = ""

        for word in words:
            if len(current + word) <= chars_per_line:
                current += word + " "
            else:
                if current:
                    lines.append(current.strip())
                current = word + " "

        if current:
            lines.append(current.strip())

        return "\n".join(lines)

    def _add_bottom_bar(self, draw: ImageDraw, accent_color: tuple):
        """하단 장식 바"""
        r, g, b = accent_color
        bar_y = self.HEIGHT - 80
        draw.rectangle([0, bar_y, self.WIDTH, self.HEIGHT], fill=(r, g, b, 40))
        draw.line([(50, bar_y + 2), (self.WIDTH - 50, bar_y + 2)], fill=(r, g, b), width=3)
