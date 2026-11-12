"""Geoshift launcher icon: one shape list -> Android vector drawables + PNG previews.

Usage: python3 -I icon.py <out_dir_for_xml> <out_dir_for_png>
Paths use absolute M L C Q A Z only, in a 108 x 108 viewport.
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw

V = 108.0
BG_TOP, BG_BOTTOM = "#667EEA", "#764BA2"
WHITE, FRAME, SHIRT, SKIN, SKIN_FAR, SHORTS = "#FFFFFF", "#FFD166", "#00D2A0", "#FFE0B2", "#F5CBA7", "#3949AB"
HAIR = "#3E2723"
HAIR_TIE = "#FF6584"


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g} A{r:g},{r:g} 0 1,0 {cx + r:g},{cy:g} A{r:g},{r:g} 0 1,0 {cx - r:g},{cy:g} Z"


def stroke(d, color, width, alpha=1.0):
    return dict(d=d, stroke=color, width=width, alpha=alpha)


def fill(d, color, alpha=1.0):
    return dict(d=d, fill=color, alpha=alpha)


# Ground the bike stands on; part of the background layer.
GROUND = [
    fill("M0,82.6 Q54,76.2 108,82.6 L108,108 L0,108 Z", WHITE, 0.14),
    fill("M0,92 Q54,86 108,92 L108,108 L0,108 Z", WHITE, 0.12),
]

# The cyclist (girl with twin ponytails), drawn back to front. Designed in the full viewport, then scaled into the safe zone.
RIDER = [
    # speed lines
    stroke("M17.5,47 L27.5,47", WHITE, 2.6, 0.6),
    stroke("M13.5,56 L25,56", WHITE, 2.6, 0.4),
    # far twin ponytail (flying back and up in the wind)
    fill("M59,24.5 C53,20.5 45,19 38,22 C43,24 49,25.5 58,26.5 Z", HAIR),
    stroke("M58.5,24 L59.5,26", HAIR_TIE, 2.2),
    # far leg and shoe
    stroke("M48.5,46.5 L52.5,58 L47.5,69.5", SKIN_FAR, 4.4),
    stroke("M45,71 L50,71", WHITE, 3.2),
    # wheels
    stroke(circle(36, 68, 11.5), WHITE, 3.6),
    stroke(circle(72, 68, 11.5), WHITE, 3.6),
    fill(circle(36, 68, 1.9), WHITE),
    fill(circle(72, 68, 1.9), WHITE),
    # frame: rear triangle, top tube, down tube, fork
    stroke("M36,68 L51,68.5 L46.6,52.5 Z", FRAME, 3.1),
    stroke("M46.9,53.6 L65.6,51 M51,68.5 L66,52.6", FRAME, 3.1),
    stroke("M64.9,47.8 L66,52.6 L72,68", FRAME, 3.1),
    # saddle and handlebar
    stroke("M42.6,48.9 L50.4,48.9", WHITE, 3.6),
    stroke("M64.9,47.8 L64.5,45 L69.5,43.8", WHITE, 3.2),
    # near leg: thigh and shin, shorts over the thigh, shoe on the pedal
    stroke("M49,46.5 L59,54 L55.5,65.5", SKIN, 4.8),
    stroke("M48.6,46.2 L55,51", SHORTS, 7.0),
    stroke("M53.2,67 L58.6,67", WHITE, 3.4),
    # near twin ponytail (flying back and lower in the wind)
    fill("M59.5,27.5 C53,26 46,26.5 40,30 C45,31.5 52,31 58.5,29.5 Z", HAIR),
    stroke("M58.8,27.8 L59.6,29.8", HAIR_TIE, 2.2),
    # arm (under the torso, so it leaves the shoulder cleanly), torso (mint jersey), sleeve, neck, head, helmet
    stroke("M59.4,36 Q62,42.8 68.8,44", SKIN, 4.0),
    stroke("M48.8,45 L59.4,35.2", SHIRT, 8.8),
    stroke("M59.6,36 L61,39.4", SHIRT, 5.0),
    stroke("M61.4,32.2 L63.6,29.4", SKIN, 4.0),
    fill(circle(66, 26.4, 6.5), SKIN),
    stroke("M62.5,26.8 Q61.5,29.5 63.2,31", HAIR, 1.8),
    fill("M58.9,26.8 C58.4,21.3 62.2,18.3 66.4,18.4 C70.8,18.5 73.8,21.5 74.3,25.3 Z", WHITE),
    stroke("M61.2,22.4 Q65.6,19.6 71.4,21.6", SHIRT, 1.6),
]

# Foreground fits the adaptive-icon safe zone (66 dp circle) after this transform about the centre.
FG_SCALE, FG_DY = 0.88, 1.5


# ---- path flattening -------------------------------------------------------------------------

def arc(x0, y0, rx, ry, phi, large, sweep, x1, y1, out):
    if rx == 0 or ry == 0:
        out.append((x1, y1)); return
    p = math.radians(phi)
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    xp = math.cos(p) * dx + math.sin(p) * dy
    yp = -math.sin(p) * dx + math.cos(p) * dy
    lam = xp * xp / (rx * rx) + yp * yp / (ry * ry)
    if lam > 1:
        rx *= math.sqrt(lam); ry *= math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    den = rx * rx * yp * yp + ry * ry * xp * xp
    co = math.sqrt(max(0.0, num / den)) * (1 if large != sweep else -1)
    cxp, cyp = co * rx * yp / ry, -co * ry * xp / rx
    cx = math.cos(p) * cxp - math.sin(p) * cyp + (x0 + x1) / 2
    cy = math.sin(p) * cxp + math.cos(p) * cyp + (y0 + y1) / 2
    t0 = math.atan2((yp - cyp) / ry, (xp - cxp) / rx)
    t1 = math.atan2((-yp - cyp) / ry, (-xp - cxp) / rx)
    dt = t1 - t0
    if sweep and dt < 0: dt += 2 * math.pi
    if not sweep and dt > 0: dt -= 2 * math.pi
    n = max(8, int(abs(dt) / (math.pi / 48)))
    for i in range(1, n + 1):
        t = t0 + dt * i / n
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        out.append((math.cos(p) * ex - math.sin(p) * ey + cx, math.sin(p) * ex + math.cos(p) * ey + cy))


def flatten(d):
    """-> list of (points, closed)."""
    tokens = re.findall(r"[MLCQAZ]|-?\d*\.?\d+(?:e-?\d+)?", d)
    subs, pts, i, cmd = [], [], 0, None

    def num():
        nonlocal i
        v = float(tokens[i]); i += 1
        return v

    while i < len(tokens):
        if tokens[i] in "MLCQAZ":
            cmd = tokens[i]; i += 1
            if cmd == "Z":
                subs.append((pts, True)); pts = []
                continue
        if cmd == "M":
            if pts: subs.append((pts, False))
            pts = [(num(), num())]; cmd = "L"
        elif cmd == "L":
            pts.append((num(), num()))
        elif cmd == "Q":
            x0, y0 = pts[-1]; x1, y1, x2, y2 = num(), num(), num(), num()
            for k in range(1, 25):
                t = k / 24
                pts.append(((1 - t) ** 2 * x0 + 2 * t * (1 - t) * x1 + t * t * x2, (1 - t) ** 2 * y0 + 2 * t * (1 - t) * y1 + t * t * y2))
        elif cmd == "C":
            x0, y0 = pts[-1]; x1, y1, x2, y2, x3, y3 = (num() for _ in range(6))
            for k in range(1, 33):
                t = k / 32; u = 1 - t
                pts.append((u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3,
                            u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3))
        elif cmd == "A":
            x0, y0 = pts[-1]; rx, ry, phi = num(), num(), num(); large, sweep = int(num()), int(num()); x1, y1 = num(), num()
            arc(x0, y0, rx, ry, phi, large, sweep, x1, y1, pts)
    if pts: subs.append((pts, False))
    return subs


# ---- rendering -------------------------------------------------------------------------------

def rgb(c):
    return tuple(int(c[k:k + 2], 16) for k in (1, 3, 5))


def draw_shapes(img, shapes, px, scale=1.0, dx=0.0, dy=0.0, mono=None):
    """Composites [shapes] onto RGBA [img]; px = pixels per viewport unit; transform is about the centre."""
    def tr(p):
        return (((p[0] - V / 2) * scale + V / 2 + dx) * px, ((p[1] - V / 2) * scale + V / 2 + dy) * px)

    for s in shapes:
        mask = Image.new("L", img.size, 0)
        g = ImageDraw.Draw(mask)
        for pts, closed in flatten(s["d"]):
            q = [tr(p) for p in pts]
            if "fill" in s:
                if len(q) >= 3: g.polygon(q, fill=255)
            else:
                w = s["width"] * scale * px
                line = q + [q[0]] if closed else q
                g.line(line, fill=255, width=max(1, round(w)), joint="curve")
                for x, y in line:  # round caps and joins
                    g.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
        alpha = s.get("alpha", 1.0)
        if alpha < 1: mask = mask.point(lambda v: int(v * alpha))
        color = mono or rgb(s.get("fill") or s["stroke"])
        img.paste(Image.new("RGBA", img.size, color + (255,)), (0, 0), mask)


def background(px):
    n = int(V * px)
    img = Image.new("RGBA", (n, n))
    top, bottom = rgb(BG_TOP), rgb(BG_BOTTOM)
    g = ImageDraw.Draw(img)
    for y in range(n):
        t = y / (n - 1)
        g.line([(0, y), (n, y)], fill=tuple(round(top[k] + (bottom[k] - top[k]) * t) for k in range(3)) + (255,))
    draw_shapes(img, GROUND, px)
    return img


def masked(img, px, kind):
    """What a launcher shows: the central 72 x 72 of the 108 viewport under a mask."""
    n = img.size[0]
    lo, hi = 18 * px, 90 * px
    mask = Image.new("L", (n, n), 0)
    g = ImageDraw.Draw(mask)
    if kind == "circle": g.ellipse((lo, lo, hi, hi), fill=255)
    else: g.rounded_rectangle((lo, lo, hi, hi), radius=(18 if kind == "squircle" else 9) * px, fill=255)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.crop((int(lo), int(lo), int(hi), int(hi)))


def previews(out):
    px = 10
    icon = background(px)
    draw_shapes(icon, RIDER, px, FG_SCALE, 0, FG_DY)
    sheet = Image.new("RGBA", (1500, 560), (24, 26, 34, 255))
    x = 20
    for kind in ("circle", "squircle", "rounded"):
        big = masked(icon, px, kind).resize((360, 360), Image.LANCZOS)
        sheet.paste(big, (x, 20), big); x += 380
    # launcher sizes: 96 px (xhdpi), 60 px, 48 px
    y = 400
    x = 20
    for size in (144, 96, 60, 48):
        small = masked(icon, px, "circle").resize((size, size), Image.LANCZOS)
        sheet.paste(small, (x, y), small); x += size + 24
    # light tile behind, to judge the edge on a light wallpaper
    tile = Image.new("RGBA", (200, 150), (236, 238, 244, 255))
    small = masked(icon, px, "squircle").resize((110, 110), Image.LANCZOS)
    tile.paste(small, (45, 20), small)
    sheet.paste(tile, (x + 20, 400))
    # monochrome (themed icon): tinted glyph on a tinted disc
    mono = Image.new("RGBA", icon.size, (214, 226, 255, 255))
    draw_shapes(mono, RIDER, px, FG_SCALE, 0, FG_DY, mono=(30, 52, 120))
    m = masked(mono, px, "circle").resize((360, 360), Image.LANCZOS)
    sheet.paste(m, (1160, 20), m)
    ms = masked(mono, px, "circle").resize((60, 60), Image.LANCZOS)
    sheet.paste(ms, (1160, 400), ms)
    # in-app logo: rounded square, rider larger
    logo = background(px)
    draw_shapes(logo, RIDER, px, LOGO_SCALE, 0, LOGO_DY)
    n = logo.size[0]
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n, n), radius=24 * px, fill=255)
    lg = Image.new("RGBA", (n, n), (0, 0, 0, 0)); lg.paste(logo, (0, 0), mask)
    lg = lg.resize((150, 150), Image.LANCZOS)
    sheet.paste(lg, (1300, 400), lg)
    sheet.save(os.path.join(out, "preview.png"))


LOGO_SCALE, LOGO_DY = 1.12, 0.5


# ---- Android vector drawables ----------------------------------------------------------------

HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n'
        '    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="108" android:viewportHeight="108">\n')


def argb(color, alpha=1.0):
    return "#%02X%s" % (round(alpha * 255), color[1:]) if alpha < 1 else color


def path_xml(s, mono=None, indent="    "):
    color = mono or (s.get("fill") or s["stroke"])
    a = s.get("alpha", 1.0)
    if "fill" in s:
        return f'{indent}<path android:fillColor="{argb(color, a)}" android:pathData="{s["d"]}"/>\n'
    return (f'{indent}<path android:strokeColor="{argb(color, a)}" android:strokeWidth="{s["width"]:g}" '
            f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{s["d"]}"/>\n')


def group(shapes, scale, dy, mono=None):
    out = f'    <group android:pivotX="54" android:pivotY="54" android:scaleX="{scale:g}" android:scaleY="{scale:g}" android:translateY="{dy:g}">\n'
    for s in shapes: out += path_xml(s, mono, "        ")
    return out + "    </group>\n"


GRADIENT = ('    <path android:pathData="{d}">\n'
            '        <aapt:attr name="android:fillColor">\n'
            '            <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" android:endY="108">\n'
            f'                <item android:offset="0" android:color="{BG_TOP}"/>\n'
            f'                <item android:offset="1" android:color="{BG_BOTTOM}"/>\n'
            '            </gradient>\n'
            '        </aapt:attr>\n'
            '    </path>\n')
AAPT = ' xmlns:aapt="http://schemas.android.com/aapt"'
NOTE = "<!-- Generated by tools/icon/icon.py; edit the shapes there, not here. -->\n"


def write(path, text):
    with open(path, "w", encoding="utf-8") as f: f.write(NOTE + text)


def drawables(res):
    d = os.path.join(res, "drawable")
    # adaptive background: gradient + ground
    write(os.path.join(d, "ic_launcher_background.xml"),
          HEAD.format(aapt=AAPT, size=108) + GRADIENT.format(d="M0,0 L108,0 L108,108 L0,108 Z") + "".join(path_xml(s) for s in GROUND) + "</vector>\n")
    write(os.path.join(d, "ic_launcher_foreground.xml"), HEAD.format(aapt="", size=108) + group(RIDER, FG_SCALE, FG_DY) + "</vector>\n")
    # themed icons (Android 13+): the system tints this single-colour layer
    write(os.path.join(d, "ic_launcher_monochrome.xml"), HEAD.format(aapt="", size=108) + group(RIDER, FG_SCALE, FG_DY, mono="#000000") + "</vector>\n")
    # in-app logo (splash, setup header): rounded square
    square = "M24,0 L84,0 Q108,0 108,24 L108,84 Q108,108 84,108 L24,108 Q0,108 0,84 L0,24 Q0,0 24,0 Z"
    clip = f'    <clip-path android:pathData="{square}"/>\n'
    write(os.path.join(d, "ic_logo.xml"),
          HEAD.format(aapt=AAPT, size=108) + clip + GRADIENT.format(d=square) + "".join(path_xml(s) for s in GROUND)
          + group(RIDER, LOGO_SCALE, LOGO_DY) + "</vector>\n")
    # status-bar icon for the two foreground services: white glyph filling a 24 dp box
    write(os.path.join(d, "ic_stat_geoshift.xml"),
          HEAD.format(aapt="", size=24) + group([s for s in RIDER if s.get("alpha", 1) == 1], STAT_SCALE, STAT_DY, mono="#FFFFFF") + "</vector>\n")


STAT_SCALE, STAT_DY = 1.55, 6.5

if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    previews(out)
    if res != "-":
        drawables(res)
