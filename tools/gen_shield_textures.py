#!/usr/bin/env python3
"""Generates the shield textures (white + alpha, tinted in the renderer).

  python3 tools/gen_shield_textures.py

Writes src/main/resources/assets/incursion/textures/effect/shield_hex.png (hex plasma cells, tiles seamlessly,
4 cells wide, 8 rows high) and shield_plasma.png (soft plasma clouds, tiles seamlessly).
"""
import math
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion",
                   "textures", "effect")
os.makedirs(OUT, exist_ok=True)


def hex_texture():
    w_cell = 64.0                       # cell width (flat to flat, pointy-top hexagons)
    cols, rows = 4, 8
    width = int(w_cell * cols)          # 256
    height = 448                        # 8 rows with a row pitch of 56 (a regular hex has 55.43)
    pitch = height / rows
    # stretch y so that the lattice is regular: hexes are slightly taller than wide in texture space
    ystretch = (w_cell / math.sqrt(3.0) * 1.5) / pitch
    apothem = w_cell / 2.0

    ys, xs = np.mgrid[0:height, 0:width].astype(np.float64)
    xs += 0.5
    ys = (ys + 0.5) * ystretch

    best = np.full(xs.shape, 1e18)
    best_dx = np.zeros_like(xs)
    best_dy = np.zeros_like(xs)
    best_id = np.zeros(xs.shape, dtype=np.int64)
    for r in range(-1, rows + 1):
        for c in range(-1, cols + 1):
            cx = c * w_cell + (w_cell / 2.0 if r % 2 else 0.0) + w_cell / 2.0
            cy = r * pitch * ystretch + pitch * ystretch / 2.0
            dx = xs - cx
            dy = ys - cy
            d = dx * dx + dy * dy
            closer = d < best
            best = np.where(closer, d, best)
            best_dx = np.where(closer, dx, best_dx)
            best_dy = np.where(closer, dy, best_dy)
            best_id = np.where(closer, (r % rows) * 16 + (c % cols), best_id)

    # hexagon "distance": 0 in the centre, 1 on the edge
    t = np.zeros_like(xs)
    for k in range(3):
        ang = math.radians(k * 60.0)
        t = np.maximum(t, np.abs(best_dx * math.cos(ang) + best_dy * math.sin(ang)))
    t = t / apothem

    rng = np.random.default_rng(7)
    cell_noise = rng.random(rows * 16 + cols + 1)
    flicker = cell_noise[best_id]

    edge = np.clip((t - 0.80) / 0.20, 0.0, 1.0)
    line = edge ** 1.5                                    # bright, soft ridge on the cell border
    core_glow = (1.0 - np.clip(t, 0, 1)) ** 2.0           # faint glow in the middle of a cell
    fill = 0.10 + 0.20 * flicker                          # every cell glows a bit differently
    alpha = np.clip(line * 1.0 + fill * (0.35 + 0.65 * core_glow) * (1.0 - line), 0.0, 1.0)
    # sharpen the very edge so it reads as a crisp line
    alpha = np.clip(alpha + (edge > 0.93) * 0.35, 0.0, 1.0)

    img = np.zeros((height, width, 4), dtype=np.uint8)
    img[..., :3] = 255
    img[..., 3] = (alpha * 255).astype(np.uint8)
    Image.fromarray(img, "RGBA").save(os.path.join(OUT, "shield_hex.png"))


def plasma_texture(size=256, seed=11):
    rng = np.random.default_rng(seed)
    noise = rng.standard_normal((size, size))
    fx = np.fft.fftfreq(size)[:, None]
    fy = np.fft.fftfreq(size)[None, :]
    freq = np.sqrt(fx * fx + fy * fy)
    freq[0, 0] = 1.0
    spectrum = np.fft.fft2(noise) / (freq ** 2.4)       # 1/f-ish noise, periodic by construction
    spectrum[0, 0] = 0.0
    field = np.real(np.fft.ifft2(spectrum))
    field = (field - field.min()) / (field.max() - field.min())
    # turn it into thin bright "plasma veins" plus soft clouds
    veins = 1.0 - np.abs(field - 0.5) * 2.0
    veins = np.clip(veins, 0.0, 1.0) ** 10.0
    clouds = np.clip((field - 0.55) / 0.45, 0.0, 1.0) ** 1.5
    alpha = np.clip(veins * 0.85 + clouds * 0.30, 0.0, 1.0)
    img = np.zeros((size, size, 4), dtype=np.uint8)
    img[..., :3] = 255
    img[..., 3] = (alpha * 255).astype(np.uint8)
    Image.fromarray(img, "RGBA").save(os.path.join(OUT, "shield_plasma.png"))


if __name__ == "__main__":
    hex_texture()
    plasma_texture()
    print("written to", os.path.abspath(OUT))
