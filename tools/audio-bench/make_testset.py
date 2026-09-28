"""用本机 Qwen3-TTS VoiceDesign 生成测试音频（每句的原文就是标准答案），输出到 $OUT/<id>.wav。"""
import json, os, sys, time
import numpy as np, soundfile as sf
from mlx_audio.tts.utils import load_model
out = sys.argv[1] if len(sys.argv) > 1 else "testset"
os.makedirs(out, exist_ok=True)
spec = json.load(open(os.path.join(os.path.dirname(__file__), "testset.json")))
model = load_model(os.environ.get("JUIZ_TTS_MODEL", "models/voicedesign"))
for line in spec["lines"]:
    path = os.path.join(out, line["id"] + ".wav")
    if os.path.exists(path): continue
    t = time.time()
    parts = [np.array(r.audio) for r in model.generate_voice_design(text=line["text"], instruct=spec["speakers"][line["speaker"]], language="chinese", temperature=0.8)]
    audio = np.concatenate(parts)
    sf.write(path, audio, model.sample_rate, subtype="PCM_16")
    print(line["id"], f"{len(audio)/model.sample_rate:.1f}s audio in {time.time()-t:.1f}s", flush=True)
