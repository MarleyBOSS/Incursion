#!/usr/bin/env python3
"""Generates the Core Table block textures (dark obsidian-violet stone with glowing violet runes).

  python3 tools/gen_core_table_texture.py

Writes core_table_top.png, core_table_side.png and core_table_bottom.png (16x16) to
src/main/resources/assets/incursion/textures/block/. Deterministic (fixed seed). Like the Core and loot chest
textures the faces are not flat: several similar tones from noise, a soft vertical gradient and a bevel.
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures", "block")
RNG = np.random.default_rng(20261017)

RAMP = np.array([(22, 16, 33), (32, 23, 49), (44, 31, 66), (58, 42, 86), (74, 54, 108), (90, 68, 128)], dtype=float)
EDGE_LIGHT = np.array((70, 52, 98), dtype=float)
EDGE_DARK = np.array((14, 10, 22), dtype=float)
GLOW = [np.array(c, dtype=float) for c in ((92, 44, 132), (140, 66, 190), (186, 110, 236), (230, 190, 255))]


def noise(w, h, scale):
    gw, gh = max(2, w // scale + 2), max(2, h // scale + 2)
    grid = RNG.random((gh, gw))
    img = Image.fromarray((grid * 255).astype(np.uint8)).resize((gw * scale, gh * scale), Image.BICUBIC)
    return np.asarray(img, dtype=float)[:h, :w] / 255.0


def ramp(v):
    pos = np.clip(v, 0.0, 1.0) * (len(RAMP) - 1)
    lo = np.floor(pos).astype(int)
    hi = np.minimum(lo + 1, len(RAMP) - 1)
    t = (pos - lo)[..., None]
    return RAMP[lo] * (1 - t) + RAMP[hi] * t


def stone(size=16, light=0.0):
    v = 0.34 + 0.36 * noise(size, size, 4) + 0.20 * noise(size, size, 2) + 0.10 * RNG.random((size, size))
    v += light + 0.12 - 0.24 * (np.arange(size)[:, None] / (size - 1))
    return ramp(v)


def bevel(img):
    out = img.copy()
    out[0, :] = EDGE_LIGHT
    out[:, 0] = EDGE_LIGHT
    out[-1, :] = EDGE_DARK
    out[:, -1] = EDGE_DARK
    return out


def glow_pixel(img, x, y, strength):
    idx = int(np.clip(strength, 0, len(GLOW) - 1))
    img[y, x] = GLOW[idx]


def top():
    img = bevel(stone(light=0.05))
    # A rune ring with a bright centre.
    for x in range(4, 12):
        for y in (4, 11):
            glow_pixel(img, x, y, 1)
    for y in range(4, 12):
        for x in (4, 11):
            glow_pixel(img, x, y, 1)
    for x in range(6, 10):
        for y in range(6, 10):
            glow_pixel(img, x, y, 2)
    glow_pixel(img, 7, 7, 3)
    glow_pixel(img, 8, 7, 3)
    glow_pixel(img, 7, 8, 3)
    glow_pixel(img, 8, 8, 3)
    return img


def side():
    img = bevel(stone())
    # A glowing band near the top and two small rune marks.
    for x in range(1, 15):
        glow_pixel(img, x, 3, 1)
        glow_pixel(img, x, 4, 0)
    for x in (4, 7, 10):
        glow_pixel(img, x, 8, 2)
        glow_pixel(img, x, 9, 1)
        glow_pixel(img, x, 10, 2)
    return img


def bottom():
    return bevel(stone(light=-0.06))


def save(name, img):
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA").save(os.path.join(OUT, name))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    save("core_table_top.png", top())
    save("core_table_side.png", side())
    save("core_table_bottom.png", bottom())
    print("written to", os.path.abspath(OUT))
