"""漫画对话框（兼作字幕）：style=0 是老式美漫，style=1 是日漫，中间连续过渡。

美漫：黄底旁白框 + 硬投影、米白对话气泡、粗描边、弯尾巴、手写字、吼叫用爆炸框、小声用虚线框。
日漫：纯白、细描边、直楔形尾巴、竖排（从右往左）、旁白用宋体、可爱台词用蓬蓬框。
"""
import glob, math, os
import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

SS = 2  # 超采样倍数，边缘抗锯齿
FALLBACK = "/System/Library/Fonts/STHeiti Medium.ttc"


def _find(name):
    for root in ["/System/Library/AssetsV2", "/Library/Fonts", os.path.expanduser("~/Library/Fonts")]:
        hits = sorted(glob.glob(f"{root}/**/{name}", recursive=True))
        if hits: return hits[0]
    return None


FACES = {  # (文件, ttc 序号)
    "comic": (_find("Hanzipen.ttc"), 2),   # 手写体，美漫字
    "round": (_find("Yuanti.ttc"), 2),     # 圆体粗，过渡段与日漫对白
    "song": (_find("SimSong.ttc"), 0),     # 宋体，日漫旁白
}
_fonts = {}


def font(face, size):
    key = (face, size)
    if key not in _fonts:
        path, idx = FACES.get(face, (None, 0))
        _fonts[key] = ImageFont.truetype(path, size, index=idx) if path else ImageFont.truetype(FALLBACK, size)
    return _fonts[key]


VERT = {"，": "︐", "、": "︑", "。": "︒", "：": "︓", "；": "︔", "！": "︕", "？": "︖", "…": "︙",
        "—": "︱", "（": "︵", "）": "︶", "「": "﹁", "」": "﹂", "“": "﹁", "”": "﹂"}


def lerp(a, b, t):
    if isinstance(a, tuple): return tuple(int(x + (y - x) * t) for x, y in zip(a, b))
    return a + (b - a) * t


# ---------------- 排字 ----------------

def _cells(col):
    """竖排：把一列拆成格子。两个以内的英文数字横着挤进一格（纵中横），更长的整段旋转。"""
    out, i = [], 0
    while i < len(col):
        ch = col[i]
        if ch.isascii() and (ch.isalnum() or ch in "#@%&"):
            j = i
            while j < len(col) and col[j].isascii() and (col[j].isalnum() or col[j] in "#@%&"): j += 1
            run = col[i:j]
            out.append(("tcy" if len(run) <= 2 else "rot", run)); i = j
        elif ch == " ":
            i += 1
        else:
            out.append(("ch", VERT.get(ch, ch))); i += 1
    return out


def layout(text, f, size, vertical, maxlen, align):
    """返回 (宽, 高, 画字函数)。text 里的换行是作者指定的断句；超长再自动折。"""
    tmp = ImageDraw.Draw(Image.new("L", (8, 8)))
    if not vertical:
        lines = []
        for para in text.split("\n"):
            cur = ""
            for ch in para:
                if maxlen and tmp.textlength(cur + ch, font=f) > maxlen: lines.append(cur); cur = ch
                else: cur += ch
            lines.append(cur)
        lh = int(size * 1.32)
        widths = [tmp.textlength(l, font=f) for l in lines]
        w, h = int(max(widths)), lh * (len(lines) - 1) + size

        def draw(img, x0, y0, fill, stroke=0, stroke_fill=None):
            d = ImageDraw.Draw(img)
            for k, (l, lw) in enumerate(zip(lines, widths)):
                x = x0 + (w - lw) / 2 if align == "center" else x0
                d.text((x, y0 + k * lh - size * 0.08), l, font=f, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill)
        return w, h, draw

    cols = []
    for para in text.split("\n"):
        cells = _cells(para)
        n = maxlen or 99
        for k in range(0, max(1, len(cells)), n): cols.append(cells[k:k + n])
    pitch, step = int(size * 1.42), size * 1.04

    def col_len(cells):
        L = 0
        for kind, s in cells:
            L += step * (max(1, math.ceil(tmp.textlength(s, font=f) / size)) if kind == "rot" else 1)
        return L
    w = pitch * (len(cols) - 1) + size
    h = int(max(col_len(c) for c in cols))

    def draw(img, x0, y0, fill, stroke=0, stroke_fill=None):
        d = ImageDraw.Draw(img)
        for ci, cells in enumerate(cols):
            x = x0 + w - size - ci * pitch
            y = y0
            for kind, s in cells:
                if kind == "ch":
                    d.text((x + size / 2, y + size / 2), s, font=f, fill=fill, anchor="mm", stroke_width=stroke, stroke_fill=stroke_fill)
                    y += step
                elif kind == "tcy":
                    ff = f if len(s) == 1 else font_like(f, int(size * 0.78))
                    d.text((x + size / 2, y + size / 2), s, font=ff, fill=fill, anchor="mm")
                    y += step
                else:
                    tw = int(tmp.textlength(s, font=f)) + 4
                    lay = Image.new("RGBA", (tw, size + 8), (0, 0, 0, 0))
                    ImageDraw.Draw(lay).text((2, size / 2 + 4), s, font=f, fill=fill, anchor="lm")
                    lay = lay.rotate(-90, expand=True)
                    img.alpha_composite(lay, (int(x + (size - lay.width) / 2), int(y)))
                    y += step * max(1, math.ceil(tw / size))
    return w, h, draw


def font_like(f, size):
    return ImageFont.truetype(f.path, size, index=f.index)


# ---------------- 形状 ----------------

def _bezier(p0, p1, p2, n=16):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in np.linspace(0, 1, n)]


def _tail(cx, cy, rx, ry, tip, width, curve, zigzag=False):
    """尾巴：从椭圆边上两点收到尖。美漫弯，日漫直，吼叫是闪电形。"""
    tx, ty = tip
    th = math.atan2((ty - cy) / ry, (tx - cx) / rx)
    circ = math.pi * (rx + ry)
    d = width / circ * math.pi
    b1 = (cx + rx * 0.86 * math.cos(th - d), cy + ry * 0.86 * math.sin(th - d))
    b2 = (cx + rx * 0.86 * math.cos(th + d), cy + ry * 0.86 * math.sin(th + d))
    if zigzag:
        mx, my = (b1[0] + b2[0]) / 2, (b1[1] + b2[1]) / 2
        vx, vy = tx - mx, ty - my; L = math.hypot(vx, vy) or 1
        px, py = -vy / L, vx / L
        k1 = (mx + vx * 0.45 + px * width * 0.9, my + vy * 0.45 + py * width * 0.9)
        k2 = (mx + vx * 0.55 - px * width * 0.2, my + vy * 0.55 - py * width * 0.2)
        return [b1, k1, (tx, ty), k2, b2]
    mid = ((b1[0] + b2[0]) / 2, (b1[1] + b2[1]) / 2)
    vx, vy = tx - mid[0], ty - mid[1]; L = math.hypot(vx, vy) or 1
    px, py = -vy / L * curve * L, vx / L * curve * L
    c1 = ((b1[0] + tx) / 2 + px, (b1[1] + ty) / 2 + py)
    c2 = ((b2[0] + tx) / 2 + px * 0.6, (b2[1] + ty) / 2 + py * 0.6)
    return _bezier(b1, c1, (tx, ty)) + _bezier((tx, ty), c2, b2)[1:]


_cache = {}


def render(text, kind="speech", style=0.0, size=48, tail=None, maxlen=None, vertical=None, seed=3):
    """画一个对话框。kind: speech / shout / whisper / cute / caption / silent。
    tail：尾巴尖相对对话框中心的偏移（像素）。返回 (RGBA 图, 对话框中心在图中的位置)。"""
    key = (text, kind, round(style, 3), size, tail, maxlen, vertical, seed)
    if key in _cache: return _cache[key]
    s = max(0.0, min(1.0, style))
    manga = s >= 0.75
    if vertical is None: vertical = manga and kind in ("speech", "cute", "caption", "whisper")
    face = "comic" if s < 0.3 else ("song" if (manga and kind == "caption") else "round")
    fs = size * SS
    f = font(face, fs)
    align = "left" if (kind == "caption" and not vertical) else "center"
    tw, th, draw_text = layout(text, f, fs, vertical, None if not maxlen else (maxlen if vertical else maxlen * SS), align)

    lw = {"caption": lerp(5.0, 2.2, s), "shout": 7.0, "cute": 3.2}.get(kind, lerp(6.0, 2.6, s)) * SS
    pad = size * 0.55 * SS
    shadow = (1 - min(1, s * 2)) * 11 * SS if kind in ("caption", "shout", "speech", "silent") else 0
    if kind == "caption":
        fill = lerp((243, 212, 104), (255, 255, 255), min(1, s * 1.3))
    else:
        fill = lerp((250, 245, 230), (255, 255, 255), s)
    ink = (14, 14, 18)
    txt = (150, 18, 22) if kind == "shout" else ((60, 60, 70) if kind == "whisper" else (18, 16, 16))

    if kind == "caption":
        rx, ry = tw / 2 + pad, th / 2 + pad * 0.8
    elif kind == "shout":
        rx, ry = tw / 2 * 1.42 + pad, th / 2 * 1.5 + pad
    elif kind == "cute":
        rx, ry = tw / 2 * 1.28 + pad, th / 2 * 1.22 + pad
    else:
        rx, ry = tw / 2 * 1.3 + pad, th / 2 * 1.3 + pad
    tip = (tail[0] * SS, tail[1] * SS) if tail else None
    ext_x = max(rx * 1.2, abs(tip[0]) if tip else 0); ext_y = max(ry * 1.2, abs(tip[1]) if tip else 0)
    margin = lw * 2 + shadow + 12 * SS
    Wc, Hc = int(ext_x * 2 + margin * 2), int(ext_y * 2 + margin * 2)
    cx, cy = Wc / 2, Hc / 2
    M = Image.new("L", (Wc, Hc), 0); d = ImageDraw.Draw(M)
    rng = np.random.default_rng(seed)

    if kind == "caption":
        r = int(lerp(0, size * 0.35 * SS, math.sin(math.pi * min(1, s * 1.4))))
        d.rounded_rectangle([cx - rx, cy - ry, cx + rx, cy + ry], radius=r, fill=255)
    elif kind == "shout":
        n = 24; pts = []
        for i in range(n * 2):
            a = 2 * math.pi * i / (n * 2) + rng.uniform(-0.05, 0.05)
            k = (1.13 + rng.uniform(-0.05, 0.08)) if i % 2 == 0 else (0.88 + rng.uniform(-0.03, 0.02))
            pts.append((cx + rx * k * math.cos(a), cy + ry * k * math.sin(a)))
        d.polygon(pts, fill=255)
        if tip: d.polygon(_tail(cx, cy, rx * 0.9, ry * 0.9, (cx + tip[0], cy + tip[1]), size * 1.3 * SS, 0, zigzag=True), fill=255)
    elif kind == "cute":
        er, eq = rx * 0.9, ry * 0.9
        d.ellipse([cx - er, cy - eq, cx + er, cy + eq], fill=255)
        per = math.pi * (3 * (er + eq) - math.sqrt((3 * er + eq) * (er + 3 * eq)))
        n = max(10, int(per / (size * 1.05 * SS)))
        br = size * 0.62 * SS
        for i in range(n):
            a = 2 * math.pi * i / n
            x, y = cx + er * math.cos(a), cy + eq * math.sin(a)
            d.ellipse([x - br, y - br, x + br, y + br], fill=255)
        if tip: d.polygon(_tail(cx, cy, er, eq, (cx + tip[0], cy + tip[1]), size * 0.7 * SS, 0.0), fill=255)
    else:
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=255)
        if tip:
            width = lerp(1.25, 0.7, s) * size * SS
            d.polygon(_tail(cx, cy, rx, ry, (cx + tip[0], cy + tip[1]), width, lerp(0.22, 0.0, s)), fill=255)

    # 描边 = 膨胀后的形状 − 形状
    sigma = lw / 1.86
    dil = M.filter(ImageFilter.GaussianBlur(sigma)).point(lambda v: 255 if v > 8 else 0)
    O = ImageChops.subtract(dil, M)
    if kind == "whisper":  # 虚线画在框内侧：暗背景上也看得见断口
        ero = M.filter(ImageFilter.GaussianBlur(sigma)).point(lambda v: 255 if v > 247 else 0)
        O = ImageChops.subtract(M, ero)
        yy, xx = np.mgrid[0:Hc, 0:Wc]
        ang = np.arctan2((yy - cy) / ry, (xx - cx) / rx)
        dash = ((ang / (2 * math.pi) * 36) % 1.0 < 0.58).astype(np.uint8) * 255
        O = ImageChops.multiply(O, Image.fromarray(dash, "L"))
    layer = Image.new("RGBA", (Wc, Hc), (0, 0, 0, 0))
    if shadow > 0:
        sh = Image.new("L", (Wc, Hc), 0); sh.paste(dil, (int(shadow), int(shadow * 1.15)))
        layer.paste((0, 0, 0, 230), (0, 0), sh)
    if kind == "whisper":
        layer.paste(fill + (255,), (0, 0), M)
        layer.paste(ink + (255,), (0, 0), O)
    else:
        layer.paste(ink + (255,), (0, 0), O)
        layer.paste(fill + (255,), (0, 0), M)
    draw_text(layer, cx - tw / 2, cy - th / 2, txt + (255,))
    out = layer.resize((Wc // SS, Hc // SS), Image.LANCZOS)
    center = (cx / SS, cy / SS)
    if kind in ("caption", "shout") and s < 0.5:  # 美漫：框子歪一点，更有手作感
        ang = rng.uniform(-2.2, 2.2) * (1 - s * 2)
        out = out.rotate(ang, resample=Image.BICUBIC, expand=True)
        center = (out.width / 2, out.height / 2)
    _cache[key] = (out, center)
    return out, center


def sfx(text, size=110, style=0.0, angle=-8, fill=(246, 206, 64), stroke=(12, 12, 14), red=(170, 24, 28)):
    """拟声字：美漫是黄字粗黑边加一层红色错位。"""
    key = ("sfx", text, size, style, angle)
    if key in _cache: return _cache[key]
    f = font("comic" if style < 0.5 else "round", size * SS)
    tmp = ImageDraw.Draw(Image.new("L", (8, 8)))
    w = int(tmp.textlength(text, font=f)) + 60 * SS; h = int(size * SS * 1.6)
    lay = Image.new("RGBA", (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    d.text((30 * SS + 8 * SS, h / 2 + 8 * SS), text, font=f, fill=red + (255,), anchor="lm", stroke_width=9 * SS, stroke_fill=red + (255,))
    d.text((30 * SS, h / 2), text, font=f, fill=fill + (255,), anchor="lm", stroke_width=8 * SS, stroke_fill=stroke + (255,))
    lay = lay.resize((w // SS, h // SS), Image.LANCZOS).rotate(angle, resample=Image.BICUBIC, expand=True)
    _cache[key] = (lay, (lay.width / 2, lay.height / 2))
    return _cache[key]


def pop_scale(t, dur=0.22):
    """弹出：0.55 → 1.08 → 1.0。"""
    if t >= dur: return 1.0
    x = max(0.0, t / dur); c = 1.9
    return 0.55 + 0.45 * (1 + (c + 1) * (x - 1) ** 3 + c * (x - 1) ** 2)


def place(base, bub, at, scale=1.0, alpha=1.0, offset=(0, 0)):
    """把对话框贴到画面上：at 是对话框中心的画面坐标。"""
    img, (ox, oy) = bub
    if scale != 1.0:
        img = img.resize((max(1, int(img.width * scale)), max(1, int(img.height * scale))), Image.BILINEAR)
        ox, oy = ox * scale, oy * scale
    if alpha < 1.0:
        img = img.copy(); img.putalpha(img.getchannel("A").point(lambda v: int(v * alpha)))
    b = base.convert("RGBA")
    x, y = int(at[0] - ox + offset[0]), int(at[1] - oy + offset[1])
    b.alpha_composite(img, (max(0, x), max(0, y)), (max(0, -x), max(0, -y)))
    return b.convert("RGB")
