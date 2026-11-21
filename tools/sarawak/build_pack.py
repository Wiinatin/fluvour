#!/usr/bin/env python3
"""Build assets/sarawak/pack.tsv from tools/sarawak/phrasebook.txt (pdftotext, no -layout).
Run from project root: python3 tools/sarawak/build_pack.py
Columns: standard_malay, sarawak, english, example, source, tier, day_order
(example, tier, day_order left empty on purpose; later steps fill them)."""
import re, csv, sys, collections
RAW = open('tools/sarawak/phrasebook.txt', encoding='utf-8').read().replace('\f', '\n')
L = [l.rstrip() for l in RAW.split('\n')]

def find(pred, start=0):
    for i in range(start, len(L)):
        if pred(L[i].strip()): return i
    raise SystemExit('marker not found')
u0 = find(lambda s: s.startswith('Sarawak Dialect - Bahasa Malaysia')) + 1
m0 = find(lambda s: s == 'Bahasa Malaysia - Bahasa Sarawak', u0)
e0 = find(lambda s: s.startswith('English - Bahasa Sarawak'), m0)
s0 = find(lambda s: s.startswith('Salam Perkenalan / Greetings'), e0)
SEC = {'unique': L[u0:m0], 'my': L[m0+1:e0], 'en': L[e0+1:s0]}

FIX = [  # glued / odd lines in the PDF text
 ('Bergala - gala', 'Bergala-gala'), ('Terkenjit - kenjit', 'Terkenjit-kenjit'),
 ('Kebor - Tepuk - Tepuk', 'Kebor - Tepuk'), ('Ngarut Ngeriput', 'Ngarut Ngeriput'),
 ('Jatuh (airmata), Teary eyes', 'Jatuh (airmata) / Teary eyes'),
 ('Mengintai, On the lookout', 'Mengintai / On the lookout'),
 ('Comel - Lebeh / Cute', 'Comel - Lebeh - Cute'), ('DolokE', 'Dolok'),
 ('Cup - Cangkir Cook - Masak', 'Cup - Cangkir\nCook - Masak'),
 ('Suk / Suka Kettle - Ketel', 'Suk / Suka\nKeen_FIX'), ('Konon / Reputedly Kupak', 'Konon / Reputedly\nKupak'),
]
def pre(lines):
    out = []
    for l in lines:
        l = l.strip()
        for a, b in FIX: l = l.replace(a, b)
        l = re.sub(r'^([A-Z])\1(?=[a-z])', r'\1', l)            # "SSleepy" -> "Sleepy"
        l = re.sub(r'(?<=[a-z\)])(?=[A-Z][a-z]* - )', '\n', l)   # "BalitBad - Jaik"
        l = re.sub(r'(?<=[a-z])(?=[A-Z]$)', '\n', l)             # "SunyekR"
        l = l.replace('Keen_FIX', 'Kettle - Ketel')
        out += l.split('\n')
    return out

def top_split(s, pat):
    """split on regex pat at paren depth 0"""
    parts, depth, cur, i = [], 0, '', 0
    rx = re.compile(pat)
    while i < len(s):
        c = s[i]
        if c == '(': depth += 1
        if c == ')': depth = max(0, depth-1)
        m = rx.match(s, i) if depth == 0 else None
        if m and m.end() > i:
            parts.append(cur); cur = ''; i = m.end(); continue
        cur += c; i += 1
    parts.append(cur)
    return [p.strip() for p in parts if p.strip()]

def clean(s): return re.sub(r'\s+', ' ', s.replace('?', '')).strip(' -;,')
VARIANT = {'sine': 'siney'}   # spelling variants merged into one row
def variants(s):
    out = []
    for p in top_split(s, r'\s*/\s*|\s*,\s*'):
        p = clean(p)
        if p and p.lower() not in [o.lower() for o in out]: out.append(VARIANT.get(p.lower(), p) if p.lower() in VARIANT else p)
    return out

DASH = r'\s+-\s*|(?<=\S)-\s+'
LETTER = re.compile(r'^[A-Z]\s*$')
rows, dropped = [], []   # rows: (malay_list, sarawak_variants, english_list, source)

# ---- unique words (Sarawak - Malay / English), join wrapped lines
ent = []
for l in pre(SEC['unique']):
    if not l or LETTER.match(l) or l.startswith('Sarawak Dialect'): continue
    if ' - ' in l or re.search(r'\S-\s', l) or re.search(r'\s-\S', l): ent.append(l)
    elif ent: ent[-1] += l   # wrapped mid-word continuation
U = []
for l in ent:
    p = re.split(DASH, l, maxsplit=1)
    if len(p) < 2: dropped.append((l, 'unique: no dash')); continue
    head, rest = p
    segs = top_split(rest, r'\s*/\s*')
    if len(segs) >= 2: eng, mal = segs[-1], ' / '.join(segs[:-1])
    else: mal, eng = rest, ''
    if head.strip().lower() == 'saya':
        dropped.append((l, 'unique: reversed pronoun (Saya -> Kamek already covered)')); continue
    U.append((variants(mal), variants(head), variants(eng), 'unique'))
rows += U
uhead = collections.defaultdict(set)
for mal, sar, eng, _ in U:
    for s in sar: uhead[s.lower()] |= {m.lower() for m in mal}

# ---- Malay -> Sarawak
M = []
for l in pre(SEC['my']):
    if not l or LETTER.match(l): continue
    l = re.sub(r'\s*-\s*$', '', l)
    p = [x for x in re.split(DASH, l) if x.strip()]
    if len(p) == 1 and ' / ' in p[0]:   # "Menguap / Nguap"
        a, b = [x.strip() for x in p[0].split(' / ', 1)]; p = [a, b]
    if len(p) == 1: dropped.append((l, 'my: only one side')); continue
    mal, sar = p[0], p[1]; eng = p[2] if len(p) > 2 else ''
    mv, sv, ev = variants(mal), variants(sar), variants(eng)
    if mv and sv and mv[0].lower() == sv[0].lower() and len(sv) == 1:
        dropped.append((l, 'my: identical Malay/Sarawak')); continue
    if mv and sv and mv[0].lower() in uhead and sv[0].lower() in uhead[mv[0].lower()]:
        dropped.append((l, 'my: reversed (Sarawak word listed as Malay)')); continue
    M.append((mv, sv, ev, 'my'))
rows += M

# ---- English -> Sarawak
E = []
for l in pre(SEC['en']):
    if not l or LETTER.match(l): continue
    p = [x for x in re.split(DASH, l) if x.strip()]
    if len(p) < 2: dropped.append((l, 'en: no dash')); continue
    eng, sar = clean(p[0]), p[1]
    sv = variants(sar)
    if eng.lower() in [s.lower() for s in sv] and len(sv) == 1:
        dropped.append((l, 'en: identical English/Sarawak')); continue
    E.append(([], sv, [eng], 'en'))
rows += E

# ---- merge by Sarawak word (lowercase); one row per Sarawak word
pack = collections.OrderedDict()
def add(lst, items):
    for x in items:
        if x.lower() not in [y.lower() for y in lst]: lst.append(x)
for mal, sar, eng, src in rows:
    for s in sar:
        d = pack.setdefault(s.lower(), {'sar': s, 'mal': [], 'eng': [], 'src': []})
        add(d['mal'], mal); add(d['eng'], eng)
        if src not in d['src']: d['src'].append(src)
# sort by Sarawak headword for stable diffs
with open('assets/sarawak/pack.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(['standard_malay', 'sarawak', 'english', 'example', 'source', 'tier', 'day_order'])
    for k in sorted(pack):
        d = pack[k]
        w.writerow(['; '.join(d['mal']), d['sar'], '; '.join(d['eng']), '', '+'.join(d['src']), '', ''])
with open('tools/sarawak/dropped.tsv', 'w', encoding='utf-8') as f:
    for l, r in dropped: f.write(f'{r}\t{l}\n')
c = collections.Counter(tuple(d['src']) for d in pack.values())
print('parsed rows: unique', len(U), '| malay->sarawak', len(M), '| english->sarawak', len(E))
print('merged pack rows:', len(pack), '| dropped:', len(dropped))
print('by source:', dict(('+'.join(k), v) for k, v in c.items()))
print('no standard malay:', sum(1 for d in pack.values() if not d['mal']), '| no english:', sum(1 for d in pack.values() if not d['eng']))
