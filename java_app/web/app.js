let cachedProjects = [];
let vaultItems = [];
let searchTimer = null;
let draggedTaskId = null;

function switchTab(tab) {
  document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === 'tab-' + tab));
  if (tab === 'overview') loadDashboard();
  if (tab === 'tasks') loadTasks();
  if (tab === 'notes') loadNotes();
  if (tab === 'vault') loadVault();
  if (tab === 'engine') loadTelemetry();
}

document.querySelectorAll('.nav-item').forEach(btn => {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});

function focusFtsSearch() {
  switchTab('notes');
  setTimeout(() => document.getElementById('ftsSearchInput').focus(), 40);
}

document.addEventListener('keydown', (e) => {
  const tag = (e.target.tagName || '').toLowerCase();
  if (e.key === '/' && tag !== 'input' && tag !== 'textarea') {
    e.preventDefault();
    focusFtsSearch();
  }
  if (e.key === 'Escape') closeModals();
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && document.activeElement?.id === 'sqlInput') {
    e.preventDefault();
    runSqlConsole();
  }
});

function showToast(msg) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.add('show');
  setTimeout(() => el.classList.remove('show'), 2500);
}

function escHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function highlightText(text, query) {
  const safe = escHtml(text);
  if (!query) return safe;
  const escapedQ = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return safe.replace(new RegExp(`(${escapedQ})`, 'gi'), '<mark>$1</mark>');
}

async function api(url, opts = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...opts
  });
  return res.json();
}

async function loadDashboard() {
  const data = await api('/api/dashboard');
  document.getElementById('dbEndpointLabel').textContent =
    (data.dbHealthy ? 'WAL · ' : 'OFFLINE · ') + data.telemetry.avgLatencyMs + 'ms';

  document.getElementById('kpiProjects').textContent = data.counts.projects;
  document.getElementById('kpiTasks').textContent = data.counts.tasks;
  document.getElementById('navTaskCount').textContent = data.counts.tasks;
  document.getElementById('kpiTaskBreakdown').textContent =
    `${data.counts.done} done · ${data.counts.inProgress} active`;
  document.getElementById('kpiNotes').textContent = data.counts.notes;
  document.getElementById('kpiVault').textContent = data.counts.scratchpad;
  document.getElementById('kpiQueries').textContent = data.telemetry.totalQueries;
  document.getElementById('kpiLatency').textContent = `${data.telemetry.avgLatencyMs} ms avg`;

  // Update donut chart
  const total = data.counts.tasks || 0;
  const circ = 2 * Math.PI * 46; // ~289.03
  const doneLen = total ? (data.counts.done / total) * circ : 0;
  const progLen = total ? (data.counts.inProgress / total) * circ : 0;
  document.getElementById('donutDone').setAttribute('stroke-dasharray', `${doneLen} ${circ}`);
  const progEl = document.getElementById('donutProg');
  progEl.setAttribute('stroke-dasharray', `${progLen} ${circ}`);
  progEl.setAttribute('stroke-dashoffset', `${-doneLen}`);
  document.getElementById('donutPct').textContent = total ? Math.round((data.counts.done * 100) / total) + '%' : '0%';
  document.getElementById('legDone').textContent = data.counts.done;
  document.getElementById('legProg').textContent = data.counts.inProgress;
  document.getElementById('legTodo').textContent = data.counts.todo;

  cachedProjects = data.projects || [];
  populateProjectSelects();

  const list = document.getElementById('projectsList');
  if (cachedProjects.length === 0) {
    list.innerHTML = '<div class="sub-sm">No projects yet.</div>';
    return;
  }
  list.innerHTML = cachedProjects.map(p => `
    <div class="project-card" onclick="openProjectTasks(${p.id})">
      <div class="project-top">
        <div class="project-title">
          <span class="color-dot" style="background:${escHtml(p.color)}"></span>
          <span>${escHtml(p.name)}</span>
        </div>
        <button class="icon-btn" title="Delete project" onclick="event.stopPropagation(); deleteProject(${p.id})">&times;</button>
      </div>
      <div class="sub-sm">${escHtml(p.description || 'No description')}</div>
      <div class="bar-track">
        <div class="bar-fill" style="width:${p.progressPct}%;background:${escHtml(p.color)}"></div>
      </div>
      <div class="task-foot">
        <span>${p.doneTasks}/${p.totalTasks} done</span>
        <span class="mono">${p.progressPct}%</span>
      </div>
    </div>
  `).join('');
}

function openProjectTasks(projectId) {
  document.getElementById('taskProjectFilter').value = String(projectId);
  switchTab('tasks');
}

function populateProjectSelects() {
  const filter = document.getElementById('taskProjectFilter');
  const currentFilter = filter.value;
  filter.innerHTML = '<option value="">All Projects</option>' +
    cachedProjects.map(p => `<option value="${p.id}">${escHtml(p.name)}</option>`).join('');
  filter.value = currentFilter;

  const modalSel = document.getElementById('taskProject');
  modalSel.innerHTML = cachedProjects.map(p => `<option value="${p.id}">${escHtml(p.name)}</option>`).join('');
}

async function loadTasks() {
  if (cachedProjects.length === 0) await loadDashboard();
  const projectId = document.getElementById('taskProjectFilter').value;
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  const data = await api('/api/tasks' + qs);
  const cols = { TODO: [], IN_PROGRESS: [], DONE: [] };
  (data.tasks || []).forEach(t => (cols[t.status] || cols.TODO).push(t));

  document.getElementById('countTodo').textContent = cols.TODO.length;
  document.getElementById('countInProgress').textContent = cols.IN_PROGRESS.length;
  document.getElementById('countDone').textContent = cols.DONE.length;

  ['TODO', 'IN_PROGRESS', 'DONE'].forEach(status => {
    const container = document.getElementById('col-' + status);
    if (cols[status].length === 0) {
      container.innerHTML = '<div class="sub-sm" style="padding:8px 4px;">Drop tasks here</div>';
      return;
    }
    container.innerHTML = cols[status].map(t => `
      <div class="task-card" draggable="true" ondragstart="onDragStart(event, ${t.id})" ondragend="onDragEnd(event)">
        <div class="task-top">
          <span style="display:flex;align-items:center;gap:6px;">
            <span class="color-dot" style="background:${escHtml(t.projectColor)}"></span>
            ${escHtml(t.projectName)}
          </span>
          <span class="prio prio-${escHtml(t.priority)}">${escHtml(t.priority)}</span>
        </div>
        <div class="task-title">${escHtml(t.title)}</div>
        ${t.description ? `<div class="task-desc">${escHtml(t.description)}</div>` : ''}
        <div class="task-foot">
          <span>Due ${escHtml(t.dueDate || '—')}</span>
          <span class="mono">#${t.id}</span>
        </div>
        <div class="task-actions">
          ${status !== 'TODO' ? `<button class="btn btn-ghost btn-sm" onclick="moveTask(${t.id}, 'TODO')">To Do</button>` : ''}
          ${status !== 'IN_PROGRESS' ? `<button class="btn btn-ghost btn-sm" onclick="moveTask(${t.id}, 'IN_PROGRESS')">In Progress</button>` : ''}
          ${status !== 'DONE' ? `<button class="btn btn-primary btn-sm" onclick="moveTask(${t.id}, 'DONE')">Done</button>` : ''}
          <button class="btn btn-danger btn-sm" onclick="deleteTask(${t.id})">Delete</button>
        </div>
      </div>
    `).join('');
  });
}

// Drag & Drop Kanban
function onDragStart(e, id) {
  draggedTaskId = id;
  e.currentTarget.classList.add('dragging');
  e.dataTransfer.effectAllowed = 'move';
}
function onDragEnd(e) {
  e.currentTarget.classList.remove('dragging');
  document.querySelectorAll('.kcol').forEach(c => c.classList.remove('drag-over'));
}
function onDragOver(e) {
  e.preventDefault();
  e.currentTarget.classList.add('drag-over');
}
function onDragLeave(e) {
  e.currentTarget.classList.remove('drag-over');
}
function onDrop(e, newStatus) {
  e.preventDefault();
  e.currentTarget.classList.remove('drag-over');
  if (draggedTaskId != null) {
    const id = draggedTaskId;
    draggedTaskId = null;
    moveTask(id, newStatus);
  }
}

async function moveTask(id, status) {
  await api(`/api/tasks/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status })
  });
  showToast(`Task #${id} → ${status}`);
  loadTasks();
  loadDashboard();
}

async function deleteTask(id) {
  await api(`/api/tasks/${id}`, { method: 'DELETE' });
  showToast(`Task #${id} deleted`);
  loadTasks();
  loadDashboard();
}

async function deleteProject(id) {
  await api(`/api/projects/${id}`, { method: 'DELETE' });
  showToast(`Project #${id} deleted`);
  loadDashboard();
}

async function loadNotes() {
  const q = document.getElementById('ftsSearchInput').value.trim();
  const qs = q ? `?q=${encodeURIComponent(q)}` : '';
  const data = await api('/api/notes' + qs);
  const list = document.getElementById('notesList');
  const notes = data.notes || [];
  if (notes.length === 0) {
    list.innerHTML = '<div class="sub-sm">No matching documents in FTS5 index.</div>';
    return;
  }
  list.innerHTML = notes.map(n => `
    <div class="note-card">
      <div class="project-top">
        <h3>${highlightText(n.title, q)}</h3>
        <button class="icon-btn" title="Delete note" onclick="deleteNote(${n.id})">&times;</button>
      </div>
      <div class="note-body">${highlightText(n.body, q)}</div>
      <div class="task-foot">
        <span class="mono">FTS5 #${n.id}</span>
        <button class="btn btn-ghost btn-sm" onclick="copyText(${JSON.stringify(n.body)})">Copy</button>
      </div>
    </div>
  `).join('');
}

function copyText(txt) {
  navigator.clipboard?.writeText(txt).then(() => showToast('Copied to clipboard'));
}

function debouncedSearchNotes() {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(loadNotes, 160);
}

function clearNoteSearch() {
  document.getElementById('ftsSearchInput').value = '';
  loadNotes();
}

async function deleteNote(id) {
  await api(`/api/notes/${id}`, { method: 'DELETE' });
  showToast(`Note #${id} deleted`);
  loadNotes();
  loadDashboard();
}

function pickTtl(sec) {
  document.getElementById('vaultTtl').value = String(sec);
  document.querySelectorAll('#ttlPills .pill').forEach(p => {
    p.classList.toggle('active', Number(p.dataset.ttl) === sec);
  });
}

async function loadVault() {
  const data = await api('/api/scratchpad');
  vaultItems = data.items || [];
  renderVault();
}

function renderVault() {
  const list = document.getElementById('vaultList');
  const now = Math.floor(Date.now() / 1000);
  if (vaultItems.length === 0) {
    list.innerHTML = '<div class="sub-sm">No active TTL secrets. Create a 15s secret to watch the C engine reap it live.</div>';
    return;
  }
  list.innerHTML = vaultItems.map(item => {
    const rem = Math.max(0, Number(item.expiresAt) - now);
    const pct = item.ttlSeconds > 0 ? Math.min(100, Math.round((rem * 100) / item.ttlSeconds)) : 0;
    return `
      <div class="vault-item">
        <div class="project-top">
          <strong>${escHtml(item.label)}</strong>
          <div style="display:flex;gap:6px;align-items:center;">
            <span class="ttl-tag">${rem > 0 ? rem + 's left' : 'REAPED'}</span>
            <button class="icon-btn" onclick="deleteVaultItem(${item.id})">&times;</button>
          </div>
        </div>
        <div class="note-body">${escHtml(item.content)}</div>
        <div class="bar-track">
          <div class="bar-fill" style="width:${pct}%;background:#fbbf24"></div>
        </div>
      </div>
    `;
  }).join('');
}

setInterval(() => {
  if (vaultItems.length > 0) {
    const now = Math.floor(Date.now() / 1000);
    const hadExpired = vaultItems.some(i => Number(i.expiresAt) - now <= 0);
    renderVault();
    if (hadExpired) {
      loadVault();
      loadDashboard();
    }
  }
}, 1000);

async function createVaultItem(e) {
  e.preventDefault();
  const label = document.getElementById('vaultLabel').value;
  const content = document.getElementById('vaultContent').value;
  const ttlSeconds = Number(document.getElementById('vaultTtl').value);
  await api('/api/scratchpad', {
    method: 'POST',
    body: JSON.stringify({ label, content, ttlSeconds })
  });
  document.getElementById('vaultLabel').value = '';
  document.getElementById('vaultContent').value = '';
  showToast(`Stored with ${ttlSeconds}s native TTL`);
  loadVault();
  loadDashboard();
}

async function deleteVaultItem(id) {
  await api(`/api/scratchpad/${id}`, { method: 'DELETE' });
  loadVault();
  loadDashboard();
}

function setSql(sql) {
  document.getElementById('sqlInput').value = sql;
}

async function runSqlConsole() {
  const sql = document.getElementById('sqlInput').value.trim();
  if (!sql) return;
  const res = await api('/api/sql', {
    method: 'POST',
    body: JSON.stringify({ sql })
  });
  const statusEl = document.getElementById('sqlStatus');
  const outEl = document.getElementById('sqlOutput');
  if (!res.ok) {
    statusEl.textContent = `ERROR · ${res.durationMs} ms`;
    outEl.innerHTML = `<div class="mono" style="color:#f87171;padding:8px 0;">${escHtml(res.error)}</div>`;
  } else {
    statusEl.textContent = `OK · ${res.rows.length} row(s) · ${res.durationMs} ms`;
    if (res.columns.length === 0) {
      outEl.innerHTML = '<div class="sub-sm">Executed successfully.</div>';
    } else {
      outEl.innerHTML = `
        <table class="data-table">
          <thead><tr>${res.columns.map(c => `<th>${escHtml(c)}</th>`).join('')}</tr></thead>
          <tbody>
            ${res.rows.map(r => `<tr>${r.map(v => `<td>${v === null ? '<span class="sub-sm">NULL</span>' : escHtml(v)}</td>`).join('')}</tr>`).join('')}
          </tbody>
        </table>
      `;
    }
  }
  loadTelemetry();
}

async function runEngineAction(action) {
  const res = await api(`/api/engine/${action}`, { method: 'POST' });
  if (res.ok) {
    showToast(action === 'backup'
      ? `Backup written: ${res.file} (${res.durationMs} ms)`
      : `VACUUM completed (${res.durationMs} ms)`);
  } else {
    showToast(`Failed: ${res.error}`);
  }
  loadTelemetry();
}

async function loadTelemetry() {
  const data = await api('/api/telemetry');
  const tbody = document.getElementById('telemetryBody');
  const queries = data.queries || [];
  tbody.innerHTML = queries.map(q => `
    <tr>
      <td class="mono">#${q.seq}</td>
      <td class="sql-cell">${escHtml(q.sql)}</td>
      <td class="mono">${q.rowCount}</td>
      <td class="mono">${q.durationMs} ms</td>
      <td>${q.ok ? '<span style="color:#34d399">OK</span>' : `<span style="color:#f87171">${escHtml(q.error)}</span>`}</td>
    </tr>
  `).join('');
}

function openModal(id) {
  document.getElementById('modalBackdrop').style.display = 'block';
  document.getElementById(id).style.display = 'block';
}
function closeModals() {
  document.getElementById('modalBackdrop').style.display = 'none';
  document.querySelectorAll('.modal').forEach(m => m.style.display = 'none');
}
function openProjectModal() { openModal('projectModal'); }
function openTaskModal() { openModal('taskModal'); }
function openNoteModal() { openModal('noteModal'); }

async function submitProject(e) {
  e.preventDefault();
  await api('/api/projects', {
    method: 'POST',
    body: JSON.stringify({
      name: document.getElementById('projName').value,
      description: document.getElementById('projDesc').value,
      color: document.getElementById('projColor').value
    })
  });
  e.target.reset();
  closeModals();
  showToast('Project created');
  loadDashboard();
}

async function submitTask(e) {
  e.preventDefault();
  await api('/api/tasks', {
    method: 'POST',
    body: JSON.stringify({
      projectId: Number(document.getElementById('taskProject').value || 1),
      title: document.getElementById('taskTitle').value,
      description: document.getElementById('taskDesc').value,
      priority: document.getElementById('taskPriority').value,
      dueDate: document.getElementById('taskDue').value
    })
  });
  e.target.reset();
  closeModals();
  showToast('Task created');
  loadTasks();
  loadDashboard();
}

async function submitNote(e) {
  e.preventDefault();
  await api('/api/notes', {
    method: 'POST',
    body: JSON.stringify({
      title: document.getElementById('noteTitle').value,
      body: document.getElementById('noteBody').value
    })
  });
  e.target.reset();
  closeModals();
  showToast('Note indexed in FTS5');
  loadNotes();
  loadDashboard();
}

function connectEvents() {
  const es = new EventSource('/api/events');
  const feed = document.getElementById('eventFeed');
  es.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data);
      const li = document.createElement('li');
      li.textContent = data.event;
      if (feed.firstElementChild?.classList.contains('stream-empty')) {
        feed.innerHTML = '';
      }
      feed.prepend(li);
      while (feed.children.length > 12) feed.removeChild(feed.lastChild);
    } catch (_) {}
  };
}

loadDashboard();
loadTasks();
loadNotes();
loadVault();
connectEvents();
