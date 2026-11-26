"""Zero-dependency Ollama HTTP client for embeddings and LLM generation."""

import json
import re
import time
import urllib.error
import urllib.request
from typing import Dict, List, Optional, Tuple


class OllamaClient:
    def __init__(
        self,
        base_url: str = "http://127.0.0.1:11434",
        embed_model: str = "nomic-embed-text:latest",
        chat_model: str = "haervwe/qwen3-vl-4b-heretic:latest",
    ):
        self.base_url = base_url.rstrip("/")
        self.embed_model = embed_model
        self.chat_model = chat_model

    def _post(self, path: str, payload: dict, timeout: int = 120) -> dict:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}{path}",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def list_models(self) -> List[str]:
        try:
            with urllib.request.urlopen(f"{self.base_url}/api/tags", timeout=5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return [m["name"] for m in data.get("models", [])]
        except Exception:
            return []

    def embed(self, text: str, model: Optional[str] = None) -> Tuple[List[float], float]:
        t0 = time.perf_counter()
        res = self._post(
            "/api/embed",
            {"model": model or self.embed_model, "input": text},
            timeout=60,
        )
        ms = (time.perf_counter() - t0) * 1000.0
        vec = res["embeddings"][0]
        return vec, ms

    def embed_batch(self, texts: List[str], model: Optional[str] = None) -> Tuple[List[List[float]], float]:
        t0 = time.perf_counter()
        res = self._post(
            "/api/embed",
            {"model": model or self.embed_model, "input": texts},
            timeout=120,
        )
        ms = (time.perf_counter() - t0) * 1000.0
        return res["embeddings"], ms

    def generate(
        self,
        prompt: str,
        system: str = "",
        model: Optional[str] = None,
        temperature: float = 0.2,
        num_predict: int = 384,
    ) -> Tuple[str, float, Dict]:
        t0 = time.perf_counter()
        chosen = model or self.chat_model
        payload = {
            "model": chosen,
            "prompt": prompt,
            "stream": False,
            "think": False,
            "options": {
                "temperature": temperature,
                "num_predict": num_predict,
            },
        }
        if system:
            payload["system"] = system
        res = self._post("/api/generate", payload, timeout=180)
        ms = (time.perf_counter() - t0) * 1000.0
        raw_text = res.get("response", "").strip()
        # Strip any residual <think>...</think> blocks if model ignores think=False
        clean_text = re.sub(r"<think>.*?</think>", "", raw_text, flags=re.DOTALL).strip()
        meta = {
            "model": chosen,
            "eval_count": res.get("eval_count", 0),
            "prompt_eval_count": res.get("prompt_eval_count", 0),
        }
        return clean_text, ms, meta

    def chat_stream(
        self,
        messages: List[Dict[str, str]],
        model: Optional[str] = None,
        temperature: float = 0.3,
    ):
        """Yields text tokens incrementally from Ollama /api/chat."""
        chosen = model or self.chat_model
        payload = {
            "model": chosen,
            "messages": messages,
            "stream": True,
            "think": False,
            "options": {"temperature": temperature},
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/api/chat",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        in_think = False
        with urllib.request.urlopen(req, timeout=180) as resp:
            for raw_line in resp:
                line = raw_line.decode("utf-8", errors="ignore").strip()
                if not line:
                    continue
                obj = json.loads(line)
                tok = obj.get("message", {}).get("content", "")
                if tok:
                    if "<think>" in tok:
                        in_think = True
                        tok = tok.split("<think>", 1)[0]
                    if "</think>" in tok:
                        in_think = False
                        tok = tok.split("</think>", 1)[1]
                    if not in_think and tok:
                        yield tok
                if obj.get("done"):
                    break
