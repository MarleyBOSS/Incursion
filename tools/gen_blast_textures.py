#!/usr/bin/env python3
"""Generates the textures of the Core's explosion and upgrade animation (white with alpha, tinted by the renderer).

  python3 tools/gen_blast_textures.py

Writes assets/incursion/textures/effect/shock_ring.png (a soft, wide ring: a shock wave that must not shimmer when it is
huge) and blast_circle.png (a reactor circle: rings, tick marks and two triangles; no runes).
"""
import math
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures", "effect")


def smooth(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def save(alpha, name):
    h, w = alpha.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    rgba[..., 0] = 255
    rgba[..., 1] = 255
    rgba[..., 2] = 255
    rgba[..., 3] = np.clip(alpha * 255.0 + 0.5, 0, 255).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(os.path.join(OUT, name))


def grid(n):
    c = (np.arange(n) + 0.5) / n * 2.0 - 1.0
    x, y = np.meshgrid(c, c)
    return x, y, np.sqrt(x * x + y * y)


def shock_ring():
    n = 512
    x, y, r = grid(n)
    # a bright crest at r = 0.86 that falls off softly to both sides, a faint haze inside, nothing at the rim
    crest = np.exp(-((r - 0.86) / 0.045) ** 2)
    trail = smooth((r - 0.25) / 0.6) * smooth((0.86 - r) / 0.6 + 0.0) * 0.0 + 0.22 * smooth((r - 0.3) / 0.56) * (r < 0.86)
    alpha = (crest + trail) * smooth((1.0 - r) / 0.08)
    save(alpha, "shock_ring.png")


def seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    t = np.clip(((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy), 0.0, 1.0)
    return np.hypot(px - (ax + t * dx), py - (ay + t * dy))


def blast_circle():
    n = 1024
    x, y, r = grid(n)
    px = 1.0 / n  # one pixel in texture units (the picture spans -1..1)
    alpha = np.zeros_like(r)

    def line(dist, half):
        return np.clip((half - dist) / (2 * px) + 0.5, 0.0, 1.0)

    # rings
    for radius, half, level in ((0.97, 0.013, 1.0), (0.90, 0.006, 0.8), (0.64, 0.007, 0.7), (0.34, 0.008, 0.8)):
        alpha = np.maximum(alpha, level * line(np.abs(r - radius), half))
    # tick marks between the two outer rings: 72, every 6th one longer
    ang = np.arctan2(y, x)
    for k in range(72):
        a = k * 2 * math.pi / 72
        inner = 0.905 if k % 6 else 0.84
        outer = 0.965
        d = seg_dist(x, y, math.cos(a) * inner, math.sin(a) * inner, math.cos(a) * outer, math.sin(a) * outer)
        alpha = np.maximum(alpha, 0.85 * line(d, 0.0045 if k % 6 else 0.008))
    # two triangles (a hexagram) inside the second ring
    for rot in (math.pi / 2, -math.pi / 2):
        pts = [(math.cos(rot + k * 2 * math.pi / 3) * 0.90, math.sin(rot + k * 2 * math.pi / 3) * 0.90) for k in range(3)]
        for k in range(3):
            a, b = pts[k], pts[(k + 1) % 3]
            alpha = np.maximum(alpha, 0.75 * line(seg_dist(x, y, a[0], a[1], b[0], b[1]), 0.006))
        for a in pts:
            alpha = np.maximum(alpha, 0.95 * line(np.hypot(x - a[0], y - a[1]), 0.035))
    # a soft disc in the middle and a faint fill between the inner rings
    alpha = np.maximum(alpha, 0.5 * smooth((0.12 - r) / 0.12))
    alpha = np.maximum(alpha, 0.06 * (r < 0.64))
    alpha *= smooth((1.0 - r) / 0.02)
    img = Image.fromarray((np.clip(alpha, 0, 1) * 255).astype(np.uint8), "L").resize((512, 512), Image.LANCZOS)
    save(np.asarray(img).astype(np.float64) / 255.0, "blast_circle.png")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    shock_ring()
    blast_circle()
    print("written to", os.path.abspath(OUT))
