"""Awakened wall texture of the Sarcophagus (stage 3+): the obsidian wall with glowing amber cracks that pulse.
Reads sarkophag_wall.png (8 frames) and writes sarkophag_wall_awake.png (+ .mcmeta). Deterministic."""
import numpy as np
from PIL import Image

SRC = 'src/main/resources/assets/incursion/textures/block/sarkophag_wall.png'
DST = 'src/main/resources/assets/incursion/textures/block/sarkophag_wall_awake.png'
im = np.array(Image.open(SRC).convert('RGB')).astype(np.float32)
frames = im.shape[0] // 16
rng = np.random.RandomState(11)
crack = np.zeros((16, 16), np.float32)
for _ in range(5):
    x, y = rng.randint(0, 16), rng.randint(0, 16)
    for _ in range(14):
        crack[y, x] = 1.0
        x = (x + rng.choice([-1, 0, 1, 1])) % 16
        y = (y + rng.choice([-1, 0, 1, 1])) % 16
out = im.copy()
for f in range(frames):
    pulse = 0.55 + 0.45 * np.sin(2 * np.pi * f / frames)
    block = out[f * 16:(f + 1) * 16]
    for y in range(16):
        for x in range(16):
            if crack[y, x] > 0:
                k = pulse * (0.7 + 0.3 * ((x * 5 + y * 3) % 4) / 3)
                block[y, x] = (255 * (0.55 + 0.45 * k), 120 * (0.35 + 0.65 * k), 25 * k)
            else:
                # a faint glow next to the cracks
                near = crack[(y - 1) % 16, x] + crack[(y + 1) % 16, x] + crack[y, (x - 1) % 16] + crack[y, (x + 1) % 16]
                if near > 0:
                    block[y, x] = block[y, x] * (1 - 0.25 * pulse) + np.array([120, 50, 10]) * 0.25 * pulse
Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(DST)
open(DST + '.mcmeta', 'w').write('{\n  "animation": { "frametime": 5, "interpolate": true }\n}\n')
