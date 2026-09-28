"""Juiz 宣传片配乐：程序化作曲 + 合成器编曲，跟着分镜时间轴走。

  黑白段    D 小调爵士：低音提琴行走、鼓刷、叮叮镲、钢琴 Charleston 伴奏、弱音小号；
            她挂电话后只剩一架钢琴；九宫格时弦乐推起来。
  觉醒      闪光那一下从 D 小调换到 D 大调，八音盒奏出 Juiz 的主题。
  功能段    B 小调轻快律动（Bm7-Gmaj7-D-A），拨弦琶音、电钢、鼓组；深夜只留垫音和八音盒。
  撕开纸页  上行噪声、弦乐渐强、军鼓滚奏，落到 D 大调强拍。
  结尾      D 大调（卡农进行），钢琴八分和弦、弦乐、钟琴完整奏出主题，最后停在 Dadd9。

所有音色都是这里合成的（加法、FM、Karplus-Strong、滤波噪声），没有采样。
"""
import math
import numpy as np
from scipy import signal

SR = 48000
RNG = np.random.default_rng(2027)


def m2f(m): return 440.0 * 2 ** ((m - 69) / 12)


def _t(n): return np.arange(n) / SR


def _lp(x, fc, order=2):
    sos = signal.butter(order, min(fc, SR * 0.45), "low", fs=SR, output="sos"); return signal.sosfilt(sos, x)


def _hp(x, fc, order=2):
    sos = signal.butter(order, fc, "high", fs=SR, output="sos"); return signal.sosfilt(sos, x)


def _bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, min(hi, SR * 0.45)], "band", fs=SR, output="sos"); return signal.sosfilt(sos, x)


def _env(n, a, r, hold=None):
    """线性起音 a 秒、到 hold 秒后 r 秒释放。"""
    t = _t(n); e = np.minimum(1.0, t / max(a, 1e-4))
    if hold is not None: e *= np.clip(1 - (t - hold) / max(r, 1e-4), 0, 1)
    return e


# ---------------- 音色 ----------------

def piano(f, dur, vel=0.7, bright=1.0):
    n = int((dur + 0.9) * SR); t = _t(n); out = np.zeros(n)
    B = 0.00035
    for k in range(1, 10):
        fk = f * k * math.sqrt(1 + B * k * k)
        if fk > 16000: break
        dec = (0.9 + 0.75 * k) * (f / 262) ** 0.35
        out += (bright ** (k - 1)) / k ** 1.1 * np.sin(2 * np.pi * fk * t + RNG.uniform(0, 6.28)) * np.exp(-t * dec)
    out *= np.minimum(1, t / 0.003)
    out *= np.clip(1 - (t - dur) / 0.25, 0, 1)  # 制音器
    ham = _lp(RNG.standard_normal(int(0.012 * SR)), 3000) * np.exp(-_t(int(0.012 * SR)) * 300)
    out[: len(ham)] += ham * 0.15
    return (out * vel * 0.35).astype(np.float32)


def epiano(f, dur, vel=0.6):
    n = int((dur + 0.8) * SR); t = _t(n)
    idx = 1.4 * np.exp(-t * 4) + 0.25
    s = np.sin(2 * np.pi * f * t + idx * np.sin(2 * np.pi * f * t))
    s += 0.12 * np.sin(2 * np.pi * f * 14 * t) * np.exp(-t * 25)
    e = np.exp(-t * 1.3) * np.minimum(1, t / 0.004) * np.clip(1 - (t - dur) / 0.3, 0, 1)
    return (s * e * vel * 0.3).astype(np.float32)


def upright(f, dur, vel=0.8):
    n = int((dur + 0.35) * SR); t = _t(n)
    ff = f * (1 + 0.03 * np.exp(-t * 40))
    ph = 2 * np.pi * np.cumsum(ff) / SR
    s = np.sin(ph) + 0.5 * np.sin(2 * ph) + 0.18 * np.sin(3 * ph) + 0.06 * np.sin(4 * ph)
    e = np.exp(-t * 2.2) * np.minimum(1, t / 0.006) * np.clip(1 - (t - dur) / 0.12, 0, 1)
    thump = _lp(RNG.standard_normal(n), 600) * np.exp(-t * 60) * 0.6
    return ((s * e + thump) * vel * 0.4).astype(np.float32)


def synbass(f, dur, vel=0.8):
    n = int((dur + 0.15) * SR); t = _t(n)
    s = sum(np.sin(2 * np.pi * f * k * t) / k for k in range(1, 7))
    s = _lp(s, 380 + 1400 * vel)
    s += 0.8 * np.sin(2 * np.pi * f / 2 * t)
    e = np.minimum(1, t / 0.005) * (0.7 + 0.3 * np.exp(-t * 8)) * np.clip(1 - (t - dur) / 0.08, 0, 1)
    return (s * e * vel * 0.28).astype(np.float32)


def celesta(f, dur, vel=0.6):
    n = int((dur + 1.6) * SR); t = _t(n)
    s = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * f * 4 * t) * np.exp(-t * 6) + 0.15 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t * 4)
    e = np.exp(-t * 2.4) * np.minimum(1, t / 0.002)
    return (s * e * vel * 0.3).astype(np.float32)


def bell(f, dur, vel=0.6):
    n = int((dur + 2.0) * SR); t = _t(n)
    idx = 3.2 * np.exp(-t * 3.5)
    s = np.sin(2 * np.pi * f * t + idx * np.sin(2 * np.pi * f * 3.5 * t))
    e = np.exp(-t * 1.5) * np.minimum(1, t / 0.002)
    return (s * e * vel * 0.26).astype(np.float32)


def strings(f, dur, vel=0.5, attack=0.35, release=0.7, bright=5000):
    n = int((dur + release) * SR); t = _t(n)
    vib = 1 + 0.004 * np.sin(2 * np.pi * 5.3 * t + RNG.uniform(0, 6)) * np.minimum(1, t / 0.6)
    out = np.zeros(n)
    for det in (-0.006, -0.002, 0.003, 0.007):
        ph = 2 * np.pi * np.cumsum(f * (1 + det) * vib) / SR + RNG.uniform(0, 6)
        for k in range(1, 14):
            if f * k > bright * 1.6: break
            out += np.sin(k * ph) / k
    out = _lp(out, bright)
    e = np.minimum(1, t / attack) * np.clip(1 - (t - dur) / release, 0, 1)
    return (out * e * vel * 0.07).astype(np.float32)


def mute_trumpet(f, dur, vel=0.6):
    n = int((dur + 0.25) * SR); t = _t(n)
    vib = 1 + 0.006 * np.sin(2 * np.pi * 5.6 * t) * np.clip((t - 0.25) / 0.3, 0, 1)
    ph = 2 * np.pi * np.cumsum(f * vib) / SR
    out = np.zeros(n)
    for k in range(1, 16):
        fk = f * k
        if fk > 9000: break
        w = math.exp(-((math.log(fk / 1600)) ** 2) / 0.35) + 0.25 / k  # 弱音器的鼻音共振峰
        out += w * np.sin(k * ph)
    breath = _bp(RNG.standard_normal(n), 1200, 4000) * 0.05
    e = np.minimum(1, t / 0.06) * np.clip(1 - (t - dur) / 0.15, 0, 1) * (0.85 + 0.15 * np.exp(-t * 3))
    return ((out * 0.16 + breath) * e * vel).astype(np.float32)


def pluck(f, dur, vel=0.6, damp=0.994):
    """Karplus–Strong 拨弦，按周期整块计算。"""
    N = max(2, int(round(SR / f))); n = int((dur + 0.5) * SR)
    y = np.zeros(n + 2 * N)
    y[:N] = _lp(RNG.uniform(-1, 1, N), 6000 * vel + 1500)
    for s in range(N, n + N, N):
        prev = y[s - N:s]; prev2 = y[s - N - 1:s - 1] if s - N - 1 >= 0 else np.concatenate(([0.0], y[s - N:s - 1]))
        y[s:s + N] = 0.5 * (prev + prev2) * damp
    y = y[:n]; t = _t(n)
    y *= np.clip(1 - (t - dur) / 0.2, 0, 1)
    return (y * vel * 0.5).astype(np.float32)


def kick(vel=0.8):
    n = int(0.45 * SR); t = _t(n)
    f = 44 + 90 * np.exp(-t * 32)
    s = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 7)
    s[: int(0.004 * SR)] += RNG.standard_normal(int(0.004 * SR)) * 0.3
    return (s * vel * 0.8).astype(np.float32)


def snare(vel=0.7):
    n = int(0.3 * SR); t = _t(n)
    s = _bp(RNG.standard_normal(n), 1500, 9000) * np.exp(-t * 20) * 0.7 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 28) * 0.5
    return (s * vel * 0.55).astype(np.float32)


def rim(vel=0.5):
    n = int(0.08 * SR); t = _t(n)
    return ((np.sin(2 * np.pi * 1700 * t) + _bp(RNG.standard_normal(n), 2000, 6000)) * np.exp(-t * 90) * vel * 0.3).astype(np.float32)


def hat(vel=0.4, open_=False):
    n = int((0.5 if open_ else 0.08) * SR); t = _t(n)
    s = _hp(RNG.standard_normal(n), 7000) * np.exp(-t * (7 if open_ else 55))
    return (s * vel * 0.3).astype(np.float32)


def ride(vel=0.4):
    n = int(1.6 * SR); t = _t(n)
    s = sum(np.sign(np.sin(2 * np.pi * fr * t)) for fr in (3150, 4270, 5390, 6580)) * 0.06
    s = _hp(s + _hp(RNG.standard_normal(n), 6000) * 0.25, 3000) * np.exp(-t * 2.6)
    s += np.sin(2 * np.pi * 5100 * t) * np.exp(-t * 14) * 0.25
    return (s * vel * 0.22).astype(np.float32)


def brush(dur, vel=0.4):
    n = int(dur * SR); t = _t(n)
    shape = np.sin(np.pi * t / dur) ** 1.5
    return (_bp(RNG.standard_normal(n), 2500, 9000) * shape * vel * 0.08).astype(np.float32)


def brush_tap(vel=0.5):
    n = int(0.2 * SR); t = _t(n)
    return (_bp(RNG.standard_normal(n), 1800, 8000) * np.exp(-t * 26) * vel * 0.3).astype(np.float32)


def crash(vel=0.6, length=3.0):
    n = int(length * SR); t = _t(n)
    s = _hp(RNG.standard_normal(n), 3500) * np.exp(-t * 1.6) + _hp(RNG.standard_normal(n), 8000) * np.exp(-t * 4) * 0.5
    return (s * vel * 0.18).astype(np.float32)


def boom(vel=0.8):
    n = int(2.0 * SR); t = _t(n)
    f = 36 + 40 * np.exp(-t * 10)
    return (np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 2.2) * vel * 0.9).astype(np.float32)


def riser(dur, vel=0.5):
    """上行噪声：几个频带的滤波噪声按时间交叉淡化，听起来像截止频率一路往上扫。"""
    n = int(dur * SR); x = _t(n) / dur
    noise = RNG.standard_normal(n); out = np.zeros(n)
    bands = [(150, 700), (400, 1500), (900, 3200), (2200, 6500), (5000, 12000)]
    for i, (lo, hi) in enumerate(bands):
        c = i / (len(bands) - 1)
        out += _bp(noise, lo, hi) * np.exp(-((x - c) ** 2) / 0.045)
    tone = np.sin(2 * np.pi * np.cumsum(200 + 700 * x ** 2) / SR) * 0.12
    return ((out * 0.5 + tone) * x ** 1.6 * vel).astype(np.float32)


def rev_cymbal(dur, vel=0.5):
    return crash(vel, dur)[::-1].copy()


def timp_roll(dur, vel=0.5, f=73.4):
    n = int(dur * SR); t = _t(n)
    s = np.sin(2 * np.pi * f * t) * (0.6 + 0.4 * np.sin(2 * np.pi * 18 * t)) + _lp(RNG.standard_normal(n), 400) * 0.5
    return (s * (t / dur) ** 1.4 * vel * 0.35).astype(np.float32)


# ---------------- 混音台 ----------------

class Mix:
    def __init__(self, total):
        self.n = int(total * SR) + 3 * SR
        self.dry = np.zeros((self.n, 2), np.float32)
        self.wet = np.zeros((self.n, 2), np.float32)

    def add(self, sig, at, gain=1.0, pan=0.0, rev=0.15):
        if at < 0: sig = sig[int(-at * SR):]; at = 0
        i = int(at * SR); j = min(self.n, i + len(sig))
        if j <= i: return
        s = sig[: j - i] * gain
        a = (pan + 1) * math.pi / 4; l, r = math.cos(a), math.sin(a)
        self.dry[i:j, 0] += s * l; self.dry[i:j, 1] += s * r
        if rev: self.wet[i:j, 0] += s * l * rev; self.wet[i:j, 1] += s * r * rev

    def render(self):
        ir_n = int(2.6 * SR); t = _t(ir_n)
        pre = int(0.022 * SR)
        out = self.dry.copy()
        for ch in range(2):
            ir = RNG.standard_normal(ir_n) * np.exp(-t * 6.9 / 2.2)
            ir = _lp(ir, 5500) * 0.6 + _lp(ir, 1800) * 0.4 * (t / 2.6)
            ir[:pre] = 0; ir /= np.sqrt(np.sum(ir ** 2))
            out[:, ch] += signal.fftconvolve(self.wet[:, ch], ir)[: self.n].astype(np.float32) * 0.9
        return out


def hum(x, ms=8):  # 人味：一点点时值偏差
    return x + RNG.uniform(-ms, ms) / 1000


# ---------------- 乐思 ----------------

THEME = [  # Juiz 主题（D 大调），(midi, 拍)
    [(74, .5), (78, .5), (81, 1), (79, .5), (78, .5), (76, 1)],
    [(74, .5), (76, .5), (78, 1), (81, 1), (83, 1)],
    [(81, 1.5), (79, .5), (78, 1), (76, 1)],
    [(78, 3), (None, 1)],
]
ANSWER = [
    [(74, .5), (78, .5), (81, 1), (79, .5), (78, .5), (76, 1)],
    [(74, .5), (76, .5), (78, 1), (83, 1), (81, 1)],
    [(79, 1), (78, 1), (76, 1), (81, 1)],
    [(76, 1.5), (78, .5), (76, 1), (73, 1)],
]
NOIR_TPT = [
    [(69, 1.5), (67, .5), (65, 1), (64, .5), (62, .5)],
    [(61, 1), (62, 2), (None, 1)],
    [(69, .5), (72, 1), (70, .5), (69, 1), (65, 1)],
    [(67, 1.5), (64, .5), (62, 2)],
]
SAD_PIANO = [  # 她挂了电话之后
    [(69, 1), (65, 1), (64, 1), (62, 1)],
    [(61, 1), (62, 1), (64, 1), (57, 1)],
    [(58, 1), (57, 1), (55, 1), (53, 1)],
    [(52, 3), (None, 1)],
]


def play_line(mx, line, t0, beat, inst, gain=1.0, pan=0.0, rev=0.25, transpose=0, vel=0.7, until=None, swing=0.0):
    t = t0
    for bar in line:
        pos = 0.0
        for m, b in bar:
            at = t + pos * beat
            if swing and abs(pos % 1 - 0.5) < 1e-6: at += swing * beat
            if m is not None and (until is None or at < until):
                d = b * beat * 0.95
                if until is not None: d = min(d, until - at)
                mx.add(inst(m2f(m + transpose), d, vel * RNG.uniform(0.9, 1.05)), hum(at), gain, pan, rev)
            pos += b
        t += 4 * beat
    return t


# ---------------- 总谱 ----------------

def score(T, FLASH, total, voice_windows=()):
    mx = Mix(total)
    S = lambda name: T[name][0]
    E = lambda name: T[name][1]

    # ===== 黑白段：D 小调爵士，80 BPM 摇摆 =====
    beat = 60 / 80; bar = 4 * beat; sw = 0.18
    prog = [("Dm9", 38), ("Gm9", 43), ("Em7b5", 40), ("Dm9", 38)]
    voic = {"Dm9": [53, 57, 60, 64], "Gm9": [58, 62, 65, 69], "Em7b5": [55, 58, 62, 64], "A7b9": [55, 58, 61, 64]}
    walk = [[38, 41, 45, 42], [43, 46, 50, 41], [40, 43, 45, 39], [38, 45, 41, 37]]
    # 雨与低鸣贯穿黑白段
    rain = _lp(RNG.standard_normal(int(FLASH * SR)), 2500) * 0.035
    rain *= np.minimum(1, _t(len(rain)) / 1.5) * np.clip((FLASH - _t(len(rain))) / 0.4, 0, 1)
    mx.add(rain.astype(np.float32), 0, 1.0, 0, 0.3)
    # 标题：低音提琴长音 + 钢琴一记 Dm9 + 弱音小号
    for k in range(3): mx.add(upright(m2f(38), bar * 0.9, 0.6), k * bar * 0.5 + 0.1, 0.9, -0.1, 0.1)
    for m in voic["Dm9"]: mx.add(piano(m2f(m), bar * 1.6, 0.35), hum(0.05), 0.8, 0.15, 0.35)
    play_line(mx, NOIR_TPT[:2], 0.75, beat, mute_trumpet, 0.55, 0.25, 0.35, vel=0.55, until=E("title"), swing=sw)
    for k in range(int(E("title") / beat)): mx.add(ride(0.18), hum(k * beat), 0.8, 0.35, 0.2)
    # 训斥：整组爵士进来
    t0, t1 = S("boss"), E("boss")
    k = 0
    while t0 + k * bar < t1:
        bt = t0 + k * bar; ch, root = prog[k % 4]
        for i, m in enumerate(walk[k % 4]):
            at = bt + i * beat
            if at < t1: mx.add(upright(m2f(m), beat * 0.9, 0.85), hum(at), 1.0, -0.1, 0.1)
        vo = voic["A7b9"] if (k % 4 == 2) else voic[ch]
        for at, d in ((bt, 1.2 * beat), (bt + (1.5 + sw) * beat, 0.6 * beat)):
            if at < t1:
                for m in vo: mx.add(piano(m2f(m), d, 0.42), hum(at), 0.8, 0.15, 0.3)
        for i in range(4):
            at = bt + i * beat
            if at >= t1: break
            mx.add(ride(0.4 if i in (1, 3) else 0.32), hum(at), 1.1, 0.35, 0.2)
            if i in (1, 3):
                mx.add(ride(0.2), hum(at + (0.5 + sw) * beat), 0.8, 0.35, 0.2)
                mx.add(brush_tap(0.5), hum(at), 0.9, -0.2, 0.15)
            mx.add(brush(beat * 0.9, 0.5), at, 0.9, -0.25, 0.1)
        k += 1
    for at in (S("boss") + 1.0, S("boss") + 3.1):  # 吼的时候，低音铜管 + 重击
        for m in (38, 44, 50): mx.add(mute_trumpet(m2f(m), 0.7, 0.9), at, 0.7, -0.05, 0.25)
        mx.add(boom(0.5), at, 0.8, 0, 0.1)
    # 她：只剩一架钢琴
    t0 = S("woman") + 0.6; pb = 60 / 72
    play_line(mx, SAD_PIANO, t0, pb, piano, 0.9, 0.1, 0.4, vel=0.5, until=E("woman"))
    left = [[50, 57], [46, 53], [43, 50], [45, 52, 55, 61]]
    for i, ch in enumerate(left):
        at = t0 + i * 4 * pb
        if at < E("woman"):
            for m in ch: mx.add(piano(m2f(m), 3.6 * pb, 0.3), hum(at), 0.8, -0.1, 0.4)
    # 九宫格：弦乐推起来，低音回来，定音鼓滚奏进觉醒
    t0, t1 = S("grid"), E("grid")
    grid_prog = [[50, 57, 62, 65], [46, 53, 62, 65], [43, 55, 62, 70], [45, 57, 61, 67]]
    seg = (t1 - t0) / 4
    for i, ch in enumerate(grid_prog):
        for m in ch: mx.add(strings(m2f(m), seg * 1.05, 0.35 + 0.12 * i, attack=0.5), t0 + i * seg, 1.0, (m % 5 - 2) * 0.15, 0.45)
        mx.add(upright(m2f(ch[0] - 12), seg * 0.9, 0.7), t0 + i * seg, 1.0, -0.1, 0.1)
    play_line(mx, NOIR_TPT[2:], t0 + 0.4, beat, mute_trumpet, 0.5, 0.3, 0.4, vel=0.5, until=t1, swing=sw)
    mx.add(timp_roll(t1 - t0, 0.6), t0, 0.8, 0, 0.2)
    # 闪光之前：高音弦乐颤音 + 八音盒零星几个音（D 大调五声音阶，先埋一点光）+ 反向镲
    t0 = S("glow")
    mx.add(strings(m2f(81), FLASH - t0, 0.25, attack=0.8, release=0.1, bright=8000), t0, 1.0, 0.2, 0.5)
    for i, m in enumerate([86, 81, 88, 83, 90]):
        mx.add(celesta(m2f(m), 0.4, 0.35), t0 + 0.4 + i * 0.55, 0.8, 0.4 * math.sin(i * 2), 0.6)
    mx.add(rev_cymbal(1.6, 0.6), FLASH - 1.6, 0.9, 0, 0.3)

    # ===== 觉醒：D 大调 =====
    for m in (38, 50, 57, 62, 66, 69, 74):
        mx.add(strings(m2f(m), E("glow") - FLASH + 0.8, 0.42, attack=0.25), FLASH, 1.0, (m % 7 - 3) * 0.12, 0.5)
    mx.add(boom(0.7), FLASH, 0.9, 0, 0.15); mx.add(crash(0.45, 4), FLASH, 0.8, 0.1, 0.4)
    play_line(mx, THEME[:2], FLASH + 0.25, 60 / 100, celesta, 1.0, 0.1, 0.55, transpose=12, vel=0.6)
    play_line(mx, THEME[:2], FLASH + 0.25, 60 / 100, bell, 0.45, -0.2, 0.55, vel=0.45)

    # ===== 功能段：B 小调律动，104 BPM =====
    t0, t1 = S("shield"), S("portal")
    beat = 60 / 104; bar = 4 * beat
    fprog = [[47, [59, 62, 66, 69]], [43, [55, 59, 62, 66]], [38, [57, 62, 66, 69]], [45, [57, 61, 64, 69]]]
    arp_pat = [0, 2, 1, 3, 2, 1, 3, 2]
    mx.add(boom(0.9), t0, 1.0, 0, 0.1); mx.add(crash(0.6, 3), t0, 0.9, -0.1, 0.35)
    k = 0
    while t0 + k * bar < t1:
        bt = t0 + k * bar; root, ch = fprog[k % 4]
        sc = next(n for n in ("shield", "relief", "reply", "night", "handoff", "approve") if S(n) <= bt + 1e-3 < E(n))
        night = sc == "night"
        # 贝斯
        if not night:
            for i in range(8):
                at = bt + i * beat / 2
                if at >= t1: break
                if i in (0, 3, 4, 6) or sc in ("shield", "handoff"):
                    mx.add(synbass(m2f(root - 12 if root > 40 else root), beat * 0.42, 0.7), hum(at, 4), 0.55, 0, 0.05)
        else:
            mx.add(upright(m2f(root - 12 if root > 40 else root), bar * 0.9, 0.5), bt, 0.9, -0.1, 0.2)
        # 和声：电钢 / 夜里的弦乐垫音
        if night:
            for m in ch: mx.add(strings(m2f(m), bar * 1.02, 0.28, attack=0.6, bright=3500), bt, 1.0, (m % 5 - 2) * 0.2, 0.55)
        else:
            for at in (bt, bt + 1.5 * beat, bt + 3 * beat):
                if at < t1:
                    for m in ch: mx.add(epiano(m2f(m), beat * 0.9, 0.5), hum(at), 1.1, 0.2, 0.3)
        # 拨弦琶音（深夜换成八音盒，慢一倍）
        step = beat if night else beat / 2
        for i in range(int(bar / step)):
            at = bt + i * step
            if at >= t1: break
            m = ch[arp_pat[i % 8]] + 12
            if night: mx.add(celesta(m2f(m + 12), step, 0.3), hum(at), 0.6, 0.35 * math.sin(i), 0.55)
            else: mx.add(pluck(m2f(m), step * 0.9, 0.6), hum(at, 4), 0.8, 0.35 * math.sin(i), 0.3)
        # 鼓
        if not night:
            full = sc in ("shield", "reply", "approve", "handoff")
            for i in range(8):
                at = bt + i * beat / 2
                if at >= t1: break
                if i in (0, 4) or (full and i == 5): mx.add(kick(0.7 if full else 0.5), hum(at, 3), 0.65, 0, 0.03)
                if i in (2, 6): mx.add(snare(0.6) if full else rim(0.5), hum(at, 3), 0.7, 0.05, 0.18)
                mx.add(hat(0.4 if i % 2 else 0.26), hum(at, 3), 0.9, 0.3, 0.05)
        k += 1
    # 主题片段：回复（钟琴）、交给大模型（弦乐）、批准（钟琴后半）
    def at_bar(t, t_ref=t0): return t_ref + math.ceil((t - t_ref) / bar - 1e-3) * bar
    play_line(mx, THEME[:2], at_bar(S("reply")), beat, bell, 0.55, 0.25, 0.4, vel=0.55, until=E("reply"))
    ho = at_bar(S("handoff"))
    play_line(mx, THEME[:2], ho, beat, lambda f, d, v: strings(f, d, v, attack=0.08, release=0.4, bright=7000), 1.0, -0.2, 0.45, vel=0.55, until=E("handoff"))
    play_line(mx, THEME[:2], ho, beat, celesta, 0.6, 0.3, 0.5, transpose=12, vel=0.45, until=E("handoff"))
    play_line(mx, THEME[2:], at_bar(S("approve")), beat, bell, 0.6, 0.2, 0.4, vel=0.6, until=t1)
    for i, m in enumerate((79, 84)): mx.add(bell(m2f(m + 12), 0.8, 0.5), S("approve") + 0.9 + i * 0.15, 0.7, 0.3, 0.5)
    # 深夜：钟表一样轻的滴答
    for i in range(int((E("night") - S("night")) / beat)):
        mx.add(rim(0.12), S("night") + i * beat, 0.5, 0.5, 0.2)

    # ===== 撕开纸页 =====
    t0, t1 = S("portal"), E("portal")
    L = t1 - t0
    mx.add(riser(L, 0.8), t0, 1.2, 0, 0.3)
    for m in (45, 57, 61, 64, 69, 73, 76):
        mx.add(strings(m2f(m), L, 0.35 + 0.25 * (m > 60), attack=L * 0.9, release=0.05, bright=7000), t0, 1.3, (m % 7 - 3) * 0.12, 0.45)
    rb = 60 / 112 / 4; tt = t0 + L * 0.3; i = 0
    while tt < t1 - 0.02:
        prog_ = (tt - t0) / L
        mx.add(snare(0.25 + 0.5 * prog_), tt, 0.6, 0.05, 0.2)
        tt += rb * (1.0 if prog_ < 0.75 else 0.5); i += 1
    mx.add(rev_cymbal(1.2, 0.7), t1 - 1.2, 0.9, 0, 0.3)

    # ===== 结尾：D 大调卡农进行，112 BPM =====
    t0, t1 = S("field"), total
    beat = 60 / 112; bar = 4 * beat
    canon = [(38, [62, 66, 69]), (45, [61, 64, 69]), (47, [62, 66, 71]), (42, [61, 66, 69]),
             (43, [62, 67, 71]), (38, [62, 66, 69]), (43, [62, 67, 71]), (45, [61, 64, 69])]
    end_bar = S("end") + bar  # 结尾最后一个和弦从这里开始
    mx.add(boom(0.8), t0, 1.0, 0, 0.1); mx.add(crash(0.8, 4), t0, 0.9, 0.15, 0.4); mx.add(crash(0.5, 4), t0, 0.7, -0.3, 0.4)
    k = 0
    while t0 + k * bar < end_bar - 1e-3:
        bt = t0 + k * bar; root, ch = canon[k % 8]
        sc = next(n for n in ("field", "crowd", "arrive", "end") if S(n) <= bt + 1e-3 < E(n))
        light = sc == "crowd"
        for m in ch + [ch[0] - 12]:
            mx.add(strings(m2f(m), bar * 1.02, 0.3 + 0.1 * (sc != "field"), attack=0.3), bt, 1.0, (m % 5 - 2) * 0.18, 0.45)
        for i in range(8):
            at = bt + i * beat / 2
            if at >= end_bar: break
            if light and i % 2: continue
            for m in ch: mx.add(piano(m2f(m), beat * 0.45, 0.32 + 0.08 * (i % 2 == 0), bright=1.1), hum(at, 5), 0.75, 0.15, 0.3)
            mx.add(synbass(m2f(root), beat * 0.4, 0.7), hum(at, 4), 0.6 if i % 2 == 0 else 0.4, 0, 0.05)
            if not light:
                if i in (0, 3, 4): mx.add(kick(0.8), hum(at, 3), 0.9, 0, 0.03)
                if i in (2, 6): mx.add(snare(0.65), hum(at, 3), 0.75, 0.05, 0.2)
                mx.add(hat(0.36 if i % 2 else 0.24, open_=(i == 7)), hum(at, 3), 0.85, 0.3, 0.05)
            elif i in (0, 4):
                mx.add(kick(0.5), hum(at, 3), 0.7, 0, 0.03)
        if k % 4 == 0 and k: mx.add(crash(0.35, 3), bt, 0.6, -0.2, 0.4)
        k += 1
    # 主题：田野（钟琴 + 钢琴高八度）、人群（弦乐低八度 + 三度和声）、到达（主题回答）、结尾停在主音
    def fb(t): return t0 + math.ceil((t - t0) / bar - 1e-3) * bar
    play_line(mx, THEME, fb(S("field")), beat, bell, 0.7, 0.2, 0.4, vel=0.65, until=E("field") + bar)
    play_line(mx, THEME, fb(S("field")), beat, piano, 0.5, -0.2, 0.35, transpose=12, vel=0.45, until=E("field") + bar)
    cs = fb(S("crowd"))
    sfn = lambda f, d, v: strings(f, d, v, attack=0.12, release=0.5, bright=6500)
    play_line(mx, THEME, cs, beat, sfn, 1.2, -0.15, 0.5, transpose=-12, vel=0.6, until=E("crowd"))
    play_line(mx, [[(m - 3 if m else None, b) for m, b in bar_] for bar_ in THEME], cs, beat, sfn, 0.8, 0.2, 0.5, transpose=-12, vel=0.45, until=E("crowd"))
    play_line(mx, ANSWER, fb(S("arrive")), beat, bell, 0.7, 0.2, 0.4, vel=0.65, until=end_bar)
    play_line(mx, ANSWER, fb(S("arrive")), beat, celesta, 0.5, -0.25, 0.45, transpose=12, vel=0.4, until=end_bar)
    # 最后的和弦：Dadd9，钟琴点缀，慢慢收
    last = t1 - end_bar + 1.5
    for m in (38, 50, 57, 62, 64, 66, 69, 74):
        mx.add(strings(m2f(m), last, 0.4, attack=0.2, release=1.5), end_bar, 1.0, (m % 7 - 3) * 0.12, 0.5)
        mx.add(piano(m2f(m), last, 0.35), hum(end_bar), 0.7, 0.1, 0.45)
    mx.add(bell(m2f(74 + 12), 2.0, 0.6), end_bar, 0.7, 0.2, 0.6); mx.add(crash(0.5, 4), end_bar, 0.7, 0, 0.5)
    for i, m in enumerate([86, 90, 93, 98, 93, 90]):
        mx.add(celesta(m2f(m), 0.6, 0.35), end_bar + 0.8 + i * 0.32, 0.6, 0.4 * math.sin(i * 1.7), 0.6)

    out = mx.render()[: int(total * SR)]
    fade = int(2.5 * SR); out[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 1.5
    return out
