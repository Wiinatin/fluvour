#!/usr/bin/env python3
"""Cleans the Thai frequency list (FrequencyWords th_50k): repairs double-encoded rows, drops fragments, Latin words and run-together phrases.
Used by build_levels.py (hermit('th')) and runnable on its own on assets/levels/th.tsv:  python3 tools/levels/clean_thai.py
Needs `pip install pythainlp` (only to spot phrases). Words keep their order; ranks are renumbered from 1."""
import re, sys

THAI = re.compile(r"^[\u0e01-\u0e4e\u0e46]+$")
LEADING_OK = set("เแโใไ")
NON_BASE = set("ะัาำิีึืฺุู็่้๊๋์ํ๎")  # vowel signs and tone marks cannot start a word (leading vowels เแโใไ can)

def repair(w):
    """UTF-8 bytes that were read as cp874: encode back and decode properly. Returns None when it cannot be repaired."""
    if THAI.match(w) and not re.search("เ[ธน][\u0e01-\u0e5b]", w): return w
    try:
        return w.encode("cp874").decode("utf-8")
    except Exception:
        return None

def ok_word(w, tok):
    if not THAI.match(w) or w[0] in NON_BASE or len(w) > 14: return False
    parts = [t for t in tok(w) if t.strip()]
    return len(parts) <= 1 or (len(parts) == 2 and len(w) <= 10)

def clean(words):
    from pythainlp.tokenize import word_tokenize
    tok = lambda s: word_tokenize(s, engine="newmm", keep_whitespace=False)
    seen, out = set(), []
    for w in words:
        w = w.strip()
        if not w: continue
        w = repair(w)
        if w is None or w in seen or not ok_word(w, tok): continue
        seen.add(w); out.append(w)
    return out

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/levels/th.tsv"
    old = [l.split("\t")[0] for l in open(path, encoding="utf-8").read().splitlines()]
    new = clean(old)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for i, w in enumerate(new, 1): f.write(f"{w}\t{i}\t\n")
    print(len(old), "->", len(new), "words")
