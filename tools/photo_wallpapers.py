"""
Turns photos of Islamic geometric patterns from Pexels (Pexels License) into calm TV wallpapers:
cropped to 1920x1080, made grey, lowered in contrast and tinted into a narrow band around the
home screen's teal-grey, so the pattern reads as a quiet texture behind the text.
Called by fetch_data.py; can also be run on its own.
"""
import os
import subprocess
import sys

from PIL import Image, ImageEnhance, ImageFilter, ImageOps

# (file name, Pexels photo id, photographer handle, photo page)
PHOTOS = [
    ("10-geometric-ornament", 34256958, "sonniewing", "https://www.pexels.com/photo/34256958/"),
    ("11-geometric-ceiling", 36919265, "nur-cosar-2152749774", "https://www.pexels.com/photo/36919265/"),
    ("12-new-delhi-ceiling", 34517853, "rajneesh-narula-252427656", "https://www.pexels.com/photo/34517853/"),
    ("13-geometric-ceiling-2", 34256957, "sonniewing", "https://www.pexels.com/photo/34256957/"),
    ("14-tile-pattern", 30781474, "kovalskiolga", "https://www.pexels.com/photo/30781474/"),
    ("15-algiers", 28358694, "aymene-kralifa-364767791", "https://www.pexels.com/photo/28358694/"),
]
W, H = 1920, 1080
BASE = (26, 40, 42)     # same teal-grey as tools/wallpapers.py


def mute(img):
    img = ImageOps.fit(img.convert("RGB"), (W, H))
    g = ImageOps.grayscale(img).filter(ImageFilter.GaussianBlur(1.5))
    g = ImageEnhance.Contrast(g).enhance(0.55)
    lo = tuple(int(c * 0.85) for c in BASE)
    hi = tuple(min(255, int(c * 1.55)) for c in BASE)
    return ImageOps.colorize(g, lo, hi)


def build(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, pid, _, _ in PHOTOS:
        raw = os.path.join(out_dir, f".{name}.raw.jpg")
        subprocess.run(["curl", "-sSfL", "-m", "60", "-A", "Mozilla/5.0", "-o", raw,
                        f"https://images.pexels.com/photos/{pid}/pexels-photo-{pid}.jpeg"
                        f"?auto=compress&cs=tinysrgb&w={W}&h={H}&fit=crop"], check=True)
        mute(Image.open(raw)).save(os.path.join(out_dir, f"{name}.jpg"), quality=90)
        os.remove(raw)
        print(f"  wrote wallpapers/{name}.jpg")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "assets", "wallpapers"))
