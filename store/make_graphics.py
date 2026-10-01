#!/usr/bin/env python3
"""Render icon-512.png and feature-graphic.png. Needs Pillow + cairosvg."""
import io, pathlib, re
import cairosvg
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent
FONTS = ROOT.parent / "app/src/main/res/font"
BG, FG = "#141220", "#A98DFF"          # ic_launcher_background / vector fillColor
# Read the crescent from the launcher vector so the store icon can never drift from the app icon.
PATH = re.search(r'pathData="([^"]+)"', (ROOT.parent / "app/src/main/res/drawable/ic_launcher_foreground.xml").read_text()).group(1)

def svg_png(view, size, with_bg=True):
    bg = f'<rect width="108" height="108" fill="{BG}"/>' if with_bg else ""
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{size}" height="{size}">'
           f'{bg}<path d="{PATH}" fill="{FG}"/></svg>')
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode()))).convert("RGBA")

# 1) icon: the launcher shows the central 72dp of the 108dp adaptive canvas -> that window at 512px.
svg_png("18 18 72 72", 512).convert("RGB").save(ROOT / "icon-512.png")

# 2) feature graphic 1024x500, drawn at 2x then downsampled
S = 2
W, H = 1024 * S, 500 * S
img = Image.new("RGB", (W, H), BG)
# soft violet glow behind the card
glow = Image.new("RGB", (W, H), BG)
ImageDraw.Draw(glow).ellipse((560 * S, 40 * S, 1060 * S, 540 * S), fill="#2A2150")
img = Image.blend(img, glow.filter(ImageFilter.GaussianBlur(90 * S)), 0.9)
d = ImageDraw.Draw(img)
font = lambda f, px: ImageFont.truetype(str(FONTS / f"gothic_a1_{f}.ttf"), px * S)
rr = lambda box, r, fill: d.rounded_rectangle([v * S for v in box], r * S, fill=fill)

# icon tile (the launcher icon on its background colour)
tile = svg_png("18 18 72 72", 128 * S)
mask = Image.new("L", tile.size, 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, tile.size[0] - 1, tile.size[1] - 1), 30 * S, fill=255)
img.paste(tile.convert("RGB"), (72 * S, 84 * S), mask)
d.rounded_rectangle((72 * S, 84 * S, 200 * S, 212 * S), 30 * S, outline="#2C2740", width=2 * S)

d.text((72 * S, 232 * S), "커퓨", font=font("extrabold", 92), fill="#EFECF8")
d.text((74 * S, 352 * S), "잠들기로 한 시간,", font=font("bold", 34), fill="#A98DFF")
d.text((74 * S, 398 * S), "지켰는지 매일 아침 확인", font=font("bold", 34), fill="#EFECF8")

# mock week card
cx0, cy0, cx1, cy1 = 580, 74, 952, 426
rr((cx0, cy0, cx1, cy1), 32, "#1F1B2D")
d.text(((cx0 + 32) * S, (cy0 + 28) * S), "5", font=font("extrabold", 52), fill="#EFECF8")
d.text(((cx0 + 66) * S, (cy0 + 46) * S), "/ 7일 성공", font=font("regular", 24), fill="#9E96B8")
rr((cx1 - 142, cy0 + 34, cx1 - 32, cy0 + 72), 19, "#2A2340")
d.text(((cx1 - 124) * S, (cy0 + 40) * S), "3일 연속", font=font("bold", 22), fill="#A98DFF")
days = ["금", "토", "일", "월", "화", "수", "목"]
ok = [1, 0, 1, 0, 1, 1, 1]
step = (cx1 - cx0 - 64 - 36) / 6
for i, (lab, good) in enumerate(zip(days, ok)):
    x = cx0 + 32 + 18 + i * step
    y = cy0 + 130
    d.ellipse(((x - 18) * S, (y - 18) * S, (x + 18) * S, (y + 18) * S), fill="#4FCB8B" if good else "#FF7A7E")
    if i == 6:
        d.ellipse(((x - 25) * S, (y - 25) * S, (x + 25) * S, (y + 25) * S), outline=FG, width=3 * S)
    tw = d.textlength(lab, font=font("regular", 20))
    d.text((x * S - tw / 2, (y + 38) * S), lab, font=font("regular", 20), fill="#9E96B8")
# mini usage chart: quiet through the 02-07 band, use before and after it
gx0, gx1, gy0, gy1 = cx0 + 32, cx1 - 32, cy0 + 214, cy1 - 36
rr((gx0 + (gx1 - gx0) * 2 / 24, gy0 - 8, gx0 + (gx1 - gx0) * 7 / 24, gy1 + 4), 6, "#2A2340")
pts = [0.35, 0.1, 0, 0, 0, 0, 0, 0, 0.6, 0.9, 0.35, 0.3, 0.45, 0, 0, 0, 0.25, 0.1, 0, 0.5, 0.85, 0.7, 0.95, 0.4, 0.35]
xy = [((gx0 + (gx1 - gx0) * i / (len(pts) - 1)) * S, (gy1 - (gy1 - gy0) * v) * S) for i, v in enumerate(pts)]
poly = Image.new("RGBA", (W, H), (0, 0, 0, 0))
pd = ImageDraw.Draw(poly)
pd.polygon(xy + [(xy[-1][0], gy1 * S), (xy[0][0], gy1 * S)], fill=(169, 141, 255, 50))
img.paste(poly, (0, 0), poly)
d = ImageDraw.Draw(img)
d.line(xy, fill=FG, width=4 * S, joint="curve")
d.line([(gx0 * S, gy1 * S), (gx1 * S, gy1 * S)], fill="#2C2740", width=2 * S)

img.resize((1024, 500), Image.LANCZOS).convert("RGB").save(ROOT / "feature-graphic.png")
