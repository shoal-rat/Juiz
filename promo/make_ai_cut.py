"""Juiz 宣传片 · AI 视频版（60 秒）：RunningHub 上 MiniMax H3（RH Enhanced）用分镜图做首帧生成的 12 段 5 秒视频，
叠加同样的漫画对话框字幕、本机 Qwen3-TTS 配音与 compose.py 配乐 → promo/juiz_promo_ai.mp4

生成出来的原声（人声和音乐）是模型自己编的无意义内容，全部不用，换成本机配音与 compose.py 的配乐。
第 7 段模型在回复卡片上画了乱码英文，这里用一块干净的中文"选句代答"面板盖住。
"""
import json, math, os, subprocess, sys
import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
import bubbles as B
import compose

W, H, FPS, SR = 1920, 1080, 30, 48000
CLIPS = os.path.join(ROOT, "runninghub", "clips")
SHOTS = ["01_boss", "02_woman", "03_cry", "04_grid", "05_glow", "06_shield", "07_reply", "08_night", "09_handoff", "10_approve", "11_portal", "12_arrive"]
SEG = 5.0
TOTAL = 61.0
FONT = "/System/Library/Fonts/STHeiti Medium.ttc"
MONO = "/System/Library/Fonts/Menlo.ttc"

# ---------------- 配音 ----------------
voice = {}
for line in json.load(open(os.path.join(ROOT, "lines.json")))["lines"]:
    a, sr = sf.read(os.path.join(ROOT, "voice", line["id"] + ".wav"), dtype="float32")
    if a.ndim > 1: a = a.mean(axis=1)
    idx = np.arange(0, len(a), sr / SR)
    voice[line["id"]] = np.interp(idx, np.arange(len(a)), a).astype(np.float32)
dur = lambda v: len(voice[v]) / SR


def frac(parts, k):
    n = [len(p.replace("\n", "")) for p in parts]
    return sum(n[:k]) / max(1, sum(n))


N5 = ["活交给大模型去做，", "你只需要最后，\n点一下批准。"]
cue = {
    "boss_1": 0.3,
    "woman_1": 5.5,
    "nar_1": 10.6,
    "nar_2": 15.5,
    "juiz_1": 21.7,
    "nar_3": 25.35,
    "nar_3b": 30.6,
    "nar_4": 35.6,
    "nar_5": 45.05 - dur("nar_5") * frac(N5, 1),   # "你只需要最后……"正好落在批准那一段
    "nar_6": 50.9,
    "juiz_3": 55.5,
    "nar_7": 58.0,
}

# ---------------- 对话框 ----------------
BUB = [
    dict(t=cue["boss_1"], d=dur("boss_1"), parts=["这点事都做不好？！", "明天早上之前，\n给我重做！"], kind="shout", style=0.0,
         at=[(1400, 220), (1330, 610)], tail=[(1010, 300), (1060, 470)], size=[62, 56]),
    dict(t=cue["woman_1"], d=dur("woman_1"), parts=["好的……好的，\n我马上改……"], kind="whisper", style=0.0, at=[(380, 190)], tail=[(640, 360)], size=46, shake=True),
    dict(t=cue["nar_1"], d=dur("nar_1"), parts=["每天，都有这样一通电话。"], kind="caption", style=0.0, at=[(470, 120)], tail=[None], size=54),
    dict(t=cue["nar_2"], d=dur("nar_2"), parts=["而这样的人，\n不止你一个。"], kind="caption", style=0.0, at=[(300, 118)], tail=[None], size=50),
    dict(t=cue["juiz_1"], d=dur("juiz_1"), parts=["别怕，\n我来帮你！"], kind="cute", style=1.0, at=[(820, 300)], tail=[(610, 330)], size=52),
    dict(t=cue["nar_3"], d=dur("nar_3"), parts=["难听的话，Juiz 替你挡住。", "你只看要点。"], kind="caption", style=0.3, at=[(520, 980), (1500, 980)], tail=[None, None], size=46),
    dict(t=cue["nar_3b"], d=dur("nar_3b"), parts=["想怎么回，你来挑；", "Juiz 替你说出口。"], kind="caption", style=0.45, at=[(1560, 110), (1560, 980)], tail=[None, None], size=46),
    dict(t=cue["nar_4"], d=dur("nar_4"), parts=["深夜的电话，交给 Juiz。"], kind="caption", style=0.55, at=[(400, 975)], tail=[None], size=46),
    dict(t=cue["nar_5"], d=dur("nar_5"), parts=N5, kind="caption", style=[0.62, 0.7], at=[(360, 980), (1560, 170)], tail=[None, None], size=46),
    dict(t=cue["nar_6"], d=dur("nar_6"), parts=["从今天起，\n负面情绪，\n挡在门外。"], kind="caption", style=0.9, at=[(1780, 320)], tail=[None], size=46),
    dict(t=cue["juiz_3"], d=dur("juiz_3"), parts=["早上好！\n今天也一起加油吧！"], kind="cute", style=1.0, at=[(185, 360)], tail=[(370, 240)], size=42),
    dict(t=cue["nar_7"], d=dur("nar_7"), parts=["Juiz。\n你的私人助理。"], kind="caption", style=1.0, at=[(1790, 540)], tail=[None], size=44),
]
SHOW = []
for b in BUB:
    for k, text in enumerate(b["parts"]):
        start = b["t"] + b["d"] * frac(b["parts"], k)
        seg_end = (math.floor(start / SEG) + 1) * SEG if start < 55 else TOTAL
        end = min(b["t"] + b["d"] + 0.8, seg_end - 0.04)
        pick = lambda v: v[k] if isinstance(v, list) else v
        SHOW.append((start, end, dict(text=text, kind=b["kind"], style=pick(b["style"]), at=b["at"][k], tail=b["tail"][k],
                                       size=pick(b["size"]), shake=b.get("shake", False))))


def ease(x):
    x = max(0.0, min(1.0, x)); return x * x * (3 - 2 * x)


def draw_bubbles(im, tt):
    for start, end, p in SHOW:
        if not (start <= tt < end): continue
        cx, cy = p["at"]
        rel = None if p["tail"] is None else (int(p["tail"][0] - cx), int(p["tail"][1] - cy))
        bub = B.render(p["text"], p["kind"], p["style"], p["size"], tail=rel)
        age, left = tt - start, end - tt
        alpha = min(1.0, left / 0.2)
        if p["style"] < 0.75: scale, off = B.pop_scale(age), (0, 0)
        else: scale = 1.0; alpha *= min(1.0, age / 0.28); off = (0, int(14 * (1 - ease(age / 0.35))))
        if p["shake"]: off = (off[0] + int(3 * math.sin(tt * 41)), off[1] + int(2 * math.cos(tt * 37)))
        im = B.place(im, bub, (cx, cy), scale=scale, alpha=alpha, offset=off)
    return im


# ---------------- 第 7 段：选句代答面板（盖住模型画的乱码） ----------------

def reply_panel(highlight):
    w, h = 640, 330
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=28, fill=(12, 26, 44, 236), outline=(92, 225, 230, 255), width=3)
    f = ImageFont.truetype(FONT, 32); fm = ImageFont.truetype(FONT, 22)
    d.text((28, 22), "REPLY · 选一句，Juiz 替你说", font=fm, fill=(147, 200, 220))
    opts = ["收到，我十点前重做发您", "口径我先跟财务确认一下", "我十分钟后给您回电"]
    for i, o in enumerate(opts):
        y = 70 + i * 82
        on = i == 1 and highlight > 0
        d.rounded_rectangle([20, y, w - 20, y + 66], radius=16,
                            fill=(92, 225, 230, int(70 + 120 * highlight)) if on else (30, 48, 72, 230),
                            outline=(92, 225, 230, 255) if on else (52, 76, 104, 255), width=2)
        d.text((44, y + 15), o, font=f, fill=(240, 250, 255))
    return img


PANELS = [reply_panel(0.0), reply_panel(1.0)]
# 模型画的乱码卡片会在面板四周露出来：先把那一片整体虚化（羽化边缘），再盖上面板
from PIL import ImageFilter
_BLUR_BOX = (860, 130, 1420, 470)
_bm = Image.new("L", (W, H), 0); ImageDraw.Draw(_bm).rounded_rectangle(_BLUR_BOX, radius=60, fill=255)
BLUR_MASK = _bm.filter(ImageFilter.GaussianBlur(28))

# ---------------- 画面 ----------------

def open_clip(name):
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", os.path.join(CLIPS, name + ".mp4"),
                          "-vf", f"scale=-2:{H},crop={W}:{H},fps={FPS}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    return p


def frames():
    last = None
    for i, name in enumerate(SHOTS):
        p = open_clip(name)
        want = int((SEG if i < len(SHOTS) - 1 else TOTAL - SEG * i) * FPS)
        got = 0
        while got < want:
            raw = p.stdout.read(W * H * 3)
            if len(raw) < W * H * 3:
                if last is None: break
                yield last.copy(), i; got += 1; continue   # 最后一段不够长：停在最后一帧
            last = Image.frombytes("RGB", (W, H), raw)
            yield last, i; got += 1
        p.stdout.close(); p.wait()


def end_title(im, tt):
    q = ease((tt - 57.3) / 0.8)
    if q <= 0: return im
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    for y in range(H - 260, H):
        d.line([(0, y), (W, y)], fill=(6, 10, 20, int(170 * q * (y - (H - 260)) / 260)))
    d.text((80, H - 200), "Juiz", font=ImageFont.truetype(FONT, 96), fill=(240, 248, 255, int(255 * q)))
    d.text((88, H - 92), "Personal Assistant · 开源 github.com/shoal-rat/Juiz", font=ImageFont.truetype(FONT, 32), fill=(190, 220, 245, int(255 * q)))
    base = im.convert("RGBA"); base.alpha_composite(ov); return base.convert("RGB")


def compose_frame(im, i, tt):
    if SHOTS[i] == "07_reply":
        k = tt - 30.0
        im = Image.composite(im.filter(ImageFilter.GaussianBlur(22)), im, BLUR_MASK)
        panel = PANELS[1] if k > 1.6 else PANELS[0]
        a = min(1.0, k / 0.25)   # 不淡出：切到下一段时自然消失，免得露出底下的乱码
        pn = panel.copy()
        if a < 1: pn.putalpha(pn.getchannel("A").point(lambda v: int(v * a)))
        base = im.convert("RGBA"); base.alpha_composite(pn, (700, 115)); im = base.convert("RGB")
    im = draw_bubbles(im, tt)
    if tt > 57.3: im = end_title(im, tt)
    if tt > TOTAL - 0.8: im = Image.blend(im, Image.new("RGB", (W, H)), ease((tt - (TOTAL - 0.8)) / 0.8))
    return im


# ---------------- 声音 ----------------

def ai_sfx():
    import sfx as X
    tr = X.Track(TOTAL)
    tr.add(X.rain(15.2, 0.7), 0.0, 0.9); tr.add(X.thunder(), 0.1, 0.8)
    for dt in (0.9, 3.0): tr.add(X.slam(), cue["boss_1"] + dt, 0.7, -0.2)
    tr.add(X.receiver_click(), 5.3, 0.6); tr.add(X.sniff(), 9.3, 0.8, 0.1)
    for k in range(3): tr.add(X.heartbeat(), 10.4 + k * 1.1, 0.5)
    tr.add(X.drip(), 12.6, 0.9, 0.15)
    tr.add(X.paper(0.5), 15.0, 0.6); tr.add(X.many_rings(4.6), 15.3, 0.8); tr.add(X.whoosh(0.7, up=True), 19.35, 0.9)
    tr.add(X.vibrate(), 20.0, 0.7); tr.add(X.sparkle(1.3), 20.05, 1.0); tr.add(X.chime((84, 88, 91), 0.1, 0.14), 20.15, 0.8)
    # 撞击都放在旁白开口之前，不然会盖住"朱伊斯"
    tr.add(X.impact(0.8), 24.95, 0.9, -0.3); tr.add(X.shatter(), 25.0, 0.7, -0.2); tr.add(X.shield_hum(3.8), 25.0, 0.6); tr.add(X.zap(), 29.2, 0.6, 0.2)
    for k, f in enumerate((800, 950, 1100)): tr.add(X.pop(f), 30.1 + 0.15 * k, 0.9, -0.2 + 0.2 * k)
    tr.add(X.tap(), 31.6, 1.0); tr.add(X.chime((88, 93), 0.08, 0.12), 31.65, 0.8)
    tr.add(X.crickets(5.0), 35.0, 0.9); tr.add(X.clock_tick(5.0), 35.0, 0.7, -0.4)
    tr.add(X.whoosh(1.0, up=True), 40.2, 0.9); tr.add(X.sparkle(2.8, 10, 3200), 40.7, 0.8); tr.add(X.paper(0.8), 42.6, 0.5)
    tr.add(X.birds(5.0, 5), 45.0, 0.8); tr.add(X.scan_beep(), 46.2, 0.9)
    tr.add(X.tear(0.9), 50.2, 1.0); tr.add(X.wind(5.0, 0.8), 50.0, 0.9); tr.add(X.whoosh(1.0, up=True), 51.8, 0.8); tr.add(X.birds(2.5, 5), 52.6, 0.8)
    tr.add(X.door(), 55.05, 0.7); tr.add(X.office(6.0), 55.0, 0.9); tr.add(X.chime((91, 96), 0.1, 0.1), 55.4, 0.7)
    tr.add(X.sparkle(2.0, 10, 3000), 58.2, 0.6)
    return tr


def build_audio():
    T = {"title": (0.0, 0.0), "boss": (0.0, 5.0), "woman": (5.0, 15.0), "grid": (15.0, 20.0), "glow": (20.0, 25.0),
         "shield": (25.0, 30.0), "relief": (30.0, 30.0), "reply": (30.0, 35.0), "night": (35.0, 40.0), "handoff": (40.0, 45.0),
         "approve": (45.0, 50.0), "portal": (50.0, 52.6), "field": (52.6, 55.0), "crowd": (55.0, 55.0), "arrive": (55.0, 57.2),
         "end": (57.2, TOTAL)}
    music = compose.score(T, 20.05, TOTAL)
    n = len(music)
    vo = np.zeros((n, 2), np.float32)

    def add(sig, at, g=1.0):
        i = int(at * SR); j = min(n, i + len(sig))
        if j > i: vo[i:j] += sig[: j - i, None] * g

    duck = np.ones(n, np.float32)
    for vid, at in cue.items():
        add(voice[vid], at, 0.95)
        i = int(at * SR); j = min(n, i + len(voice[vid])); duck[max(0, i - 6000):j + 6000] = 0.5
    duck = np.convolve(duck, np.ones(9600) / 9600, mode="same").astype(np.float32)
    fx = ai_sfx().buf[:n]
    fxduck = np.clip(1 - (1 - duck) * 1.3, 0.3, 1)  # 说话时音效压到三成左右，别盖住人声
    mix = music / max(np.abs(music).max(), 1e-6) * 0.55 * duck[:, None] + fx * fxduck[:, None] + vo
    fade_n = int(0.8 * SR); mix[-fade_n:] *= np.linspace(1, 0, fade_n)[:, None]
    return (0.95 * np.tanh(mix / 0.95)).astype(np.float32)  # 软削波，峰值不会超过 0.95


def main():
    out = os.path.join(ROOT, "juiz_promo_ai.mp4")
    wav = os.path.join(ROOT, "juiz_promo_ai_audio.wav")
    sf.write(wav, build_audio(), SR)
    if "--audio-only" in sys.argv: print("audio", wav); return
    proc = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                             "-i", wav, "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                             "-movflags", "+faststart", "-shortest", out], stdin=subprocess.PIPE)
    k = 0
    for im, i in frames():
        tt = k / FPS
        if tt >= TOTAL: break
        proc.stdin.write(compose_frame(im, i, tt).tobytes()); k += 1
        if k % 150 == 0: print(f"{k}/{int(TOTAL * FPS)}", flush=True)
    proc.stdin.close(); proc.wait()
    print("done", out, round(k / FPS, 1), "s")


if __name__ == "__main__":
    main()
