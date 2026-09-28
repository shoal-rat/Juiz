"""Juiz 音频测试台的本地语音服务（只用于电脑上的测试，不随应用发布）。
POST /asr  body=WAV           → {"text": "..."}          Qwen3-ASR（mlx-audio）
POST /tts  body={"text","instruct"} → WAV（16 位单声道）   Qwen3-TTS VoiceDesign（mlx-audio）
模型目录通过环境变量 JUIZ_ASR_MODEL / JUIZ_TTS_MODEL 指定。"""
import io, json, os, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import numpy as np, soundfile as sf

ASR_DIR = os.environ.get("JUIZ_ASR_MODEL", "models/qwen3-asr")
TTS_DIR = os.environ.get("JUIZ_TTS_MODEL", "models/voicedesign")
_lock = threading.Lock()
_asr = _tts = None

def asr():
    global _asr
    if _asr is None:
        from mlx_audio.stt.utils import load_model
        _asr = load_model(ASR_DIR)
    return _asr

def tts():
    global _tts
    if _tts is None:
        from mlx_audio.tts.utils import load_model
        _tts = load_model(TTS_DIR)
    return _tts

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _send(self, code, body, ctype):
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        t0 = time.time()
        try:
            with _lock:
                if self.path == "/asr":
                    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
                        f.write(data); p = f.name
                    text = asr().generate(p, language="Chinese").text.strip()
                    os.unlink(p)
                    self._send(200, json.dumps({"text": text, "ms": int((time.time() - t0) * 1000)}, ensure_ascii=False).encode(), "application/json")
                elif self.path == "/tts":
                    req = json.loads(data)
                    m = tts()
                    parts = [np.array(r.audio) for r in m.generate_voice_design(text=req["text"], instruct=req.get("instruct", "年轻女性，语气平和、礼貌、专业。"), language="chinese", temperature=0.7)]
                    audio = np.concatenate(parts)
                    buf = io.BytesIO(); sf.write(buf, audio, m.sample_rate, format="WAV", subtype="PCM_16")
                    self._send(200, buf.getvalue(), "audio/wav")
                else:
                    self._send(404, b"{}", "application/json")
        except Exception as e:
            self._send(500, json.dumps({"error": str(e)}).encode(), "application/json")

if __name__ == "__main__":
    port = int(os.environ.get("JUIZ_BENCH_PORT", "8765"))
    print(f"audio bench server on 127.0.0.1:{port}  asr={ASR_DIR}  tts={TTS_DIR}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
