"""Conversational RAG Chat Web UI backed by Custom C DBMS (VECTOR(768)) + Ollama Streaming."""

import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from rag_engine import RagEngine

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8095
BASE_DIR = Path(__file__).resolve().parent
DB_PATH = str(BASE_DIR / "rag_studio.db")
RAG = RagEngine(db_path=DB_PATH, embed_model="nomic-embed-text:latest", chat_model="qwen3:8b")

if RAG.store.count_chunks() == 0:
    print("[VectorRAG] Indexing project documentation into custom C DBMS...")
    stats = RAG.ingest_project_docs()
    print(f"[VectorRAG] Indexed {sum(s['chunks_added'] for s in stats)} chunks.")

HTML_PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>VectorRAG Chat — Custom C DBMS + Ollama</title>
<link rel="preconnect" href="https://fonts.googleapis.com" />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet" />
<style>
  :root {
    --bg:#09090b; --s1:#0f0f13; --s2:#17171d; --s3:#202028;
    --b:rgba(255,255,255,.08); --b2:rgba(255,255,255,.15);
    --t:#fafafa; --t2:#a1a1aa; --t3:#71717a;
    --acc:#6366f1; --acc2:#818cf8; --g:#34d399; --a:#fbbf24;
  }
  * { box-sizing:border-box; }
  html, body { height:100%; margin:0; font-family:Inter,system-ui,sans-serif; background:var(--bg); color:var(--t); -webkit-font-smoothing:antialiased; }
  .layout { display:grid; grid-template-columns:290px 1fr; height:100vh; }

  /* SIDEBAR */
  .sidebar { background:var(--s1); border-right:1px solid var(--b); display:flex; flex-direction:column; padding:16px 14px; gap:14px; overflow-y:auto; }
  .brand { display:flex; align-items:center; gap:10px; padding:4px 4px 8px; border-bottom:1px solid var(--b); }
  .logo { width:34px; height:34px; border-radius:9px; background:linear-gradient(135deg,#6366f1,#a855f7); display:grid; place-items:center; font-weight:700; font-size:13px; color:#fff; box-shadow:0 6px 20px rgba(99,102,241,.3); }
  .brand h1 { margin:0; font-size:15px; letter-spacing:-.02em; }
  .brand span { font-size:11px; color:var(--t3); display:block; }

  .sec-title { font-size:11px; font-weight:600; text-transform:uppercase; letter-spacing:.05em; color:var(--t3); margin:4px 0 6px; display:flex; justify-content:space-between; align-items:center; }
  .stat-row { display:flex; gap:6px; flex-wrap:wrap; }
  .pill { font-size:11px; font-family:"JetBrains Mono",monospace; padding:3px 8px; border-radius:999px; background:var(--s2); border:1px solid var(--b); color:var(--t2); }
  .pill.g { color:var(--g); border-color:rgba(52,211,153,.25); }
  .pill.acc { color:#c7d2fe; border-color:rgba(129,140,248,.28); }
  .pill.a { color:var(--a); border-color:rgba(251,191,36,.25); }

  .doc-list { list-style:none; margin:0; padding:0; display:flex; flex-direction:column; gap:5px; max-height:210px; overflow-y:auto; }
  .doc-list li { display:flex; justify-content:space-between; align-items:center; padding:6px 9px; background:var(--s2); border:1px solid var(--b); border-radius:8px; font-size:12px; color:var(--t2); }
  .doc-list li span:last-child { font-family:"JetBrains Mono",monospace; font-size:11px; color:var(--t3); }

  .upload-box { background:var(--s2); border:1px dashed var(--b2); border-radius:10px; padding:12px; display:flex; flex-direction:column; gap:8px; }
  .upload-box input[type=text], .upload-box textarea { background:#0a0a0d; border:1px solid var(--b); border-radius:7px; color:var(--t); padding:7px 9px; font-size:12.5px; font-family:inherit; width:100%; outline:none; }
  .upload-box input[type=file] { font-size:11.5px; color:var(--t2); }
  .btn { background:var(--acc); color:#fff; border:none; border-radius:8px; padding:8px 13px; font-size:12.5px; font-weight:600; cursor:pointer; transition:opacity .15s; }
  .btn:hover { opacity:.9; }
  .btn:disabled { opacity:.5; cursor:not-allowed; }
  .btn.ghost { background:var(--s2); color:var(--t2); border:1px solid var(--b); }
  .btn.ghost:hover { color:var(--t); border-color:var(--b2); }

  /* MAIN CHAT */
  .main { display:flex; flex-direction:column; min-width:0; height:100vh; }
  .topbar { height:56px; border-bottom:1px solid var(--b); padding:0 22px; display:flex; align-items:center; justify-content:space-between; gap:12px; background:rgba(9,9,11,.9); backdrop-filter:blur(10px); }
  .controls { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  select { background:var(--s2); border:1px solid var(--b2); color:var(--t); border-radius:8px; padding:6px 10px; font-size:12.5px; outline:none; }
  .toggle { display:inline-flex; align-items:center; gap:6px; font-size:12.5px; color:var(--t2); background:var(--s2); border:1px solid var(--b); padding:5px 10px; border-radius:8px; cursor:pointer; user-select:none; }
  .toggle input { accent-color:var(--acc); }

  .chat-scroll { flex:1; overflow-y:auto; padding:24px 20px; }
  .chat-inner { max-width:860px; margin:0 auto; display:flex; flex-direction:column; gap:18px; }

  .welcome { text-align:center; padding:42px 16px 24px; }
  .welcome h2 { margin:0 0 8px; font-size:24px; letter-spacing:-.03em; }
  .welcome p { margin:0 0 22px; color:var(--t2); font-size:14px; }
  .starters { display:grid; grid-template-columns:repeat(2, 1fr); gap:10px; max-width:640px; margin:0 auto; text-align:left; }
  .starter { background:var(--s1); border:1px solid var(--b); border-radius:10px; padding:12px 14px; font-size:13px; color:var(--t2); cursor:pointer; transition:border-color .15s, color .15s; }
  .starter:hover { border-color:var(--acc2); color:var(--t); }

  .msg { display:flex; gap:12px; align-items:flex-start; }
  .msg.user { justify-content:flex-end; }
  .avatar { width:30px; height:30px; border-radius:8px; display:grid; place-items:center; font-size:11px; font-weight:700; flex-shrink:0; }
  .avatar.ai { background:linear-gradient(135deg,#6366f1,#8b5cf6); color:#fff; }
  .bubble { max-width:84%; border-radius:12px; padding:12px 16px; font-size:14px; line-height:1.65; }
  .msg.user .bubble { background:var(--acc); color:#fff; border-bottom-right-radius:4px; }
  .msg.ai .bubble { background:var(--s1); border:1px solid var(--b); color:#e4e4e7; border-top-left-radius:4px; width:100%; max-width:780px; }
  .msg-text { white-space:pre-wrap; word-break:break-word; }
  .msg-text code { font-family:"JetBrains Mono",monospace; font-size:12.5px; background:var(--s3); padding:1px 5px; border-radius:4px; color:#c7d2fe; }

  .meta-bar { display:flex; gap:6px; flex-wrap:wrap; margin-top:10px; padding-top:8px; border-top:1px solid var(--b); }
  details.sources { margin-top:8px; }
  details.sources summary { font-size:12px; color:var(--acc2); cursor:pointer; user-select:none; }
  .src-card { background:var(--s2); border:1px solid var(--b); border-radius:8px; padding:10px 12px; margin-top:8px; font-size:12px; }
  .src-card-head { display:flex; justify-content:space-between; font-family:"JetBrains Mono",monospace; font-size:11px; color:var(--t2); margin-bottom:5px; }
  .sim-bar { height:3px; background:rgba(255,255,255,.07); border-radius:999px; overflow:hidden; margin-top:6px; }
  .sim-bar > div { height:100%; background:var(--g); }

  /* COMPOSER */
  .composer-wrap { border-top:1px solid var(--b); padding:14px 20px 18px; background:var(--bg); }
  .composer { max-width:860px; margin:0 auto; display:flex; gap:10px; align-items:flex-end; background:var(--s1); border:1px solid var(--b2); border-radius:12px; padding:8px 10px 8px 14px; }
  .composer:focus-within { border-color:var(--acc2); }
  .composer textarea { flex:1; background:transparent; border:none; color:var(--t); font-size:14px; font-family:inherit; resize:none; max-height:160px; outline:none; padding:4px 0; line-height:1.45; }

  @media (max-width:860px) { .layout { grid-template-columns:1fr; } .sidebar { display:none; } .starters { grid-template-columns:1fr; } }
</style>
</head>
<body>
<div class="layout">
  <aside class="sidebar">
    <div class="brand">
      <div class="logo">VR</div>
      <div>
        <h1>VectorRAG Chat</h1>
        <span>Custom C DBMS &middot; Ollama</span>
      </div>
    </div>

    <div>
      <div class="sec-title">Vector Store</div>
      <div class="stat-row" id="statsBar"><span class="pill">Loading...</span></div>
    </div>

    <div>
      <div class="sec-title">Indexed Documents</div>
      <ul class="doc-list" id="docList"></ul>
    </div>

    <div class="upload-box">
      <div class="sec-title" style="margin:0;">Add Knowledge</div>
      <input type="file" id="filePicker" accept=".txt,.md,.py,.c,.h,.java,.json,.csv" onchange="uploadFile(event)" />
      <input type="text" id="inSource" placeholder="Document name (e.g. notes.md)" />
      <textarea id="inText" rows="3" placeholder="Or paste text to embed into VECTOR(768)..."></textarea>
      <button class="btn" onclick="ingestText()" id="ingestBtn">Embed &amp; Store</button>
      <div id="inStatus" style="font-size:11.5px;color:var(--t2);"></div>
    </div>

    <div style="margin-top:auto;">
      <button class="btn ghost" style="width:100%;" onclick="newChat()">+ New Conversation</button>
    </div>
  </aside>

  <div class="main">
    <header class="topbar">
      <div class="controls">
        <select id="modelSel" title="Ollama LLM Model"></select>
        <label class="toggle" title="Retrieve top-K chunks from Custom C DBMS before answering">
          <input type="checkbox" id="ragToggle" checked />
          <span>RAG Vector Search</span>
        </label>
        <select id="metricSel" title="Vector similarity metric">
          <option value="cosine">cosine_similarity</option>
          <option value="l2">l2_distance</option>
        </select>
        <select id="topKSel" title="Top-K chunks">
          <option value="3">Top 3</option>
          <option value="4" selected>Top 4</option>
          <option value="6">Top 6</option>
        </select>
      </div>
      <button class="btn ghost" onclick="newChat()">Clear Chat</button>
    </header>

    <div class="chat-scroll" id="chatScroll">
      <div class="chat-inner" id="chatList">
        <div class="welcome" id="welcomeBox">
          <h2>Talk to Your Custom Vector Database</h2>
          <p>Ask questions grounded in your indexed documents — retrieved in ~10ms by your C DBMS (<code>VECTOR(768)</code>) and streamed live by Ollama.</p>
          <div class="starters">
            <div class="starter" onclick="askPreset('How does WAL mode work in this DBMS and how does it handle crash recovery?')">How does WAL mode work and handle crash recovery?</div>
            <div class="starter" onclick="askPreset('Explain how MVCC snapshot isolation handles concurrent readers and writers.')">Explain MVCC snapshot isolation for readers &amp; writers</div>
            <div class="starter" onclick="askPreset('What is the storage page layout and how are overflow pages used?')">What is the 4KB storage page layout and overflow format?</div>
            <div class="starter" onclick="askPreset('What C API functions should I call to prepare, bind, and step a query?')">How do I use the C API (prepare, bind, step)?</div>
          </div>
        </div>
      </div>
    </div>

    <div class="composer-wrap">
      <div class="composer">
        <textarea id="msgInput" rows="1" placeholder="Ask a question about your documents... (Enter to send, Shift+Enter for newline)" onkeydown="onKey(event)"></textarea>
        <button class="btn" id="sendBtn" onclick="sendMessage()">Send</button>
      </div>
    </div>
  </div>
</div>

<script>
let history = [];
let busy = false;

function esc(s) {
  return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function renderMarkdownLite(txt) {
  return esc(txt)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

async function loadStatus() {
  const res = await fetch('/api/status').then(r => r.json());
  document.getElementById('statsBar').innerHTML =
    `<span class="pill g">${res.chunks} chunks</span><span class="pill acc">768-dim</span><span class="pill">${res.db_size_kb} KB</span>`;
  document.getElementById('docList').innerHTML = (res.sources || []).map(s =>
    `<li><span>${esc(s.source)}</span><span>${s.chunks}</span></li>`
  ).join('') || '<li><span>No docs indexed</span><span>0</span></li>';

  const sel = document.getElementById('modelSel');
  if (!sel.options.length) {
    const models = (res.models || ['qwen3:8b']).filter(m => !m.includes('embed'));
    sel.innerHTML = models.map(m => `<option value="${esc(m)}" ${m==='qwen3:8b'?'selected':''}>${esc(m)}</option>`).join('');
  }
}

function scrollBottom() {
  const el = document.getElementById('chatScroll');
  el.scrollTop = el.scrollHeight;
}

function newChat() {
  history = [];
  location.reload();
}

function askPreset(q) {
  document.getElementById('msgInput').value = q;
  sendMessage();
}

function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendMessage();
  }
}

async function sendMessage() {
  if (busy) return;
  const input = document.getElementById('msgInput');
  const q = input.value.trim();
  if (!q) return;

  const welcome = document.getElementById('welcomeBox');
  if (welcome) welcome.remove();

  input.value = '';
  busy = true;
  document.getElementById('sendBtn').disabled = true;

  const list = document.getElementById('chatList');
  list.insertAdjacentHTML('beforeend', `<div class="msg user"><div class="bubble"><div class="msg-text">${esc(q)}</div></div></div>`);

  const aiEl = document.createElement('div');
  aiEl.className = 'msg ai';
  aiEl.innerHTML = `<div class="avatar ai">AI</div><div class="bubble"><div class="msg-text" style="color:var(--t3)">Searching C DBMS vector store...</div><div class="meta-bar" style="display:none;"></div><div class="sources-slot"></div></div>`;
  list.appendChild(aiEl);
  scrollBottom();

  const textEl = aiEl.querySelector('.msg-text');
  const metaEl = aiEl.querySelector('.meta-bar');
  const srcEl  = aiEl.querySelector('.sources-slot');
  let fullReply = '';

  try {
    const resp = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: q,
        history: history.slice(-6),
        use_rag: document.getElementById('ragToggle').checked,
        model: document.getElementById('modelSel').value,
        metric: document.getElementById('metricSel').value,
        top_k: Number(document.getElementById('topKSel').value)
      })
    });

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop();
      for (const part of parts) {
        if (!part.startsWith('data: ')) continue;
        const ev = JSON.parse(part.slice(6));
        if (ev.type === 'retrieval') {
          textEl.style.color = '';
          textEl.textContent = '';
          if (ev.sources && ev.sources.length) {
            srcEl.innerHTML = `
              <details class="sources">
                <summary>Retrieved ${ev.sources.length} vector chunks from C DBMS (${ev.knn_ms} ms)</summary>
                ${ev.sources.map((s, i) => `
                  <div class="src-card">
                    <div class="src-card-head">
                      <span>[${i+1}] ${esc(s.source)} · chunk #${s.chunk_idx}</span>
                      <span>cos=${s.cosine_similarity} · L2=${s.l2_distance}</span>
                    </div>
                    <div style="color:#d4d4d8;line-height:1.5;">${esc(s.content)}</div>
                    <div class="sim-bar"><div style="width:${Math.max(0,Math.min(100,Math.round(s.cosine_similarity*100)))}%"></div></div>
                  </div>
                `).join('')}
              </details>`;
          }
          metaEl.style.display = 'flex';
          metaEl.innerHTML = `<span class="pill">Embed ${ev.embed_ms} ms</span><span class="pill g">C DBMS KNN ${ev.knn_ms} ms</span><span class="pill a" id="llmPill">Streaming...</span>`;
          scrollBottom();
        } else if (ev.type === 'token') {
          if (textEl.style.color) { textEl.style.color = ''; textEl.textContent = ''; }
          fullReply += ev.token;
          textEl.innerHTML = renderMarkdownLite(fullReply);
          scrollBottom();
        } else if (ev.type === 'done') {
          metaEl.style.display = 'flex';
          const lp = metaEl.querySelector('#llmPill');
          if (lp) lp.textContent = `LLM ${ev.llm_ms} ms · ${ev.model}`;
          else metaEl.innerHTML = `<span class="pill a">LLM ${ev.llm_ms} ms · ${ev.model}</span>`;
        } else if (ev.type === 'error') {
          textEl.style.color = '#f87171';
          textEl.textContent = 'Error: ' + ev.error;
        }
      }
    }
    if (fullReply) {
      history.push({ role: 'user', content: q });
      history.push({ role: 'assistant', content: fullReply });
    }
  } catch (err) {
    textEl.style.color = '#f87171';
    textEl.textContent = 'Request failed: ' + err.message;
  } finally {
    busy = false;
    document.getElementById('sendBtn').disabled = false;
    input.focus();
  }
}

async function uploadFile(e) {
  const file = e.target.files[0];
  if (!file) return;
  const text = await file.text();
  document.getElementById('inSource').value = file.name;
  document.getElementById('inText').value = text;
  await ingestText();
  e.target.value = '';
}

async function ingestText() {
  const source = document.getElementById('inSource').value.trim() || 'custom_doc.txt';
  const text = document.getElementById('inText').value.trim();
  if (!text) return;
  const btn = document.getElementById('ingestBtn');
  const st = document.getElementById('inStatus');
  btn.disabled = true;
  st.textContent = 'Embedding & writing to C DBMS...';
  try {
    const res = await fetch('/api/ingest', {
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({ source, text })
    }).then(r => r.json());
    st.textContent = `Indexed ${res.chunks_added} chunks (embed ${res.embed_ms}ms · DBMS ${res.insert_ms}ms)`;
    document.getElementById('inText').value = '';
    loadStatus();
  } finally {
    btn.disabled = false;
  }
}

loadStatus();
</script>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code: int, obj: dict):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_sse(self, obj: dict):
        payload = f"data: {json.dumps(obj)}\n\n".encode("utf-8")
        self.wfile.write(payload)
        self.wfile.flush()

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            data = HTML_PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if self.path == "/api/status":
            self._send_json(
                200,
                {
                    "chunks": RAG.store.count_chunks(),
                    "db_size_kb": RAG.store.db_size_kb(),
                    "sources": RAG.store.list_sources(),
                    "models": RAG.ollama.list_models(),
                },
            )
            return
        self._send_json(404, {"error": "Not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8")) if length > 0 else {}

        if self.path == "/api/chat":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            question = body.get("question", "").strip()
            history = body.get("history", [])
            use_rag = bool(body.get("use_rag", True))
            model = body.get("model") or RAG.ollama.chat_model
            metric = body.get("metric", "cosine")
            top_k = int(body.get("top_k", 4))

            try:
                messages = []
                if use_rag:
                    q_vec, embed_ms = RAG.ollama.embed(question)
                    hits, knn_ms = RAG.store.search_knn(q_vec, top_k=top_k, metric=metric)
                    self._send_sse(
                        {
                            "type": "retrieval",
                            "embed_ms": round(embed_ms, 1),
                            "knn_ms": round(knn_ms, 1),
                            "sources": hits,
                        }
                    )
                    ctx = "\n\n".join(
                        f"[{i}] Source: {h['source']} (chunk #{h['chunk_idx']}, cosine={h['cosine_similarity']})\n{h['content']}"
                        for i, h in enumerate(hits, 1)
                    )
                    messages.append(
                        {
                            "role": "system",
                            "content": (
                                "You are a helpful technical assistant backed by a custom C vector database. "
                                "Answer using the retrieved context below and cite sources inline like [1], [2]. "
                                "If the context does not contain the answer, say so clearly.\n\n"
                                f"Retrieved Context:\n{ctx}"
                            ),
                        }
                    )
                else:
                    messages.append({"role": "system", "content": "You are a helpful, concise assistant."})

                for m in history:
                    if m.get("role") in ("user", "assistant") and m.get("content"):
                        messages.append({"role": m["role"], "content": m["content"]})
                messages.append({"role": "user", "content": question})

                t0 = time.perf_counter()
                for tok in RAG.ollama.chat_stream(messages, model=model):
                    self._send_sse({"type": "token", "token": tok})
                llm_ms = round((time.perf_counter() - t0) * 1000.0, 1)
                self._send_sse({"type": "done", "llm_ms": llm_ms, "model": model})
            except (BrokenPipeError, ConnectionResetError):
                return
            except Exception as e:
                try:
                    self._send_sse({"type": "error", "error": str(e)})
                except (BrokenPipeError, ConnectionResetError):
                    pass
            return

        if self.path == "/api/ingest":
            res = RAG.ingest_text(body.get("source", "note.txt"), body.get("text", ""))
            self._send_json(200, res)
            return

        self._send_json(404, {"error": "Not found"})

    def log_message(self, format, *args):
        pass


if __name__ == "__main__":
    print(f"VectorRAG Chat Web UI running at http://127.0.0.1:{PORT}")
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        RAG.close()
