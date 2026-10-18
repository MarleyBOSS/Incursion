#!/usr/bin/env python3
"""Generates the textures of the Sarcophagus (the Core block), the Core Rune and the Mythic Potion.

  python3 tools/gen_sarcophagus_textures.py

Writes to src/main/resources/assets/incursion/textures/:
  block/sarkophag_marble.png      16x16  black marble with faint violet veins (outside walls, top plate)
  block/sarkophag_inner.png       16x16  darker, plainer stone (inside of the cavity, under the top plate)
  block/sarkophag_glass.png       16x16  RGBA translucent glass with soft light streaks
  block/sarkophag_gunpowder.png   16x16  heap of gunpowder (grey, grainy)
  block/sarkophag_blaze.png       16x16  heap of blaze powder (yellow-orange, grainy)
  effect/trim.png                 16x16  plain white (gold edge lines are tinted by the vertex colour, additive)
  effect/orb.png                  64x64  fire (black = nothing, additive), tiles around the sphere
  item/core_rune.png              16x16  the Core Rune (placeholder icon)
  item/mythic_potion.png          16x16  the Mythic Potion (placeholder icon)
Deterministic: running it again writes the same files.
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures")


def save(img, *path):
    full = os.path.join(ROOT, *path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full)
    print("wrote", os.path.normpath(full))


def value_noise(size_x, size_y, cells, rng, wrap=True):
    """Smooth value noise in 0..1; tiles when wrap is true."""
    grid = rng.random((cells + 1, cells + 1))
    if wrap:
        grid[-1, :] = grid[0, :]
        grid[:, -1] = grid[:, 0]
    xs = np.linspace(0, cells, size_x, endpoint=False)
    ys = np.linspace(0, cells, size_y, endpoint=False)
    x0 = np.floor(xs).astype(int)
    y0 = np.floor(ys).astype(int)
    fx = xs - x0
    fy = ys - y0
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    out = np.zeros((size_y, size_x))
    for j in range(size_y):
        for i in range(size_x):
            a = grid[y0[j], x0[i]]
            b = grid[y0[j], x0[i] + 1]
            c = grid[y0[j] + 1, x0[i]]
            d = grid[y0[j] + 1, x0[i] + 1]
            top = a + (b - a) * fx[i]
            bottom = c + (d - c) * fx[i]
            out[j, i] = top + (bottom - top) * fy[j]
    return out


def fbm(size_x, size_y, rng, octaves=((2, 0.55), (4, 0.3), (8, 0.15))):
    total = np.zeros((size_y, size_x))
    for cells, weight in octaves:
        total += value_noise(size_x, size_y, cells, rng) * weight
    return total


def to_image(rgb, alpha=None):
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is None:
        return Image.fromarray(rgb, "RGB")
    a = np.clip(alpha, 0, 255).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb, a]), "RGBA")


def marble():
    rng = np.random.default_rng(1801)
    base = fbm(16, 16, rng)
    veins = fbm(16, 16, rng, ((2, 0.6), (4, 0.4)))
    vein = np.exp(-((veins - 0.5) ** 2) / 0.004)  # thin bright lines where the noise crosses 0.5
    dark = np.array([16, 14, 22], dtype=float)
    mid = np.array([34, 30, 46], dtype=float)
    vein_col = np.array([86, 70, 120], dtype=float)
    k = base[..., None]
    rgb = dark * (1 - k) + mid * k
    rgb = rgb * (1 - 0.55 * vein[..., None]) + vein_col * 0.55 * vein[..., None]
    return to_image(rgb)


def inner():
    rng = np.random.default_rng(1802)
    base = fbm(16, 16, rng)
    dark = np.array([10, 9, 15], dtype=float)
    mid = np.array([22, 20, 32], dtype=float)
    k = base[..., None]
    return to_image(dark * (1 - k) + mid * k)


def glass():
    rng = np.random.default_rng(1803)
    n = fbm(16, 16, rng, ((2, 0.7), (4, 0.3)))
    yy, xx = np.mgrid[0:16, 0:16]
    streak = np.exp(-(((xx + yy) - 17) ** 2) / 10.0) + 0.6 * np.exp(-(((xx + yy) - 7) ** 2) / 5.0)
    rgb = np.zeros((16, 16, 3))
    rgb[..., 0] = 120 + 60 * streak + 20 * n
    rgb[..., 1] = 150 + 60 * streak + 20 * n
    rgb[..., 2] = 185 + 50 * streak + 20 * n
    alpha = 70 + 70 * streak + 25 * n  # mostly see-through, brighter in the streaks
    return to_image(rgb, alpha)


def powder(name, dark, light, seed):
    rng = np.random.default_rng(seed)
    grain = rng.random((16, 16))
    blob = fbm(16, 16, rng, ((4, 0.6), (8, 0.4)))
    k = (0.55 * grain + 0.45 * blob)[..., None]
    rgb = np.array(dark, dtype=float) * (1 - k) + np.array(light, dtype=float) * k
    return to_image(rgb)


def trim():
    return to_image(np.full((16, 16, 3), 255.0))


def orb():
    rng = np.random.default_rng(1804)
    size = 64
    n1 = fbm(size, size, rng, ((4, 0.5), (8, 0.3), (16, 0.2)))
    n2 = fbm(size, size, rng, ((8, 0.6), (16, 0.4)))
    heat = np.clip(n1 * 1.25 + (n2 - 0.5) * 0.7, 0, 1)
    heat = heat ** 1.4
    # fade towards the poles so the seam of the sphere caps stays quiet
    yy = np.linspace(0, 1, size)[:, None]
    heat *= (0.55 + 0.45 * np.sin(np.pi * yy))
    c0 = np.array([0, 0, 0], dtype=float)
    c1 = np.array([200, 40, 0], dtype=float)
    c2 = np.array([255, 150, 10], dtype=float)
    c3 = np.array([255, 245, 190], dtype=float)
    rgb = np.zeros((size, size, 3))
    for j in range(size):
        for i in range(size):
            h = heat[j, i]
            if h < 0.35:
                rgb[j, i] = c0 + (c1 - c0) * (h / 0.35)
            elif h < 0.65:
                rgb[j, i] = c1 + (c2 - c1) * ((h - 0.35) / 0.30)
            else:
                rgb[j, i] = c2 + (c3 - c2) * min(1.0, (h - 0.65) / 0.30)
    return to_image(rgb)


PALETTE = {
    ".": None,
    "O": (22, 16, 8),      # dark outline
    "o": (74, 52, 14),     # gold shadow
    "G": (214, 170, 60),   # gold
    "g": (255, 222, 120),  # bright gold
    "V": (38, 26, 58),     # dark violet
    "v": (74, 46, 112),    # violet
    "w": (136, 92, 190),   # light violet
    "L": (232, 90, 12),    # liquid orange
    "l": (255, 150, 20),   # liquid light
    "Y": (255, 236, 150),  # liquid glow
    "K": (96, 62, 30),     # cork
    "k": (140, 96, 50),    # cork light
    "S": (190, 210, 230),  # glass
    "s": (235, 245, 255),  # glass highlight
}


def from_art(rows):
    rgba = np.zeros((16, 16, 4), dtype=np.uint8)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            colour = PALETTE[ch]
            if colour is not None:
                rgba[y, x] = (*colour, 255)
    return Image.fromarray(rgba, "RGBA")


RUNE = [
    "................",
    "....OOOOOOOO....",
    "...OGGGGGGGGO...",
    "..OGVVVVVVVVGO..",
    "..OGVvvvvvvVGO..",
    "..OGVvvgvvvVGO..",
    "..OGVvgYgvvVGO..",
    "..OGVgYYYgvVGO..",
    "..OGVvgYgvvVGO..",
    "..OGVvvgvvvVGO..",
    "..OGVvgvgvvVGO..",
    "..OGVvvvvvvVGO..",
    "..OGVVVVVVVVGO..",
    "...OGGGGGGGGO...",
    "....OOOOOOOO....",
    "................",
]

POTION = [
    "................",
    ".....OKKKKO.....",
    ".....OkKKKO.....",
    "......OSSO......",
    "......OSSO......",
    ".....OSssSO.....",
    "....OSsLLLSO....",
    "...OSsLlllLSO...",
    "..OSsLlYYllLSO..",
    "..OSLlYYYYllSO..",
    "..OSLllYYllLSO..",
    "..OSLLllllLLSO..",
    "...OSLLLLLLSO...",
    "....OSSSSSSO....",
    ".....OOOOOO.....",
    "................",
]


def main():
    save(marble(), "block", "sarkophag_marble.png")
    save(inner(), "block", "sarkophag_inner.png")
    save(glass(), "block", "sarkophag_glass.png")
    save(powder("gun", (40, 40, 44), (120, 120, 126), 1805), "block", "sarkophag_gunpowder.png")
    save(powder("blaze", (200, 120, 10), (255, 214, 70), 1806), "block", "sarkophag_blaze.png")
    save(trim(), "effect", "trim.png")
    save(orb(), "effect", "orb.png")
    save(from_art(RUNE), "item", "core_rune.png")
    save(from_art(POTION), "item", "mythic_potion.png")


if __name__ == "__main__":
    main()
