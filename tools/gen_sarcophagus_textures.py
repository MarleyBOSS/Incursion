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


def posterize(v, levels):
    """Maps 0..1 noise to the given list of colours (hard steps, like vanilla pixel art)."""
    idx = np.clip((v * len(levels)).astype(int), 0, len(levels) - 1)
    return np.array(levels, dtype=float)[idx]


MARBLE_LEVELS = [(13, 9, 24), (19, 14, 33), (27, 20, 45), (42, 31, 70)]


def marble_rgb(size, seed):
    """Obsidian in vanilla pixel style (16px): per-pixel noise in four hard steps, a few lighter violet pixels."""
    rng = np.random.default_rng(seed)
    smooth = fbm(size, size, rng, ((2, 0.6), (4, 0.4)))
    smooth = (smooth - smooth.min()) / (smooth.max() - smooth.min() + 1e-9)
    noise = rng.random((size, size))
    v = (0.35 * smooth + 0.65 * noise) ** 1.7
    v = v / (v.max() + 1e-9)
    return posterize(v * 0.98, MARBLE_LEVELS)


def gold_ring(rgb, dist_px, xx, yy):
    """The gold border: one beaded pixel ring along the edges (the 3D gold bar sits on the edge itself)."""
    bead = ((xx + yy) % 2 == 0)
    ring = dist_px < 1.0
    rgb[ring & bead] = (232, 188, 70)
    rgb[ring & ~bead] = (150, 112, 32)


FRAMES = 8


def mix(c0, c1, t):
    t = np.clip(t, 0.0, 1.0)[..., None]
    return np.array(c0, dtype=float) * (1 - t) + np.array(c1, dtype=float) * t


def cracks_mask(size, seed):
    rng = np.random.default_rng(seed)
    fbm(size, size, rng)  # keep the random stream in step with marble_rgb
    cracks = fbm(size, size, rng, ((3, 0.6), (6, 0.4)))
    return np.abs(cracks - 0.5) < 0.026


def wall_frame(f):
    """Wall of the lower half (16px): x = u, y = 1 - v. Glowing golden cracks, a pulsing glyph, a shimmering bead ring."""
    size = 16
    phase = 2 * np.pi * f / FRAMES
    rgb = marble_rgb(size, 1801)
    yy, xx = np.mgrid[0:size, 0:size]
    u = (xx + 0.5) / size
    y = 1.0 - (yy + 0.5) / size
    edge = 0.5 + np.abs(u - 0.5)
    d_notch = (edge - y) / np.sqrt(2.0)
    dist_px = np.minimum.reduce([y, u, 1 - u, d_notch]) * size
    inside = dist_px >= 2.0
    # glowing cracks: a warm wave travels through them
    crack = cracks_mask(size, 1801) & inside
    wave = 0.5 + 0.5 * np.sin(phase - (xx * 0.55 + yy * 0.35))
    rgb[crack] = mix((44, 30, 20), (170, 124, 36), wave ** 2)[crack]
    # the glyph (a diamond with a heart) in the lower middle
    gx = np.abs(xx - 7.5)
    gy = np.abs(yy - 12.0)
    d = gx + gy
    glyph = (((d >= 1.9) & (d < 3.1)) | ((gx < 1.0) & (gy < 1.0))) & (yy >= 10) & (yy <= 14)
    beat = 0.5 + 0.5 * np.sin(phase)
    rgb[glyph] = mix((100, 70, 20), (240, 196, 80), beat * np.ones_like(d))[glyph]
    # the border: dark gold on the edge pixel (under the 3D bar), a beaded ring inside it that shimmers
    edge_row = dist_px < 1.0
    ring = (dist_px >= 1.0) & (dist_px < 2.0)
    rgb[edge_row] = (8, 5, 14)
    shimmer = 0.5 + 0.5 * np.sin(phase - (xx + yy) * 0.45)
    rgb[ring] = mix((70, 50, 18), (176, 132, 40), shimmer)[ring]
    rgb[d_notch < 0] *= 0.4
    return rgb


def top_frame(f):
    """Top plate (16px): x = u, z = v. A turning rune ring, a heart that pulses, sparks running along the diagonals."""
    size = 16
    phase = 2 * np.pi * f / FRAMES
    rgb = marble_rgb(size, 1807)
    yy, xx = np.mgrid[0:size, 0:size]
    u = (xx + 0.5) / size
    v = (yy + 0.5) / size
    dist_px = np.minimum.reduce([u, 1 - u, v, 1 - v]) * size
    edge_row = dist_px < 1.0
    ring_b = (dist_px >= 1.0) & (dist_px < 2.0)
    shimmer = 0.5 + 0.5 * np.sin(phase - (xx + yy) * 0.45)
    rgb[edge_row] = (8, 5, 14)
    rgb[ring_b] = mix((70, 50, 18), (176, 132, 40), shimmer)[ring_b]
    dx = xx - 7.5
    dz = yy - 7.5
    r = np.sqrt(dx * dx + dz * dz)
    ang = np.arctan2(dz, dx)
    inner = (r >= 2.3) & (r < 3.5)
    lit = np.sin(3 * ang - phase) > -0.35  # three arcs that turn
    rgb[inner & lit] = (200, 154, 52)
    rgb[inner & ~lit] = (30, 22, 40)
    outer = (r >= 4.6) & (r < 5.6)
    lit2 = np.sin(6 * ang + phase) > 0.2
    rgb[outer & lit2] = (60, 42, 24)
    heart = r < 1.6
    beat = 0.5 + 0.5 * np.sin(phase)
    rgb[heart] = mix((200, 130, 30), (255, 240, 170), beat * np.ones_like(r))[heart]
    diag = np.minimum(np.abs(xx - yy), np.abs(xx + yy - (size - 1))) == 0
    along = np.maximum(np.abs(dx), np.abs(dz))
    runner = ((along * 1.0 - f) % 4 == 0)
    return rgb


def wall():
    return frames_image([wall_frame(f) for f in range(FRAMES)])


def top():
    return frames_image([top_frame(f) for f in range(FRAMES)])


def frames_image(frames):
    return to_image(np.concatenate(frames, axis=0))


def write_mcmeta(frametime, *path):
    full = os.path.join(ROOT, *path)
    with open(full + ".mcmeta", "w") as fh:
        fh.write('{\n  "animation": { "frametime": %d, "interpolate": true }\n}\n' % frametime)
    print("wrote", os.path.normpath(full) + ".mcmeta")


def plain_marble():
    return to_image(marble_rgb(16, 1808))


def inner():
    rng = np.random.default_rng(1802)
    base = fbm(16, 16, rng)
    base = (base - base.min()) / (base.max() - base.min() + 1e-9)
    return to_image(posterize(base * 0.9, [(7, 6, 11), (11, 10, 17), (17, 15, 25)]))


def gold():
    """Gold block style: noisy pixels in four shades (the 3D frame cubes each pick one pixel)."""
    rng = np.random.default_rng(1809)
    shades = [(236, 190, 58), (214, 162, 40)]
    idx = (rng.random((16, 16)) < 0.3).astype(int)
    return to_image(np.array(shades, dtype=float)[idx])


def glass():
    """Diamond glass (16px): u 0..1 along the top edge, v 0 (top edge) .. 1 (tip). Clear, a few light streaks, a light rim."""
    size = 16
    yy, xx = np.mgrid[0:size, 0:size]
    u = (xx + 0.5) / size
    v = (yy + 0.5) / size
    X = u
    Y = v * 0.5
    dl = (X - Y) / np.sqrt(2.0)
    dr = ((1 - X) - Y) / np.sqrt(2.0)
    dist_px = np.minimum.reduce([dl, dr, Y]) * size
    rgb = np.zeros((size, size, 3))
    rgb[...] = (205, 215, 225)
    alpha = np.full((size, size), 34.0)
    alpha += 12 * (1.0 - v)
    streak = ((xx * 2 + yy) % 11 == 0) | ((xx * 2 + yy) % 11 == 1)
    streak &= (np.arange(size)[None, :] + np.arange(size)[:, None]) % 3 != 0
    alpha[streak] = 110
    rgb[streak] = (240, 244, 250)
    rim = dist_px < 1.0
    alpha[rim] = 130
    rgb[rim] = (225, 232, 240)
    return to_image(rgb, alpha)


def powder(name, dark, light, seed):
    rng = np.random.default_rng(seed)
    grain = rng.random((16, 16))
    blob = fbm(16, 16, rng, ((4, 0.6), (8, 0.4)))
    k = 0.6 * grain + 0.4 * blob
    k = (k - k.min()) / (k.max() - k.min() + 1e-9)
    levels = [tuple(np.array(dark) * (1 - t) + np.array(light) * t) for t in (0.0, 0.33, 0.66, 1.0)]
    return to_image(posterize(k * 0.99, levels))


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


def rune_frame(f):
    """The Core Rune (16px item icon, animated): an obsidian tablet with diamond gems, a glowing glyph and a golden heart."""
    phase = 2 * np.pi * f / FRAMES
    rgba = np.zeros((16, 16, 4), dtype=float)
    yy, xx = np.mgrid[0:16, 0:16]
    tablet = (xx >= 3) & (xx <= 12) & (yy >= 1) & (yy <= 14)
    corner = ((xx == 3) | (xx == 12)) & ((yy == 1) | (yy == 14))
    tablet &= ~corner
    body = np.zeros((16, 16, 3))
    t = (xx - 3 + (yy - 1) * 0.6) / 17.0
    body[:] = mix((58, 40, 96), (20, 12, 36), t)  # obsidian, lighter top-left
    rng = np.random.default_rng(1811)
    speck = rng.random((16, 16)) < 0.12
    body[speck] *= 1.35
    outline = tablet & ((xx == 3) | (xx == 12) | (yy == 1) | (yy == 14))
    body[outline] = (8, 4, 16)
    body[tablet & ~outline & ((xx == 4) | (yy == 2))] = (96, 74, 84)
    body[tablet & ~outline & ((xx == 11) | (yy == 13))] = (12, 6, 24)
    # diamond gems in the four corners, they twinkle
    for i, (gx, gy) in enumerate([(4, 2), (11, 2), (4, 13), (11, 13)]):
        k = 0.5 + 0.5 * np.sin(phase + i * 1.6)
        body[gy, gx] = mix((120, 20, 30), (255, 90, 80), np.array(k))
    cx, cy = 7.5, 7.5
    d = np.abs(xx - cx) + np.abs(yy - cy)
    ring = (d >= 2.9) & (d < 4.1)
    heart = (np.abs(xx - cx) < 1.0) & (np.abs(yy - cy) < 1.0)
    spikes = ((np.abs(xx - cx) < 1.0) & ((yy == 3) | (yy == 12))) | ((np.abs(yy - cy) < 1.0) & ((xx == 5) | (xx == 10)))
    ang = np.arctan2(yy - cy, xx - cx)
    glow = 0.5 + 0.5 * np.sin(phase - ang)  # the glow runs around the ring
    ringpx = ring & tablet & ~outline
    body[ringpx] = mix((64, 34, 120), (190, 120, 255), glow)[ringpx]
    spikepx = spikes & tablet & ~outline & ~ring
    body[spikepx] = mix((190, 140, 40), (255, 236, 150), glow)[spikepx]
    beat = np.full((16, 16), 0.5 + 0.5 * np.sin(phase))
    heartpx = heart & tablet
    body[heartpx] = mix((220, 150, 30), (255, 246, 190), beat)[heartpx]
    rgba[..., :3] = body
    rgba[..., 3] = np.where(tablet, 255, 0)
    sparks = [(2, 0), (13, 0), (2, 15), (13, 15), (1, 8), (14, 7)]
    for i, (sx, sy) in enumerate(sparks):
        if (f + i * 3) % FRAMES == 0:
            rgba[sy, sx] = (255, 226, 150, 255)
    return rgba


def rune_image():
    frames = [rune_frame(f) for f in range(FRAMES)]
    arr = np.clip(np.concatenate(frames, axis=0), 0, 255).astype(np.uint8)
    return Image.fromarray(arr, "RGBA")


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
    save(powder("gun", (45, 45, 50), (150, 150, 160), 1805), "block", "sarkophag_gunpowder.png")
    save(powder("blaze", (225, 130, 10), (255, 232, 90), 1806), "block", "sarkophag_blaze.png")
    save(trim(), "effect", "trim.png")
    save(orb(), "effect", "orb.png")
    save(flare(), "effect", "flare.png")
    save(rune_image(), "item", "core_rune.png")
    write_mcmeta(3, "item", "core_rune.png")
    write_mcmeta(5, "block", "sarkophag_wall.png")
    write_mcmeta(5, "block", "sarkophag_top.png")
    save(from_art(POTION), "item", "mythic_potion.png")


if __name__ == "__main__":
    main()
