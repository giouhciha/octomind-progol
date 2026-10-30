const SLOTS = { MS: 9, WEEKEND: 14, REVANCHA: 7 };

const token = () => localStorage.getItem('id_token') || '';

function authHeaders() {
  return {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + token(),
  };
}

async function api(path, options = {}) {
  let res = await fetch(CONFIG.apiUrl + path, { ...options, headers: authHeaders() });
  if (res.status === 401) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      res = await fetch(CONFIG.apiUrl + path, { ...options, headers: authHeaders() });
    } else {
      window.location.href = 'index.html';
      throw new Error('Sesión expirada');
    }
  }
  if (res.status === 204) return {};
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}

async function refreshAccessToken() {
  const refresh = localStorage.getItem('refresh_token');
  if (!refresh) return false;
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: CONFIG.clientId,
    refresh_token: refresh,
  });
  try {
    const res = await fetch(`${CONFIG.cognitoDomain}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    });
    if (!res.ok) return false;
    const tokens = await res.json();
    localStorage.setItem('access_token', tokens.access_token);
    if (tokens.id_token) localStorage.setItem('id_token', tokens.id_token);
    return true;
  } catch (e) {
    return false;
  }
}

function msg(text, ok = true) {
  const el = document.getElementById('msg');
  el.textContent = text;
  el.className = 'msg ' + (ok ? 'ok' : 'err');
}

// ── Editor de casillas ──────────────────────────────────────
function renderSlots() {
  const drawType = document.getElementById('drawType').value;
  const count = SLOTS[drawType];
  const box = document.getElementById('slots');
  box.innerHTML = '';
  const table = document.createElement('table');
  table.innerHTML = '<thead><tr><th>#</th><th>Local</th><th>Visitante</th></tr></thead>';
  const tbody = document.createElement('tbody');
  for (let slot = 1; slot <= count; slot++) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${slot}</td>
      <td><input id="local-${slot}" placeholder="Equipo local"></td>
      <td><input id="visitor-${slot}" placeholder="Equipo visitante"></td>`;
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  box.appendChild(table);
  attachTeamFilters();
}

// Solo letras (con acentos), espacios, guiones, apóstrofes y puntos en los equipos.
function attachTeamFilters() {
  const count = SLOTS[document.getElementById('drawType').value];
  for (let slot = 1; slot <= count; slot++) {
    for (const side of ['local', 'visitor']) {
      const el = document.getElementById(`${side}-${slot}`);
      el.addEventListener('input', () => {
        el.value = el.value.replace(/[^A-Za-zÁÉÍÓÚÑÜáéíóúñü .\-']/g, '');
      });
    }
  }
}

function toEpoch(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return isNaN(t) ? null : Math.floor(t / 1000);
}

function epochToLocalInput(value) {
  if (!value) return '';
  const d = new Date(value * 1000);
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function collectContest() {
  const drawType = document.getElementById('drawType').value;
  const contestNumber = parseInt(document.getElementById('contestNumber').value, 10);
  const endDate = toEpoch(document.getElementById('endDate').value);
  const count = SLOTS[drawType];
  const matches = [];
  for (let slot = 1; slot <= count; slot++) {
    const local = document.getElementById(`local-${slot}`).value.trim();
    const visitor = document.getElementById(`visitor-${slot}`).value.trim();
    matches.push({ slot, local, visitor });
  }
  return { drawType, contestNumber, endDate, matches };
}

async function saveContest() {
  const payload = collectContest();
  if (!payload.contestNumber) { msg('Falta el número de concurso', false); return; }
  try {
    await api('admin/contests', { method: 'POST', body: JSON.stringify(payload) });
    msg('Concurso guardado (borrador)');
    loadContests();
  } catch (e) { msg(e.message, false); }
}

async function publishContest() {
  const payload = collectContest();
  if (!payload.contestNumber) { msg('Falta el número de concurso', false); return; }
  try {
    // Guarda los datos completos (incluidos los nombres) antes de publicar.
    await api('admin/contests', { method: 'POST', body: JSON.stringify(payload) });
    await api('admin/publish', {
      method: 'POST',
      body: JSON.stringify({ drawType: payload.drawType, contestNumber: payload.contestNumber }),
    });
    msg('Publicado ✓ (ranking recalculado)');
    loadContests();
  } catch (e) { msg(e.message, false); }
}

// ── Ranking y usuarios ───────────────────────────────────────
async function loadLeaderboard() {
  const tbody = document.querySelector('#leaderboardTable tbody');
  try {
    const data = await api('leaderboard');
    const rows = data.leaderboard || [];
    if (!rows.length) {
      tbody.innerHTML = '<tr><td colspan="5">Sin ranking todavía</td></tr>';
      return;
    }
    tbody.innerHTML = rows.map(r =>
      `<tr><td>${r.name || 'Principal'}</td><td><a href="#" class="users-link" onclick="viewUserTemplates('${r.userId}', '${r.email || ''}'); return false;">${r.email || r.userId}</a></td><td>${r.totalHits}</td><td>${r.contestsPlayed}</td><td>${r.averageHits}</td></tr>`
    ).join('');
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="5">${e.message}</td></tr>`;
  }
}

let usersCache = [];

async function loadUsers() {
  const tbody = document.querySelector('#usersTable tbody');
  try {
    const data = await api('admin/users');
    usersCache = data.users || [];
    if (!usersCache.length) {
      tbody.innerHTML = '<tr><td colspan="3">Sin usuarios registrados</td></tr>';
      return;
    }
    tbody.innerHTML = usersCache.map(u => {
      const role = (u.groups || []).includes('admins') ? 'Admin' : 'Usuario';
      return `<tr>
        <td><a href="#" class="users-link" onclick="viewUserTemplates('${u.userId}', '${u.email || ''}'); return false;">${u.email || u.userId || ''}</a></td>
        <td>${u.status || ''}</td>
        <td>${role}</td>
      </tr>`;
    }).join('');
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="3">${e.message}</td></tr>`;
  }
}

async function viewUserTemplates(userId, email) {
  if (!userId) return;
  document.getElementById('templatesTitle').textContent = `Quinielas de ${email || userId}`;
  document.getElementById('templatesBody').innerHTML = 'Cargando…';
  document.getElementById('templatesModal').style.display = 'flex';
  try {
    const data = await api(`admin/templates?userId=${encodeURIComponent(userId)}`);
    const templates = data.templates || [];
    document.getElementById('templatesBody').innerHTML = renderTemplates(templates);
  } catch (e) {
    document.getElementById('templatesBody').innerHTML = `<p>${e.message}</p>`;
  }
}

function renderTemplates(templates) {
  if (!templates.length) return '<p>Este usuario no tiene quinielas guardadas.</p>';
  return templates.map(t => {
    const favorites = (t.config && t.config.favorites) || [];
    const combos = t.combinations || [];
    return `
      <div class="template">
        <h3>${t.drawType || ''} #${t.contestNumber ?? ''}</h3>
        <div class="favs">Favoritos: ${favorites.map(f => f || '—').join(' ')}</div>
        <ol>${combos.map(c => `<li>${c}</li>`).join('')}</ol>
      </div>`;
  }).join('');
}

function closeTemplatesModal() {
  document.getElementById('templatesModal').style.display = 'none';
}

// ── Concursos registrados ────────────────────────────────────
let contestsCache = [];

async function loadContests() {
  try {
    const data = await api('admin/contests');
    contestsCache = data.contests || [];
    renderContests();
  } catch (e) {
    document.querySelector('#contestsTable tbody').innerHTML = `<tr><td colspan="5">${e.message}</td></tr>`;
  }
}

function renderContests() {
  const tbody = document.querySelector('#contestsTable tbody');
  const drawType = document.getElementById('filterDrawType').value;
  const status = document.getElementById('filterStatus').value;
  const rows = [];
  for (let i = 0; i < contestsCache.length; i++) {
    const c = contestsCache[i];
    if (drawType && c.drawType !== drawType) continue;
    if (status === 'DRAFT' && c.status !== 'DRAFT') continue;
    if (status === 'PUBLISHED' && c.status !== 'PUBLISHED') continue;
    if (status === 'ACTIVE') {
      const active = c.status === 'PUBLISHED' && (!c.endDate || c.endDate * 1000 > Date.now());
      if (!active) continue;
    }
    rows.push({ c, i });
  }
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="5">Sin concursos</td></tr>';
    return;
  }
  tbody.innerHTML = rows.map(({ c, i }) => `
    <tr>
      <td>${c.drawType || ''}</td>
      <td>${c.contestNumber ?? ''}</td>
      <td>${c.endDate ? new Date(c.endDate * 1000).toLocaleString() : ''}</td>
      <td>${c.status || ''}</td>
      <td>
        <button class="btn small" onclick="editContest(${i})">Editar</button>
        <button class="btn small danger" onclick="deleteContest(${i})">Borrar</button>
      </td>
    </tr>`).join('');
}

function editContest(index) {
  const c = contestsCache[index];
  if (!c) return;
  document.getElementById('drawType').value = c.drawType;
  document.getElementById('contestNumber').value = c.contestNumber;
  document.getElementById('endDate').value = epochToLocalInput(c.endDate);
  renderSlots();
  const count = SLOTS[c.drawType];
  const matches = c.matches || [];
  for (let slot = 1; slot <= count; slot++) {
    const match = matches.find(m => m.slot === slot) || {};
    const local = document.getElementById(`local-${slot}`);
    const visitor = document.getElementById(`visitor-${slot}`);
    if (local) local.value = match.local || '';
    if (visitor) visitor.value = match.visitor || '';
  }
  msg(`Concurso ${c.drawType} #${c.contestNumber} cargado para editar`);
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function deleteContest(index) {
  const c = contestsCache[index];
  if (!c) return;
  if (!confirm(`¿Borrar ${c.drawType} #${c.contestNumber}?`)) return;
  try {
    await api(`admin/contests?drawType=${c.drawType}&contestNumber=${c.contestNumber}`, { method: 'DELETE' });
    msg('Concurso borrado');
    loadContests();
  } catch (e) { msg(e.message, false); }
}

// ── Histórico (Lotería Nacional) ─────────────────────────────
async function loadHistory() {
  const drawType = document.getElementById('historyDrawType').value;
  const tbody = document.querySelector('#historyTable tbody');
  try {
    const data = await api(`admin/history?drawType=${drawType}&limit=10`);
    const items = data.history || [];
    if (!items.length) {
      tbody.innerHTML = '<tr><td colspan="3">Sin histórico</td></tr>';
      return;
    }
    tbody.innerHTML = items.map(c =>
      `<tr><td>${c.contestNumber ?? ''}</td><td>${c.date || ''}</td><td>${(c.results || []).join(' ')}</td></tr>`
    ).join('');
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="3">${e.message}</td></tr>`;
  }
}

async function refreshHistory() {
  const btn = document.getElementById('refreshHistoryBtn');
  btn.disabled = true;
  btn.textContent = 'Sincronizando…';
  try {
    const data = await api('admin/refresh-history', { method: 'POST' });
    msg(data.message || 'Actualización iniciada');
  } catch (e) {
    msg(e.message, false);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Sincronizar histórico';
  }
}

// ── Init ─────────────────────────────────────────────────────
function init() {
  if (!token()) { window.location.href = 'index.html'; return; }
  renderSlots();
  document.getElementById('drawType').addEventListener('change', renderSlots);
  document.getElementById('saveBtn').addEventListener('click', saveContest);
  document.getElementById('publishBtn').addEventListener('click', publishContest);
  document.getElementById('logoutBtn').addEventListener('click', () => {
    localStorage.removeItem('access_token');
    localStorage.removeItem('id_token');
    localStorage.removeItem('refresh_token');
    window.location.href = `${CONFIG.cognitoDomain}/logout?client_id=${CONFIG.clientId}`
      + `&logout_uri=${encodeURIComponent(CONFIG.logoutUri)}`;
  });
  loadLeaderboard();
  loadUsers();
  loadContests();
  document.getElementById('filterDrawType').addEventListener('change', renderContests);
  document.getElementById('filterStatus').addEventListener('change', renderContests);
  document.getElementById('historyDrawType').addEventListener('change', loadHistory);
  document.getElementById('refreshHistoryBtn').addEventListener('click', refreshHistory);
  loadHistory();
}

init();
