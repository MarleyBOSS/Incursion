#!/usr/bin/env python3
"""Generates the Core Table block textures (obsidian frame, violet cloth with a glowing rune and gem corners, like the vanilla enchanting table).

  python3 tools/gen_core_table_texture.py

Writes the animated core_table_top/_working/_done and core_table_side (16x16 frames in a vertical strip + .mcmeta), core_table_bottom and the effect textures (rune circles, halo, beam) to
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


FRAMES = 16
PERIMETER = ([(x, 3) for x in range(3, 13)] + [(12, y) for y in range(4, 13)]
             + [(x, 12) for x in range(11, 2, -1)] + [(3, y) for y in range(11, 3, -1)])


def top_base(mode):
    img = obsidian()
    # Cloth field inside a 2px obsidian frame, like the red carpet of the vanilla table.
    img[2:14, 2:14] = cloth(0.08 if mode != "idle" else 0.0)[2:14, 2:14]
    for (x, y) in PERIMETER:  # gold stitched border
        img[y, x] = GOLD * (0.55 if ((x + y) % 2) else 0.8)
    # Diamond-like gems in the four corners of the frame, as on the vanilla table.
    for (cx, cy) in ((0, 0), (14, 0), (0, 14), (14, 14)):
        for dx in range(2):
            for dy in range(2):
                img[cy + dy, cx + dx] = GEM[1]
        img[cy, cx] = GEM[2]
        img[cy + 1, cx + 1] = GEM[0]
    img[0, 2:14] = OBS[4]
    img[15, 2:14] = OBS[0]
    return img


RUNE_RING = [(6, 5), (7, 5), (8, 5), (9, 5), (6, 10), (7, 10), (8, 10), (9, 10),
             (5, 6), (5, 7), (5, 8), (5, 9), (10, 6), (10, 7), (10, 8), (10, 9)]
RUNE_CORE = [(7, 7), (8, 7), (7, 8), (8, 8)]
RUNE_INNER = [(7, 6), (8, 6), (6, 7), (9, 7), (6, 8), (9, 8), (7, 9), (8, 9)]
RUNE_TIPS = [(7, 4), (8, 4), (7, 11), (8, 11), (4, 7), (4, 8), (11, 7), (11, 8)]


def top_frame(base, mode, f):
    glow = [np.array(c, dtype=float) for c in GLOW[mode]]
    img = base.copy()
    phase = f / FRAMES
    pulse = 0.5 + 0.5 * np.sin(2 * np.pi * phase)          # 0..1, whole loop
    fast = 0.5 + 0.5 * np.sin(2 * np.pi * phase * 2 + 1.0)  # twice per loop
    # A soft diagonal shimmer sweeping over the cloth.
    for y in range(2, 14):
        for x in range(2, 14):
            d = ((x + y) / 24.0 - phase) % 1.0
            band = max(0.0, 1.0 - d * 7.0)
            if band > 0:
                img[y, x] = np.clip(img[y, x] + band * band * 38.0, 0, 255)
    # The glowing rune pulses; the tips lag behind the ring.
    def lerp(c0, c1, k):
        return c0 * (1 - k) + c1 * k
    for (x, y) in RUNE_RING:
        img[y, x] = lerp(glow[0], glow[2], 0.35 + 0.65 * pulse)
    for (x, y) in RUNE_INNER:
        img[y, x] = lerp(glow[1], glow[3], 0.2 + 0.8 * fast)
    for (x, y) in RUNE_CORE:
        img[y, x] = lerp(glow[2], glow[3], 0.5 + 0.5 * pulse)
    tip = 0.5 + 0.5 * np.sin(2 * np.pi * phase - 1.4)
    for (x, y) in RUNE_TIPS:
        img[y, x] = lerp(img[y, x], glow[1], 0.25 + 0.65 * tip)
    # Energy sparks running around the stitched border (more of them when working).
    sparks = 4 if mode == "work" else 2
    n = len(PERIMETER)
    for k in range(sparks):
        head = int((phase * n + k * n / sparks) % n)
        for trail, strength in ((0, 1.0), (1, 0.6), (2, 0.3)):
            x, y = PERIMETER[(head - trail) % n]
            img[y, x] = lerp(img[y, x], glow[3], strength)
    # Twinkling gems.
    for i, (cx, cy) in enumerate(((0, 0), (14, 0), (0, 14), (14, 14))):
        if (f + i * 4) % FRAMES in (0, 1):
            img[cy, cx] = (255, 255, 255)
            img[cy + 1, cx] = GEM[2]
            img[cy, cx + 1] = GEM[2]
    return img


def side_base():
    img = obsidian()
    img[4:6, :] = cloth(0.05)[4:6, :]
    img[6, :] = GOLD * 0.6
    img[7, :] = OBS[0]
    img[9, 1] = GEM[1]
    img[9, 14] = GEM[1]
    img[0, :] = OBS[4]
    img[15, :] = OBS[0]
    img[:, 0] = OBS[3]
    img[:, 15] = OBS[0]
    return img


def side_frame(base, f):
    img = base.copy()
    glow = [np.array(c, dtype=float) for c in GLOW["idle"]]
    phase = f / FRAMES
    for i, x in enumerate((3, 7, 11)):
        k = 0.5 + 0.5 * np.sin(2 * np.pi * phase - i * 1.1)
        img[10, x] = glow[0] * (1 - k) + glow[2] * k
        img[11, x] = glow[1] * (1 - k) + glow[3] * k
        img[12, x] = glow[0] * (1 - k) + glow[2] * k
    # A glint running along the golden hem.
    head = int(phase * 22) - 3
    for trail, strength in ((0, 1.0), (1, 0.55), (2, 0.25)):
        x = head - trail
        if 1 <= x <= 14:
            img[6, x] = img[6, x] * (1 - strength) + np.array((255, 235, 190)) * strength
    if f in (3, 4):
        img[9, 1] = GEM[2]
    if f in (11, 12):
        img[9, 14] = GEM[2]
    return img


def sheet(frames):
    return np.concatenate(frames, axis=0)


def bottom():
    img = obsidian(-0.04)
    img[0, :] = OBS[3]
    img[:, 0] = OBS[3]
    return img


def ss_canvas(size=128, ss=4):
    from PIL import ImageDraw
    img = Image.new("L", (size * ss, size * ss), 0)
    return img, ImageDraw.Draw(img), size * ss


def finish_mask(img, size):
    out = img.resize((size, size), Image.LANCZOS)
    arr = np.asarray(out, dtype=float) / 255.0
    return glow_image(arr)


def glow_image(shape):
    """Additive textures: the shape goes into the brightness (black = nothing); the additive render type adds RGB
    and ignores alpha."""
    v = (np.clip(shape, 0, 1) * 255).astype(np.uint8)
    rgba = np.zeros(shape.shape + (4,), dtype=np.uint8)
    rgba[..., 0:3] = v[..., None]
    rgba[..., 3] = 255
    return Image.fromarray(rgba, "RGBA")


def rune_circle():
    """Outer magic circle: rings, twelve rune marks, cardinal diamonds. White with alpha (tinted when drawn)."""
    size = 128
    img, d, big = ss_canvas(size)
    c = big / 2
    def ring(r, w):
        d.ellipse((c - r, c - r, c + r, c + r), outline=255, width=w)
    ring(big * 0.485, 7)
    ring(big * 0.455, 3)
    ring(big * 0.33, 4)
    rng = np.random.default_rng(7)
    for i in range(12):
        ang = i * np.pi / 6
        for j in range(3):  # a small rune: three strokes in the band between the rings
            r0 = big * (0.355 + 0.02 * j)
            a0 = ang + (rng.random() - 0.5) * 0.18
            r1 = r0 + big * (0.02 + rng.random() * 0.03)
            a1 = a0 + (rng.random() - 0.5) * 0.2
            d.line((c + r0 * np.cos(a0), c + r0 * np.sin(a0), c + r1 * np.cos(a1), c + r1 * np.sin(a1)), fill=255, width=6)
        d.line((c + big * 0.34 * np.cos(ang), c + big * 0.34 * np.sin(ang), c + big * 0.435 * np.cos(ang), c + big * 0.435 * np.sin(ang)), fill=200, width=3)
    for i in range(4):
        ang = i * np.pi / 2
        px, py = c + big * 0.47 * np.cos(ang), c + big * 0.47 * np.sin(ang)
        r = big * 0.028
        d.polygon([(px + r * 1.6 * np.cos(ang), py + r * 1.6 * np.sin(ang)), (px - r * np.sin(ang), py + r * np.cos(ang)),
                   (px - r * 1.6 * np.cos(ang), py - r * 1.6 * np.sin(ang)), (px + r * np.sin(ang), py - r * np.cos(ang))], fill=255)
    return finish_mask(img, size)


def rune_inner():
    """Inner figure: hexagram with a circle and a bright centre."""
    size = 128
    img, d, big = ss_canvas(size)
    c = big / 2
    r = big * 0.40
    for offset in (-np.pi / 2, np.pi / 2):
        pts = [(c + r * np.cos(offset + k * 2 * np.pi / 3), c + r * np.sin(offset + k * 2 * np.pi / 3)) for k in range(3)]
        d.line(pts + [pts[0]], fill=255, width=6)
    rr = big * 0.24
    d.ellipse((c - rr, c - rr, c + rr, c + rr), outline=230, width=4)
    rr = big * 0.05
    d.ellipse((c - rr, c - rr, c + rr, c + rr), fill=255)
    return finish_mask(img, size)


def halo():
    size = 64
    y, x = np.mgrid[0:size, 0:size]
    r = np.hypot(x - size / 2 + 0.5, y - size / 2 + 0.5) / (size / 2)
    a = np.clip(1.0 - r, 0, 1) ** 2.2
    return glow_image(a)


def beam():
    w, h = 16, 64
    y, x = np.mgrid[0:h, 0:w]
    across = np.exp(-(((x - w / 2 + 0.5) / (w * 0.22)) ** 2))
    along = np.clip(y / (h - 1), 0, 1)  # bright at the bottom (y grows downwards in the texture)
    a = across * (along ** 1.6)
    return glow_image(a)


def save(name, img):
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA").save(os.path.join(OUT, name))


def save_animated(name, frames, frametime):
    save(name, sheet(frames))
    with open(os.path.join(OUT, name + ".mcmeta"), "w") as fh:
        fh.write('{\n  "animation": {\n    "frametime": %d,\n    "interpolate": true\n  }\n}\n' % frametime)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for mode, name, frametime in (("idle", "core_table_top.png", 3), ("work", "core_table_top_working.png", 1),
                                  ("done", "core_table_top_done.png", 3)):
        base = top_base(mode)
        save_animated(name, [top_frame(base, mode, f) for f in range(FRAMES)], frametime)
    sbase = side_base()
    save_animated("core_table_side.png", [side_frame(sbase, f) for f in range(FRAMES)], 3)
    save("core_table_bottom.png", bottom())
    effects = os.path.join(OUT, "..", "effect")
    os.makedirs(effects, exist_ok=True)
    rune_circle().save(os.path.join(effects, "rune_circle.png"))
    rune_inner().save(os.path.join(effects, "rune_inner.png"))
    halo().save(os.path.join(effects, "halo.png"))
    beam().save(os.path.join(effects, "beam.png"))
    print("written to", os.path.abspath(OUT))
