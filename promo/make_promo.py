"""Juiz 宣传片合成器：分镜图 + 配音 + 漫画对话框字幕 + 程序生成的配乐与音效 → promo/juiz_promo.mp4

对话框就是字幕：开头是老式美漫（黄底旁白框、爆炸框、手写字），随着世界变亮，逐渐变成日漫（纯白细线、竖排、宋体旁白）。
伙伴 Juiz 的对话框从一出场就是日漫式的，和黑白美漫世界形成反差。

依赖：Pillow、numpy、scipy、soundfile；ffmpeg 在 PATH 中。
用法：python make_promo.py            渲染整片
      python make_promo.py --preview  每个对话框出现时各截一帧，检查位置
"""
import json, math, os, subprocess, sys
import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageEnhance, ImageOps

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
import bubbles as B

J = os.path.dirname(ROOT)
W, H, FPS = 1920, 1080, 30
SR = 48000
FONT = "/System/Library/Fonts/STHeiti Medium.ttc"
FONT_LIGHT = "/System/Library/Fonts/STHeiti Light.ttc"
MONO = "/System/Library/Fonts/Menlo.ttc"


def font(size, light=False):
    return ImageFont.truetype(FONT_LIGHT if light else FONT, size)


def ease(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


# ---------------- 配音 ----------------

SPEC = json.load(open(os.path.join(ROOT, "lines.json")))
voice = {}
for line in SPEC["lines"]:
    a, sr = sf.read(os.path.join(ROOT, "voice", line["id"] + ".wav"), dtype="float32")
    if a.ndim > 1: a = a.mean(axis=1)
    idx = np.arange(0, len(a), sr / SR)
    voice[line["id"]] = (np.interp(idx, np.arange(len(a)), a).astype(np.float32), line["text"])


def dur(vid): return len(voice[vid][0]) / SR


def frac(parts, k):
    """第 k 段在整句里开始的位置（按字数估算）。"""
    n = [len(p.replace("\n", "")) for p in parts]
    return sum(n[:k]) / max(1, sum(n))


# ---------------- 对话框（字幕） ----------------
# 每段一个框：center 是框的中心，tail 是尾巴尖（画面坐标），None 表示没有尾巴。
BUB = [
    dict(vid="nar_1", parts=["每天，都有这样一通电话。"], kind="caption", style=0.0, at=[(W / 2, H / 2 - 20)], tail=[None], size=66),
    dict(vid="boss_1", parts=["这点事都做不好？！", "明天早上之前，\n给我重做！"], kind="shout", style=0.0,
         at=[(1390, 215), (1340, 610)], tail=[(1010, 300), (1060, 470)], size=[64, 58]),
    dict(vid="woman_1", parts=["好的……好的，\n我马上改……"], kind="whisper", style=0.0, at=[(360, 200)], tail=[(640, 360)], size=44, shake=True),
    dict(vid="nar_2", parts=["而这样的人，\n不止你一个。"], kind="caption", style=0.0, at=[(300, 118)], tail=[None], size=50),
    dict(vid="juiz_1", parts=["别怕，\n我来帮你！"], kind="cute", style=1.0, at=[(1330, 330)], tail=[(1070, 330)], size=52),
    dict(vid="nar_3", parts=["难听的话，Juiz 替你挡住。", "你只看要点。"], kind="caption", style=0.3, at=[(520, 980), (330, 980)], tail=[None, None], size=46),
    dict(vid="nar_3b", parts=["想怎么回，你来挑；", "Juiz 替你说出口。"], kind="caption", style=0.45, at=[(330, 110), (1560, 980)], tail=[None, None], size=46),
    dict(vid="juiz_2", parts=["您好，\n我是她的AI助理。\n文件已经发到\n您登记的邮箱了。"], kind="speech", style=1.0, at=[(1660, 262)], tail=[(1560, 480)], size=35),
    dict(vid="nar_4", parts=["深夜的电话，交给 Juiz。"], kind="caption", style=0.55, at=[(400, 975)], tail=[None], size=46),
    dict(vid="nar_5", parts=["活交给大模型去做，", "你只需要最后，\n点一下批准。"], kind="caption", style=[0.62, 0.7], at=[(360, 980), (1560, 170)], tail=[None, None], size=46),
    dict(vid="nar_6", parts=["从今天起，\n负面情绪，\n挡在门外。"], kind="caption", style=0.9, at=[(1780, 300)], tail=[None], size=46),
    dict(vid="nar_6b", parts=["每一个像她一样的人，\n都值得被温柔以待。"], kind="caption", style=1.0, at=[(118, 330)], tail=[None], size=40),
    dict(vid="juiz_3", parts=["早上好！\n今天也一起加油吧！"], kind="cute", style=1.0, at=[(185, 360)], tail=[(370, 240)], size=42),
    dict(vid="nar_7", parts=["Juiz。\n你的私人助理。"], kind="caption", style=1.0, at=[(1790, 540)], tail=[None], size=44),
]

# ---------------- 时间轴 ----------------

T, ORDER = {}, []
t = 0.0


def scene(name, length):
    global t
    T[name] = (t, t + length); ORDER.append(name); t += length


cue = {}
scene("title", 5.6); cue["nar_1"] = T["title"][0] + 0.8
scene("boss", max(7.0, dur("boss_1") + 2.5)); cue["boss_1"] = T["boss"][0] + 1.0
scene("woman", max(8.0, dur("woman_1") + 4.0)); cue["woman_1"] = T["woman"][0] + 1.2
scene("grid", max(7.0, dur("nar_2") + 3.0)); cue["nar_2"] = T["grid"][0] + 1.5
scene("glow", max(7.5, dur("juiz_1") + 4.5)); cue["juiz_1"] = T["glow"][0] + 2.8
FLASH = cue["juiz_1"] - 0.5
# 情绪滤网：前半段是护盾挡下脏话（m6b），“你只看要点”那一刻切到松一口气的画面（m6）
b3 = BUB[5]
shield_a = max(4.5, 1.0 + dur("nar_3") * frac(b3["parts"], 1))
scene("shield", shield_a); cue["nar_3"] = T["shield"][1] - dur("nar_3") * frac(b3["parts"], 1)
scene("relief", max(5.0, dur("nar_3") * (1 - frac(b3["parts"], 1)) + 3.5))
scene("reply", max(6.5, dur("nar_3b") + 2.8)); cue["nar_3b"] = T["reply"][0] + 0.8
scene("night", max(10.0, dur("juiz_2") + dur("nar_4") + 3.2)); cue["juiz_2"] = T["night"][0] + 1.2; cue["nar_4"] = cue["juiz_2"] + dur("juiz_2") + 0.5
b5 = BUB[9]
hand_a = max(5.0, 1.2 + dur("nar_5") * frac(b5["parts"], 1))
scene("handoff", hand_a); cue["nar_5"] = T["handoff"][1] - dur("nar_5") * frac(b5["parts"], 1)
scene("approve", max(5.0, dur("nar_5") * (1 - frac(b5["parts"], 1)) + 3.0))
scene("portal", 5.0)
scene("field", max(8.0, dur("nar_6") + 4.5)); cue["nar_6"] = T["field"][0] + 1.6
scene("crowd", max(7.0, dur("nar_6b") + 3.0)); cue["nar_6b"] = T["crowd"][0] + 1.2
scene("arrive", max(6.0, dur("juiz_3") + 3.5)); cue["juiz_3"] = T["arrive"][0] + 1.0
scene("end", max(8.0, dur("nar_7") + 4.5)); cue["nar_7"] = T["end"][0] + 1.6
TOTAL = t
RINGS = [T["title"][0] + 3.6, T["title"][0] + 4.6]
CROSSFADE = {"relief": 0.35, "night": 0.5, "approve": 0.5, "crowd": 0.5, "arrive": 0.5, "end": 0.8}


def scene_at(tt):
    for name in ORDER:
        a, b = T[name]
        if a <= tt < b: return name
    return ORDER[-1]


# 每个框什么时候出现、消失
SHOW = []  # (开始, 结束, bubble 参数)
for b in BUB:
    c, d = cue[b["vid"]], dur(b["vid"])
    for k, text in enumerate(b["parts"]):
        start = c + d * frac(b["parts"], k)
        end = min(c + d + 0.8, T[scene_at(start + 0.01)][1] - 0.04)
        pick = lambda v: v[k] if isinstance(v, list) else v
        SHOW.append((start, end, dict(text=text, kind=b["kind"], style=pick(b["style"]), at=b["at"][k], tail=b["tail"][k],
                                       size=pick(b["size"]), shake=b.get("shake", False))))


def draw_bubbles(im, tt):
    for start, end, p in SHOW:
        if not (start <= tt < end): continue
        cx, cy = p["at"]
        rel = None if p["tail"] is None else (int(p["tail"][0] - cx), int(p["tail"][1] - cy))
        bub = B.render(p["text"], p["kind"], p["style"], p["size"], tail=rel)
        age, left = tt - start, end - tt
        alpha = min(1.0, left / 0.2)
        if p["style"] < 0.75:
            scale = B.pop_scale(age); off = (0, 0)
        else:  # 日漫：淡入并轻轻上浮
            scale = 1.0; alpha *= min(1.0, age / 0.28); off = (0, int(14 * (1 - ease(age / 0.35))))
        if p["shake"]:
            off = (off[0] + int(3 * math.sin(tt * 41)), off[1] + int(2 * math.cos(tt * 37)))
        im = B.place(im, bub, (cx, cy), scale=scale, alpha=alpha, offset=off)
    return im


# ---------------- 图像 ----------------

def art(name):
    for ext in (".jpg", ".png"):
        p = os.path.join(ROOT, "art", name + ext)
        if os.path.exists(p): return p
    return os.path.join(ROOT, "art", name + ".jpg")


def load(name, scale=1.3):
    im = Image.open(art(name)).convert("RGB")
    return ImageOps.fit(im, (int(W * scale), int(H * scale)), Image.LANCZOS)


def pick(new, old):
    return new if os.path.exists(art(new)) else old


SHOTS = {"boss": "n1_boss", "woman": "n2_woman", "cry": "n3_cry", "glow0": "n5_glow",
         "glow": pick("m5_glow", "n5_glow"), "shield": pick("m6b_shield", "n6_relief"), "relief": pick("m6_relief", "n6_relief"),
         "reply": pick("m6c_reply", "n6_relief"), "night": pick("m7_night", "n7_night"), "handoff": pick("m7b_handoff", "n7_night"),
         "approve": pick("m7c_approve", "n7_night"), "portal": pick("m7d_portal", "n8_field"), "field": pick("m8_field", "n8_field"),
         "crowd": pick("m11_grid", "n4_grid"), "arrive": pick("m9_arrive", "n9_arrive"), "end": pick("m10_key", "n8_field")}
BIG = {k: load(v) for k, v in SHOTS.items()}
GRID0 = ImageOps.fit(Image.open(art("n4_grid")).convert("RGB"), (W, H), Image.LANCZOS)
MARK = Image.open(os.path.join(J, "art", "icon_fg.png")).convert("RGBA")
MARK = MARK.crop(MARK.getbbox()); MARK = MARK.resize((int(MARK.width * 150 / MARK.height), 150), Image.LANCZOS)


def center_panel(g):
    """找出九格漫画的白色格线，返回正中那一格的 (x0, y0, x1, y1)。"""
    a = np.asarray(g.convert("L"), dtype=np.float32)

    def gutters(profile, n):
        idx = np.where(profile > 200)[0]
        groups, cur = [], []
        for i in idx:
            if cur and i != cur[-1] + 1: groups.append(cur); cur = []
            cur.append(i)
        if cur: groups.append(cur)
        return [int(np.mean(gp)) for gp in groups if 40 < np.mean(gp) < n - 40]
    cols = gutters(a.mean(axis=0), W); rows = gutters(a.mean(axis=1), H)
    if len(cols) >= 2 and len(rows) >= 2: return cols[0] + 8, rows[0] + 8, cols[1] - 8, rows[1] - 8
    return W // 3 + 8, H // 3 + 8, 2 * W // 3 - 8, 2 * H // 3 - 8


def grid_with_heroine():
    """把女主角放进黑白九宫格正中那一格：镜头从她拉远，才发现每一格都是同样的人。"""
    g = GRID0.copy()
    x0, y0, x1, y1 = center_panel(g)
    her = ImageOps.fit(Image.open(art("n2_woman")).convert("RGB"), (x1 - x0, y1 - y0), Image.LANCZOS, centering=(0.45, 0.4))
    g.paste(her, (x0, y0))
    return g, ((x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0), (y1 - y0))


def pullback(g, center, p):
    """从正中一格拉远到整页。"""
    cx, cy, pw, ph = center
    q = ease(min(1.0, p / 0.75))
    z0 = min(W / pw, H / ph) * 0.98
    z = z0 + (1.0 - z0) * q
    ccx = cx + (W / 2 - cx) * q; ccy = cy + (H / 2 - cy) * q
    cw, ch = W / z, H / z
    box = [ccx - cw / 2, ccy - ch / 2, ccx + cw / 2, ccy + ch / 2]
    box[0] = max(0, min(W - cw, box[0])); box[1] = max(0, min(H - ch, box[1])); box[2] = box[0] + cw; box[3] = box[1] + ch
    return g.crop(tuple(int(v) for v in box)).resize((W, H), Image.BILINEAR)


def halftone_layer():
    im = Image.new("L", (W, H), 0); d = ImageDraw.Draw(im)
    for y in range(0, H, 9):
        for x in range((y // 9 % 2) * 4, W, 9):
            d.ellipse([x, y, x + 2, y + 2], fill=40)
    return im


HALFTONE = halftone_layer()
GRID, GRID_CENTER = grid_with_heroine()
CROWD = ImageOps.fit(Image.open(art(SHOTS["crowd"])).convert("RGB"), (W, H), Image.LANCZOS)
_c = center_panel(CROWD); CROWD_CENTER = ((_c[0] + _c[2]) / 2, (_c[1] + _c[3]) / 2, _c[2] - _c[0], _c[3] - _c[1])


def kenburns(img_big, p, z0, z1, cx0=0.5, cy0=0.5, cx1=0.5, cy1=0.5):
    z = z0 + (z1 - z0) * ease(p)
    bw, bh = img_big.size
    cw, ch = bw / z / 1.3, bh / z / 1.3
    cx = (cx0 + (cx1 - cx0) * ease(p)) * bw; cy = (cy0 + (cy1 - cy0) * ease(p)) * bh
    box = [cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2]
    box[0] = max(0, min(bw - cw, box[0])); box[1] = max(0, min(bh - ch, box[1]))
    box[2] = box[0] + cw; box[3] = box[1] + ch
    return img_big.crop(tuple(int(v) for v in box)).resize((W, H), Image.BILINEAR)


def vignette(im, strength=0.55):
    mask = Image.new("L", (W, H), 0); d = ImageDraw.Draw(mask)
    d.ellipse([-W * 0.15, -H * 0.25, W * 1.15, H * 1.25], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(160))
    dark = Image.new("RGB", (W, H), (5, 7, 12))
    return Image.composite(im, Image.blend(im, dark, strength), mask)


def rain(im, t, amount=140, color=(200, 210, 230), alpha=70):
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    rng = np.random.default_rng(7)
    xs = rng.uniform(0, W, amount); spd = rng.uniform(900, 1500, amount); off = rng.uniform(0, H, amount); ln = rng.uniform(25, 60, amount)
    for x, s, o, l in zip(xs, spd, off, ln):
        y = (o + s * t) % (H + 80) - 40
        d.line([(x, y), (x - 6, y + l)], fill=color + (alpha,), width=2)
    base = im.convert("RGBA"); base.alpha_composite(ov); return base.convert("RGB")


def add_halftone(im, amt=1.0):
    return Image.blend(im, Image.composite(Image.new("RGB", (W, H), (0, 0, 0)), im, HALFTONE.point(lambda v: int(v * amt))), 0.35)


def glow(im, cx, cy, r, color=(92, 225, 230), a=0.8):
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    for k in range(10, 0, -1):
        rr = r * k / 10; d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=color + (int(18 * a * (11 - k) / 10),))
    base = im.convert("RGBA"); base.alpha_composite(ov.filter(ImageFilter.GaussianBlur(20))); return base.convert("RGB")


def motes(im, tt, n=40, color=(255, 250, 220), a=120, speed=60, size=5):
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    for i in range(n):
        x = (i * 97 + tt * speed * (0.6 + (i % 5) * 0.2)) % W; y = (i * 53 + math.sin(tt * 0.8 + i) * 40 - tt * 12 * (i % 3)) % H
        s = size * (0.6 + (i % 4) * 0.3)
        d.ellipse([x, y, x + s, y + s], fill=color + (a,))
    base = im.convert("RGBA"); base.alpha_composite(ov.filter(ImageFilter.GaussianBlur(1.2))); return base.convert("RGB")


def scraps(im, tt, t0):
    """撕开纸页时飞出的纸屑。"""
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    rng = np.random.default_rng(11)
    k = max(0.0, tt - t0)
    for i in range(46):
        x0, y0 = W / 2 + rng.uniform(-60, 60), rng.uniform(100, H - 100)
        vx, vy = rng.uniform(-520, 520), rng.uniform(-260, 120)
        x, y = x0 + vx * k, y0 + vy * k + 180 * k * k
        r = rng.uniform(8, 22); a0 = rng.uniform(0, 6.28) + k * rng.uniform(-6, 6)
        pts = [(x + r * math.cos(a0 + j * 2.1), y + r * 0.7 * math.sin(a0 + j * 2.1)) for j in range(3)]
        d.polygon(pts, fill=(238, 232, 214, 220) if i % 3 else (20, 20, 22, 220))
    base = im.convert("RGBA"); base.alpha_composite(ov); return base.convert("RGB")


def phone_card(caption, label, t):
    card = Image.new("RGBA", (520, 640), (0, 0, 0, 0)); d = ImageDraw.Draw(card)
    d.rounded_rectangle([0, 0, 519, 639], radius=48, fill=(10, 15, 28, 238), outline=(92, 225, 230, 255), width=4)
    d.text((44, 50), "SHIELD ACTIVE", font=ImageFont.truetype(MONO, 24), fill=(245, 184, 91))
    d.text((44, 96), label, font=font(38), fill=(230, 238, 255))
    y = 190
    d.rounded_rectangle([32, y, 487, y + 300], radius=26, fill=(23, 35, 58), outline=(34, 48, 75), width=2)
    shown = caption[: int(len(caption) * min(1, max(0, t)))]
    lines, cur = [], ""
    f = font(36)
    for ch in shown:
        if d.textlength(cur + ch, font=f) > 395: lines.append(cur); cur = ch
        else: cur += ch
    lines.append(cur)
    for i, l in enumerate(lines): d.text((58, y + 34 + i * 52), l, font=f, fill=(230, 238, 255))
    d.text((58, y + 330), "辱骂与宣泄已过滤", font=font(28, True), fill=(147, 165, 194))
    d.text((58, y + 378), "要点：时间 · 口径 · 交付物", font=font(26, True), fill=(92, 225, 230))
    return card


# ---------------- 画面 ----------------

def render_scene(name, tt):
    a, b = T[name]; p = (tt - a) / (b - a); k = tt - a
    if name == "title":
        im = Image.new("RGB", (W, H), (6, 8, 12)); im = rain(im, tt, 220, alpha=60)
        im = vignette(add_halftone(im))
        for r0 in RINGS:
            if r0 <= tt < r0 + 0.8:
                sh = (int(8 * math.sin(tt * 90)), int(6 * math.cos(tt * 70)))
                im = B.place(im, B.sfx("叮铃铃——！", 104), (W * 0.64, H * 0.78), scale=B.pop_scale(tt - r0, 0.12), offset=sh)
        return im
    if name == "boss":
        im = kenburns(BIG["boss"], p, 1.0, 1.12, 0.5, 0.45, 0.46, 0.4)
        c = cue["boss_1"]
        if c <= tt <= c + dur("boss_1"):
            s = 14 * math.exp(-(((tt - c) % 0.9) * 3))
            im = im.transform(im.size, Image.AFFINE, (1, 0, math.sin(tt * 90) * s, 0, 1, math.cos(tt * 70) * s))
        return vignette(rain(im, tt, 90, alpha=40), 0.5)
    if name == "woman":
        if p < 0.55:
            im = kenburns(BIG["woman"], p / 0.55, 1.0, 1.1, 0.5, 0.5, 0.55, 0.45)
        else:
            im = kenburns(BIG["cry"], (p - 0.55) / 0.45, 1.05, 1.18, 0.5, 0.45, 0.5, 0.42)
            if p > 0.66:  # 无声的“……”：美漫里最常见的沉默
                im = B.place(im, B.render("……", "speech", 0.0, 60, tail=(250, -60)), (820, 760), scale=B.pop_scale((p - 0.66) * (b - a)))
        return vignette(rain(im, tt, 70, alpha=35))
    if name == "grid":
        return vignette(ImageEnhance.Color(pullback(GRID, GRID_CENTER, p)).enhance(0.75), 0.4)
    if name == "glow":
        if tt < FLASH:
            im = kenburns(BIG["glow0"], p, 1.0, 1.08)
            pulse = 0.6 + 0.4 * math.sin(tt * 4)
            im = glow(im, W * 0.5, H * 0.62, 380 + 60 * pulse * (1 + 2 * ease((tt - a) / (FLASH - a))), a=0.9)
            return vignette(im, 0.35)
        im = kenburns(BIG["glow"], (tt - FLASH) / (b - FLASH), 1.04, 1.0, 0.5, 0.45, 0.5, 0.5)
        im = motes(im, tt, 24, (150, 235, 255), 150, 20, 6)
        f = 1 - ease((tt - FLASH) / 0.45)
        if f > 0: im = Image.blend(im, Image.new("RGB", (W, H), (225, 252, 255)), f * 0.9)
        return im
    if name == "shield":
        im = kenburns(BIG["shield"], p, 1.08, 1.0)
        if k < 0.6:  # 冲击
            s = 16 * (1 - k / 0.6); im = im.transform(im.size, Image.AFFINE, (1, 0, math.sin(tt * 80) * s, 0, 1, math.cos(tt * 60) * s))
        # 脏话框：先砸过来，被护盾挡下后褪色、划掉
        if k > 0.2:
            bub = B.render("你是猪脑子吗？！\n#@%&！", "shout", 0.0, 50, tail=(-150, 170), seed=5)
            blocked = ease((k - 1.3) / 0.5)
            im = B.place(im, bub, (420, 250), scale=B.pop_scale(k - 0.2), alpha=1 - 0.72 * blocked)
            if blocked > 0:
                d = ImageDraw.Draw(im, "RGBA"); L = 330 * blocked
                d.line([(420 - L, 250 + L * 0.35), (420 + L, 250 - L * 0.35)], fill=(92, 225, 230, 230), width=12)
        return im
    if name == "relief":
        im = kenburns(BIG["relief"], p, 1.0, 1.04)
        q = ease(k / 0.5)
        card = phone_card("明早十点前，按财务系统的口径重做报表。", "来电 · 王总", (k - 0.4) / 1.6)
        card = card.resize((int(card.width * 0.82), int(card.height * 0.82)), Image.LANCZOS)
        base = im.convert("RGBA"); base.alpha_composite(card, (int(W - card.width - 50 + (1 - q) * 600), 40)); return base.convert("RGB")
    if name == "reply":
        return kenburns(BIG["reply"], p, 1.0, 1.07, 0.5, 0.5, 0.52, 0.48)
    if name == "night":
        im = kenburns(BIG["night"], p, 1.0, 1.05, 0.5, 0.5, 0.53, 0.5)
        d = ImageDraw.Draw(im); d.text((70, 60), "02:30", font=ImageFont.truetype(MONO, 72), fill=(166, 200, 255))
        return im
    if name == "handoff":
        im = kenburns(BIG["handoff"], p, 1.12, 1.0, 0.45, 0.6, 0.5, 0.5)
        return motes(im, tt, 30, (190, 225, 255), 130, 25, 5)
    if name == "approve":
        return motes(kenburns(BIG["approve"], p, 1.0, 1.06), tt, 18, (255, 236, 190), 110, 15, 5)
    if name == "portal":
        im = kenburns(BIG["portal"], p, 1.0, 1.3, 0.5, 0.5, 0.52, 0.5)
        im = scraps(im, tt, a + 0.2)
        if p > 0.78: im = Image.blend(im, Image.new("RGB", (W, H), (255, 252, 238)), ease((p - 0.78) / 0.22))
        return im
    if name == "field":
        im = kenburns(BIG["field"], p, 1.1, 1.0, 0.45, 0.5, 0.52, 0.5)
        im = motes(im, tt)
        f = 1 - ease(k / 0.6)
        if f > 0: im = Image.blend(im, Image.new("RGB", (W, H), (255, 252, 238)), f)
        return im
    if name == "crowd":  # 和开头的黑白九宫格对称：同样九个人，同样拉远，这次都笑着
        return motes(pullback(CROWD, CROWD_CENTER, p), tt, 24)
    if name == "arrive":
        return motes(kenburns(BIG["arrive"], p, 1.0, 1.08, 0.45, 0.5, 0.42, 0.45), tt, 20)
    if name == "end":
        im = kenburns(BIG["end"], p, 1.0, 1.05, 0.45, 0.5, 0.42, 0.5)
        im = motes(im, tt, 30, (220, 240, 255), 110, 12, 4)
        d = ImageDraw.Draw(im)
        q = ease((p - 0.1) / 0.25)
        if q > 0:
            ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); o = ImageDraw.Draw(ov)
            o.text((1090, 330), "Juiz", font=font(150), fill=(240, 248, 255, int(255 * q)))
            o.text((1100, 510), "Personal Assistant", font=ImageFont.truetype(MONO, 44), fill=(180, 215, 255, int(255 * q)))
            q2 = ease((p - 0.35) / 0.2)
            o.text((1100, 610), "开源 · github.com/shoal-rat/Juiz", font=font(38, True), fill=(230, 238, 255, int(255 * q2)))
            base = im.convert("RGBA"); base.alpha_composite(ov); im = base.convert("RGB")
        if p > 0.9: im = Image.blend(im, Image.new("RGB", (W, H), (0, 0, 0)), ease((p - 0.9) / 0.1))
        return im
    raise ValueError(name)


def frame(tt):
    name = scene_at(tt)
    im = render_scene(name, tt)
    cf = CROSSFADE.get(name)
    if cf and tt - T[name][0] < cf:
        prev = ORDER[ORDER.index(name) - 1]
        im = Image.blend(render_scene(prev, min(tt, T[prev][1] - 1e-3)), im, ease((tt - T[name][0]) / cf))
    return draw_bubbles(im, tt)


# ---------------- 声音 ----------------

def promo_sfx():
    """音效表：跟着分镜走。"""
    import sfx as X
    tr = X.Track(TOTAL)
    S_ = lambda k: T[k][0]
    tr.add(X.rain(FLASH + 0.3, 0.7), 0.0, 0.9)
    tr.add(X.thunder(), S_("title") + 0.2, 0.8)
    for at in RINGS: tr.add(X.ring(1)[: int(0.8 * X.SR)], at, 1.0)
    tr.add(X.receiver_click(), S_("boss") + 0.6, 0.8)
    for dt in (0.9, 3.0): tr.add(X.slam(), cue["boss_1"] + dt, 0.7, -0.2)
    tr.add(X.thunder(2.5), S_("boss") + 5.0, 0.6)
    tr.add(X.receiver_click(), S_("woman") + 0.6, 0.6)
    cry_at = S_("woman") + (T["woman"][1] - S_("woman")) * 0.55
    tr.add(X.sniff(), cry_at + 0.4, 0.8, 0.1)
    tr.add(X.drip(), cry_at + 2.0, 0.9, 0.15)
    for k in range(3): tr.add(X.heartbeat(), cry_at + 0.6 + k * 1.1, 0.5)
    tr.add(X.paper(0.5), S_("grid") + 0.1, 0.6)
    tr.add(X.many_rings(T["grid"][1] - S_("grid")), S_("grid") + 0.4, 0.8)
    tr.add(X.vibrate(), S_("glow") + 0.5, 0.9)
    tr.add(X.whoosh(0.7, up=True), FLASH - 0.65, 0.9)
    tr.add(X.sparkle(1.8), FLASH, 1.0)
    tr.add(X.chime((84, 88, 91), 0.1, 0.14), FLASH + 0.1, 0.8)
    sh = S_("shield")
    for dt in (0.0, 0.55, 1.05): tr.add(X.impact(0.7), sh + dt, 0.8, -0.3)
    tr.add(X.shatter(), sh + 0.25, 0.9, -0.2)
    tr.add(X.shield_hum(3.5), sh, 0.9)
    tr.add(X.zap(), sh + 1.3, 0.8, 0.2)
    tr.add(X.whoosh(0.4), S_("relief") + 0.1, 0.6, 0.4)
    tr.add(X.pop(900), S_("relief") + 0.5, 0.8, 0.4)
    rp = S_("reply")
    for k, f in enumerate((800, 950, 1100)): tr.add(X.pop(f), rp + 0.4 + 0.15 * k, 0.9, -0.2 + 0.2 * k)
    tr.add(X.tap(), rp + 2.0, 1.0); tr.add(X.chime((88, 93), 0.08, 0.12), rp + 2.05, 0.8)
    ng = S_("night"); nd = T["night"][1] - ng
    tr.add(X.crickets(nd), ng, 0.9); tr.add(X.clock_tick(nd), ng, 0.7, -0.4); tr.add(X.vibrate(0.9), ng + 0.5, 0.6, 0.3)
    ho = S_("handoff")
    tr.add(X.whoosh(1.0, up=True), ho + 0.2, 0.9); tr.add(X.sparkle(2.5, 10, 3200), ho + 0.8, 0.8); tr.add(X.paper(0.8), ho + 2.5, 0.5)
    ap = S_("approve"); tr.add(X.birds(T["approve"][1] - ap, 5), ap, 0.8); tr.add(X.scan_beep(), ap + 0.5, 0.9)
    pt = S_("portal"); pd = T["portal"][1] - pt
    tr.add(X.tear(0.9), pt + 0.3, 1.0); tr.add(X.wind(pd, 0.8), pt, 0.9); tr.add(X.whoosh(1.0, up=True), T["portal"][1] - 1.0, 0.9)
    fd = S_("field"); tr.add(X.birds(T["field"][1] - fd, 8), fd, 0.9); tr.add(X.wind(T["field"][1] - fd, 0.35), fd, 0.8)
    tr.add(X.birds(T["crowd"][1] - S_("crowd"), 4), S_("crowd"), 0.5); tr.add(X.sparkle(1.5, 8, 3500), S_("crowd") + 0.5, 0.6)
    ar = S_("arrive"); tr.add(X.door(), ar + 0.1, 0.7); tr.add(X.office(T["arrive"][1] - ar), ar, 0.9); tr.add(X.chime((91, 96), 0.1, 0.1), cue["juiz_3"] - 0.1, 0.7)
    tr.add(X.sparkle(2.0, 10, 3000), S_("end") + 0.8, 0.7)
    return tr


def build_audio():
    """配乐（compose.py 作曲编曲）+ 音效（sfx.py）+ 配音；说话时把音乐和音效压低。"""
    import compose
    music = compose.score(T, FLASH, TOTAL)
    n = len(music)
    vo = np.zeros((n, 2), dtype=np.float32)

    def add(buf, sig, at, gain=1.0):
        i = int(at * SR); j = min(n, i + len(sig))
        if j > i: buf[i:j] += sig[: j - i, None] * gain if sig.ndim == 1 else sig[: j - i] * gain

    fx = promo_sfx().buf[:n]
    duck = np.ones(n, dtype=np.float32)
    for vid, at in cue.items():
        v = voice[vid][0]; add(vo, v, at, 0.95)
        i = int(at * SR); j = min(n, i + len(v)); duck[max(0, i - 6000):j + 6000] = 0.5
    duck = np.convolve(duck, np.ones(9600) / 9600, mode="same").astype(np.float32)
    mpeak = np.abs(music).max()
    fxduck = np.clip(1 - (1 - duck) * 1.3, 0.3, 1)  # 说话时音效压到三成左右，别盖住人声
    mix = music / max(mpeak, 1e-6) * 0.55 * duck[:, None] + fx * fxduck[:, None] + vo
    return (0.95 * np.tanh(mix / 0.95)).astype(np.float32)  # 软削波，峰值不会超过 0.95


def main():
    out = os.path.join(ROOT, "juiz_promo.mp4")
    if "--preview" in sys.argv:
        pdir = os.path.join(ROOT, "preview"); os.makedirs(pdir, exist_ok=True)
        times = {f"{name}": (a + b) / 2 for name, (a, b) in T.items()}
        for i, (s0, e0, p) in enumerate(SHOW): times[f"bub{i:02d}"] = min(e0 - 0.05, s0 + 0.6)
        only = [a for a in sys.argv[2:] if not a.startswith("-")]
        for key, tt in sorted(times.items(), key=lambda kv: kv[1]):
            if only and not any(key.startswith(o) or scene_at(tt) == o for o in only): continue
            frame(tt).save(os.path.join(pdir, f"{tt:06.2f}_{key}_{scene_at(tt)}.jpg"), quality=80)
        print("preview written; total", round(TOTAL, 1), "s"); return
    wav = os.path.join(ROOT, "juiz_promo_audio.wav")
    sf.write(wav, build_audio(), SR)
    if "--audio-only" in sys.argv: print("audio written", wav); return
    frames = int(TOTAL * FPS)
    proc = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                             "-i", wav, "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                             "-movflags", "+faststart", "-shortest", out], stdin=subprocess.PIPE)
    for i in range(frames):
        proc.stdin.write(frame(i / FPS).tobytes())
        if i % 150 == 0: print(f"{i}/{frames}", flush=True)
    proc.stdin.close(); proc.wait()
    print("done", out, round(TOTAL, 1), "s")


if __name__ == "__main__":
    main()
