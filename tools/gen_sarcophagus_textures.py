#!/usr/bin/env python3
"""Generates the textures of the Sarcophagus (the Core block), the Core Rune and the Mythic Potion.

  python3 tools/gen_sarcophagus_textures.py

Writes to src/main/resources/assets/incursion/textures/:
  block/sarkophag_wall.png / _top.png  128x128  black marble with a beaded gold border (walls with V notch / top plate)
  block/sarkophag_marble.png      64x64  plain black marble (floor, slab sides)
  block/sarkophag_gold.png        16x16  gold for the 3D edge bars
  effect/flare.png, crystal.png   star of light, violet crystal energy (additive)
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


GOLD = np.array([214, 170, 60], dtype=float)
GOLD_LIGHT = np.array([255, 226, 130], dtype=float)
GOLD_DARK = np.array([112, 80, 20], dtype=float)


def marble_rgb(size, seed):
    """Glossy black marble: dark body, faint pale cracks, a few thin gold threads."""
    rng = np.random.default_rng(seed)
    base = fbm(size, size, rng, ((2, 0.5), (4, 0.3), (8, 0.2)))
    cracks = fbm(size, size, rng, ((3, 0.6), (6, 0.4)))
    threads = fbm(size, size, rng, ((2, 0.7), (5, 0.3)))
    dark = np.array([7, 7, 11], dtype=float)
    mid = np.array([27, 26, 36], dtype=float)
    k = base[..., None]
    rgb = dark * (1 - k) + mid * k
    crack = np.exp(-((cracks - 0.5) ** 2) / 0.0010)
    rgb += crack[..., None] * np.array([70, 66, 82], dtype=float) * 0.5
    thread = np.exp(-((threads - 0.63) ** 2) / 0.0004)
    rgb += thread[..., None] * np.array([150, 110, 30], dtype=float) * 0.45
    return rgb


def gold_band(rgb, dist, lo, hi, phase, beads=True):
    """Paints a band of gold where lo <= dist <= hi; with beads (small dots) running along 'phase'."""
    inside = (dist >= lo) & (dist <= hi)
    t = np.clip((dist - lo) / max(hi - lo, 1e-6), 0, 1)
    shade = 1.0 - 0.55 * np.abs(t - 0.5) * 2.0  # lighter in the middle of the band
    col = GOLD_DARK[None, None, :] * (1 - shade[..., None]) + GOLD_LIGHT[None, None, :] * shade[..., None]
    if beads:
        bead = 0.78 + 0.22 * np.sin(phase)
        col = col * bead[..., None]
    rgb[inside] = col[inside]


def wall():
    """Wall of the lower half (128px): x = u, y = 1 - v. Gold border along the outside and the V notch."""
    size = 128
    rgb = marble_rgb(size, 1801)
    yy, xx = np.mgrid[0:size, 0:size]
    u = (xx + 0.5) / size
    y = 1.0 - (yy + 0.5) / size
    edge = 0.5 + np.abs(u - 0.5)  # height of the notch edge
    d_notch = (edge - y) / np.sqrt(2.0)
    dist = np.minimum.reduce([y, u, 1 - u, d_notch])
    phase = (u + y) * 190.0
    gold_band(rgb, dist, 0.030, 0.052, phase)
    gold_band(rgb, dist, 0.066, 0.074, phase, beads=False)
    # above the notch edge nothing of the wall is drawn: darken so a seam never shows light
    rgb[d_notch < 0] *= 0.4
    return to_image(rgb)


def top():
    """Top plate (128px): x = u, z = v. Gold border, thin diagonals to the centre, a small ring."""
    size = 128
    rgb = marble_rgb(size, 1807)
    yy, xx = np.mgrid[0:size, 0:size]
    u = (xx + 0.5) / size
    v = (yy + 0.5) / size
    dist = np.minimum.reduce([u, 1 - u, v, 1 - v])
    phase = (u + v) * 190.0
    gold_band(rgb, dist, 0.030, 0.052, phase)
    gold_band(rgb, dist, 0.066, 0.074, phase, beads=False)
    r = np.sqrt((u - 0.5) ** 2 + (v - 0.5) ** 2)
    gold_band(rgb, np.abs(r - 0.16), 0.0, 0.006, phase, beads=False)
    gold_band(rgb, np.abs(r - 0.10), 0.0, 0.004, phase, beads=False)
    diag = np.minimum(np.abs(u - v), np.abs(u + v - 1.0)) / np.sqrt(2.0)
    mask = (r > 0.2) & (dist > 0.09)
    d2 = np.where(mask, diag, 1.0)
    gold_band(rgb, d2, 0.0, 0.0035, phase, beads=False)
    return to_image(rgb)


def plain_marble():
    return to_image(marble_rgb(64, 1808))


def inner():
    rng = np.random.default_rng(1802)
    base = fbm(64, 64, rng)
    dark = np.array([8, 7, 12], dtype=float)
    mid = np.array([20, 18, 30], dtype=float)
    k = base[..., None]
    return to_image(dark * (1 - k) + mid * k)


def gold():
    rng = np.random.default_rng(1809)
    n = rng.random((16, 16))[..., None]
    rgb = GOLD[None, None, :] * (0.92 + 0.12 * n)
    return to_image(rgb)


def glass():
    """Glass triangle (128px): u 0..1 along the top edge, v 0 (top edge) .. 1 (tip). Real height is half the width."""
    size = 128
    rng = np.random.default_rng(1803)
    n = fbm(size, size, rng, ((2, 0.7), (4, 0.3)))
    yy, xx = np.mgrid[0:size, 0:size]
    u = (xx + 0.5) / size
    v = (yy + 0.5) / size
    X = u
    Y = v * 0.5
    dl = (X - Y) / np.sqrt(2.0)
    dr = ((1 - X) - Y) / np.sqrt(2.0)
    dt = Y
    dist = np.minimum.reduce([dl, dr, dt])
    streak = np.exp(-(((u * 1.0 + v * 0.6) - 0.75) ** 2) / 0.004) + 0.6 * np.exp(-(((u + v * 0.6) - 0.35) ** 2) / 0.0015)
    sky = np.clip(1.0 - v * 0.8, 0, 1)
    rgb = np.zeros((size, size, 3))
    rgb[..., 0] = 150 + 70 * streak + 25 * n
    rgb[..., 1] = 175 + 60 * streak + 20 * n
    rgb[..., 2] = 210 + 45 * streak + 15 * n
    alpha = 34 + 30 * sky + 120 * streak + 18 * n
    alpha = alpha + 90 * np.exp(-dist / 0.012)  # glassy rim
    phase = (u + Y) * 190.0
    band = np.zeros((size, size), dtype=bool)
    inner_band = (dist >= 0.030) & (dist <= 0.040)
    col = GOLD[None, None, :] * np.ones((size, size, 1))
    col = col * (0.8 + 0.2 * np.sin(phase))[..., None]
    rgb[inner_band] = col[inner_band]
    alpha[inner_band] = 255
    return to_image(rgb, alpha)


def powder(name, dark, light, seed):
    rng = np.random.default_rng(seed)
    grain = rng.random((32, 32))
    blob = fbm(32, 32, rng, ((4, 0.6), (8, 0.4)))
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


def flare():
    """Star of light (128px, grey, additive): a bright core with four long and four short spikes."""
    size = 128
    yy, xx = np.mgrid[0:size, 0:size]
    x = (xx + 0.5) / size * 2 - 1
    y = (yy + 0.5) / size * 2 - 1
    r = np.sqrt(x * x + y * y)
    val = np.exp(-r * 9.0) * 1.1
    for ang, length, width in [(0, 2.2, 0.018), (np.pi / 2, 2.2, 0.018), (np.pi / 4, 3.4, 0.012), (3 * np.pi / 4, 3.4, 0.012)]:
        along = x * np.cos(ang) + y * np.sin(ang)
        perp = -x * np.sin(ang) + y * np.cos(ang)
        val += 0.9 * np.exp(-(perp / width) ** 2) * np.exp(-np.abs(along) * length)
    val *= np.clip(1.4 - r, 0, 1)
    g = np.clip(val, 0, 1) * 255
    return to_image(np.dstack([g, g, g]))


def crystal():
    """Violet crystal energy (64px, additive): black -> violet -> white veins."""
    rng = np.random.default_rng(1810)
    size = 64
    n1 = fbm(size, size, rng, ((3, 0.5), (6, 0.3), (12, 0.2)))
    n2 = fbm(size, size, rng, ((4, 0.6), (8, 0.4)))
    vein = np.exp(-((n2 - 0.5) ** 2) / 0.003)
    h = np.clip(n1 * 1.3, 0, 1) ** 1.2
    rgb = np.zeros((size, size, 3))
    rgb[..., 0] = 70 + 150 * h
    rgb[..., 1] = 20 + 90 * h
    rgb[..., 2] = 150 + 105 * h
    rgb += vein[..., None] * np.array([120, 120, 90], dtype=float)
    rgb *= (0.35 + 0.65 * h)[..., None]
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
    save(wall(), "block", "sarkophag_wall.png")
    save(top(), "block", "sarkophag_top.png")
    save(plain_marble(), "block", "sarkophag_marble.png")
    save(inner(), "block", "sarkophag_inner.png")
    save(gold(), "block", "sarkophag_gold.png")
    save(glass(), "block", "sarkophag_glass.png")
    save(powder("gun", (50, 50, 56), (150, 150, 160), 1805), "block", "sarkophag_gunpowder.png")
    save(powder("blaze", (225, 130, 10), (255, 232, 90), 1806), "block", "sarkophag_blaze.png")
    save(trim(), "effect", "trim.png")
    save(orb(), "effect", "orb.png")
    save(flare(), "effect", "flare.png")
    save(crystal(), "effect", "crystal.png")
    save(from_art(RUNE), "item", "core_rune.png")
    save(from_art(POTION), "item", "mythic_potion.png")


if __name__ == "__main__":
    main()
