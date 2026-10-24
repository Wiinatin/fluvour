#!/usr/bin/env python3
"""Builds app/src/main/assets/levels/<lang>.tsv  (Phase 2A step 7: level tags).

Line format:  word <TAB> frequency rank (1 = most common, 0 = not in the list) <TAB> level code (may be empty)
Level codes:  ja N1-N5 (JLPT) | zh H1-H7 (HSK 3.0, H7 = 7-9) or h1-h6 (HSK 2.0, only when a word is not in 3.0)
              | en A1-C2 (CEFR). Other languages carry frequency only.
Needs network. Needs `pip install wordfreq` (Japanese frequency only).
Run from the project root:  python3 tools/levels/build_levels.py
Sources and licences are listed in docs/LEVELS.md.
"""
import csv, io, json, os, urllib.request

OUT = "app/src/main/assets/levels"
TOP = 30000
HERMIT = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{c}/{c}_50k.txt"
HSK = "https://raw.githubusercontent.com/drkameleon/complete-hsk-vocabulary/main/complete.min.json"
JLPT = "https://raw.githubusercontent.com/stephenmk/yomitan-jlpt-vocab/main/original_data/n{n}.csv"
CEFRJ = "https://raw.githubusercontent.com/openlanguageprofiles/olp-en-cefrj/master/cefrj-vocabulary-profile-1.5.csv"
OCTA = "https://raw.githubusercontent.com/openlanguageprofiles/olp-en-cefrj/master/octanove-vocabulary-profile-c1c2-1.0.csv"
HERMIT_CODE = {"zh": "zh_cn"}  # app language code -> folder name
LANGS = ["ja", "th", "en", "zh", "ko", "es", "fr", "de", "vi", "id"]

def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8-sig")

def hermit(lang):
    c = HERMIT_CODE.get(lang, lang)
    ranks = {}
    for line in get(HERMIT.format(c=c)).splitlines():
        w = line.rsplit(" ", 1)[0].strip().lower()
        if w and w not in ranks and len(ranks) < TOP:
            ranks[w] = len(ranks) + 1
    return ranks

def japanese_freq():
    from wordfreq import top_n_list
    ranks = {}
    for w in top_n_list("ja", TOP * 2):
        w = w.strip().lower()
        if w and w not in ranks and len(w) > 0 and not w.isdigit() and len(ranks) < TOP:
            ranks[w] = len(ranks) + 1
    return ranks

def jlpt_levels():
    lv = {}
    for n in (5, 4, 3, 2, 1):  # easiest first wins
        for row in csv.DictReader(io.StringIO(get(JLPT.format(n=n)))):
            # The kana reading is used only for kana-only words: reading 'かい' would otherwise tag every homophone.
            key = (row["kanji"] or row["kana"] or "").strip().lower()
            if key and key not in lv: lv[key] = f"N{n}"
    return lv

def hsk_levels():
    lv = {}
    for e in json.loads(get(HSK)):
        new = sorted(int(x[1:]) for x in e["l"] if x[0] == "n")
        old = sorted(int(x[1:]) for x in e["l"] if x[0] == "o")
        code = f"H{new[0]}" if new else (f"h{old[0]}" if old else None)
        keys = [e["s"]] + [f["t"] for f in e.get("f", []) if f.get("t")]
        for k in keys:
            if code and k not in lv: lv[k] = code
    return lv

def cefr_levels():
    lv, order = {}, ["A1", "A2", "B1", "B2", "C1", "C2"]
    for url in (CEFRJ, OCTA):
        for row in csv.DictReader(io.StringIO(get(url))):
            lvl = row["CEFR"].strip().upper()
            if lvl not in order: continue
            for h in row["headword"].split("/"):
                h = h.strip().lower()
                if h and (h not in lv or order.index(lvl) < order.index(lv[h])): lv[h] = lvl
    return lv

def write(lang, ranks, levels):
    words = dict(ranks)
    for w in levels: words.setdefault(w, 0)
    rows = sorted(words.items(), key=lambda kv: (kv[1] == 0, kv[1], kv[0]))
    with open(f"{OUT}/{lang}.tsv", "w", encoding="utf-8", newline="\n") as f:
        for w, r in rows:
            if "\t" in w or "\n" in w: continue
            f.write(f"{w}\t{r}\t{levels.get(w, '')}\n")
    print(lang, len(rows), "words,", sum(1 for w in words if w in levels), "with a level")

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    extra = {"ja": jlpt_levels, "zh": hsk_levels, "en": cefr_levels}
    for l in LANGS:
        write(l, japanese_freq() if l == "ja" else hermit(l), extra[l]() if l in extra else {})
