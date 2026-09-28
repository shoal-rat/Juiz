"""宣传片音效：全部程序合成（滤波噪声、正弦、FM），没有采样。每个函数返回 48 kHz 单声道 float32。"""
import math
import numpy as np
from scipy import signal

SR = 48000
RNG = np.random.default_rng(99)


def _t(n): return np.arange(n) / SR


def _bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, min(hi, SR * 0.45)], "band", fs=SR, output="sos"); return signal.sosfilt(sos, x)


def _lp(x, fc, order=2):
    sos = signal.butter(order, min(fc, SR * 0.45), "low", fs=SR, output="sos"); return signal.sosfilt(sos, x)


def _hp(x, fc, order=2):
    sos = signal.butter(order, fc, "high", fs=SR, output="sos"); return signal.sosfilt(sos, x)


def _f(x): return np.asarray(x, dtype=np.float32)


def fade(x, a=0.02, r=0.1):
    n = len(x); t = np.arange(n)
    return _f(x * np.minimum(1, t / max(1, a * SR)) * np.minimum(1, (n - t) / max(1, r * SR)))


# ---------- 环境 ----------

def rain(dur, heavy=0.6):
    n = int(dur * SR)
    bed = _bp(RNG.standard_normal(n), 400, 6000) * 0.25
    drops = np.zeros(n)
    for _ in range(int(dur * 60 * heavy)):
        i = RNG.integers(0, n - 2000); L = RNG.integers(200, 900)
        drops[i:i + L] += _hp(RNG.standard_normal(L), 2500)[:L] * np.exp(-np.arange(L) / (L / 5)) * RNG.uniform(0.2, 0.6)
    return fade(_f((bed + drops * 0.5) * heavy), 0.5, 0.5)


def thunder(dur=3.0):
    n = int(dur * SR); t = _t(n)
    x = _lp(RNG.standard_normal(n), 160) * (np.exp(-t * 1.4) * (1 + 0.6 * np.sin(2 * np.pi * 3 * t) ** 2)) * 3
    crack = _bp(RNG.standard_normal(int(0.25 * SR)), 800, 5000) * np.exp(-_t(int(0.25 * SR)) * 18)
    x[: len(crack)] += crack * 0.6
    return fade(_f(x * 0.5), 0.005, 0.6)


def crickets(dur):
    n = int(dur * SR); t = _t(n); out = np.zeros(n)
    for f0, rate, ph in ((4300, 2.2, 0), (4700, 2.7, 1.3), (5100, 1.9, 2.1)):
        env = (np.sin(2 * np.pi * rate * t + ph) > 0.6).astype(float)
        env = _lp(env, 60) * (0.5 + 0.5 * np.sin(2 * np.pi * 28 * t) ** 2)
        out += np.sin(2 * np.pi * f0 * t) * env * 0.05
    return fade(_f(out), 0.8, 0.8)


def birds(dur, count=6):
    n = int(dur * SR); out = np.zeros(n)
    for _ in range(count):
        at = RNG.uniform(0, max(0.1, dur - 0.6)); i = int(at * SR)
        for k in range(RNG.integers(2, 5)):
            L = int(RNG.uniform(0.05, 0.12) * SR); tt = _t(L)
            f = RNG.uniform(2600, 4200) + RNG.uniform(-1500, 1500) * tt / tt[-1]
            chirp = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * tt / tt[-1]) ** 2
            j = i + k * int(0.14 * SR)
            if j + L < n: out[j:j + L] += chirp * RNG.uniform(0.04, 0.08)
    return _f(out)


def wind(dur, strength=0.5):
    n = int(dur * SR); t = _t(n)
    x = _lp(RNG.standard_normal(n), 700) * (0.6 + 0.4 * np.sin(2 * np.pi * 0.25 * t + 1)) * strength
    return fade(_f(x * 0.4), 1.0, 1.0)


def office(dur):
    n = int(dur * SR)
    murmur = _bp(RNG.standard_normal(n), 250, 900) * 0.05 * (0.7 + 0.3 * np.sin(2 * np.pi * 0.7 * _t(n)))
    keys = np.zeros(n)
    for _ in range(int(dur * 6)):
        i = RNG.integers(0, n - 800)
        keys[i:i + 600] += _hp(RNG.standard_normal(600), 2000) * np.exp(-_t(600) * 90) * RNG.uniform(0.08, 0.2)
    return fade(_f(murmur + keys), 0.6, 0.6)


# ---------- 电话 ----------

def ring(n_rings=2):
    x = []
    for _ in range(n_rings):
        t = _t(int(1.0 * SR))
        s = (np.sin(2 * np.pi * 440 * t) + np.sin(2 * np.pi * 480 * t)) * (np.sin(2 * np.pi * 20 * t) > 0) * 0.12
        x.append(s); x.append(np.zeros(int(0.6 * SR)))
    return fade(_f(np.concatenate(x)), 0.005, 0.05)


def many_rings(dur, voices=6):
    """九宫格：好多部电话在远处同时响。"""
    n = int(dur * SR); out = np.zeros(n)
    for k in range(voices):
        r = ring(3) * RNG.uniform(0.25, 0.5)
        r = _lp(r * np.sign(RNG.standard_normal()), RNG.uniform(1200, 2500))
        i = int(RNG.uniform(0, dur * 0.4) * SR)
        out[i:i + len(r)] += r[: n - i]
    return fade(_f(out), 0.3, 0.8)


def vibrate(dur=0.9):
    t = _t(int(dur * SR))
    buzz = np.sin(2 * np.pi * 150 * t) * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 45 * t)))
    buzz *= ((t % 0.45) < 0.3)
    return fade(_f(_lp(buzz, 900) * 0.25), 0.01, 0.05)


def receiver_click():
    L = int(0.08 * SR)
    return _f(_bp(RNG.standard_normal(L), 300, 3000) * np.exp(-_t(L) * 60) * 0.4)


def phone_line(voice):
    """把人声变成听筒里的声音。"""
    x = _bp(voice.astype(np.float64), 320, 3300, 3)
    x = np.tanh(x * 2.2) / 2.2 + _bp(RNG.standard_normal(len(x)), 1000, 4000) * 0.004
    return _f(x * 1.3)


# ---------- 情绪 ----------

def drip():
    L = int(0.35 * SR); t = _t(L)
    f = 1400 * np.exp(-t * 6) + 500
    return _f(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 14) * 0.25)


def sniff():
    L = int(0.45 * SR); t = _t(L)
    return _f(_bp(RNG.standard_normal(L), 1500, 6000) * np.sin(np.pi * t / t[-1]) ** 3 * 0.12)


def heartbeat():
    def thump(f, a):
        L = int(0.18 * SR); t = _t(L)
        return np.sin(2 * np.pi * f * t) * np.exp(-t * 22) * a
    x = np.zeros(int(0.7 * SR)); a = thump(55, 0.8); b = thump(48, 0.55)
    x[: len(a)] += a; x[int(0.22 * SR): int(0.22 * SR) + len(b)] += b
    return _f(x)


def paper(dur=0.6):
    n = int(dur * SR); out = np.zeros(n)
    for _ in range(int(dur * 40)):
        i = RNG.integers(0, n - 1500); L = RNG.integers(300, 1400)
        out[i:i + L] += _bp(RNG.standard_normal(L), 1500, 8000) * np.exp(-_t(L) * 40) * RNG.uniform(0.1, 0.35)
    return fade(_f(out), 0.02, 0.15)


def tear(dur=0.9):
    n = int(dur * SR)
    x = _bp(RNG.standard_normal(n), 900, 7000) * (RNG.random(n) > 0.5)
    x = np.convolve(x, np.ones(5) / 5, mode="same") * np.linspace(1.0, 0.25, n)
    return fade(_f(x * 0.5), 0.01, 0.2)


# ---------- 魔法与界面 ----------

def whoosh(dur=0.8, up=True):
    n = int(dur * SR); x = _t(n) / dur
    noise = RNG.standard_normal(n); out = np.zeros(n)
    for i, (lo, hi) in enumerate([(200, 800), (600, 2000), (1500, 5000), (4000, 11000)]):
        c = i / 3 if up else 1 - i / 3
        out += _bp(noise, lo, hi) * np.exp(-((x - c) ** 2) / 0.05)
    return fade(_f(out * np.sin(np.pi * x) * 0.35), 0.01, 0.05)


def sparkle(dur=1.2, density=14, base=2800):
    n = int(dur * SR); out = np.zeros(n)
    for _ in range(int(dur * density)):
        i = RNG.integers(0, max(1, n - int(0.5 * SR))); L = int(0.4 * SR); t = _t(L)
        f = RNG.uniform(base, base * 2.2)
        out[i:i + L] += (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(2 * np.pi * f * 2.7 * t)) * np.exp(-t * 12) * RNG.uniform(0.03, 0.07)
    return fade(_f(out), 0.01, 0.2)


def chime(notes=(84, 91), gap=0.12, vel=0.18):
    L = int(1.4 * SR); out = np.zeros(L + int(gap * SR * len(notes)))
    for k, m in enumerate(notes):
        f = 440 * 2 ** ((m - 69) / 12); t = _t(L)
        s = np.sin(2 * np.pi * f * t + 1.5 * np.exp(-t * 4) * np.sin(2 * np.pi * f * 3.5 * t)) * np.exp(-t * 3)
        i = int(k * gap * SR); out[i:i + L] += s * vel
    return _f(out)


def pop(f=900, vel=0.2):
    L = int(0.12 * SR); t = _t(L)
    ff = f * (1 + 0.8 * t / t[-1])
    return _f(np.sin(2 * np.pi * np.cumsum(ff) / SR) * np.exp(-t * 35) * vel)


def tap():
    L = int(0.05 * SR)
    return _f(_bp(RNG.standard_normal(L), 1500, 7000) * np.exp(-_t(L) * 120) * 0.35 + np.sin(2 * np.pi * 2200 * _t(L)) * np.exp(-_t(L) * 90) * 0.15)


def scan_beep():
    """指纹：短促扫描声 + 成功提示。"""
    L = int(0.35 * SR); t = _t(L)
    sweep = np.sin(2 * np.pi * np.cumsum(900 + 1400 * t / t[-1]) / SR) * 0.08 * (t < 0.3)
    return _f(np.concatenate([sweep, chime((79, 86, 91), 0.09, 0.16)]))


def shield_hum(dur=3.0):
    n = int(dur * SR); t = _t(n)
    x = (np.sin(2 * np.pi * 110 * t) + 0.5 * np.sin(2 * np.pi * 220.7 * t) + 0.25 * np.sin(2 * np.pi * 331 * t)) * (0.7 + 0.3 * np.sin(2 * np.pi * 6 * t))
    return fade(_f(x * 0.06), 0.15, 0.6)


def impact(vel=0.8):
    L = int(0.6 * SR); t = _t(L)
    body = np.sin(2 * np.pi * np.cumsum(90 * np.exp(-t * 8) + 40) / SR) * np.exp(-t * 7)
    hit = _bp(RNG.standard_normal(L), 400, 4000) * np.exp(-t * 30)
    return _f((body * 0.8 + hit * 0.5) * vel)


def shatter(dur=1.2):
    n = int(dur * SR); out = np.zeros(n)
    burst = _hp(RNG.standard_normal(int(0.3 * SR)), 2500) * np.exp(-_t(int(0.3 * SR)) * 12)
    out[: len(burst)] += burst * 0.5
    for _ in range(40):
        i = RNG.integers(0, n - int(0.3 * SR)); L = int(0.25 * SR); t = _t(L)
        f = RNG.uniform(3000, 8000)
        out[i:i + L] += np.sin(2 * np.pi * f * t) * np.exp(-t * 25) * RNG.uniform(0.02, 0.06)
    return fade(_f(out), 0.002, 0.3)


def zap(dur=0.5):
    n = int(dur * SR); t = _t(n)
    f = 1800 * np.exp(-t * 6) + 200
    return fade(_f((np.sin(2 * np.pi * np.cumsum(f) / SR) + 0.3 * np.sign(np.sin(2 * np.pi * np.cumsum(f * 1.01) / SR))) * np.exp(-t * 5) * 0.12), 0.002, 0.1)


def clock_tick(dur, bpm=60):
    n = int(dur * SR); out = np.zeros(n); L = int(0.03 * SR)
    click = _bp(RNG.standard_normal(L), 2000, 6000) * np.exp(-_t(L) * 150) * 0.12
    k = 0
    while True:
        i = int(k * 60 / bpm * SR)
        if i + L >= n: break
        out[i:i + L] += click * (1.0 if k % 2 == 0 else 0.7); k += 1
    return _f(out)


def slam():
    L = int(0.5 * SR); t = _t(L)
    return _f((_lp(RNG.standard_normal(L), 900) * np.exp(-t * 18) * 0.9 + np.sin(2 * np.pi * 70 * t) * np.exp(-t * 12) * 0.6))


def door():
    return _f(np.concatenate([whoosh(0.5, up=False) * 0.6, _lp(RNG.standard_normal(int(0.1 * SR)), 1200) * np.exp(-_t(int(0.1 * SR)) * 50) * 0.3]))


class Track:
    """一条立体声音效轨。"""

    def __init__(self, total):
        self.n = int(total * SR) + SR
        self.buf = np.zeros((self.n, 2), np.float32)

    def add(self, sig, at, gain=1.0, pan=0.0):
        if at < 0: sig = sig[int(-at * SR):]; at = 0
        i = int(at * SR); j = min(self.n, i + len(sig))
        if j <= i: return
        a = (pan + 1) * math.pi / 4
        self.buf[i:j, 0] += sig[: j - i] * gain * math.cos(a)
        self.buf[i:j, 1] += sig[: j - i] * gain * math.sin(a)
