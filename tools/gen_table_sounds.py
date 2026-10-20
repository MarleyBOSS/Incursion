#!/usr/bin/env python3
"""Synthesizes the Core Table sounds (mono Ogg Vorbis, needs numpy and ffmpeg with libvorbis).

  python3 tools/gen_table_sounds.py

Writes src/main/resources/assets/incursion/sounds/block/table/{loop,start,finish,zap,ambient}.ogg and
sounds/block/core/{open,hum}.ogg.
Deterministic (fixed seed). The loop is built from whole-number frequencies so it repeats without a click.
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
RNG = np.random.default_rng(20261017)
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "sounds", "block", "table")


def t_axis(seconds):
    return np.arange(int(SR * seconds)) / SR


def env_decay(t, tau):
    return np.exp(-t / tau)


def bell(freq, seconds, tau, amp=1.0):
    """A glassy bell: a few slightly inharmonic partials with different decay times."""
    t = t_axis(seconds)
    out = np.zeros_like(t)
    for ratio, level, decay in ((1.0, 1.0, 1.0), (2.01, 0.45, 0.7), (2.76, 0.30, 0.5), (4.07, 0.18, 0.35), (5.4, 0.08, 0.25)):
        out += level * np.sin(2 * np.pi * freq * ratio * t) * env_decay(t, tau * decay)
    attack = np.minimum(t / 0.004, 1.0)
    return amp * out * attack


def add_at(buf, sound, start, wrap=False):
    i = int(start * SR)
    n = len(sound)
    if wrap:
        idx = (np.arange(n) + i) % len(buf)
        np.add.at(buf, idx, sound)
    else:
        end = min(len(buf), i + n)
        buf[i:end] += sound[: end - i]


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = a * acc + (1 - a) * v
        y[i] = acc
    return y


def normalize(x, peak=0.85):
    return x * (peak / max(1e-9, np.max(np.abs(x))))


def fade(x, fin=0.005, fout=0.05):
    n = len(x)
    a, b = int(fin * SR), int(fout * SR)
    x = x.copy()
    x[:a] *= np.linspace(0, 1, a)
    x[n - b:] *= np.linspace(1, 0, b)
    return x


def loop_sound():
    seconds = 3.0
    t = t_axis(seconds)
    out = np.zeros_like(t)
    # Deep warm pad: whole-Hz tones so that 3 s contains whole cycles, slow whole-cycle tremolo.
    for freq, level, trem in ((110, 0.55, 1 / 3), (165, 0.35, 2 / 3), (220, 0.40, 1 / 3), (330, 0.20, 1.0), (440, 0.12, 2 / 3)):
        out += level * np.sin(2 * np.pi * freq * t + 2 * np.pi * RNG.random()) * (0.75 + 0.25 * np.sin(2 * np.pi * trem * t + RNG.random() * 6))
    # Shimmer: detuned high partials beating slowly.
    for freq in (880, 882, 1320, 1323):
        out += 0.05 * np.sin(2 * np.pi * freq * t) * (0.5 + 0.5 * np.sin(2 * np.pi * (1 / 3) * t + freq))
    # A soft rising whoosh of band-limited noise, whole loop long.
    noise = lowpass(RNG.standard_normal(len(t)), 1800) * 0.25
    out += noise * (0.5 + 0.5 * np.sin(2 * np.pi * (1 / 3) * t - 1.0))
    # Glassy chimes (pentatonic), wrapped around the loop end.
    for start, freq, amp in ((0.15, 880, 0.30), (0.95, 1320, 0.22), (1.55, 1174.7, 0.26), (2.2, 1568, 0.20), (2.6, 988, 0.18)):
        add_at(out, bell(freq, 1.6, 0.35, amp), start, wrap=True)
    return normalize(out, 0.7)


def start_sound():
    seconds = 1.8
    t = t_axis(seconds)
    sweep_f = 180 * (1400 / 180) ** (t / seconds)
    phase = 2 * np.pi * np.cumsum(sweep_f) / SR
    out = 0.35 * np.sin(phase) * np.minimum(t / 0.05, 1) * np.minimum((seconds - t) / 0.3, 1)
    out += 0.12 * np.sin(2 * phase)
    boom = np.sin(2 * np.pi * 70 * t) * env_decay(t, 0.35) * 0.6
    out += boom
    out += lowpass(RNG.standard_normal(len(t)), 3000) * 0.15 * (t / seconds)
    for i, freq in enumerate((784, 988, 1174.7, 1568, 1976)):
        add_at(out, bell(freq, 1.0, 0.25, 0.22), 0.8 + i * 0.1)
    return fade(normalize(out, 0.85), 0.003, 0.15)


def finish_sound():
    seconds = 3.2
    out = np.zeros(int(SR * seconds))
    t = t_axis(seconds)
    out += 0.7 * np.sin(2 * np.pi * 55 * t) * env_decay(t, 0.5) * np.minimum(t / 0.01, 1)
    for freq, delay, amp in ((523.25, 0.0, 0.5), (659.25, 0.07, 0.45), (783.99, 0.14, 0.45), (1174.66, 0.22, 0.4), (1567.98, 0.32, 0.3)):
        add_at(out, bell(freq, 2.6, 0.9, amp), delay)
    for i in range(14):  # rising sparkles
        add_at(out, bell(1500 + i * 190 + RNG.random() * 90, 0.5, 0.10, 0.12), 0.25 + i * 0.09)
    return fade(normalize(out, 0.85), 0.002, 0.4)


def zap_sound():
    seconds = 0.6
    t = t_axis(seconds)
    noise = RNG.standard_normal(len(t))
    crackle = noise * (RNG.random(len(t)) > 0.985) * 3.0
    crackle = lowpass(crackle, 6000)
    out = crackle * env_decay(t, 0.08)
    out += 0.4 * np.sin(2 * np.pi * (900 * np.exp(-t * 9) + 300) * t) * env_decay(t, 0.12)
    add_at(out, bell(2093, 0.4, 0.10, 0.3), 0.02)
    return fade(normalize(out, 0.8), 0.001, 0.1)


def ambient_sound():
    seconds = 2.6
    out = np.zeros(int(SR * seconds))
    for start, freq, amp in ((0.0, 1318.5, 0.5), (0.35, 1760, 0.35), (0.8, 1567.98, 0.30)):
        add_at(out, bell(freq, 1.8, 0.5, amp), start)
    t = t_axis(seconds)
    out += 0.12 * np.sin(2 * np.pi * 220 * t) * np.sin(np.pi * t / seconds) ** 2
    return fade(normalize(out, 0.6), 0.003, 0.3)


def write_ogg(name, samples, out=None):
    out = out or OUT
    os.makedirs(out, exist_ok=True)
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, name + ".wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path, "-c:a", "libvorbis", "-q:a", "4",
                        os.path.join(out, name + ".ogg")], check=True)


def core_open_sound():
    """Heavy plates unfolding: hydraulic hiss, metallic clanks, a rumble and a rising hum that ends in a bright chord."""
    seconds = 3.0
    t = t_axis(seconds)
    out = np.zeros_like(t)
    rumble = lowpass(RNG.standard_normal(len(t)), 140) * 4.0
    out += rumble * np.sin(np.pi * np.clip(t / 2.2, 0, 1)) * 0.9
    out += 0.35 * np.sin(2 * np.pi * 48 * t) * np.minimum(t / 0.3, 1) * np.minimum((seconds - t) / 0.8, 1)
    hiss = RNG.standard_normal(len(t))
    hiss = hiss - lowpass(hiss, 3000)
    for start, length in ((0.05, 0.5), (0.5, 0.45), (0.95, 0.4)):
        env = np.zeros_like(t)
        i0, i1 = int(start * SR), int((start + length) * SR)
        env[i0:i1] = np.sin(np.linspace(0, np.pi, i1 - i0)) ** 2
        out += hiss * env * 0.22
    for start, freq in ((0.18, 310), (0.62, 262), (1.05, 392), (1.35, 220)):
        clank = bell(freq, 0.7, 0.10, 0.5)
        add_at(out, clank, start)
        burst = lowpass(RNG.standard_normal(int(0.08 * SR)), 4000) * np.linspace(1, 0, int(0.08 * SR)) * 1.2
        add_at(out, burst, start)
    sweep_f = 70 * (360 / 70) ** (np.clip((t - 0.7) / 1.8, 0, 1))
    phase = 2 * np.pi * np.cumsum(sweep_f) / SR
    out += 0.28 * np.sin(phase) * np.clip((t - 0.7) / 0.6, 0, 1) * np.clip((seconds - t) / 0.7, 0, 1)
    for i, freq in enumerate((523.25, 659.25, 783.99, 1046.5)):
        add_at(out, bell(freq, 1.4, 0.45, 0.28), 1.9 + i * 0.07)
    return fade(normalize(out, 0.85), 0.003, 0.4)


def core_hum_sound():
    """A deep, calm drone with a slow pulse; whole-Hz tones so that 4 s loops without a click."""
    seconds = 4.0
    t = t_axis(seconds)
    out = np.zeros_like(t)
    for freq, level, trem in ((55, 0.7, 0.25), (110, 0.5, 0.5), (165, 0.25, 0.25), (220, 0.18, 0.75)):
        out += level * np.sin(2 * np.pi * freq * t + RNG.random() * 6) * (0.7 + 0.3 * np.sin(2 * np.pi * trem * t + RNG.random() * 6))
    for freq in (660, 663):
        out += 0.04 * np.sin(2 * np.pi * freq * t) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.25 * t))
    add_at(out, bell(1318.5, 2.0, 0.5, 0.12), 0.5, wrap=True)
    add_at(out, bell(987.8, 2.0, 0.5, 0.10), 2.3, wrap=True)
    return normalize(out, 0.55)


if __name__ == "__main__":
    write_ogg("loop", loop_sound())
    write_ogg("start", start_sound())
    write_ogg("finish", finish_sound())
    write_ogg("zap", zap_sound())
    write_ogg("ambient", ambient_sound())
    core_dir = os.path.join(OUT, "..", "core")
    write_ogg("open", core_open_sound(), core_dir)
    # the hum (and the music of the evolution) now come from tools/gen_core_music.py
    print("written to", os.path.abspath(OUT))
