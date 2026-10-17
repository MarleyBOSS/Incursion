#!/usr/bin/env python3
"""Generates the Core Table block textures (obsidian frame, violet cloth with a glowing rune and gem corners, like the vanilla enchanting table).

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

OBS = np.array([(10, 7, 18), (16, 11, 28), (24, 17, 40), (34, 24, 56), (46, 33, 74)], dtype=float)
CLOTH = np.array([(52, 22, 92), (70, 32, 122), (92, 44, 150), (112, 58, 178)], dtype=float)
GEM = [np.array(c, dtype=float) for c in ((40, 150, 170), (90, 230, 235), (200, 255, 255))]
GLOW = {"idle": [(120, 60, 176), (170, 100, 224), (214, 160, 255), (245, 225, 255)],
        "work": [(150, 80, 210), (200, 130, 255), (232, 190, 255), (255, 245, 255)],
        "done": [(60, 160, 90), (110, 220, 130), (170, 255, 190), (240, 255, 245)]}
GOLD = np.array((200, 150, 60), dtype=float)


def noise(w, h, scale):
    gw, gh = max(2, w // scale + 2), max(2, h // scale + 2)
    grid = RNG.random((gh, gw))
    img = Image.fromarray((grid * 255).astype(np.uint8)).resize((gw * scale, gh * scale), Image.BICUBIC)
    return np.asarray(img, dtype=float)[:h, :w] / 255.0


def lerp_ramp(table, v):
    pos = np.clip(v, 0.0, 1.0) * (len(table) - 1)
    lo = np.floor(pos).astype(int)
    hi = np.minimum(lo + 1, len(table) - 1)
    t = (pos - lo)[..., None]
    return table[lo] * (1 - t) + table[hi] * t


def obsidian(light=0.0):
    v = 0.30 + 0.35 * noise(16, 16, 4) + 0.20 * noise(16, 16, 2) + 0.15 * RNG.random((16, 16)) + light
    return lerp_ramp(OBS, v)


def cloth(light=0.0):
    v = 0.45 + 0.25 * noise(16, 16, 2) + 0.15 * RNG.random((16, 16)) + light
    img = lerp_ramp(CLOTH, v)
    img[::2, :] *= 0.94  # faint weave
    return img


def top(mode):
    glow = [np.array(c, dtype=float) for c in GLOW[mode]]
    img = obsidian()
    # Cloth field inside a 2px obsidian frame, like the red carpet of the vanilla table.
    img[2:14, 2:14] = cloth(0.08 if mode != "idle" else 0.0)[2:14, 2:14]
    # Gold-ish stitched border on the cloth.
    for i in range(3, 13):
        for (x, y) in ((i, 3), (i, 12), (3, i), (12, i)):
            img[y, x] = GOLD * (0.55 if (i % 2) else 0.8)
    # Glowing rune: ring with a bright core and four tips.
    for (x, y) in ((6, 5), (7, 5), (8, 5), (9, 5), (6, 10), (7, 10), (8, 10), (9, 10),
                   (5, 6), (5, 7), (5, 8), (5, 9), (10, 6), (10, 7), (10, 8), (10, 9)):
        img[y, x] = glow[1]
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        img[y, x] = glow[3]
    for (x, y) in ((7, 6), (8, 6), (6, 7), (9, 7), (6, 8), (9, 8), (7, 9), (8, 9)):
        img[y, x] = glow[2]
    for (x, y) in ((7, 4), (8, 4), (7, 11), (8, 11), (4, 7), (4, 8), (11, 7), (11, 8)):
        img[y, x] = glow[0]
    # Diamond-like gems in the four corners of the frame, as on the vanilla table.
    for (cx, cy) in ((0, 0), (14, 0), (0, 14), (14, 14)):
        for dx in range(2):
            for dy in range(2):
                img[cy + dy, cx + dx] = GEM[1]
        img[cy, cx] = GEM[2]
        img[cy + 1, cx + 1] = GEM[0]
    # Bevel on the frame.
    img[0, 2:14] = OBS[4]
    img[15, 2:14] = OBS[0]
    return img


def side():
    img = obsidian()
    # Rows 0-3 are hidden by the model (it uses uv 0,4 -> 16,16); the visible part starts with a cloth hem.
    img[4:6, :] = cloth(0.05)[4:6, :]
    img[6, :] = GOLD * 0.6
    img[7, :] = OBS[0]
    # Glowing rune marks and gem studs in the obsidian below.
    for x in (3, 7, 11):
        img[10, x] = np.array(GLOW["idle"][1])
        img[11, x] = np.array(GLOW["idle"][2])
        img[12, x] = np.array(GLOW["idle"][1])
    img[9, 1] = GEM[1]
    img[9, 14] = GEM[1]
    img[0, :] = OBS[4]
    img[15, :] = OBS[0]
    img[:, 0] = OBS[3]
    img[:, 15] = OBS[0]
    return img


def bottom():
    img = obsidian(-0.04)
    img[0, :] = OBS[3]
    img[:, 0] = OBS[3]
    return img


def save(name, img):
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA").save(os.path.join(OUT, name))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    save("core_table_top.png", top("idle"))
    save("core_table_top_working.png", top("work"))
    save("core_table_top_done.png", top("done"))
    save("core_table_side.png", side())
    save("core_table_bottom.png", bottom())
    print("written to", os.path.abspath(OUT))
