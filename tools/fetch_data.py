#!/usr/bin/env python3
"""
Downloads the third-party content the TV app bundles, into app/assets/.

Nothing fetched here is committed to the repo: it belongs to its publishers (see README,
"Credits and sources"). Run this once before ./app/build.sh.

    python3 tools/fetch_data.py

Needs: curl, pdftotext (poppler), Pillow (pip install pillow). Uses curl rather than urllib so it works on machines
whose Python has no CA bundle installed.
"""
import html
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import photo_wallpapers  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "assets")
QURAN_API = "https://api.quran.com/api/v4"
SAHIH_INTL = 20          # Quran.com translation id: Saheeh International
IBN_KATHIR = 169         # Quran.com tafsir id: Ibn Kathir (abridged), English
MASJIDAL_AUDIO = "https://masjidal.s3.us-east-2.amazonaws.com/audio"
MCA_PDF = "https://mcabayarea.org/wp-content/uploads/2025/12/2026_MCA_Prayer_Time.pdf"
HISN_API = "https://www.hisnmuslim.com/api"   # Hisn al-Muslim; chapter 25 = after the prayer
HADITH_API = "https://cdn.jsdelivr.net/gh/fawazahmed0/hadith-api@1/editions/eng-nawawi.json"
BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"

# Short, reflective verses for the "From the Quran" rotation (ranges are shown together).
VERSES = ("2:152 2:153 2:186 3:8 3:139 3:173 3:200 7:56 9:51 11:88 13:11 13:28 14:7 15:49 16:97 "
          "16:127 17:24 17:80 18:10 18:46 20:25 20:114 21:87 25:63 25:74 28:24 29:69 31:17 33:41 "
          "33:70 39:10 39:53 40:44 40:60 41:34 42:36 47:7 49:10 49:13 50:16 55:13 57:4 59:18 64:11 "
          "67:2 73:8 87:14 89:27-28 93:5 94:5-6 103:1-3 112:1-4").split()

# Duas from the Quran ("Rabbana..." / "Rabbi..."). Each is checked to contain رب.
DUAS = ("2:127 2:128 2:201 2:250 3:8 3:9 3:16 3:53 3:147 3:191 3:192 3:193 3:194 7:23 7:47 7:89 "
        "7:126 10:85 14:38 14:40 14:41 17:24 17:80 18:10 20:25 20:114 21:83 21:89 23:29 23:97 "
        "23:109 23:118 25:65 25:74 26:83 27:19 28:16 28:24 40:7 59:10 60:5 71:28").split()

# Surah Al-Haqqah themes. Titles are ours; "quote" spans are copied verbatim from the
# Ibn Kathir (abridged) commentary at fetch time and joined with an ellipsis.
HAQQAH_THEMES = [
    (1, 3, "The Day that will surely come", [
        "Al-Haqqah is one of the names of the Day of Judgement, because during it the promise and the threat will inevitably occur."]),
    (4, 5, "The people of Thamud", [
        "Then Allah mentions the destruction of the nations that denied the Resurrection.",
        "(As for Thamud, they were destroyed by the Taghiyah!) It is the cry which will silence them, and the quake that will silence them."]),
    (6, 8, "The people of 'Ad and the cold wind", [
        "(And as for `Ad, they were destroyed by a wind Sarsar) meaning, a cold wind.",
        "Rather they are all gone, right down to the last of them"]),
    (9, 10, "Pharaoh and the overturned cities", [
        "(the overthrown cities) those nations that rejected their Messengers.",
        "Al-Khati'ah means their rejection of what Allah revealed."]),
    (11, 12, "Nuh's ship — a blessing to remember", [
        "the people of the earth were covered with the flood except for those who were with Nuh in the ship.",
        "For this reason Allah reminds humanity of His blessing"]),
    (13, 17, "When the Trumpet is blown", [
        "Allah informs of the horrors that will take place on the Day of Judgement. The first of these events is the blowing of fright (into the Trumpet)"]),
    (18, 18, "Nothing is hidden from Allah", [
        "you will all be presented to the Knower of the secrets and private counsels. He is the One from Whom none of your affairs are hidden."]),
    (19, 24, "The book given in the right hand", [
        "Allah informs of the happiness of those who receive their Book in the right hand on the Day of Judgement and being pleased with this.",
        "He will say this because he knows that what is in it is good and purely virtuous deeds."]),
    (25, 37, "The book given in the left hand", [
        "These Ayat inform about the condition of the wretched people when one of them is given his Record (of deeds) in his left hand when the people are brought before Allah. At this time he will be very remorseful."]),
    (38, 43, "The Qur'an is the Speech of Allah", [
        "This is an oath swearing that the Qur'an is His Speech, His inspiration and His revelation to His servant and Messenger, whom He chose to convey His Message, and the Messenger carried out this trust faithfully."]),
    (44, 47, "The Prophet ﷺ conveyed it truthfully", [
        "if Muhammad forged something against Us, as they claim,",
        "then We would surely be swift in punishing him. And of course, Muhammad did not do any of this (as the disbelievers claimed)."]),
    (48, 52, "A reminder for the mindful", [
        "(And verily, this (Qur'an) is a Reminder for those who have Taqwa.) meaning, the Qur'an."]),
]

# Quran player sections.
PLAYER_GROUPS = [("Surahs", [2, 18]),
                 ("Juz 29 · Tabarak", list(range(67, 78))),
                 ("Juz 30 · 'Amma", list(range(78, 115)))]

# Masjidal's public adhan recordings chosen for the app.
ADHAN = {"adhan.mp3": "athan/3.mp3", "fajr.mp3": "athan/f2.mp3"}


def curl(url, out=None, ua=None):
    cmd = ["curl", "-sSfL", "-m", "60"] + (["-A", ua] if ua else []) + [url]
    if out:
        subprocess.run(cmd + ["-o", out], check=True)
        return None
    return subprocess.run(cmd, check=True, capture_output=True).stdout


def get_json(url):
    return json.loads(curl(url))


def strip_markup(t):
    t = re.sub(r"<sup.*?</sup>", "", t)
    return re.sub(r"<[^>]+>", "", t).strip()


def clean_translation(t):
    t = strip_markup(t)
    # Verses that continue into the next one end with "…" rather than a dangling comma.
    if t and t[-1] in ",;:-—":
        t = re.sub(r"[\s,;:\-—]+$", "…", t)
    return t


def verse(key):
    v = get_json(f"{QURAN_API}/verses/by_key/{key}?translations={SAHIH_INTL}&fields=text_uthmani")["verse"]
    time.sleep(0.1)
    return v["text_uthmani"], strip_markup(v["translations"][0]["text"])


def passage(ref):
    """'89:27-28' -> joined Arabic and English for the range."""
    s, a = ref.split(":")
    lo, hi = (a.split("-") + [a])[:2]
    parts = [verse(f"{s}:{n}") for n in range(int(lo), int(hi) + 1)]
    return " ".join(p[0] for p in parts), clean_translation(" ".join(p[1] for p in parts))


def chapters():
    return {c["id"]: c for c in get_json(f"{QURAN_API}/chapters?language=en")["chapters"]}


def surah_ayahs(n, ch):
    out, page = [], 1
    while True:
        r = get_json(f"{QURAN_API}/verses/by_chapter/{n}?translations={SAHIH_INTL}"
                     f"&fields=text_uthmani&per_page=50&page={page}")
        for v in r["verses"]:
            out.append({"s": n, "a": v["verse_number"], "n": ch[n]["verses_count"],
                        "surah": ch[n]["name_simple"], "surah_ar": ch[n]["name_arabic"],
                        "ar": v["text_uthmani"], "en": clean_translation(v["translations"][0]["text"])})
        if not r["pagination"].get("next_page"):
            return out
        page += 1


def write(name, data):
    with open(os.path.join(ASSETS, name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"  wrote {name}")


def fetch_verses(ch):
    out = []
    for ref in VERSES:
        ar, en = passage(ref)
        out.append({"ref": ref, "ar": ar, "en": en, "surah": ch[int(ref.split(':')[0])]["name_simple"]})
    write("verses.json", out)


def fetch_duas(ch):
    out = []
    for ref in DUAS:
        ar, en = passage(ref)
        simple = get_json(f"{QURAN_API}/verses/by_key/{ref}?fields=text_imlaei_simple")["verse"]["text_imlaei_simple"]
        if "رب" not in simple:
            sys.exit(f"dua {ref} does not contain رب — wrong verse key?")
        out.append({"ref": ref, "surah": ch[int(ref.split(':')[0])]["name_simple"], "ar": ar, "en": en})
    write("duas.json", out)


def fetch_hadith():
    hs = get_json(HADITH_API)["hadiths"]
    # Long narrations don't fit on a TV screen; keep the ones that do.
    write("hadith.json", [{"n": h["hadithnumber"], "en": re.sub(r"\s+", " ", h["text"]).strip()}
                          for h in hs if len(h["text"]) <= 600])


def tafsir_text(ayah):
    t = get_json(f"{QURAN_API}/tafsirs/{IBN_KATHIR}/by_ayah/69:{ayah}")["tafsir"]["text"]
    t = html.unescape(re.sub(r"<[^>]+>", " ", t))
    t = re.sub(r"[؀-ۿﹰ-﻿ࢠ-ࣿﭐ-﷿]+", "", t)   # Arabic quotations
    return re.sub(r"\(\s*\)|\s+", " ", t)


def fetch_haqqah(ch):
    write("haqqah.json", surah_ayahs(69, ch))
    texts = {}
    out = []
    for lo, hi, title, spans in HAQQAH_THEMES:
        # The tafsir for a group of ayahs is returned for the first ayah of its passage.
        body = ""
        for a in range(lo, 0, -1):
            body = texts.setdefault(a, tafsir_text(a))
            if body.strip():
                break
        for s in spans:
            if s not in body:
                sys.exit(f"Ibn Kathir quote not found for 69:{lo}-{hi}: {s[:60]}…")
        out.append({"from": lo, "to": hi, "title": title, "text": " … ".join(spans),
                    "src": "Tafsir Ibn Kathir (abridged)"})
    write("haqqah_meaning.json", out)


def fetch_player(ch):
    write("quran_surahs.json", [{"title": t, "surahs": [{"n": n, "en": ch[n]["name_simple"],
                                                         "ar": ch[n]["name_arabic"], "ayahs": ch[n]["verses_count"]}
                                                        for n in ns]} for t, ns in PLAYER_GROUPS])


def fetch_adhkar():
    """Hisn al-Muslim, chapter 25: what to say after completing the prayer (Arabic + English)."""
    def chapter(lang):
        d = json.loads(curl(f"{HISN_API}/{lang}/25.json").decode("utf-8-sig"))
        return next(iter(d.values()))
    ar, en = chapter("ar"), chapter("en")
    # Which prayers each entry is for (Hisn al-Muslim notes these in the text itself).
    only = {72: ["Fajr", "Maghrib"], 73: ["Fajr"]}
    out = []
    for a, e in zip(ar, en):
        n = int(e["ID"])
        text_ar = a["ARABIC_TEXT"].strip()
        text_ar = re.sub(r"^\(\(|\)\)\.?$", "", text_ar).replace("((", "").replace("))", "").strip()
        out.append({"id": n, "ar": text_ar, "en": e["TRANSLATED_TEXT"].strip(),
                    "repeat": int(e.get("REPEAT") or 1), "only": only.get(n, [])})
    write("adhkar.json", out)


def fetch_mca_schedule():
    """MCA's printed 2026 timetable -> {date: times}; the offline fallback for prayer times."""
    pdf = os.path.join(ASSETS, ".mca.pdf")
    curl(MCA_PDF, pdf, ua=BROWSER_UA)
    txt = subprocess.run(["pdftotext", "-layout", pdf, "-"], check=True, capture_output=True, text=True).stdout
    os.remove(pdf)
    months = ["January", "February", "March", "April", "May", "June", "July", "August",
              "September", "October", "November", "December"]
    names = ["fajr", "fajr_iq", "sunrise", "dhuhr", "dhuhr_iq", "asr", "asr_iq", "maghrib", "isha", "isha_iq"]

    def to24(t, k):
        h, m = map(int, t.split(":"))
        if k.startswith(("fajr", "sunrise")):
            h %= 12
        elif k.startswith("dhuhr"):
            h = h if h >= 11 else h + 12
        else:
            h = h % 12 + 12
        return f"{h:02d}:{m:02d}"

    out, block = {}, None
    for ln in txt.split("\n"):
        found = [m for m in months if re.search(r"\b" + m + r"\b", ln)]
        if len(found) == 3:
            block = found
            continue
        toks = ln.split()
        if not block or not toks or not toks[0].isdigit():
            continue
        groups, i = [], 0
        while i < len(toks):
            if toks[i].isdigit():
                groups.append((int(toks[i]), toks[i + 1:i + 11]))
                i += 11
            else:
                i += 1
        # Columns are three months side by side; short months leave a gap, so place each
        # group by its horizontal position on the line.
        pos = [m.start() for m in re.finditer(r"(?<!\S)(\d{1,2})(?=\s+\d{1,2}:\d\d)", ln)]
        cols = [block[min(2, p * 3 // max(1, len(ln)))] for p in pos] if len(groups) < 3 else block
        if len(groups) == 2 and "February" in block:
            cols = [block[0], block[2]]
        for (day, vals), mon in zip(groups, cols):
            if len(vals) == 10:
                out[f"2026-{months.index(mon) + 1:02d}-{day:02d}"] = {n: to24(v, n) for n, v in zip(names, vals)}
    if len(out) != 365:
        sys.exit(f"MCA schedule parse produced {len(out)} days, expected 365")
    out["2026-08-19"]["isha"] = "21:14"     # typo in the printed PDF (9:43 -> 9:14)
    write("mca_2026.json", dict(sorted(out.items())))


def fetch_adhan():
    os.makedirs(os.path.join(ASSETS, "adhan"), exist_ok=True)
    for name, path in ADHAN.items():
        curl(f"{MASJIDAL_AUDIO}/{path}", os.path.join(ASSETS, "adhan", name))
        print(f"  wrote adhan/{name}")


if __name__ == "__main__":
    os.makedirs(ASSETS, exist_ok=True)
    ch = chapters()
    print("Quran.com: verses, duas, Juz 'Amma, Al-Haqqah, player list")
    fetch_verses(ch)
    fetch_duas(ch)
    write("juz_amma.json", [a for n in range(78, 115) for a in surah_ayahs(n, ch)])
    fetch_haqqah(ch)
    fetch_player(ch)
    print("Hadith: Forty Hadith of Imam an-Nawawi")
    fetch_hadith()
    print("Hisn al-Muslim: adhkar after the prayer")
    fetch_adhkar()
    print("MCA: 2026 printed prayer schedule")
    fetch_mca_schedule()
    print("Masjidal: adhan recordings")
    fetch_adhan()
    print("Pexels: pattern wallpapers")
    photo_wallpapers.build(os.path.join(ASSETS, "wallpapers"))
    print("done")
