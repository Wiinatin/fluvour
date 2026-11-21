#!/usr/bin/env python3
"""Builds app/src/main/assets/lexicon/th.tsv (word<TAB>count) for ThaiSegmenter.

Sources, both from the PyThaiNLP package (pip download pythainlp --no-deps, then unzip the wheel), licence CC0 1.0
(see pythainlp/corpus/corpus_license.md): corpus/words_th.txt (plain word list) and corpus/tnc_freq.txt (word counts from the
Thai National Corpus). Kept: Thai-only words of 2+ characters that are in the word list, plus any counted word seen 5+ times.
A word with no count gets 0 (the app treats it as a real but rare word).

Usage: python3 build_th.py <path to pythainlp/corpus> > app/src/main/assets/lexicon/th.tsv
"""
import re, sys
corpus = sys.argv[1]
thai = re.compile(r'^[\u0E01-\u0E3A\u0E40-\u0E4E]{2,}$')
lst = {w for w in (l.strip() for l in open(f'{corpus}/words_th.txt', encoding='utf8')) if thai.match(w)}
tnc = {}
for l in open(f'{corpus}/tnc_freq.txt', encoding='utf8'):
    p = l.rstrip('\n').split('\t')
    if len(p) == 2 and p[1].isdigit() and thai.match(p[0]):
        tnc[p[0]] = int(p[1])
keep = {w: c for w, c in tnc.items() if w in lst or c >= 5}
for w in lst:
    keep.setdefault(w, 0)
sys.stdout.write(''.join(f'{w}\t{c}\n' for w, c in sorted(keep.items())))
