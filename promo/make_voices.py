"""宣传片配音：本机 Qwen3-TTS VoiceDesign，每句生成多条，用 Qwen3-ASR 按字错率挑最好的一条。"""
import json, os, re, sys, time
import numpy as np, soundfile as sf
from mlx_audio.tts.utils import load_model as load_tts
from mlx_audio.stt.utils import load_model as load_asr
V = os.environ["JUIZ_MODELS"]
spec = json.load(open(os.path.join(os.path.dirname(__file__), "lines.json")))
out = os.path.join(os.path.dirname(__file__), "voice")
os.makedirs(out, exist_ok=True)
takes = int(sys.argv[1]) if len(sys.argv) > 1 else 3
only = set(sys.argv[2:])  # 只重做这几句
tts = load_tts(f"{V}/voicedesign"); asr = load_asr(f"{V}/qwen3-asr")
def norm(s): return re.sub(r"[^一-鿿A-Za-z]", "", s).lower()
def cer(a, b):
    a, b = norm(a), norm(b); d = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, y in enumerate(b, 1): p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (x != y))
    return d[len(b)] / max(1, len(a))
pick = json.load(open(f"{out}/picked.json")) if only and os.path.exists(f"{out}/picked.json") else {}
for line in spec["lines"]:
    if only and line["id"] not in only: continue
    say = line.get("say", line["text"])  # 专有名词按读音写，画面上仍显示 text
    best = None
    for k in range(takes):
        parts = [np.array(r.audio) for r in tts.generate_voice_design(text=say, instruct=spec["characters"][line["who"]] + line["emotion"], language="chinese", temperature=0.9)]
        audio = np.concatenate(parts); path = f"{out}/{line['id']}.take{k+1}.wav"
        sf.write(path, audio, tts.sample_rate, subtype="PCM_16")
        heard = asr.generate(path, language="Chinese").text.strip(); c = cer(say, heard)
        print(line["id"], k + 1, f"{len(audio)/tts.sample_rate:.1f}s", f"cer={c:.2f}", heard, flush=True)
        if best is None or c < best[0]: best = (c, path)
    os.replace(best[1], f"{out}/{line['id']}.wav"); pick[line["id"]] = round(best[0], 3)
json.dump(pick, open(f"{out}/picked.json", "w"), ensure_ascii=False, indent=1)
print("picked", pick)
