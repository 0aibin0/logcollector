#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the Android launcher / status-bar icon PNGs for LogCollector.

The artwork is pure geometry (no text glyphs):

  * ic_launcher.png       rounded square, vertical #16202B -> #0B1118 gradient,
                          5 left-aligned rounded "log line" bars on top.
  * ic_launcher_round.png identical artwork, background clipped to a circle.
  * ic_stat.png           fully transparent background, the same 5 bars in
                          pure white (monochrome notification icon).

Everything is rasterised at 4x supersampling with hard-edged coverage tests and
then box-downsampled, which is what produces the anti-aliased edges.

PNG output uses the standard library only (zlib + struct) by default.  Pillow is
used when it is importable and ``--no-pillow`` was not passed; the Pillow-free
encoder is the reference implementation and produces identical pixels, only a
different (also valid) zlib stream.

Output is fully deterministic: no randomness, no timestamps.
"""

from __future__ import annotations

import argparse
import math
import os
import struct
import sys
import zlib

# ---------------------------------------------------------------------------
# Design constants.  Every length is a fraction of the icon side length so the
# same numbers work for every density bucket.
# ---------------------------------------------------------------------------
SUPERSAMPLE = 4  # render scale, box-downsampled afterwards (anti-aliasing)

BG_TOP_HEX = "#16202B"
BG_BOTTOM_HEX = "#0B1118"

CORNER_RADIUS = 0.22  # rounded-square corner radius

BAR_LEFT = 0.18  # common left margin of the bar group
BAR_HEIGHT = 0.08
BAR_GAP = 0.06
BAR_COUNT = 5

# (colour, width) per bar, width as a fraction of the side.
BARS = (
    ("#4ADE80", 0.64),
    ("#94A3B8", 0.46),
    ("#4ADE80", 0.72),
    ("#F59E0B", 0.38),
    ("#64748B", 0.56),
)
STAT_COLOR = "#FFFFFF"  # ic_stat.png is monochrome white

# density bucket -> pixel size
MIPMAP_SIZES = (
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192),
)
DRAWABLE_SIZES = (
    ("mdpi", 24),
    ("hdpi", 36),
    ("xhdpi", 48),
    ("xxhdpi", 72),
    ("xxxhdpi", 96),
)


# ---------------------------------------------------------------------------
# tiny colour helper
# ---------------------------------------------------------------------------
def hex_rgb(value):
    """'#4ADE80' -> (0.29..., 0.87..., 0.50...) floats in 0..1."""
    text = value.lstrip("#")
    if len(text) != 6:
        raise ValueError("expected #RRGGBB, got %r" % (value,))
    return tuple(int(text[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def lerp(a, b, t):
    return a + (b - a) * t


# ---------------------------------------------------------------------------
# geometry: per-scanline spans for the two shapes we need
# ---------------------------------------------------------------------------
def row_span_rounded_rect(yc, x0, y0, x1, y1, radius):
    """Horizontal [lo, hi) span of a rounded rect at scanline centre `yc`.

    A capsule (fully rounded bar) is just radius = half the bar height.
    Returns None when the scanline misses the shape.
    """
    if yc < y0 or yc >= y1:
        return None
    d = 0.0
    if radius > 0.0:
        if yc < y0 + radius:
            dy = (y0 + radius) - yc
            d = radius - math.sqrt(max(0.0, radius * radius - dy * dy))
        elif yc > y1 - radius:
            dy = yc - (y1 - radius)
            d = radius - math.sqrt(max(0.0, radius * radius - dy * dy))
    lo = x0 + d
    hi = x1 - d
    if hi <= lo:
        return None
    return lo, hi


def span_to_pixels(lo, hi, limit):
    """Pixel indices whose centres (i + 0.5) fall inside [lo, hi)."""
    first = int(math.ceil(lo - 0.5))
    last = int(math.ceil(hi - 0.5)) - 1
    if first < 0:
        first = 0
    if last > limit - 1:
        last = limit - 1
    return first, last


def build_shape_mask(kind, width, height):
    """0/1 coverage mask for the icon silhouette at supersampled resolution.

    kind is 'rounded', 'circle' or None (None == no clipping).
    """
    if kind is None:
        return None
    mask = bytearray(width * height)
    if kind == "circle":
        cx = cy = width / 2.0
        radius = width / 2.0
    elif kind == "rounded":
        radius = CORNER_RADIUS * width
    else:
        raise ValueError("unknown shape %r" % (kind,))

    for j in range(height):
        yc = j + 0.5
        if kind == "circle":
            dy = yc - cy
            if abs(dy) >= radius:
                continue
            dx = math.sqrt(radius * radius - dy * dy)
            lo, hi = cx - dx, cx + dx
        else:
            span = row_span_rounded_rect(yc, 0.0, 0.0, float(width),
                                         float(height), radius)
            if span is None:
                continue
            lo, hi = span
        first, last = span_to_pixels(lo, hi, width)
        if last < first:
            continue
        base = j * width
        for i in range(first, last + 1):
            mask[base + i] = 1
    return mask


# ---------------------------------------------------------------------------
# rasteriser: premultiplied float RGBA buffer
# ---------------------------------------------------------------------------
def fill_gradient(buffer, mask, width, height, top, bottom):
    """Opaque vertical gradient, restricted to the silhouette mask."""
    for j in range(height):
        t = (j + 0.5) / float(height)
        r = lerp(top[0], bottom[0], t)
        g = lerp(top[1], bottom[1], t)
        b = lerp(top[2], bottom[2], t)
        base = j * width
        for i in range(width):
            if mask is not None and not mask[base + i]:
                continue
            k = (base + i) * 4
            buffer[k] = r
            buffer[k + 1] = g
            buffer[k + 2] = b
            buffer[k + 3] = 1.0


def fill_capsule(buffer, mask, width, height, x0, y0, x1, y1, radius, rgb):
    """Opaque capsule (rounded bar); clipped by `mask` when one is given.

    The source alpha is exactly 1.0, so premultiplied source-over reduces to a
    plain assignment.
    """
    r, g, b = rgb
    for j in range(height):
        span = row_span_rounded_rect(j + 0.5, x0, y0, x1, y1, radius)
        if span is None:
            continue
        first, last = span_to_pixels(span[0], span[1], width)
        if last < first:
            continue
        base = j * width
        for i in range(first, last + 1):
            if mask is not None and not mask[base + i]:
                continue
            k = (base + i) * 4
            buffer[k] = r
            buffer[k + 1] = g
            buffer[k + 2] = b
            buffer[k + 3] = 1.0


def to_u8(value):
    """Deterministic round-half-up clamp of a 0..1 float to a byte."""
    n = int(value * 255.0 + 0.5)
    if n < 0:
        return 0
    if n > 255:
        return 255
    return n


def downsample(buffer, width, height, factor):
    """Box-filter the supersampled premultiplied buffer into 8-bit RGBA.

    Averaging happens in premultiplied space (the only correct space for a
    transparent background such as ic_stat.png), then the result is
    un-premultiplied back to straight RGBA for the PNG.
    """
    out_w = width // factor
    out_h = height // factor
    out = bytearray(out_w * out_h * 4)
    inv = 1.0 / float(factor * factor)
    for oy in range(out_h):
        for ox in range(out_w):
            sr = sg = sb = sa = 0.0
            for dy in range(factor):
                base = ((oy * factor + dy) * width + ox * factor) * 4
                for dx in range(factor):
                    k = base + dx * 4
                    sr += buffer[k]
                    sg += buffer[k + 1]
                    sb += buffer[k + 2]
                    sa += buffer[k + 3]
            sa *= inv
            if sa <= 0.0:
                continue  # fully transparent, stays (0, 0, 0, 0)
            sr *= inv
            sg *= inv
            sb *= inv
            m = (oy * out_w + ox) * 4
            out[m] = to_u8(sr / sa)
            out[m + 1] = to_u8(sg / sa)
            out[m + 2] = to_u8(sb / sa)
            out[m + 3] = to_u8(sa)
    return out_w, out_h, bytes(out)


# ---------------------------------------------------------------------------
# artwork
# ---------------------------------------------------------------------------
def render_icon(size, shape, bar_rgb):
    """Render one icon; returns (width, height, straight RGBA bytes)."""
    scale = SUPERSAMPLE
    big = size * scale
    mask = build_shape_mask(shape, big, big)
    buffer = [0.0] * (big * big * 4)

    if mask is not None:
        fill_gradient(buffer, mask, big, big,
                      hex_rgb(BG_TOP_HEX), hex_rgb(BG_BOTTOM_HEX))

    bar_h = BAR_HEIGHT * big
    gap = BAR_GAP * big
    total = BAR_COUNT * bar_h + (BAR_COUNT - 1) * gap
    top = (big - total) / 2.0  # vertically centred group
    left = BAR_LEFT * big
    radius = bar_h / 2.0  # fully rounded end caps

    for index, (colour, width_frac) in enumerate(BARS):
        y0 = top + index * (bar_h + gap)
        # bar_rgb overrides every bar (monochrome ic_stat.png); otherwise each
        # bar keeps its own colour from BARS.
        rgb = bar_rgb if bar_rgb is not None else hex_rgb(colour)
        fill_capsule(buffer, mask, big, big,
                     left, y0, left + width_frac * big, y0 + bar_h,
                     radius, rgb)

    return downsample(buffer, big, big, scale)


# ---------------------------------------------------------------------------
# PNG encoding
# ---------------------------------------------------------------------------
def chunk(tag, data):
    """length + type + data + CRC32(type + data)."""
    return (struct.pack(">I", len(data)) + tag + data +
            struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def encode_png_rgba(width, height, pixels, level=9):
    """Hand-written PNG encoder: 8-bit RGBA, colour type 6.

    Exactly one IHDR, one IDAT (every scanline prefixed with filter byte 0) and
    one IEND.  Standard library only.
    """
    expected = width * height * 4
    if len(pixels) != expected:
        raise ValueError("pixel buffer is %d bytes, expected %d"
                         % (len(pixels), expected))

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0 (None) for every scanline
        raw += pixels[y * stride:(y + 1) * stride]

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" +
            chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(bytes(raw), level)) +
            chunk(b"IEND", b""))


def make_pillow_encoder():
    """Return a Pillow-backed encoder, or None when Pillow is unavailable."""
    try:
        from PIL import Image
    except Exception:
        return None

    import io

    def encode(width, height, pixels):
        image = Image.frombytes("RGBA", (width, height), pixels)
        buf = io.BytesIO()
        image.save(buf, format="PNG", optimize=False, compress_level=9)
        return buf.getvalue()

    encode.engine = "pillow %s" % getattr(Image, "__version__", "?")
    return encode


def make_builtin_encoder():
    def encode(width, height, pixels):
        return encode_png_rgba(width, height, pixels)

    encode.engine = "builtin zlib+struct"
    return encode


# ---------------------------------------------------------------------------
# driver
# ---------------------------------------------------------------------------
def default_res_dir():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.normpath(os.path.join(here, os.pardir, "app", "src", "main", "res"))


def write_png(path, encoder, width, height, pixels):
    blob = encoder(width, height, pixels)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(blob)
    return len(blob)


def build_all(out_dir, force_no_pillow=False):
    encoder = None
    pillow_note = ""
    if force_no_pillow:
        pillow_note = "Pillow disabled by --no-pillow"
    else:
        encoder = make_pillow_encoder()
        if encoder is None:
            pillow_note = "Pillow not importable, using fallback"
    if encoder is None:
        encoder = make_builtin_encoder()

    white = hex_rgb(STAT_COLOR)
    jobs = []
    for bucket, size in MIPMAP_SIZES:
        jobs.append((os.path.join(out_dir, "mipmap-%s" % bucket, "ic_launcher.png"),
                     render_icon(size, "rounded", None), "launcher"))
        jobs.append((os.path.join(out_dir, "mipmap-%s" % bucket, "ic_launcher_round.png"),
                     render_icon(size, "circle", None), "launcher-round"))
    for bucket, size in DRAWABLE_SIZES:
        jobs.append((os.path.join(out_dir, "drawable-%s" % bucket, "ic_stat.png"),
                     render_icon(size, None, white), "stat"))

    results = []
    for path, (width, height, pixels), kind in jobs:
        nbytes = write_png(path, encoder, width, height, pixels)
        results.append((path, width, height, nbytes, kind))
    return results, encoder.engine, pillow_note


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Generate Android launcher and status-bar icon PNGs.")
    parser.add_argument("--out", default=None,
                        help="resource directory (default: <script>/../app/src/main/res)")
    parser.add_argument("--no-pillow", action="store_true",
                        help="force the standard-library PNG encoder")
    args = parser.parse_args(argv)

    out_dir = os.path.abspath(args.out) if args.out else default_res_dir()
    results, engine, note = build_all(out_dir, args.no_pillow)

    print("output root : %s" % out_dir)
    print("encoder     : %s%s" % (engine, " (%s)" % note if note else ""))
    print("supersample : %dx" % SUPERSAMPLE)
    print("")
    print("%-58s %10s %10s" % ("file", "size", "bytes"))
    for path, width, height, nbytes, _kind in results:
        rel = os.path.relpath(path, out_dir)
        print("%-58s %10s %10d" % (rel, "%dx%d" % (width, height), nbytes))
    print("")
    print("%d PNG files written" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
