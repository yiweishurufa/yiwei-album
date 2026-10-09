"""Synthesizes the bundled royalty-free ambient track for 回忆短片 (1.0.1).
Pure synthesis (numpy): soft pad chords + slow arpeggio + gentle bass. No samples, no third-party audio.
Usage: python3 tools/gen_memory_music.py out.wav  (then ffmpeg -> AAC m4a, see HANDOFF)."""
import sys, numpy as np, wave
SR = 44100
BPM = 72
beat = 60.0 / BPM
bar = 4 * beat                      # ~3.33 s
def midi(n): return 440.0 * 2 ** ((n - 69) / 12)
# I - vi - IV - V in C, with sevenths / add9 colour: Cmaj9, Am7, Fmaj7, G6sus
prog = [[48, 55, 64, 71, 74], [45, 52, 60, 67, 72], [41, 53, 57, 64, 69], [43, 55, 62, 67, 71]]
arp_pat = [0, 2, 1, 3, 2, 4, 3, 1]
n_bars = 30                         # ~100 s (longest video: 30 photos x 3 s + crossfade)
total = int(n_bars * bar * SR) + SR * 2
L = np.zeros(total); R = np.zeros(total)

def env(n, a, r):
    e = np.ones(n)
    ai = int(a * SR); ri = int(r * SR)
    e[:ai] = np.linspace(0, 1, ai) ** 1.5
    e[-ri:] *= np.linspace(1, 0, ri) ** 1.5
    return e

def pad(f, dur):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.zeros(n)
    for det, g in ((0.0, 1.0), (0.18, 0.6), (-0.21, 0.6)):
        ff = f * 2 ** (det / 1200 * 10)
        s += g * (np.sin(2 * np.pi * ff * t) + 0.25 * np.sin(2 * np.pi * 2 * ff * t) + 0.08 * np.sin(2 * np.pi * 3 * ff * t))
    trem = 1 + 0.08 * np.sin(2 * np.pi * 0.23 * t)
    return s * trem * env(n, 1.2, 1.6)

def pluck(f, dur):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * t) + 0.3 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t * 6)
    return s * np.exp(-t * 2.2) * np.minimum(1, t * 200)

def add(buf, pos, sig, g):
    end = min(len(buf), pos + len(sig)); buf[pos:end] += g * sig[:end - pos]

for b in range(n_bars):
    ch = prog[b % 4]
    start = int(b * bar * SR)
    for i, n in enumerate(ch[1:]):
        p = pad(midi(n), bar + 1.6)
        pan = 0.35 + 0.3 * (i / 3)
        add(L, start, p, 0.05 * (1 - pan)); add(R, start, p, 0.05 * pan)
    bs = pad(midi(ch[0] - 12), bar + 1.0)
    add(L, start, bs, 0.06); add(R, start, bs, 0.06)
    if b >= 2:   # arpeggio enters after two bars
        for k, idx in enumerate(arp_pat):
            pos = start + int(k * beat / 2 * SR)
            pl = pluck(midi(ch[1 + idx % 4] + 12), 2.5)
            pan = 0.2 + 0.6 * ((k % 4) / 3)
            g = 0.07 if k % 2 == 0 else 0.05
            add(L, pos, pl, g * (1 - pan)); add(R, pos, pl, g * pan)

# simple feedback-delay "room"
def delay(x, ms, fb, mix):
    d = int(ms / 1000 * SR); y = x.copy()
    for i in range(d, len(x), d):
        seg = y[i - d:i]
        end = min(len(x), i + d) - i
        y[i:i + end] += fb * seg[:end]
    return (1 - mix) * x + mix * y
L = delay(L, 333, 0.35, 0.35); R = delay(R, 417, 0.35, 0.35)
# one-pole low-pass for warmth
def lp(x, a=0.25):
    y = np.empty_like(x); acc = 0.0
    # vectorised via lfilter-like cumulative approach is unavailable; loop in chunks
    for i in range(len(x)):
        acc += a * (x[i] - acc); y[i] = acc
    return y
L = lp(L); R = lp(R)
fade_in = int(2.0 * SR); L[:fade_in] *= np.linspace(0, 1, fade_in); R[:fade_in] *= np.linspace(0, 1, fade_in)
fo = int(4 * SR); L[-fo:] *= np.linspace(1, 0, fo); R[-fo:] *= np.linspace(1, 0, fo)
peak = max(np.abs(L).max(), np.abs(R).max()); L *= 0.7 / peak; R *= 0.7 / peak
data = (np.stack([L, R], axis=1) * 32767).astype('<i2')
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data.tobytes())
print('seconds', len(L) / SR)
