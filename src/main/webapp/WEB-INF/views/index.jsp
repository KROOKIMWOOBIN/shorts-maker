<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>AI 쇼츠 제작기</title>
  <link href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@300;400;500;700;900&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet"/>
  <link rel="stylesheet" href="/css/shorts.css"/>
</head>
<body>
<div class="container">

  <header>
    <div class="logo-tag">✦ Powered by Ollama + Stable Diffusion</div>
    <h1><span class="gradient">AI 쇼츠 제작기</span></h1>
    <p class="subtitle">주제만 입력하면 스크립트 → 음성 → AI 영상 자동 생성</p>
  </header>

  <div class="card">
    <div class="card-title"><span class="dot"></span>영상 설정</div>

    <div class="form-group">
      <label>영상 주제 *</label>
      <input type="text" id="topic" placeholder="예: 블랙홀의 비밀, 고양이가 그루밍하는 이유, 세계에서 가장 깊은 바다"/>
    </div>

    <div class="form-group">
      <label>영상 길이 <span class="label-hint">(최대 60초)</span></label>
      <div class="slider-row">
        <span class="slider-tick">30초</span>
        <input type="range" id="duration" min="30" max="60" value="60" step="10"
               oninput="document.getElementById('dur-val').textContent = this.value + '초'"/>
        <span class="slider-tick">60초</span>
        <span class="slider-value" id="dur-val">60초</span>
      </div>
      <div class="slider-steps">
        <span>30</span><span>40</span><span>50</span><span>60</span>
      </div>
    </div>

    <div class="form-group">
      <label>말투 / 톤</label>
      <select id="tone">
        <option value="shocking and mind-blowing">😱 충격적이고 놀라운 (기본)</option>
        <option value="friendly and engaging">🤝 친근하고 재미있는</option>
        <option value="inspiring and motivational">⭐ 영감을 주는</option>
        <option value="mysterious and scary">🌑 미스터리하고 무서운</option>
        <option value="professional and trustworthy">👔 전문적이고 신뢰감 있는</option>
      </select>
    </div>

    <div class="form-group">
      <label>나레이션 음성</label>
      <select id="voice">
        <option value="en_US">🇺🇸 English (US)</option>
      </select>
    </div>

    <div class="form-group">
      <label>🎵 BGM 스타일</label>
      <div class="bgm-grid">
        <div class="bgm-option active" data-bgm="UPBEAT"     onclick="selectBgm(this)"><div class="bgm-icon">⚡</div><div class="bgm-label">Upbeat</div></div>
        <div class="bgm-option"        data-bgm="CALM"       onclick="selectBgm(this)"><div class="bgm-icon">🌊</div><div class="bgm-label">Calm</div></div>
        <div class="bgm-option"        data-bgm="MYSTERIOUS" onclick="selectBgm(this)"><div class="bgm-icon">🌑</div><div class="bgm-label">Mysterious</div></div>
        <div class="bgm-option"        data-bgm="INSPIRING"  onclick="selectBgm(this)"><div class="bgm-icon">🚀</div><div class="bgm-label">Inspiring</div></div>
        <div class="bgm-option"        data-bgm="CUTE"       onclick="selectBgm(this)"><div class="bgm-icon">🌸</div><div class="bgm-label">Cute</div></div>
        <div class="bgm-option"        data-bgm="NONE"       onclick="selectBgm(this)"><div class="bgm-icon">🔇</div><div class="bgm-label">No BGM</div></div>
      </div>
    </div>

    <div class="form-group">
      <label>배경 색상 테마</label>
      <div class="color-presets">
        <div class="color-preset active" style="background:linear-gradient(135deg,#0f0f23,#321050)" data-color="[15,15,35]"  onclick="selectColor(this)" title="다크 퍼플"></div>
        <div class="color-preset"        style="background:linear-gradient(135deg,#0a1e3c,#053050)" data-color="[10,30,60]"  onclick="selectColor(this)" title="딥 블루"></div>
        <div class="color-preset"        style="background:linear-gradient(135deg,#280a0a,#780a0a)" data-color="[40,10,10]"  onclick="selectColor(this)" title="다크 레드"></div>
        <div class="color-preset"        style="background:linear-gradient(135deg,#0a2814,#145030)" data-color="[10,40,20]"  onclick="selectColor(this)" title="다크 그린"></div>
        <div class="color-preset"        style="background:linear-gradient(135deg,#1a1a1a,#2a2a2a)" data-color="[25,25,25]"  onclick="selectColor(this)" title="블랙"></div>
      </div>
    </div>
  </div>

  <button class="btn-generate" id="btn-gen" onclick="generateShorts()">✦ AI 쇼츠 영상 생성하기</button>
  <button class="btn-preview"  onclick="previewScript()">📄 스크립트 미리보기 (빠름)</button>
  <div class="error-box" id="error-box"></div>

  <!-- ── 스크립트 미리보기 ───────────────────────────────── -->
  <div id="preview-section">
    <div class="card">
      <div class="card-title"><span class="dot"></span>스크립트 미리보기</div>

      <div class="lang-header">🇰🇷 한국어</div>
      <div class="script-box">
        <div class="script-title" id="prev-title-ko"></div>
        <div class="script-hook"  id="prev-hook-ko"></div>
        <div class="script-body"  id="prev-script-ko"></div>
        <div class="hashtags"     id="prev-tags-ko"></div>
      </div>

      <div class="lang-header" style="margin-top:24px">🇺🇸 English</div>
      <div class="script-box">
        <div class="script-title" id="prev-title-en"></div>
        <div class="script-hook"  id="prev-hook-en"></div>
        <div class="script-body"  id="prev-script-en"></div>
        <div class="hashtags"     id="prev-tags-en"></div>
      </div>
    </div>
  </div>

  <!-- ── 진행 상태 ──────────────────────────────────────── -->
  <div id="progress-section">
    <div class="progress-card">
      <div class="card-title"><span class="dot"></span>생성 진행 중</div>
      <div class="progress-header">
        <span class="progress-message" id="prog-message">시작 중...</span>
        <span class="progress-pct"     id="prog-pct">0%</span>
      </div>
      <div class="progress-bar-bg">
        <div class="progress-bar-fill" id="prog-bar"></div>
      </div>
    </div>
  </div>

  <!-- ── 결과 ──────────────────────────────────────────── -->
  <div id="result-section">
    <div class="card">
      <div class="card-title"><span class="dot"></span>생성 완료</div>
      <div class="success-badge"><span class="success-dot"></span>영상이 성공적으로 생성되었습니다</div>

      <div class="result-video-wrap">
        <div class="result-item-label">🎬 쇼츠 영상</div>
        <video id="result-video" controls playsinline></video>
        <a class="btn-download" id="dl-video" href="#" download>⬇ 영상 다운로드</a>
      </div>

      <div class="lang-header" style="margin-top:28px">🇰🇷 한국어</div>
      <div class="script-box">
        <div class="script-title" id="res-title-ko"></div>
        <div class="script-hook"  id="res-hook-ko"></div>
        <div class="script-body"  id="res-script-ko"></div>
        <div class="hashtags"     id="res-tags-ko"></div>
      </div>

      <div class="lang-header" style="margin-top:24px">🇺🇸 English</div>
      <div class="script-box">
        <div class="script-title" id="res-title-en"></div>
        <div class="script-hook"  id="res-hook-en"></div>
        <div class="script-body"  id="res-script-en"></div>
        <div class="hashtags"     id="res-tags-en"></div>
      </div>
    </div>
  </div>

</div>
<script src="/js/shorts.js"></script>
</body>
</html>
