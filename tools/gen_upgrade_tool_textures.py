#!/usr/bin/env python3
"""Generates the four Upgrade Tool item textures (16x16 hammer, one head colour per tier).

  python3 tools/gen_upgrade_tool_textures.py

Writes src/main/resources/assets/incursion/textures/item/upgrade_tool_1.png .. upgrade_tool_5.png.
Placeholder art: a dark outline, a brown handle and a shaded head with a glowing gem. Replace the PNGs with your own
art of the same names at any time; running the script again overwrites them.
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures",
                   "item")

# (file name, head colour, gem colour)
TIERS = [
    # tier I: plain iron; tiers II-V carry the insignia of the general they were forged with (placeholder art)
    ("upgrade_tool_1", (128, 128, 138), (210, 210, 222)),  # iron
    ("upgrade_tool_2", (64, 42, 96), (160, 90, 240)),      # Scout: shadow violet
    ("upgrade_tool_3", (112, 50, 22), (255, 128, 36)),     # Breaker: lava
    ("upgrade_tool_4", (28, 88, 100), (70, 226, 236)),     # Soulweaver: soul cyan
    ("upgrade_tool_5", (96, 22, 38), (236, 52, 80)),       # fourth general: crimson
]
OUTLINE = (22, 16, 30)
HANDLE = (122, 80, 42)
HANDLE_LIGHT = (160, 112, 62)

HEAD_ROWS = range(1, 7)    # rows 1..6
HEAD_COLS = range(6, 15)   # cols 6..14
GEM = [(3, 9), (3, 10), (4, 9), (4, 10)]


def shade(color, factor):
    return tuple(int(max(0, min(255, v * factor))) for v in color)


def make(head, gem):
    pixels = {}
    # Handle: diagonal from under the head to the bottom left, two pixels wide.
    handle = set()
    for k in range(8):
        r, c = 7 + k, 9 - k
        handle.add((r, c))
        handle.add((r, c + 1))
    head_cells = {(r, c) for r in HEAD_ROWS for c in HEAD_COLS}
    body = handle | head_cells
    for (r, c) in body:
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                n = (r + dr, c + dc)
                if n not in body and 0 <= n[0] < 16 and 0 <= n[1] < 16:
                    pixels[n] = OUTLINE
    for (r, c) in handle:
        pixels[(r, c)] = HANDLE_LIGHT if (r + c) % 4 == 0 else HANDLE
    for (r, c) in head_cells:
        factor = {1: 1.30, 2: 1.12, 3: 1.00, 4: 0.88, 5: 0.76, 6: 0.62}[r]
        if c == HEAD_COLS[0]:
            factor += 0.12
        if c == HEAD_COLS[-1]:
            factor -= 0.12
        pixels[(r, c)] = shade(head, factor)
    for (r, c) in GEM:
        pixels[(r, c)] = shade(gem, 1.0 if (r, c) != (3, 9) else 1.15)
    img = np.zeros((16, 16, 4), dtype=np.uint8)
    for (r, c), color in pixels.items():
        img[r, c] = (*color, 255)
    return Image.fromarray(img, "RGBA")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, head, gem in TIERS:
        make(head, gem).save(os.path.join(OUT, name + ".png"))
        print("wrote", name + ".png")


if __name__ == "__main__":
    main()
