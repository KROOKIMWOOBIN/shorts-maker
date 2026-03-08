const API = '/api/shorts';
let selectedColor = [15, 15, 35];
let selectedBgm   = 'UPBEAT';
let pollingInterval = null;

// ── 색상 선택 ──────────────────────────────────────────────────
function selectColor(el) {
  document.querySelectorAll('.color-preset').forEach(e => e.classList.remove('active'));
  el.classList.add('active');
  selectedColor = JSON.parse(el.dataset.color);
}

// ── BGM 스타일 선택 ────────────────────────────────────────────
function selectBgm(el) {
  document.querySelectorAll('.bgm-option').forEach(e => e.classList.remove('active'));
  el.classList.add('active');
  selectedBgm = el.dataset.bgm;
}

// ── 에러 표시 ──────────────────────────────────────────────────
function showError(msg) {
  const b = document.getElementById('error-box');
  b.textContent = msg;
  b.style.display = 'block';
  setTimeout(() => b.style.display = 'none', 7000);
}

// ── 스크립트 미리보기 ──────────────────────────────────────────
async function previewScript() {
  const topic = document.getElementById('topic').value.trim();
  if (!topic) { showError('주제를 입력해주세요.'); return; }

  const btn = event.target;
  btn.textContent = '⏳ 생성 중...';
  btn.disabled = true;
  document.getElementById('preview-section').style.display = 'none';

  try {
    const res  = await fetch(`${API}/script/preview?topic=${encodeURIComponent(topic)}&durationSeconds=${document.getElementById('duration').value}`);
    const data = await res.json();
    if (!data.success) throw new Error(data.message);

    const d = data.data;
    document.getElementById('prev-title').textContent  = d.title  || '';
    document.getElementById('prev-hook').textContent   = d.hook   ? `"${d.hook}"` : '';
    document.getElementById('prev-script').textContent = d.script || '';
    document.getElementById('prev-tags').innerHTML = (d.hashtags || [])
        .map(t => `<span class="hashtag">${t}</span>`).join('');
    document.getElementById('preview-section').style.display = 'block';

  } catch (e) {
    showError('스크립트 생성 실패: ' + e.message);
  } finally {
    btn.textContent = '📄 스크립트 미리보기 (빠름)';
    btn.disabled = false;
  }
}

// ── 쇼츠 생성 ─────────────────────────────────────────────────
async function generateShorts() {
  const topic = document.getElementById('topic').value.trim();
  if (!topic) { showError('주제를 입력해주세요.'); return; }

  const btn = document.getElementById('btn-gen');
  btn.innerHTML = '<span class="spinner"></span>생성 시작 중...';
  btn.disabled = true;

  document.getElementById('result-section').style.display   = 'none';
  document.getElementById('error-box').style.display        = 'none';
  document.getElementById('preview-section').style.display  = 'none';

  try {
    const res = await fetch(`${API}/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        topic,
        durationSeconds: parseInt(document.getElementById('duration').value),
        tone:            document.getElementById('tone').value,
        voice:           document.getElementById('voice').value,
        bgmStyle:        selectedBgm,
        backgroundColor: selectedColor
      })
    });

    const data = await res.json();
    if (!res.ok) throw new Error(data.message || '서버 오류');

    document.getElementById('progress-section').style.display = 'block';
    document.getElementById('progress-section').scrollIntoView({ behavior: 'smooth' });

    if (pollingInterval) clearInterval(pollingInterval);
    pollingInterval = setInterval(() => pollStatus(data.jobId), 2000);

  } catch (e) {
    showError('생성 실패: ' + e.message);
    btn.innerHTML = '✦ AI 쇼츠 영상 생성하기';
    btn.disabled = false;
  }
}

// ── 상태 폴링 ─────────────────────────────────────────────────
async function pollStatus(jobId) {
  try {
    const data = await (await fetch(`${API}/status/${jobId}`)).json();

    document.getElementById('prog-message').textContent = data.message || '';
    document.getElementById('prog-pct').textContent     = data.progress + '%';
    document.getElementById('prog-bar').style.width     = data.progress + '%';

    if (data.status === 'COMPLETED') {
      clearInterval(pollingInterval);
      showResult(data, jobId);

    } else if (data.status === 'ERROR') {
      clearInterval(pollingInterval);
      showError(data.message || '생성 실패');
      document.getElementById('progress-section').style.display = 'none';
      const btn = document.getElementById('btn-gen');
      btn.innerHTML = '✦ AI 쇼츠 영상 생성하기';
      btn.disabled = false;
    }
  } catch (e) {
    clearInterval(pollingInterval);
    showError('상태 확인 실패: ' + e.message);
  }
}

// ── 결과 표시 (썸네일 없음) ────────────────────────────────────
function showResult(data, jobId) {
  document.getElementById('progress-section').style.display = 'none';
  document.getElementById('result-section').style.display   = 'block';

  // 영상
  const video = document.getElementById('result-video');
  video.src = data.videoUrl + '?t=' + Date.now(); // 캐시 방지
  video.load();

  // 다운로드 링크
  document.getElementById('dl-video').href = `${API}/download/video/${jobId}`;

  // 스크립트
  if (data.script) {
    const d = data.script;
    document.getElementById('res-title').textContent  = d.title  || '';
    document.getElementById('res-hook').textContent   = d.hook   ? `"${d.hook}"` : '';
    document.getElementById('res-script').textContent = d.script || '';
    document.getElementById('res-tags').innerHTML = (d.hashtags || [])
        .map(t => `<span class="hashtag">${t}</span>`).join('');
  }

  // 버튼 복원
  const btn = document.getElementById('btn-gen');
  btn.innerHTML = '✦ AI 쇼츠 영상 생성하기';
  btn.disabled = false;

  document.getElementById('result-section').scrollIntoView({ behavior: 'smooth' });
}
