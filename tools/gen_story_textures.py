#!/usr/bin/env python3
"""Generates the textures of the story items (v0.17).

  python3 tools/gen_story_textures.py

Writes (16x16, deterministic, run again = same files):
  textures/item/tablet_elder_guardian.png, tablet_warden.png, tablet_ender_dragon.png, tablet_wither.png
      black marble tablets with a glowing glyph in the colour of their guardian (eye, ear, spiral, three skulls)
  textures/item/general_insignia_1.png .. _4.png
      the insignias of the generals: a dark iron medallion with a gem in the general's colour
  textures/block/general_relic_base.png, general_relic_1.png .. _4.png
      the plinth (obsidian, gold rim) and the crystal of the insignia block
Style like the Core texture: several close tones and noise instead of flat areas. Colours at the top of the file.
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "incursion", "textures")
ITEM = os.path.join(ROOT, "item")
BLOCK = os.path.join(ROOT, "block")

# Guardian glow colours (as in SarcophagusRenderer.GUARD): Elder Guardian teal, Warden cold blue, Dragon violet, Wither ash.
GUARD = [(64, 242, 216), (84, 184, 255), (200, 108, 255), (214, 214, 232)]
TABLETS = ["tablet_elder_guardian", "tablet_warden", "tablet_ender_dragon", "tablet_wither"]
# General colours: Scout (shadow violet), Breaker (lava), Soulweaver (soul cyan), fourth general (crimson).
GENERAL = [(150, 80, 230), (255, 120, 30), (60, 220, 230), (220, 40, 70)]

MARBLE = (24, 20, 30)
MARBLE_VEIN = (70, 60, 82)
OUTLINE = (8, 6, 12)
GOLD = (214, 168, 64)
GOLD_DARK = (138, 98, 34)
IRON = (74, 72, 82)
IRON_LIGHT = (122, 120, 132)


def noise(seed, scale=1.0):
    rng = np.random.default_rng(seed)
    big = rng.random((5, 5))
    big = np.kron(big, np.ones((4, 4)))[:16, :16]
    fine = rng.random((16, 16))
    return (0.6 * big + 0.4 * fine) * scale


def tone(color, factor):
    return tuple(int(max(0, min(255, c * factor))) for c in color)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def put(img, x, y, color, alpha=255):
    if 0 <= x < 16 and 0 <= y < 16:
        img[y, x] = (*color, alpha)


# ----------------------------------------------------------------------------------------------------------------------
# Tablets
# ----------------------------------------------------------------------------------------------------------------------

GLYPHS = [
    # eye: lens, pupil, lashes
    ["....#####....", "..##.....##..", ".#...###...#.", "#...#####...#", ".#...###...#.", "..##.....##..",
     "....#####....", "#.#.#.#.#.#.#"],
    # ear of the Warden: nested arcs (sound)
    ["..#######....", ".#.......#...", "#..#####..#..", "#.#.....#..#.", "#.#..#..#..#.", "#..#...#..#..",
     ".#..###..#...", "..#.....#...."],
    # spiral of the dragon
    ["..######.....", ".#......#....", "#..####..#...", "#.#....#.#...", "#.#.##.#.#...", "#..#..#..#...",
     ".#....#..#...", "..####..#...."],
    # three skulls of the Wither
    ["...........", ".###...###.", "#.#.#.#.#.#", ".###...###.", "....###....", "...#.#.#...",
     "....###....", "..........."],
]


def tablet(index):
    img = np.zeros((16, 16, 4), dtype=np.uint8)
    n = noise(100 + index)
    glow = GUARD[index]
    # slab: columns 3..12, rows 1..14, corners cut, slightly worn
    for y in range(1, 15):
        for x in range(3, 13):
            corner = (x in (3, 12)) and (y in (1, 14))
            if corner:
                continue
            edge = x in (3, 12) or y in (1, 14)
            if edge:
                put(img, x, y, OUTLINE)
                continue
            v = n[y, x]
            c = mix(MARBLE, MARBLE_VEIN, max(0.0, v - 0.55) * 1.8)
            c = tone(c, 0.9 + 0.25 * (1 - y / 16))  # a little lighter at the top
            put(img, x, y, c)
    # bevel: light left/top inner edge, dark right/bottom
    for y in range(2, 14):
        put(img, 4, y, tone(MARBLE_VEIN, 1.15))
        put(img, 11, y, tone(MARBLE, 0.7))
    for x in range(4, 12):
        put(img, x, 2, tone(MARBLE_VEIN, 1.25))
        put(img, x, 13, tone(MARBLE, 0.65))
    # glyph, scaled into the face (cols 5..10, rows 4..11)
    glyph = GLYPHS[index]
    gh, gw = len(glyph), len(glyph[0])
    for y in range(4, 12):
        for x in range(5, 11):
            gy = int((y - 4) / 8 * gh)
            gx = int((x - 5) / 6 * gw)
            if glyph[gy][gx] == "#":
                bright = 0.75 + 0.25 * n[y, x]
                put(img, x, y, tone(glow, bright))
    # a crack in the corner and a chipped edge
    put(img, 10, 3, OUTLINE)
    put(img, 11, 4, OUTLINE)
    put(img, 5, 12, tone(MARBLE_VEIN, 0.8))
    return Image.fromarray(img, "RGBA")


# ----------------------------------------------------------------------------------------------------------------------
# Insignias
# ----------------------------------------------------------------------------------------------------------------------

def insignia(index):
    img = np.zeros((16, 16, 4), dtype=np.uint8)
    n = noise(200 + index)
    color = GENERAL[index]
    cx, cy = 7.5, 7.5
    for y in range(16):
        for x in range(16):
            dx, dy = x - cx, y - cy
            r = (dx * dx + dy * dy) ** 0.5
            # pointed medallion: circle with a spike at the bottom
            spike = y >= 11 and abs(dx) <= (15 - y) * 0.9
            if r <= 6.4 or spike:
                if r > 5.6 and not spike:
                    put(img, x, y, OUTLINE)
                elif r > 4.6 or (spike and r > 4.6):
                    # gold rim with noise
                    t = 0.8 + 0.4 * n[y, x] - 0.25 * (dy / 7)
                    put(img, x, y, tone(mix(GOLD_DARK, GOLD, n[y, x]), t))
                elif r > 2.6:
                    # dark iron ring
                    put(img, x, y, tone(mix(IRON, IRON_LIGHT, max(0, -dy) / 6 * 0.6 + 0.3 * n[y, x]), 0.9))
                else:
                    # the gem: dark centre to bright highlight top left
                    t = 1.0 - (r / 2.6) * 0.45
                    c = tone(color, t)
                    if dx < -0.5 and dy < -0.5:
                        c = mix(c, (255, 255, 255), 0.45)
                    put(img, x, y, c)
    # outline the spike
    for y in range(11, 16):
        half = (15 - y) * 0.9
        put(img, int(round(cx - half - 0.5)), y, OUTLINE)
        put(img, int(round(cx + half + 0.5)), y, OUTLINE)
    put(img, 7, 15, OUTLINE)
    put(img, 8, 15, OUTLINE)
    return Image.fromarray(img, "RGBA")


# ----------------------------------------------------------------------------------------------------------------------
# Insignia block (plinth + crystal)
# ----------------------------------------------------------------------------------------------------------------------

def relic_base():
    img = np.zeros((16, 16, 4), dtype=np.uint8)
    n = noise(300)
    for y in range(16):
        for x in range(16):
            v = n[y, x]
            c = mix((20, 14, 30), (52, 34, 70), v)
            if x in (0, 15) or y in (0, 15):
                c = tone(mix(GOLD_DARK, GOLD, v), 0.9)
            elif x in (1, 14) or y in (1, 14):
                c = tone(c, 0.6)
            img[y, x] = (*c, 255)
    return Image.fromarray(img, "RGBA")


def relic_crystal(index):
    img = np.zeros((16, 16, 4), dtype=np.uint8)
    n = noise(400 + index)
    color = GENERAL[index]
    for y in range(16):
        for x in range(16):
            # vertical facets: bright in the middle, darker to the sides, highlight streak
            facet = 1.0 - abs(x - 7.5) / 9.0
            t = 0.45 + 0.55 * facet + 0.15 * (n[y, x] - 0.5) + 0.2 * (1 - y / 15)
            c = tone(color, t)
            if x in (5, 6) and y % 5 != 4:
                c = mix(c, (255, 255, 255), 0.35)
            if x in (0, 15):
                c = tone(color, 0.35)
            img[y, x] = (*c, 255)
    return Image.fromarray(img, "RGBA")


def main():
    for i, name in enumerate(TABLETS):
        tablet(i).save(os.path.join(ITEM, name + ".png"))
    for i in range(4):
        insignia(i).save(os.path.join(ITEM, "general_insignia_%d.png" % (i + 1)))
        relic_crystal(i).save(os.path.join(BLOCK, "general_relic_%d.png" % (i + 1)))
    relic_base().save(os.path.join(BLOCK, "general_relic_base.png"))
    print("written")


if __name__ == "__main__":
    main()
