#!/usr/bin/env python3
"""Synthesizes the music of the Core (mono Ogg Vorbis, needs numpy, scipy and ffmpeg with libvorbis).

  python3 tools/gen_core_music.py

Writes src/main/resources/assets/incursion/sounds/block/core/:
  hum.ogg             the background of an active Core: a calm, dark ambient piece of 64 s that loops without a seam. Its
                      harmony never changes (D with its fifth A and the open notes E and G, no third, no chord changes):
                      the sound only breathes (slow swells of pad, choir and drone) and a few glassy bells ring at irregular
                      times. Nothing ticks, there is no beat.
  evolve_rise.ogg     6 s build-up that starts when a new stage is raised: riser, timpani that get faster, open brass fifths
  evolve_finale.ogg   7 s climax when the new stage stands (exactly 6 s after the start, see CoreEvolution.EVOLVE_TICKS)

The upgrade can start at any moment of the background, so everything uses only the notes D, A, E and G (open fifths and
fourths over a D drone): it fits whatever the hum is doing. Deterministic (fixed seed). Mono, so that the game can place it.
"""
import os
import subprocess
import tempfile
import wave

import numpy as np
from scipy import signal

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "sounds", "block", "core")


def rng(seed):
    return np.random.default_rng(seed)


def taxis(seconds):
    return np.arange(int(round(SR * seconds))) / SR


def hz(note):
    """MIDI note number to Hz."""
    return 440.0 * 2.0 ** ((note - 69) / 12.0)


def lowpass(x, fc, order=2):
    sos = signal.butter(order, min(fc, SR * 0.45) / (SR / 2), "low", output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, fc, order=2):
    sos = signal.butter(order, fc / (SR / 2), "high", output="sos")
    return signal.sosfilt(sos, x)


def bandpass(x, f, q):
    lo = f * (1 - 0.5 / q)
    hi = f * (1 + 0.5 / q)
    sos = signal.butter(2, [lo / (SR / 2), min(hi, SR * 0.45) / (SR / 2)], "band", output="sos")
    return signal.sosfilt(sos, x)


def normalize(x, peak):
    return x * (peak / max(1e-9, np.max(np.abs(x))))


def swell(n, attack, release):
    """A smooth envelope of n samples: raised-cosine rise and fall."""
    a = max(1, int(attack * SR))
    r = max(1, int(release * SR))
    env = np.ones(n)
    a = min(a, n // 2)
    r = min(r, n - a)
    env[:a] = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    env[n - r:] = np.minimum(env[n - r:], 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, r)))
    return env


def place(buf, sound, start):
    i = int(round(start * SR))
    if i >= len(buf):
        return
    end = min(len(buf), i + len(sound))
    if end > i:
        buf[i:end] += sound[: end - i]


def saw_voice(freq, seconds, seed, voices=3, cents=7.0, vib=0.0035, vib_rate=0.23):
    """A soft stack of slightly detuned saw waves with a slow vibrato (naive saws; filter them afterwards)."""
    t = taxis(seconds)
    r = rng(seed)
    out = np.zeros_like(t)
    for v in range(voices):
        det = (v - (voices - 1) / 2.0) * cents / max(1, voices - 1) if voices > 1 else 0.0
        f = freq * 2.0 ** (det / 1200.0)
        lfo = 1.0 + vib * np.sin(2 * np.pi * (vib_rate + 0.05 * v) * t + r.random() * 6.28)
        phase = np.cumsum(f * lfo) / SR + r.random()
        out += 2.0 * (phase % 1.0) - 1.0
    return out / voices


def choir_voice(freq, seconds, seed, vowel="oo"):
    """A breathy choir sound: a saw stack through vowel formants."""
    src = saw_voice(freq, seconds, seed, voices=5, cents=14.0, vib=0.006, vib_rate=5.0 / 1.0 * 0.6)
    formants = {"oo": ((330, 1.0), (800, 0.35), (2400, 0.05)), "ah": ((700, 1.0), (1150, 0.7), (2600, 0.12))}[vowel]
    out = np.zeros_like(src)
    for f, level in formants:
        out += level * bandpass(src, f, 6.0)
    breath = bandpass(rng(seed + 99).standard_normal(len(src)), 1800, 1.5) * 0.05
    return out + breath


def bell(freq, seconds, tau, seed=0):
    """A glassy bell: a few slightly inharmonic partials with different decay times."""
    t = taxis(seconds)
    out = np.zeros_like(t)
    for ratio, level, decay in ((1.0, 1.0, 1.0), (2.01, 0.4, 0.7), (2.76, 0.25, 0.5), (4.07, 0.12, 0.35), (5.4, 0.05, 0.25)):
        out += level * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-t / (tau * decay))
    return out * np.minimum(t / 0.004, 1.0)


def timpani(freq, seconds=1.6, tau=0.5, seed=0):
    t = taxis(seconds)
    pitch = freq * (1.0 + 0.35 * np.exp(-t / 0.06))
    phase = 2 * np.pi * np.cumsum(pitch) / SR
    body = np.sin(phase) * np.exp(-t / tau) + 0.45 * np.sin(2.0 * phase * 1.0) * np.exp(-t / (tau * 0.5))
    skin = lowpass(rng(seed).standard_normal(len(t)), 1800) * np.exp(-t / 0.05) * 1.6
    return (body + skin) * np.minimum(t / 0.002, 1.0)


def reverb(x, rt60, wet, seed=5, damp=3500.0, predelay=0.03):
    """A cheap, big reverb: convolution with decaying, damped noise."""
    n = int(rt60 * SR)
    t = np.arange(n) / SR
    ir = rng(seed).standard_normal(n) * np.exp(-6.9 * t / rt60)
    ir = lowpass(ir, damp, 1)
    ir[: int(predelay * SR)] = 0.0
    ir /= np.sqrt(np.sum(ir ** 2))
    tail = signal.fftconvolve(x, ir)[: len(x)]
    return x * (1.0 - wet * 0.5) + tail * wet


def write_ogg(name, samples):
    os.makedirs(OUT, exist_ok=True)
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, name + ".wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path, "-c:a", "libvorbis", "-q:a", "5", os.path.join(OUT, name + ".ogg")], check=True)


# ----------------------------------------------------------------------------------------------------------------------
# The ambient piece
# ----------------------------------------------------------------------------------------------------------------------

LOOP = 64.0
XFADE = 7.0  # the piece is rendered this much longer and folded back onto its beginning: no seam
EDGE = 0.15  # the file fades in and out this long: the game starts the next round just before the end, they cross-fade


def slow(t, period, phase):
    """A slow, smooth swell 0..1 whose period divides the loop (so that it repeats without a seam)."""
    return 0.5 + 0.5 * np.sin(2 * np.pi * t / period + phase)


def ambient():
    seconds = LOOP + XFADE
    t = taxis(seconds)
    n = len(t)
    mix = np.zeros(n)
    # the drone under everything: D1, its fifth and the octave
    drone = 0.5 * np.sin(2 * np.pi * hz(26) * t) + 0.22 * np.sin(2 * np.pi * hz(33) * t + 1.0) + 0.12 * np.sin(2 * np.pi * hz(38) * t + 2.0)
    mix += drone * (0.8 + 0.2 * slow(t, 32.0, 0.0))
    # pad: open D (D A D E G A D), every voice breathes with its own slow swell
    pad = ((38, 64.0, 0.0, 0.55), (45, 32.0, 1.3, 0.5), (50, 64.0, 2.6, 0.5), (52, 21.333333, 0.7, 0.35), (55, 32.0, 4.0, 0.4),
           (57, 64.0, 5.2, 0.45), (62, 21.333333, 2.2, 0.3), (69, 32.0, 3.1, 0.18))
    for k, (note, period, phase, level) in enumerate(pad):
        v = lowpass(saw_voice(hz(note), seconds, 100 + k, voices=4, cents=11.0), 680 + 30 * k, 2)
        mix += v * (0.15 + 0.85 * slow(t, period, phase) ** 1.5) * level * 0.2
    # choir on the open notes D A E G, swelling in and out in turn
    choir = ((62, 64.0, 1.0, "oo"), (69, 32.0, 3.5, "ah"), (64, 64.0, 4.4, "oo"), (67, 21.333333, 0.2, "oo"), (74, 32.0, 5.5, "ah"))
    for k, (note, period, phase, vowel) in enumerate(choir):
        v = choir_voice(hz(note), seconds, 200 + k, vowel)
        mix += v * slow(t, period, phase) ** 2.5 * 0.2
    # a few glassy bells at irregular times (D E G A), soft: they must never sound like a clock
    r = rng(7)
    times = (5.2, 12.9, 21.7, 29.4, 41.3, 48.6, 57.9)
    notes = (86, 81, 79, 74, 88, 76, 81)
    for tt, note in zip(times, notes):
        for shift in (0.0, LOOP):
            if tt + shift < seconds:
                place(mix, bell(hz(note), 7.0, 1.6) * 0.04 * (0.7 + 0.6 * r.random()), tt + shift)
    mix = highpass(mix, 24)
    mix = reverb(mix, 4.5, 0.55)
    # fold the end back onto the beginning
    x = int(XFADE * SR)
    u = np.linspace(0, 1, x)
    body = mix[: int(LOOP * SR)].copy()
    body[:x] = mix[:x] * np.sin(0.5 * np.pi * u) + mix[int(LOOP * SR): int(LOOP * SR) + x] * np.cos(0.5 * np.pi * u)
    body = normalize(body, 0.5)
    e = int(EDGE * SR)
    body[:e] *= np.sin(np.linspace(0, np.pi / 2, e))
    body[-e:] *= np.cos(np.linspace(0, np.pi / 2, e))
    return body


# ----------------------------------------------------------------------------------------------------------------------
# The evolution
# ----------------------------------------------------------------------------------------------------------------------

def brass(notes, seconds, seed, attack, fc0, fc1):
    """A stack of saw waves whose filter opens while it sounds."""
    n = int(seconds * SR)
    out = np.zeros(n)
    for i, note in enumerate(notes):
        v = saw_voice(hz(note), seconds, seed + i, voices=3, cents=9.0, vib=0.002)
        a = lowpass(v, fc0)
        b = lowpass(v, fc1)
        mixk = np.linspace(0, 1, n) ** 1.3
        out += (a * (1 - mixk) + b * mixk)
    return out / len(notes) * swell(n, attack, min(0.6, seconds / 3))


def rise():
    seconds = 6.0
    n = int(seconds * SR)
    t = taxis(seconds)
    mix = np.zeros(n)
    prog = t / seconds
    # the riser: bands of noise that open one after another, starting out of nothing
    noise = rng(11).standard_normal(n)
    for j, f in enumerate((220, 440, 880, 1760, 3500, 7000)):
        band = bandpass(noise, f, 1.4)
        mix += band * (prog ** (2.4 - 0.12 * j)) * (0.22 - 0.02 * j)
    # a tone that climbs from D2 to D4 (only a swell, it is mostly a colour)
    f = hz(38) * 2.0 ** (2.0 * prog ** 1.4)
    phase = np.cumsum(f) / SR
    tone = lowpass(2.0 * (phase % 1.0) - 1.0, 1200) + lowpass(2.0 * ((phase * 1.5) % 1.0) - 1.0, 1200) * 0.5
    mix += tone * prog ** 2.0 * 0.28
    # timpani on D and A: slow at first, faster and louder towards the climax
    hits = [0.6, 1.7, 2.8, 3.7, 4.4, 4.95, 5.4, 5.7, 5.95]
    for i, h in enumerate(hits):
        k = 0.3 + 0.7 * i / (len(hits) - 1)
        place(mix, timpani(hz(38 if i % 2 == 0 else 33), 1.6, 0.5, 20 + i) * 0.9 * k, h)
    # open brass fifths over the D drone: D, G, A, D (only D A E G): 1.5 s each, swelling
    chords = [(38, 45, 50, 57, 62), (43, 50, 55, 62, 67), (45, 52, 57, 64, 69), (38, 45, 50, 57, 62, 69)]
    for i, ch in enumerate(chords):
        place(mix, brass(ch, 2.2, 200 + 10 * i, 0.7, 450, 1700) * (0.3 + 0.22 * i), i * 1.5)
    # the choir swells in the second half
    for i, note in enumerate((62, 69, 64, 74)):
        v = choir_voice(hz(note), 4.5, 300 + i, "ah")
        place(mix, v * swell(int(4.5 * SR), 3.2, 0.3) * 0.2, 1.5)
    mix = highpass(mix, 28)
    mix = reverb(mix, 3.0, 0.3)
    return normalize(mix, 0.9) * swell(n, 1.2, 0.12)


def finale():
    seconds = 7.0
    n = int(seconds * SR)
    t = taxis(seconds)
    mix = np.zeros(n)
    # the boom: a sub that falls, a burst of noise and a crack
    f = 62.0 * np.exp(-t / 0.9) + 30.0
    mix += np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 1.3) * 1.1
    burst = lowpass(rng(21).standard_normal(n), 2600)
    mix += burst * np.exp(-t / 0.5) * 1.2
    mix += highpass(rng(22).standard_normal(n), 3500) * np.exp(-t / 0.03) * 0.8
    for i, h in enumerate((0.0, 0.02)):
        place(mix, timpani(hz(38), 2.5, 0.9, 40 + i) * 1.1, h)
    # open D (D A D A D A and E): bass, brass, choir; no third, so it fits the hum
    for note, level in ((38, 0.9), (45, 0.7), (50, 0.7)):
        v = lowpass(saw_voice(hz(note), seconds, 400 + note, voices=3, cents=8.0), 700)
        mix += v * np.exp(-t / 2.4) * np.minimum(t / 0.05, 1.0) * level * 0.45
    place(mix, brass((50, 57, 62, 64, 69), 6.5, 500, 0.12, 900, 2600) * 0.9, 0.0)
    for i, note in enumerate((62, 69, 64, 74)):
        v = choir_voice(hz(note), 6.5, 600 + i, "ah")
        place(mix, v * swell(int(6.5 * SR), 0.9, 3.5) * 0.3, 0.0)
    # a shimmer of bells over D E G A
    for i, note in enumerate((74, 76, 79, 81, 86, 88, 91, 93)):
        place(mix, bell(hz(note), 4.0, 1.3) * 0.12, 0.35 + 0.34 * i)
    mix = highpass(mix, 26)
    mix = reverb(mix, 5.0, 0.4)
    return normalize(mix, 0.92) * swell(n, 0.003, 0.9)


if __name__ == "__main__":
    write_ogg("hum", ambient())
    write_ogg("evolve_rise", rise())
    write_ogg("evolve_finale", finale())
    print("written to", os.path.abspath(OUT))
