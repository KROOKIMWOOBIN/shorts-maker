<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>AI 쇼츠 제작기</title>
  <link href="https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@300;400;500;700;900&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet"/>
  <style>
    :root{--bg:#07080f;--surface:#0e1120;--surface2:#161929;--border:#1f2540;--accent:#6c63ff;--accent2:#ff6b9d;--accent3:#00d2ff;--text:#e8eaf6;--text-muted:#6b7299;--success:#4caf82;--error:#ff5252}
    *{margin:0;padding:0;box-sizing:border-box}
    body{background:var(--bg);color:var(--text);font-family:'Noto Sans KR',sans-serif;min-height:100vh;overflow-x:hidden}
    body::before{content:'';position:fixed;top:-30%;left:-20%;width:60%;height:60%;background:radial-gradient(ellipse,rgba(108,99,255,.12) 0%,transparent 70%);pointer-events:none;z-index:0}
    body::after{content:'';position:fixed;bottom:-20%;right:-10%;width:50%;height:50%;background:radial-gradient(ellipse,rgba(255,107,157,.08) 0%,transparent 70%);pointer-events:none;z-index:0}
    .container{max-width:860px;margin:0 auto;padding:48px 24px 80px;position:relative;z-index:1}
    header{text-align:center;margin-bottom:56px}
    .logo-tag{display:inline-block;font-family:'Space Mono',monospace;font-size:11px;letter-spacing:.2em;color:var(--accent);background:rgba(108,99,255,.1);border:1px solid rgba(108,99,255,.3);padding:5px 14px;border-radius:20px;margin-bottom:20px;text-transform:uppercase}
    h1{font-size:clamp(32px,5vw,52px);font-weight:900;line-height:1.1;margin-bottom:16px}
    h1 .gradient{background:linear-gradient(135deg,var(--accent) 0%,var(--accent2) 50%,var(--accent3) 100%);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text}
    .subtitle{color:var(--text-muted);font-size:15px;font-weight:300}
    .card{background:var(--surface);border:1px solid var(--border);border-radius:20px;padding:36px;margin-bottom:24px;position:relative;overflow:hidden}
    .card::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,rgba(108,99,255,.4),transparent)}
    .card-title{font-size:13px;font-weight:700;letter-spacing:.15em;text-transform:uppercase;color:var(--text-muted);margin-bottom:24px;display:flex;align-items:center;gap:10px}
    .card-title .dot{width:6px;height:6px;border-radius:50%;background:var(--accent);box-shadow:0 0 8px var(--accent)}
    .form-group{margin-bottom:22px}
    label{display:block;font-size:13px;font-weight:500;color:var(--text-muted);margin-bottom:10px}
    input[type=text],select{width:100%;background:var(--surface2);border:1px solid var(--border);border-radius:12px;padding:14px 18px;color:var(--text);font-family:'Noto Sans KR',sans-serif;font-size:15px;transition:border-color .2s,box-shadow .2s;outline:none;appearance:none}
    input[type=text]:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(108,99,255,.15)}
    input[type=text]::placeholder{color:var(--text-muted);opacity:.6}
    select option{background:var(--surface2)}
    .slider-row{display:flex;align-items:center;gap:16px}
    input[type=range]{flex:1;-webkit-appearance:none;height:4px;background:var(--border);border-radius:2px;outline:none;cursor:pointer}
    input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:18px;height:18px;border-radius:50%;background:var(--accent);box-shadow:0 0 10px rgba(108,99,255,.5);cursor:pointer}
    .slider-value{font-family:'Space Mono',monospace;font-size:14px;color:var(--accent);min-width:48px;text-align:right}
    .color-presets{display:flex;gap:10px;flex-wrap:wrap}
    .color-preset{width:36px;height:36px;border-radius:10px;cursor:pointer;border:2px solid transparent;transition:all .2s}
    .color-preset:hover{transform:scale(1.15)}
    .color-preset.active{border-color:#fff;box-shadow:0 0 0 2px rgba(255,255,255,.3)}
    .btn-generate{width:100%;padding:18px;background:linear-gradient(135deg,var(--accent),#8b5cf6);border:none;border-radius:14px;color:#fff;font-family:'Noto Sans KR',sans-serif;font-size:16px;font-weight:700;cursor:pointer;transition:all .25s;overflow:hidden}
    .btn-generate:hover{transform:translateY(-2px);box-shadow:0 8px 30px rgba(108,99,255,.4)}
    .btn-generate:disabled{opacity:.5;cursor:not-allowed;transform:none}
    .btn-preview{width:100%;padding:14px;background:transparent;border:1px solid var(--border);border-radius:12px;color:var(--text-muted);font-family:'Noto Sans KR',sans-serif;font-size:14px;cursor:pointer;transition:all .2s;margin-top:10px}
    .btn-preview:hover{border-color:var(--accent);color:var(--accent)}
    #progress-section{display:none}
    .progress-card{background:var(--surface);border:1px solid var(--border);border-radius:20px;padding:36px;margin-bottom:24px}
    .progress-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
    .progress-pct{font-family:'Space Mono',monospace;font-size:14px;color:var(--accent)}
    .progress-bar-bg{width:100%;height:6px;background:var(--border);border-radius:3px;overflow:hidden;margin-bottom:16px}
    .progress-bar-fill{height:100%;background:linear-gradient(90deg,var(--accent),var(--accent2));border-radius:3px;transition:width .5s ease;width:0}
    .progress-message{font-size:14px;color:var(--text-muted)}
    #result-section{display:none}
    .result-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:8px}
    @media(max-width:600px){.result-grid{grid-template-columns:1fr}}
    .result-item{background:var(--surface2);border:1px solid var(--border);border-radius:14px;padding:20px}
    .result-item-label{font-size:11px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:var(--text-muted);margin-bottom:12px}
    .result-item video,.result-item img{width:100%;border-radius:10px;max-height:300px}
    .btn-download{display:block;width:100%;margin-top:12px;padding:10px;background:rgba(108,99,255,.12);border:1px solid rgba(108,99,255,.3);border-radius:10px;color:var(--accent);font-family:'Noto Sans KR',sans-serif;font-size:13px;font-weight:500;cursor:pointer;text-align:center;text-decoration:none;transition:all .2s}
    .btn-download:hover{background:rgba(108,99,255,.2)}
    .script-box{background:var(--surface2);border:1px solid var(--border);border-radius:14px;padding:24px;margin-top:20px}
    .script-title{font-size:18px;font-weight:700;color:var(--accent);margin-bottom:8px}
    .script-hook{font-size:14px;color:var(--accent2);font-style:italic;margin-bottom:16px;padding-bottom:16px;border-bottom:1px solid var(--border)}
    .script-body{font-size:14px;color:var(--text-muted);line-height:1.8}
    .hashtags{margin-top:16px;display:flex;flex-wrap:wrap;gap:8px}
    .hashtag{font-size:12px;color:var(--accent3);background:rgba(0,210,255,.08);border:1px solid rgba(0,210,255,.2);border-radius:20px;padding:4px 12px}
    .error-box{background:rgba(255,82,82,.08);border:1px solid rgba(255,82,82,.3);border-radius:12px;padding:16px 20px;color:var(--error);font-size:14px;margin-top:12px;display:none}
    .success-badge{display:inline-flex;align-items:center;gap:8px;background:rgba(76,175,130,.1);border:1px solid rgba(76,175,130,.3);border-radius:20px;padding:6px 16px;color:var(--success);font-size:13px;margin-bottom:20px}
    .success-dot{width:7px;height:7px;border-radius:50%;background:var(--success);animation:pulse 1.5s infinite}
    @keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
    .spinner{display:inline-block;width:16px;height:16px;border:2px solid rgba(255,255,255,.3);border-top-color:#fff;border-radius:50%;animation:spin .8s linear infinite;vertical-align:middle;margin-right:8px}
    @keyframes spin{to{transform:rotate(360deg)}}
    #preview-section{display:none;margin-bottom:24px}
  </style>
</head>
<body>
<div class="container">
  <header>
    <div class="logo-tag">✦ Powered by Ollama AI</div>
    <h1><span class="gradient">AI 쇼츠 제작기</span></h1>
    <p class="subtitle">주제만 입력하면 스크립트 → 음성 → 자막 → 썸네일까지 자동 생성</p>
  </header>

  <div class="card">
    <div class="card-title"><span class="dot"></span>영상 설정</div>
    <div class="form-group">
      <label>영상 주제 *</label>
      <input type="text" id="topic" placeholder="예: 고양이가 물을 싫어하는 이유, 우주에서 가장 큰 별"/>
    </div>
    <div class="form-group">
      <label>영상 길이</label>
      <div class="slider-row">
        <input type="range" id="duration" min="30" max="90" value="60" step="10"
               oninput="document.getElementById('dur-val').textContent=this.value+'초'"/>
        <span class="slider-value" id="dur-val">60초</span>
      </div>
    </div>
    <div class="form-group">
      <label>말투 / 톤</label>
      <select id="tone">
        <option value="shocking and mind-blowing">Shocking & Mind-blowing</option>
        <option value="inspiring and motivational">Inspiring & Motivational</option>
        <option value="friendly and engaging">Friendly & Engaging</option>
        <option value="professional and trustworthy">Professional & Trustworthy</option>
        <option value="mysterious and scary">Mysterious & Scary</option>
      </select>
    </div>
    <div class="form-group">
      <label>나레이션 음성</label>
      <select id="voice">
        <option value="en_US">English (US)</option>
      </select>
    </div>
    <div class="form-group">
      <label>배경 색상 테마</label>
      <div class="color-presets">
        <div class="color-preset active" style="background:linear-gradient(135deg,#0f0f23,#321050)" data-color="[15,15,35]" onclick="selectColor(this)"></div>
        <div class="color-preset" style="background:linear-gradient(135deg,#0a1e3c,#053050)" data-color="[10,30,60]" onclick="selectColor(this)"></div>
        <div class="color-preset" style="background:linear-gradient(135deg,#280a0a,#780a0a)" data-color="[40,10,10]" onclick="selectColor(this)"></div>
        <div class="color-preset" style="background:linear-gradient(135deg,#0a2814,#145030)" data-color="[10,40,20]" onclick="selectColor(this)"></div>
        <div class="color-preset" style="background:linear-gradient(135deg,#1a1a1a,#2a2a2a)" data-color="[25,25,25]" onclick="selectColor(this)"></div>
      </div>
    </div>
  </div>

  <button class="btn-generate" id="btn-gen" onclick="generateShorts()">✦ AI 쇼츠 영상 생성하기</button>
  <button class="btn-preview" onclick="previewScript()">📄 스크립트 미리보기 (빠름)</button>
  <div class="error-box" id="error-box"></div>

  <div id="preview-section">
    <div class="card">
      <div class="card-title"><span class="dot"></span>스크립트 미리보기</div>
      <div class="script-box">
        <div class="script-title" id="prev-title"></div>
        <div class="script-hook" id="prev-hook"></div>
        <div class="script-body" id="prev-script"></div>
        <div class="hashtags" id="prev-tags"></div>
      </div>
    </div>
  </div>

  <div id="progress-section">
    <div class="progress-card">
      <div class="card-title"><span class="dot"></span>생성 진행 중</div>
      <div class="progress-header">
        <span class="progress-message" id="prog-message">시작 중...</span>
        <span class="progress-pct" id="prog-pct">0%</span>
      </div>
      <div class="progress-bar-bg"><div class="progress-bar-fill" id="prog-bar"></div></div>
    </div>
  </div>

  <div id="result-section">
    <div class="card">
      <div class="card-title"><span class="dot"></span>생성 완료</div>
      <div class="success-badge"><span class="success-dot"></span>영상이 성공적으로 생성되었습니다</div>
      <div class="result-grid">
        <div class="result-item">
          <div class="result-item-label">🎬 쇼츠 영상</div>
          <video id="result-video" controls playsinline></video>
          <a class="btn-download" id="dl-video" href="#" download>⬇ 영상 다운로드</a>
        </div>
        <div class="result-item">
          <div class="result-item-label">🖼 썸네일</div>
          <img id="result-thumbnail" src="" alt="썸네일"/>
          <a class="btn-download" id="dl-thumb" href="#" download>⬇ 썸네일 다운로드</a>
        </div>
      </div>
      <div class="script-box">
        <div class="script-title" id="res-title"></div>
        <div class="script-hook" id="res-hook"></div>
        <div class="script-body" id="res-script"></div>
        <div class="hashtags" id="res-tags"></div>
      </div>
    </div>
  </div>
</div>

<script>
  const API = '/api/shorts';
  let selectedColor = [15,15,35], pollingInterval = null;

  function selectColor(el) {
    document.querySelectorAll('.color-preset').forEach(e => e.classList.remove('active'));
    el.classList.add('active');
    selectedColor = JSON.parse(el.dataset.color);
  }
  function showError(msg) {
    const b = document.getElementById('error-box');
    b.textContent = msg; b.style.display = 'block';
    setTimeout(() => b.style.display = 'none', 7000);
  }
  async function previewScript() {
    const topic = document.getElementById('topic').value.trim();
    if (!topic) { showError('주제를 입력해주세요.'); return; }
    const btn = event.target;
    btn.textContent = '⏳ 생성 중...'; btn.disabled = true;
    document.getElementById('preview-section').style.display = 'none';
    try {
      const res = await fetch(API + '/script/preview?topic=' + encodeURIComponent(topic) +
          '&durationSeconds=' + document.getElementById('duration').value);
      const data = await res.json();
      if (!data.success) throw new Error(data.message);
      const d = data.data;
      document.getElementById('prev-title').textContent = d.title;
      document.getElementById('prev-hook').textContent = '"' + d.hook + '"';
      document.getElementById('prev-script').textContent = d.script;
      document.getElementById('prev-tags').innerHTML = (d.hashtags||[]).map(t=>'<span class="hashtag">'+t+'</span>').join('');
      document.getElementById('preview-section').style.display = 'block';
    } catch(e) { showError('스크립트 생성 실패: '+e.message); }
    finally { btn.textContent = '📄 스크립트 미리보기 (빠름)'; btn.disabled = false; }
  }
  async function generateShorts() {
    const topic = document.getElementById('topic').value.trim();
    if (!topic) { showError('주제를 입력해주세요.'); return; }
    const btn = document.getElementById('btn-gen');
    btn.innerHTML = '<span class="spinner"></span>생성 시작 중...'; btn.disabled = true;
    document.getElementById('result-section').style.display = 'none';
    document.getElementById('error-box').style.display = 'none';
    try {
      const res = await fetch(API + '/generate', {
        method: 'POST', headers: {'Content-Type':'application/json'},
        body: JSON.stringify({
          topic, durationSeconds: parseInt(document.getElementById('duration').value),
          tone: document.getElementById('tone').value,
          voice: document.getElementById('voice').value,
          backgroundColor: selectedColor
        })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || '서버 오류');
      document.getElementById('progress-section').style.display = 'block';
      if (pollingInterval) clearInterval(pollingInterval);
      pollingInterval = setInterval(() => pollStatus(data.jobId), 2000);
    } catch(e) {
      showError('생성 실패: '+e.message);
      btn.innerHTML = '✦ AI 쇼츠 영상 생성하기'; btn.disabled = false;
    }
  }
  async function pollStatus(jobId) {
    try {
      const data = await (await fetch(API + '/status/' + jobId)).json();
      document.getElementById('prog-message').textContent = data.message || '';
      document.getElementById('prog-pct').textContent = data.progress + '%';
      document.getElementById('prog-bar').style.width = data.progress + '%';
      if (data.status === 'COMPLETED') {
        clearInterval(pollingInterval);
        document.getElementById('progress-section').style.display = 'none';
        document.getElementById('result-section').style.display = 'block';
        document.getElementById('result-video').src = data.videoUrl;
        document.getElementById('result-thumbnail').src = data.thumbnailUrl;
        document.getElementById('dl-video').href = API + '/download/video/' + jobId;
        document.getElementById('dl-thumb').href = API + '/download/thumbnail/' + jobId;
        if (data.script) {
          const d = data.script;
          document.getElementById('res-title').textContent = d.title;
          document.getElementById('res-hook').textContent = '"' + d.hook + '"';
          document.getElementById('res-script').textContent = d.script;
          document.getElementById('res-tags').innerHTML = (d.hashtags||[]).map(t=>'<span class="hashtag">'+t+'</span>').join('');
        }
        const btn = document.getElementById('btn-gen');
        btn.innerHTML = '✦ AI 쇼츠 영상 생성하기'; btn.disabled = false;
        document.getElementById('result-section').scrollIntoView({behavior:'smooth'});
      } else if (data.status === 'ERROR') {
        clearInterval(pollingInterval);
        showError(data.message);
        document.getElementById('progress-section').style.display = 'none';
        const btn = document.getElementById('btn-gen');
        btn.innerHTML = '✦ AI 쇼츠 영상 생성하기'; btn.disabled = false;
      }
    } catch(e) { clearInterval(pollingInterval); showError('상태 확인 실패: '+e.message); }
  }
</script>
</body>
</html>
