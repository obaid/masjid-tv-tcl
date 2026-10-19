"""Very subtle tone-on-tone twelve-point star tiling wallpapers (1920x1080) for the TV home screen."""
import math, colorsys, sys
from PIL import Image, ImageDraw, ImageFilter
import numpy as np
W, H, SS = 1920, 1080, 2
OUT = sys.argv[1]

def star(cx, cy, r, n, k, rot=0):
    p = [(cx + r*math.cos(rot + 2*math.pi*i/n), cy + r*math.sin(rot + 2*math.pi*i/n)) for i in range(n)]
    o, i = [], 0
    for _ in range(n):
        o.append(p[i]); i = (i + k) % n
    return o

def ngon(cx, cy, r, n, rot=0):
    return [(cx + r*math.cos(rot + 2*math.pi*i/n), cy + r*math.sin(rot + 2*math.pi*i/n)) for i in range(n)]

def poly(d, pts, c, w):
    pts = [(x*SS, y*SS) for x, y in pts]
    d.line(pts + [pts[0]], fill=c, width=int(w*SS))

def lift(rgb, amt):
    h, l, s = colorsys.rgb_to_hls(*[c/255 for c in rgb])
    r, g, b = colorsys.hls_to_rgb(h, min(1, l + amt), s)
    return (int(r*255), int(g*255), int(b*255))

def make(name, bg, s=210, amt=0.055):
    line, faint = lift(bg, amt), lift(bg, amt*0.6)
    img = Image.new('RGB', (W*SS, H*SS), bg)
    d = ImageDraw.Draw(img)
    for gx in range(-1, W//s + 2):
        for gy in range(-1, H//s + 2):
            cx, cy = gx*s + s/2, gy*s + s/2
            poly(d, star(cx, cy, s*0.42, 12, 5), line, 1.2)
            poly(d, ngon(cx, cy, s*0.42, 12), faint, 1.0)
            poly(d, star(cx + s/2, cy + s/2, s*0.16, 8, 3), faint, 1.0)
    img = img.resize((W, H), Image.LANCZOS)
    m = Image.new('L', (W, H), 0)
    md = ImageDraw.Draw(m)
    md.rectangle((0, 0, W, H), fill=90)
    md.ellipse((-W*0.15, -H*0.35, W*1.15, H*1.35), fill=0)
    m = m.filter(ImageFilter.GaussianBlur(200))
    img = Image.composite(Image.new('RGB', (W, H), tuple(int(c*0.55) for c in bg)), img, m)
    img.save(f'{OUT}/{name}.jpg', quality=92)
    a = np.asarray(img.convert('L'), dtype=float)/255
    print(name, 'luminance range %.3f' % (np.percentile(a, 99.5) - np.percentile(a, 0.5)), 'mean %.3f' % a.mean())

# One steady background: a calm deep teal-grey, a little lighter than pure dark mode.
make('01-teal-grey', (26, 40, 42), amt=0.045)
