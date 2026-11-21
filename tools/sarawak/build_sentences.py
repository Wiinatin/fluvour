#!/usr/bin/env python3
"""Step 2a. Parse the situation sentences of the phrasebook into assets/sarawak/sentences.tsv
and attach a Sarawak example line to pack rows in assets/sarawak/pack.tsv (column 'example', its meaning in column 'example_en').
Run from project root, after build_pack.py:  python3 tools/sarawak/build_sentences.py
sentences.tsv columns: situation, sarawak, malay, english  (empty when the book gives none)
Rule for examples: the row's Sarawak word/phrase must appear as whole token(s) in the Sarawak sentence,
and either (a) it is Sarawak-marked (not also in the Malay sentence) or (b) the Malay sentence contains
the row's standard Malay word. Otherwise the row stays without an example (no guessing)."""
import re, csv, difflib, collections

RAW = open('tools/sarawak/phrasebook.txt', encoding='utf-8').read().replace('\f', '\n')
L = [l.strip() for l in RAW.split('\n')]
start = L.index('Salam Perkenalan / Greetings')
L = L[start:]

# known glitches in the PDF text of the sentence section
JOIN = [('until you see\na river.', 'until you see a river.')]
txt = '\n'.join(L)
for a, b in JOIN: txt = txt.replace(a, b)
L = txt.split('\n')
DROP = {'Saya nak order 2 laksa biasa, 1 special.'}   # a Malay line stuck inside the English block (3-line entry)

SIT = {  # Malay-block header -> situation
 'Salam Perkenalan / Greetings': 'Greetings', 'Lapangan Terbang / The Airport': 'At the airport',
 'Bertanya Arah / Asking for directions': 'Asking for directions', 'Masa / Time': 'Time',
 'Di Kedai / Restoren / at the Shop / Restaurant': 'At the shop / restaurant', 'Di Pejabat / At the Office': 'At the office'}
ENG = {'Greetings': 'Greetings', 'At the Airport': 'At the airport', 'Asking for directions': 'Asking for directions',
       'Time': 'Time', 'At the Shop': 'At the shop / restaurant', 'At the Office': 'At the office'}
SUBTITLE = {'Salam dan Perkenalan', 'Lapangan Terbang', 'Bertanya Arah', 'Masa'}

blocks = collections.OrderedDict()   # (situation, 'ms'|'en') -> lines
cur = None
for l in L:
    if not l: continue
    if l in SIT: cur = (SIT[l], 'ms'); blocks[cur] = []; continue
    if l in ENG and (ENG[l], 'ms') in blocks: cur = (ENG[l], 'en'); blocks[cur] = []; continue
    if cur is None or l in SUBTITLE: continue
    if l in DROP and cur[1] == 'en': continue
    blocks[cur].append(l)

def fixq(s): return re.sub(r'\s+([?!.,])', r'\1', s).strip()
def pairs(lines):
    lines = [fixq(x) for x in lines]
    if len(lines) % 2: lines = lines[:-1]; print('WARN odd block')
    return [(lines[i], lines[i+1]) for i in range(0, len(lines), 2)]
def norm(s): return re.sub(r'[^a-z0-9 ]', '', s.lower().replace('pukol', 'kol')).strip()
def sim(a, b):
    a, b = norm(a), norm(b)
    srt = lambda x: " ".join(sorted(x.split()))   # same words, different order
    return max(difflib.SequenceMatcher(None, a, b).ratio(), difflib.SequenceMatcher(None, srt(a), srt(b)).ratio())

out, warn = [], []
for sit in dict.fromkeys(k[0] for k in blocks):
    M = pairs(blocks[(sit, 'ms')])           # (malay, sarawak)
    E = pairs(blocks[(sit, 'en')])           # (english, sarawak) ; a few are really (malay, sarawak)
    used = set(); rows = []
    for ms, sw in M: rows.append([sw, ms, ''])
    for en, sw in E:
        # an entry whose first line is really Malay (book typo): already covered by the Malay block
        if any(sim(en, ms) > 0.9 for ms, _ in M): continue
        best, bi = 0, None
        for i, r in enumerate(rows):
            if i in used or r[2]: continue
            s = sim(sw, r[0])
            if s > best: best, bi = s, i
        if bi is not None and best >= 0.7: rows[bi][2] = en; used.add(bi)
        else: rows.append([sw, '', en]); warn.append((sit, 'English-only', en, sw, round(best, 2)))
    for sw, ms, en in rows:
        if not en: warn.append((sit, 'no English', ms, sw, 0))
        out.append((sit, sw, ms, en))

with open('assets/sarawak/sentences.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(['situation', 'sarawak', 'malay', 'english'])
    w.writerows(out)
print('sentences', len(out), dict(collections.Counter(r[0] for r in out)))
for x in warn: print('WARN', x)

# ---------- examples
def toks(s): return re.findall(r"[a-z0-9]+", s.lower())
def has(seq, sub):
    n = len(sub)
    return n > 0 and any(seq[i:i+n] == sub for i in range(len(seq) - n + 1))
ALIAS = {"siney": "sine"}
def variants(std):
    v = []
    for p in re.split(r'[;,/]', re.sub(r'\([^)]*\)', '', std)):
        t = toks(p)
        if t: v.append(t)
    return v

with open('assets/sarawak/pack.tsv', encoding='utf-8') as f:
    rd = list(csv.reader(f, delimiter='\t'))
head, rows = rd[0], rd[1:]
# 8th column: what the example means (the book's English; the Malay sentence when the book gives no English)
if 'example_en' not in head: head = head[:7] + ['example_en']
for r in rows: r[:] = (r + [''] * 8)[:8]
# English the book does not give (my translations, tools/sarawak/sentence_en_fill.tsv); used before the Malay fallback
try: FILL = {t[0]: t[1] for t in list(csv.reader(open('tools/sarawak/sentence_en_fill.tsv', encoding='utf-8'), delimiter='\t'))[1:]}
except FileNotFoundError: FILL = {}
S = [(sw, ms, en, toks(sw), toks(ms)) for _, sw, ms, en in out]
got = 0
for r in rows:
    r[3] = ''; r[7] = ''
    key = toks(re.sub(r'\([^)]*\)', '', r[1]))
    key = [ALIAS.get(k, k) for k in key]   # spelling variants merged in step 1 (Siney/Sine)
    if not key or (len(key) == 1 and len(key[0]) < 3): continue
    std = variants(r[0])
    cands = []
    for sw, ms, en, st, mt in S:
        if not has(st, key): continue
        marked = not has(mt, key)
        strong = any(has(mt, v) for v in std)
        if marked or strong: cands.append((0 if marked else 1, len(st), sw, en.strip() or FILL.get(sw) or ms))
    if cands:
        best = min(cands); r[3] = best[2]; r[7] = best[3]; got += 1
with open('assets/sarawak/pack.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(head); w.writerows(rows)
print('pack rows', len(rows), 'with example', got)
