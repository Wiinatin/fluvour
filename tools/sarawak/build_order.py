#!/usr/bin/env python3
"""Step 2b. Fill tier and day_order in assets/sarawak/pack.tsv.
Run from project root, after build_pack.py and build_sentences.py:  python3 tools/sarawak/build_order.py
Also writes tools/sarawak/ranks.tsv (why each row got its rank/tier) for Win to review.
Tier rules (1 = learn first):
  1  appears Sarawak-marked in the phrasebook's situation sentences, OR its English meaning is a
     pronoun / question word / demonstrative / basic particle (see CORE_EN)
  2  standard-Malay rank <= 1000     3  <= 4000     4  <= 12000     5  > 12000
  empty  no rank and not in tier 1 (step 3: Win reviews hand tiers)
day_order = position 1..N over rows that have a tier (10 per day by default). Tier 1 sorts by how many
situation sentences use the word, then rank; other tiers by rank.
Rows with no standard Malay stay without one; if the Sarawak spelling is also a ms.tsv word its rank is only a hint column in ranks.tsv (false friends such as Tok).
Rows identical to their standard Malay word (no Sarawak facet) get no tier.
Step 3: rows with no tier take their hand tier from tools/sarawak/tier_proposals.tsv (x/drop = excluded)."""
import re, csv

MS = {}
for i, l in enumerate(open('app/src/main/assets/levels/ms.tsv', encoding='utf-8')):
    p = l.rstrip('\n').split('\t')
    if len(p) >= 2 and p[0] not in MS: MS[p[0].lower()] = int(p[1])

CORE_EN = {'i', 'me', 'you', 'he', 'she', 'we', 'they', 'it', 'who', 'what', 'where', 'when', 'why', 'how', 'which',
           'this', 'that', 'here', 'there', 'yes', 'no', 'not', 'and', 'with', 'my', 'your', 'our', 'their', 'his', 'her'}
ALIAS = {'siney': 'sine'}
def toks(s): return re.findall(r'[a-z0-9]+', s.lower())
def has(seq, sub):
    n = len(sub)
    return n > 0 and any(seq[i:i+n] == sub for i in range(len(seq) - n + 1))

S = [(toks(sw), toks(ms)) for _, sw, ms, _ in list(csv.reader(open('assets/sarawak/sentences.tsv', encoding='utf-8'), delimiter='\t'))[1:]]
# Step 3: hand tiers (win_tier if filled, else proposed_tier) for rows the rules left without a tier. x / drop = no card.
HAND = {}
try:
    for t in list(csv.reader(open('tools/sarawak/tier_proposals.tsv', encoding='utf-8'), delimiter='\t'))[1:]:
        HAND[(t[2], t[3])] = (t[6] or t[5]).strip().lower()
except FileNotFoundError: pass
rd = list(csv.reader(open('assets/sarawak/pack.tsv', encoding='utf-8'), delimiter='\t'))
head, rows = rd[0], rd[1:]
# Step 4: English meanings for rows the phrasebook left without one (tools/sarawak/en_fill.tsv; source gets '+en-fill').
try:
    FILL = {(t[0], t[1]): t[2] for t in list(csv.reader(open('tools/sarawak/en_fill.tsv', encoding='utf-8'), delimiter='\t'))[1:]}
    for r in rows:
        if not r[2].strip() and (r[1], r[0]) in FILL:
            r[2] = FILL[(r[1], r[0])]; r[4] = (r[4] + '+en-fill')
except FileNotFoundError: pass
# Step 4: rows confirmed against Borneo Dictionary get '+bd' in source (tools/sarawak/borneo_confirmed.tsv, written by check_borneo.py).
try:
    BD = {(t[0], t[1]) for t in list(csv.reader(open('tools/sarawak/borneo_confirmed.tsv', encoding='utf-8'), delimiter='\t'))[1:]}
    for r in rows:
        if (r[1], r[0]) in BD and '+bd' not in r[4]: r[4] += '+bd'
except FileNotFoundError: pass
info = []
for idx, r in enumerate(rows):
    std, sw, en = r[0], r[1], r[2]
    how, rank = '', None
    for p in re.split(r'[;,/]', re.sub(r'\([^)]*\)', '', std)):
        w = ' '.join(toks(p))
        if w in MS and (rank is None or MS[w] < rank): rank, how = MS[w], 'malay'
    hint = None
    if not std.strip():
        w = ' '.join(toks(sw))
        if w in MS and ' ' not in w: hint = MS[w]   # same spelling as a Malay word: hint only (Tok, Tulong are false friends / noise)
    key = [ALIAS.get(k, k) for k in toks(re.sub(r'\([^)]*\)', '', sw))]
    att = sum(1 for st, mt in S if has(st, key) and not has(mt, key))
    first_en = {x.strip().lower() for x in re.split(r'[;,/]', en)}
    core = bool(first_en & CORE_EN)
    why = 'sentences' if att else ('core-english' if core else '')
    ident = any(' '.join(toks(p)) == ' '.join(key) for p in re.split(r'[;,/]', re.sub(r'\([^)]*\)', '', std)))
    if ident: tier, why = '', 'identical'   # same as the standard word: no Sarawak facet to teach
    elif why: tier = 1
    elif rank is None: tier = ''
    else: tier = 2 if rank <= 1000 else 3 if rank <= 4000 else 4 if rank <= 12000 else 5
    if tier == '' and why != 'identical' and (sw, std) in HAND:
        h = HAND[(sw, std)]
        if h in ('2', '3', '4', '5'): tier, why = int(h), 'hand-tier'
        else: why = 'excluded-' + h
    info.append([idx, rank, how, att, why, tier, hint])

order = sorted((i for i in info if i[5] != ''),
               key=lambda i: (i[5], -i[3] if i[5] == 1 else 0, i[1] if i[1] is not None else 10**6, i[0]))
for n, i in enumerate(order, 1): i.append(n)   # i[7] = day order
for i in info:
    r = rows[i[0]]
    r[5] = str(i[5])
    r[6] = str(i[7]) if len(i) > 7 else ''
with open('assets/sarawak/pack.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(head); w.writerows(rows)
with open('tools/sarawak/ranks.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(['sarawak', 'standard_malay', 'rank', 'same_spelling_rank_hint', 'sentence_uses', 'tier_reason', 'tier', 'day_order'])
    for i in info:
        r = rows[i[0]]
        w.writerow([r[1], r[0], i[1] if i[1] is not None else '', i[6] if i[6] else '', i[3] or '', i[4], i[5], i[7] if len(i) > 7 else ''])

import collections
print('rows', len(rows), '| ranked', sum(1 for i in info if i[1] is not None),
      '| no standard Malay', sum(1 for r in rows if not r[0].strip()), '(of which same-spelling hint', sum(1 for i in info if i[6]), ')',
      '| identical to standard (excluded)', sum(1 for i in info if i[4] == 'identical'))
print('tiers', dict(sorted(collections.Counter(str(i[5]) or 'none' for i in info).items())),
      '| ordered', len(order), '| tier-1 reasons', dict(collections.Counter(i[4] for i in info if i[4])))
print('with example', sum(1 for r in rows if r[3]))
