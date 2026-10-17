#!/usr/bin/env python3
"""Generates the Incursion Core textures (the shell that unfolds, the crystal, the lasers).

  python3 tools/gen_core_textures.py

Writes (16x16 frames, animated ones as vertical strips with a .mcmeta) to src/main/resources/assets/incursion/textures/:
  block/core_shell_side.png    animated  side of the shell: hinge, rune panel, glowing seam, conduits
  block/core_shell_top.png     animated  lid: the "iris" seal
  block/core_shell_inner.png   animated  inside: the dish with the emitters, ripples
  block/core_shell_bottom.png            plain obsidian (underside, plate edges)
  block/core_shell_rod.png               gold piston rod
  block/core_crystal.png                 32x32 RGBA facet of the crystal (drawn on every triangle of the octahedron)
  effect/laser.png                       additive (black = nothing) laser ribbon
Uses the palette of the Core Table (same builders, see gen_core_table_texture.py).
"""
import os

import numpy as np
from PIL import Image

import gen_core_table_texture as table

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures")
BLOCK = os.path.join(ROOT, "block")
EFFECT = os.path.join(ROOT, "effect")
F = table.FRAMES
OBS, GOLD, GEM, CLOTH = table.OBS, table.GOLD, table.GEM, table.CLOTH
VIOLET = [np.array(c, dtype=float) for c in ((92, 40, 140), (140, 64, 200), (190, 120, 245), (236, 200, 255))]
CYAN = [np.array(c, dtype=float) for c in ((30, 130, 150), (80, 220, 235), (200, 255, 255))]


def mix(a, b, k):
    return a * (1 - k) + b * k


def pulse(phase, offset=0.0):
    return 0.5 + 0.5 * np.sin(2 * np.pi * (phase - offset))


def side_base():
    img = table.obsidian()
    img[:, 0:2] = OBS[3] * 1.05            # corner posts
    img[:, 14:16] = OBS[3] * 1.05
    img[:, 2] = OBS[0]
    img[:, 13] = OBS[0]
    for (x, y) in ((0, 4), (15, 4), (0, 12), (15, 12), (1, 4), (14, 4), (1, 12), (14, 12)):
        img[y, x] = GOLD * (0.9 if x in (0, 15) else 0.55)
    img[0, :] = OBS[4]                      # lid edge
    img[1, :] = OBS[1]
    img[2, 2:14] = GOLD * 0.7               # hinge line
    for y in range(3, 7):                   # plate panel (cloth inlay)
        for x in range(3, 13):
            img[y, x] = mix(OBS[1], CLOTH[0], 0.55)
    img[7, 2:14] = OBS[0]                   # shadow above the seam
    for y in range(9, 15):                  # lower panel
        for x in range(3, 13):
            img[y, x] = mix(OBS[0], OBS[2], 0.4)
    img[15, :] = OBS[0]
    return img


RUNE = [(7, 3), (8, 3), (6, 4), (7, 4), (8, 4), (9, 4), (7, 5), (8, 5)]


def side_frame(base, f):
    img = base.copy()
    ph = f / F
    for (x, y) in RUNE:
        img[y, x] = mix(VIOLET[1], VIOLET[3], 0.3 + 0.7 * pulse(ph))
    # Seam: a cyan line with a bright dot running along it.
    for x in range(2, 14):
        d = abs(((x - 2) / 12.0) - ph)
        d = min(d, 1 - d)
        img[8, x] = mix(CYAN[0], CYAN[2], max(0.0, 1.0 - d * 6.0) * 0.9 + 0.1)
    # Conduits in the lower panel pulse one after the other, the pulse runs downwards.
    for i, x in enumerate((4, 8, 11)):
        for y in range(10, 14):
            k = pulse(ph, i / 3.0 + (y - 10) / 12.0)
            img[y, x] = mix(VIOLET[0], VIOLET[3], k)
    return img


def top_base():
    img = table.obsidian()
    for y in range(16):
        for x in range(16):
            dc = max(abs(x - 7.5), abs(y - 7.5))
            if dc < 5.5:
                img[y, x] = mix(OBS[1], CLOTH[0], 0.65)
            elif dc < 6.5:
                img[y, x] = GOLD * (0.75 if (x + y) % 2 == 0 else 0.55)
    for (cx, cy) in ((0, 0), (14, 0), (0, 14), (14, 14)):
        for dx in range(2):
            for dy in range(2):
                img[cy + dy, cx + dx] = GEM[1]
        img[cy, cx] = GEM[2]
    img[0, 2:14] = OBS[4]
    img[15, 2:14] = OBS[0]
    return img


def top_frame(base, f):
    img = base.copy()
    ph = f / F
    for y in range(16):
        for x in range(16):
            d1 = abs(x - 7.5) + abs(y - 7.5)
            if d1 <= 1.5:
                img[y, x] = mix(VIOLET[2], VIOLET[3], 0.4 + 0.6 * pulse(ph))
            elif 2.5 <= d1 <= 3.5:
                img[y, x] = mix(VIOLET[1], VIOLET[3], pulse(ph, 0.15))
            elif 4.5 <= d1 <= 5.5 and max(abs(x - 7.5), abs(y - 7.5)) < 5.5:
                img[y, x] = mix(VIOLET[0], VIOLET[2], pulse(ph, 0.3))
    if f in (2, 3):
        img[0, 0] = (255, 255, 255)
    if f in (10, 11):
        img[15, 15] = (255, 255, 255)
    return img


def inner_base():
    img = table.obsidian(-0.03)
    for (cx, cy) in ((0, 0), (13, 0), (0, 13), (13, 13)):   # emitter pads
        for dx in range(3):
            for dy in range(3):
                img[cy + dy, cx + dx] = OBS[3]
        img[cy + 1, cx + 1] = CYAN[1]
    return img


def inner_frame(base, f):
    img = base.copy()
    ph = f / F
    for y in range(16):
        for x in range(16):
            dc = max(abs(x - 7.5), abs(y - 7.5))
            if 1.5 <= dc <= 6.5 and not ((x < 3 or x > 12) and (y < 3 or y > 12)):
                ring = pulse(ph, dc / 7.0)
                if int(round(dc)) % 2 == 0:
                    img[y, x] = mix(OBS[1], VIOLET[1], ring * 0.85)
    for (cx, cy) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        img[cy, cx] = mix(CYAN[1], CYAN[2], pulse(ph, 0.2))
    return img


def bottom():
    img = table.obsidian(-0.04)
    img[0, :] = OBS[3]
    img[:, 0] = OBS[3]
    return img


def rod():
    img = np.zeros((16, 16, 3))
    for y in range(16):
        for x in range(16):
            shade = 0.55 + 0.45 * np.cos((x - 7.5) / 8.0 * np.pi / 2)
            img[y, x] = GOLD * shade
            if y % 5 == 0:
                img[y, x] *= 0.55
    return img


def crystal():
    """One facet of the octahedron (the apex is at the top middle): deep violet with a bright core and edge lines."""
    size = 32
    y, x = np.mgrid[0:size, 0:size].astype(float)
    # Barycentric-ish coordinates of the triangle (0.5,0), (0,1), (1,1).
    u = x / (size - 1)
    v = y / (size - 1)
    inside_left = u >= 0.5 * (1 - v) - 0.001
    inside_right = u <= 1.0 - 0.5 * (1 - v) + 0.001
    inside = inside_left & inside_right
    edge = np.minimum(u - 0.5 * (1 - v), (1.0 - 0.5 * (1 - v)) - u)
    edge = np.minimum(edge, 1.0 - v) * 4.0  # distance to the sides, roughly
    core = np.exp(-(((u - 0.5) / 0.22) ** 2 + ((v - 0.62) / 0.28) ** 2))
    rng = np.random.default_rng(11)
    noise = rng.random((size, size)) * 0.08
    base = np.array((70, 28, 130), dtype=float)
    bright = np.array((205, 150, 255), dtype=float)
    k = np.clip(0.25 + 0.55 * core + 0.35 * (1.0 - v) * 0.4 + noise, 0, 1)
    rgb = base[None, None, :] * (1 - k[..., None]) + bright[None, None, :] * k[..., None]
    line = np.clip(1.0 - edge / 0.9, 0, 1)
    rgb = rgb * (1 - line[..., None]) + np.array((245, 225, 255))[None, None, :] * line[..., None]
    alpha = np.where(inside, 150 + 90 * core + 60 * line, 0)
    out = np.zeros((size, size, 4), dtype=np.uint8)
    out[..., 0:3] = np.clip(rgb, 0, 255).astype(np.uint8)
    out[..., 3] = np.clip(alpha, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def laser():
    w, h = 16, 16
    y, x = np.mgrid[0:h, 0:w]
    across = np.exp(-(((x - w / 2 + 0.5) / (w * 0.16)) ** 2))
    return table.glow_image(across * (0.85 + 0.15 * np.sin(y / h * 2 * np.pi)))


def save_rgb(path, arr):
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA").save(path)


def save_animated(name, frames, frametime):
    save_rgb(os.path.join(BLOCK, name), np.concatenate(frames, axis=0))
    with open(os.path.join(BLOCK, name + ".mcmeta"), "w") as fh:
        fh.write('{\n  "animation": {\n    "frametime": %d,\n    "interpolate": true\n  }\n}\n' % frametime)


if __name__ == "__main__":
    os.makedirs(BLOCK, exist_ok=True)
    os.makedirs(EFFECT, exist_ok=True)
    sb, tb, ib = side_base(), top_base(), inner_base()
    save_animated("core_shell_side.png", [side_frame(sb, f) for f in range(F)], 3)
    save_animated("core_shell_top.png", [top_frame(tb, f) for f in range(F)], 3)
    save_animated("core_shell_inner.png", [inner_frame(ib, f) for f in range(F)], 2)
    save_rgb(os.path.join(BLOCK, "core_shell_bottom.png"), bottom())
    save_rgb(os.path.join(BLOCK, "core_shell_rod.png"), rod())
    crystal().save(os.path.join(BLOCK, "core_crystal.png"))
    laser().save(os.path.join(EFFECT, "laser.png"))
    print("written to", os.path.abspath(ROOT))
