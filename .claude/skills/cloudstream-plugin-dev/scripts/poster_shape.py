#!/usr/bin/env python3
"""Decide whether a site's poster/thumbnail images are portrait or landscape.

The answer drives `HomePageList(name, items, isHorizontalImages = …)` in a plugin:
true for landscape thumbnails (Javtiful, MissAV, SupJav), false for portrait posters.

Usage:
  poster_shape.py URL_OR_FILE [URL_OR_FILE ...] [--header "Referer: https://host/"] [--json]

Pass the exact image URLs the parser would put into posterUrl (the lazy-load attribute
when the site uses one), not a different size variant. Pass 5-10 cards from several rows,
because one row can differ from another.

Stdlib only. PNG, JPEG, GIF and WebP are read from their headers. Other formats fall back
to Pillow if it is installed, and are reported as unknown otherwise.
"""
import argparse
import io
import json
import os
import struct
import sys
import urllib.request
from collections import Counter

MAX_BYTES = 4 * 1024 * 1024
LANDSCAPE_MIN = 1.2   # width / height above this is landscape (16:9 = 1.78, 4:3 = 1.33)
PORTRAIT_MAX = 0.95   # below this is portrait (2:3 = 0.67, 3:4 = 0.75); between is square
AGREE = 0.8           # share of images that must agree for a verdict

USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) poster-shape-check"


def _jpeg_size(data):
    i = 2
    sof = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}
    while i + 4 <= len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker == 0xFF:          # fill byte
            i += 1
            continue
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:  # standalone markers
            i += 2
            continue
        length = struct.unpack(">H", data[i + 2:i + 4])[0]
        if marker in sof and i + 9 <= len(data):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        i += 2 + length
    return None


def _webp_size(data):
    chunk = data[12:16]
    if chunk == b"VP8X" and len(data) >= 30:
        w = 1 + int.from_bytes(data[24:27], "little")
        h = 1 + int.from_bytes(data[27:30], "little")
        return w, h
    if chunk == b"VP8L" and len(data) >= 25:
        bits = int.from_bytes(data[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if chunk == b"VP8 " and len(data) >= 30 and data[23:26] == b"\x9d\x01\x2a":
        w, h = struct.unpack("<HH", data[26:30])
        return w & 0x3FFF, h & 0x3FFF
    return None


def image_size(data):
    """Return (width, height, format) from the image bytes, or None if unknown."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) >= 24:
        w, h = struct.unpack(">II", data[16:24])
        return w, h, "png"
    if data[:2] == b"\xff\xd8":
        size = _jpeg_size(data)
        return (size[0], size[1], "jpeg") if size else None
    if data[:6] in (b"GIF87a", b"GIF89a") and len(data) >= 10:
        w, h = struct.unpack("<HH", data[6:10])
        return w, h, "gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        size = _webp_size(data)
        return (size[0], size[1], "webp") if size else None
    try:  # AVIF, HEIC, BMP and anything else Pillow knows
        from PIL import Image
        with Image.open(io.BytesIO(data)) as im:
            w, h = im.size
            return w, h, (im.format or "unknown").lower()
    except Exception:
        return None


def load(source, headers):
    if os.path.isfile(source):
        with open(source, "rb") as f:
            return f.read(MAX_BYTES)
    if not source.startswith(("http://", "https://")):
        raise FileNotFoundError(f"no such file, and not an http(s) URL: {source}")
    req = urllib.request.Request(source, headers={"User-Agent": USER_AGENT, **headers})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read(MAX_BYTES)


def classify(w, h):
    ratio = w / h
    if ratio > LANDSCAPE_MIN:
        return ratio, "landscape"
    if ratio < PORTRAIT_MAX:
        return ratio, "portrait"
    return ratio, "square"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sources", nargs="+", help="image URLs or local image files")
    ap.add_argument("--header", action="append", default=[], help='extra request header, e.g. "Referer: https://host/"')
    ap.add_argument("--json", action="store_true", help="print machine-readable output")
    args = ap.parse_args()

    headers = {}
    for raw in args.header:
        name, _, value = raw.partition(":")
        headers[name.strip()] = value.strip()

    rows = []
    for src in args.sources:
        try:
            data = load(src, headers)
        except Exception as e:
            rows.append({"source": src, "error": f"{type(e).__name__}: {e}"})
            continue
        size = image_size(data)
        if not size:
            rows.append({"source": src, "error": "could not read image size"})
            continue
        w, h, fmt = size
        ratio, shape = classify(w, h)
        rows.append({"source": src, "width": w, "height": h, "format": fmt,
                     "ratio": round(ratio, 3), "shape": shape})

    shapes = Counter(r["shape"] for r in rows if "shape" in r)
    total = sum(shapes.values())
    verdict, horizontal = "no usable images", None
    if total:
        top, count = shapes.most_common(1)[0]
        if count / total >= AGREE:
            verdict = top
            horizontal = {"landscape": True, "portrait": False}.get(top)
            if horizontal is None:
                verdict = "square (ambiguous: check the card layout on the phone, default false)"
                horizontal = False
        else:
            verdict = "mixed: " + ", ".join(f"{k} {v}" for k, v in shapes.most_common())
            verdict += " (split the rows, or check each row's URLs separately)"

    result = {"images": rows, "shapes": dict(shapes), "verdict": verdict,
              "isHorizontalImages": horizontal}

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        for r in rows:
            if "error" in r:
                print(f"ERROR  {r['source']}  {r['error']}")
            else:
                print(f"{r['width']}x{r['height']:<6} ratio {r['ratio']:<6} {r['shape']:<9} {r['format']:<5} {r['source']}")
        print(f"verdict: {verdict}")
        if horizontal is not None:
            print(f"use: HomePageList(..., isHorizontalImages = {str(horizontal).lower()})")
    return 0 if total else 1


if __name__ == "__main__":
    sys.exit(main())
