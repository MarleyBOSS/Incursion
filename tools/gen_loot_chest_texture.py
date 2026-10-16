#!/usr/bin/env python3
"""Generates the loot chest textures (dark obsidian-violet stone with a glowing band and a violet gem lock).

  python3 tools/gen_loot_chest_texture.py

Writes src/main/resources/assets/incursion/textures/entity/chest/loot_chest.png (64x64, the vanilla chest layout:
lid box at (0,0) 14x5x14, body box at (0,19) 14x10x14, lock box at (0,0) 2x4x1) and
src/main/resources/assets/incursion/textures/block/loot_chest.png (16x16, only used as the break particle).

Like the Core texture the surfaces are not flat colours: several similar tones from multi-scale noise, a soft vertical
gradient, a bevel on the frames and a few purple veins like the siege stone.
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures")
RNG = np.random.default_rng(20261017)

# Tone ramp of the stone, dark to light (RGB).
RAMP = np.array([(22, 16, 33), (32, 23, 49), (44, 31, 66), (58, 42, 86), (74, 54, 108), (90, 68, 128)], dtype=float)
FRAME = np.array((16, 12, 22), dtype=float)
FRAME_LIGHT = np.array((62, 48, 86), dtype=float)
FRAME_DARK = np.array((26, 19, 38), dtype=float)
VEIN = [np.array(c, dtype=float) for c in ((92, 44, 132), (132, 58, 176), (168, 84, 214))]
GLOW = [np.array(c, dtype=float) for c in ((60, 34, 92), (104, 62, 156), (150, 96, 206), (196, 150, 240))]
GEM = [np.array(c, dtype=float) for c in ((70, 34, 112), (122, 64, 180), (170, 110, 230), (226, 190, 255))]


def noise(w, h, scale):
    """Smooth value noise in [0, 1] (random grid, bicubic upscaled)."""
    gw, gh = max(2, w // scale + 2), max(2, h // scale + 2)
    grid = RNG.random((gh, gw))
    img = Image.fromarray((grid * 255).astype(np.uint8)).resize((gw * scale, gh * scale), Image.BICUBIC)
    return np.asarray(img, dtype=float)[:h, :w] / 255.0


def ramp(v):
    """Colour of the stone ramp for a value in [0, 1]."""
    pos = np.clip(v, 0.0, 1.0) * (len(RAMP) - 1)
    lo = np.floor(pos).astype(int)
    hi = np.minimum(lo + 1, len(RAMP) - 1)
    t = (pos - lo)[..., None]
    return RAMP[lo] * (1 - t) + RAMP[hi] * t


def stone(w, h, light=0.0):
    """A noisy stone surface with a soft vertical gradient (lighter on top)."""
    v = 0.36 + 0.36 * noise(w, h, 4) + 0.20 * noise(w, h, 2) + 0.12 * RNG.random((h, w))
    v += light + 0.16 - 0.30 * (np.arange(h)[:, None] / max(1, h - 1))
    return ramp(v)


def frame(img, width=1, bevel=True):
    """Dark frame around the face with a light edge inside (top/left) and a dark one (bottom/right)."""
    h, w = img.shape[:2]
    out = img.copy()
    if bevel and w > 2 * width + 2 and h > 2 * width + 2:
        out[width, width:w - width] = FRAME_LIGHT * 0.75 + out[width, width:w - width] * 0.25
        out[width:h - width, width] = FRAME_LIGHT * 0.6 + out[width:h - width, width] * 0.4
        out[h - width - 1, width:w - width] = FRAME_DARK
        out[width:h - width, w - width - 1] = FRAME_DARK
    for k in range(width):
        out[k, :] = FRAME
        out[h - 1 - k, :] = FRAME
        out[:, k] = FRAME
        out[:, w - 1 - k] = FRAME
    return out


def veins(img, count, inner):
    """A few short diagonal purple veins (like the siege stone), each with a soft dark halo."""
    h, w = img.shape[:2]
    x0, y0, x1, y1 = inner
    for _ in range(count):
        x = int(RNG.integers(x0 + 1, max(x0 + 2, x1 - 3)))
        y = int(RNG.integers(y0 + 1, max(y0 + 2, y1 - 3)))
        length = int(RNG.integers(3, 6))
        step = int(RNG.choice([-1, 1]))
        for i in range(length):
            px, py = x + i * step, y + i // 2 + (i % 2)
            if not (x0 <= px < x1 and y0 <= py < y1):
                break
            img[py, px] = VEIN[min(2, i if i < 3 else 5 - i if 5 - i >= 0 else 0)]
            for dx, dy in ((1, 0), (0, 1)):
                qx, qy = px + dx, py + dy
                if x0 <= qx < x1 and y0 <= qy < y1 and not any((img[qy, qx] == v).all() for v in VEIN):
                    img[qy, qx] = img[qy, qx] * 0.7 + np.array((60, 28, 90)) * 0.3
    return img


def band(img, rows, inner_x):
    """A glowing horizontal band: bright core, softer edges, dark outline above and below."""
    x0, x1 = inner_x
    top, bottom = rows
    n = bottom - top + 1
    for i, y in enumerate(range(top, bottom + 1)):
        if n == 1:
            tone = GLOW[2]
        else:
            tone = GLOW[1] if i in (0, n - 1) else GLOW[2] if i == n // 2 else GLOW[1]
        for x in range(x0, x1):
            shimmer = 0.88 + 0.12 * RNG.random()
            img[y, x] = tone * shimmer
    for y in (top - 1, bottom + 1):
        if 0 <= y < img.shape[0]:
            img[y, x0:x1] = FRAME_DARK * 0.9 + img[y, x0:x1] * 0.1
    return img


def face(w, h, light=0.0, border=1, vein_count=1, band_rows=None):
    img = stone(w, h, light)
    img = veins(img, vein_count, (border + 1, border + 1, w - border - 1, h - border - 1))
    if band_rows:
        img = band(img, band_rows, (border, w - border))
    return frame(img, border)


def put(canvas, x, y, img):
    h, w = img.shape[:2]
    canvas[y:y + h, x:x + w, :3] = np.clip(np.rint(img), 0, 255)
    canvas[y:y + h, x:x + w, 3] = 255


def chest_texture():
    canvas = np.zeros((64, 64, 4), dtype=float)
    # Lid box (14 x 5 x 14) at (0, 0): top, bottom, then the four sides (h = 5).
    put(canvas, 14, 0, face(14, 14, light=0.05, border=1, vein_count=2))
    put(canvas, 28, 0, face(14, 14, light=-0.25, border=1, vein_count=0))
    for x in (0, 14, 28, 42):
        w = 14
        side = face(w, 5, light=0.02, border=1, vein_count=0, band_rows=(2, 2))
        put(canvas, x, 14, side)
    # Body box (14 x 10 x 14) at (0, 19): top (hidden by the lid), bottom, four sides (h = 10).
    put(canvas, 14, 19, face(14, 14, light=-0.1, border=1, vein_count=0))
    put(canvas, 28, 19, face(14, 14, light=-0.28, border=1, vein_count=0))
    for x in (0, 14, 28, 42):
        side = face(14, 10, light=0.0, border=1, vein_count=2, band_rows=(2, 2))
        # Darker plinth at the foot.
        side[8, 1:13] = side[8, 1:13] * 0.78
        put(canvas, x, 33, side)
    # Lock (2 x 4 x 1) at (0, 0): top, bottom, west, front, east, back. The front is the gem.
    lock_tone = FRAME_LIGHT * 0.8
    put(canvas, 1, 0, np.tile(lock_tone, (1, 2, 1)))
    put(canvas, 3, 0, np.tile(FRAME_DARK, (1, 2, 1)))
    put(canvas, 0, 1, np.tile(FRAME_DARK, (4, 1, 1)))
    put(canvas, 3, 1, np.tile(FRAME_DARK, (4, 1, 1)))
    put(canvas, 4, 1, np.tile(FRAME_DARK, (4, 2, 1)))
    gem = np.zeros((4, 2, 3))
    gem[:] = FRAME
    gem[1, 0], gem[1, 1] = GEM[3], GEM[2]
    gem[2, 0], gem[2, 1] = GEM[2], GEM[1]
    gem[3, 0], gem[3, 1] = GEM[1], GEM[0]
    gem[0, 0], gem[0, 1] = FRAME_LIGHT, FRAME_LIGHT * 0.8
    put(canvas, 1, 1, gem)
    return canvas


def block_texture():
    """16x16 break particle: a piece of the chest front."""
    img = face(16, 16, light=0.0, border=1, vein_count=3, band_rows=(7, 8))
    canvas = np.zeros((16, 16, 4), dtype=float)
    put(canvas, 0, 0, img)
    return canvas


def save(canvas, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(np.rint(canvas), 0, 255).astype(np.uint8), "RGBA").save(path)
    print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    save(chest_texture(), os.path.join(ROOT, "entity", "chest", "loot_chest.png"))
    save(block_texture(), os.path.join(ROOT, "block", "loot_chest.png"))
