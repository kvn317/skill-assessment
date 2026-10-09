"""Builds MapLibre SDF glyph PBFs from TTF fonts into app/src/main/assets/fonts/<name>/<range>.pbf.

    pip install freetype-py numpy scipy
    python tools/make_glyphs.py Rye=path/Rye-Regular.ttf ...

Output matches node-fontnik: 24px glyphs, 3px buffer, SDF radius 8, cutoff 0.25.
"""
import os
import sys

import freetype
import numpy as np
from scipy.ndimage import distance_transform_edt

SIZE, BUFFER, RADIUS, CUTOFF, SS = 24, 3, 8, 0.25, 4  # SS = supersampling for smoother distances
# ponytail: Latin + general punctuation only; other scripts fall back to missing glyphs. Add ranges here if needed.
RANGES = [(0, 255), (256, 511), (8192, 8447)]
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "fonts")


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(num, wire, payload):
    key = varint((num << 3) | wire)
    return key + (varint(len(payload)) + payload if wire == 2 else payload)


def uint(num, v): return field(num, 0, varint(v))
def sint(num, v): return field(num, 0, varint((v << 1) ^ (v >> 31)))  # zigzag
def blob(num, b): return field(num, 2, b)


def glyph(face, code, ascender):
    if not face.get_char_index(code):
        return None
    face.load_char(chr(code), freetype.FT_LOAD_RENDER)
    g = face.glyph
    advance = round(g.advance.x / 64 / SS)
    bm = g.bitmap
    if bm.width == 0 or bm.rows == 0:  # space and friends: metrics only
        return uint(1, code) + uint(3, 0) + uint(4, 0) + sint(5, 0) + sint(6, 0) + uint(7, advance)
    hi = np.array(bm.buffer, dtype=np.uint8).reshape(bm.rows, bm.pitch)[:, :bm.width]
    L, T = g.bitmap_left, g.bitmap_top
    x0 = L // SS
    top = -(-T // SS)  # ceil
    w = -(-(L + bm.width) // SS) - x0
    h = top - (T - bm.rows) // SS
    pad = BUFFER * SS
    canvas = np.zeros(((h + 2 * BUFFER) * SS, (w + 2 * BUFFER) * SS), dtype=bool)
    oy, ox = top * SS - T + pad, L - x0 * SS + pad
    canvas[oy:oy + bm.rows, ox:ox + bm.width] = hi > 127
    signed = (distance_transform_edt(~canvas) - distance_transform_edt(canvas)) / SS  # + outside, - inside
    d = signed.reshape(h + 2 * BUFFER, SS, w + 2 * BUFFER, SS).mean(axis=(1, 3))
    sdf = np.clip(255 - 255 * (d / RADIUS + CUTOFF), 0, 255).astype(np.uint8)
    return (uint(1, code) + blob(2, sdf.tobytes()) + uint(3, w) + uint(4, h)
            + sint(5, x0) + sint(6, top - ascender) + uint(7, advance))


def build(name, path):
    face = freetype.Face(path)
    face.set_pixel_sizes(0, SIZE * SS)
    ascender = round(face.size.ascender / 64 / SS)
    os.makedirs(os.path.join(OUT, name), exist_ok=True)
    for a, b in RANGES:
        glyphs = [g for g in (glyph(face, c, ascender) for c in range(a, b + 1)) if g]
        stack = blob(1, name.encode()) + blob(2, f"{a}-{b}".encode()) + b"".join(blob(3, g) for g in glyphs)
        with open(os.path.join(OUT, name, f"{a}-{b}.pbf"), "wb") as f:
            f.write(blob(1, stack))
        print(name, f"{a}-{b}", len(glyphs), "glyphs")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        build(*arg.split("=", 1))
