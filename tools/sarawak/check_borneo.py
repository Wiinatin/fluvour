"""Step 4: compare assets/sarawak/pack.tsv with tools/sarawak/borneo_melayu_sarawak.tsv (Borneo Dictionary, Melayu Sarawak).
Run from project root. Writes tools/sarawak/borneo_report.tsv (status per pack row). Does not change the pack; also writes borneo_confirmed.tsv (rows whose meaning agrees with the site), which build_order.py reads to add '+bd' to source.
Pipeline: build_pack, build_sentences, build_order, check_borneo, build_order."""
import csv, re, difflib
def rd(p): return list(csv.reader(open(p, encoding='utf-8'), delimiter='\t'))
B = {}
for w, en, ms in rd('tools/sarawak/borneo_melayu_sarawak.tsv')[1:]:
    B.setdefault(re.sub(r'[^a-z ]', '', w.lower()).strip(), []).append((en, ms))
keys = list(B)
def toks(s): return set(re.findall(r'[a-z]{3,}', s.lower()))
out = []
for r in rd('assets/sarawak/pack.tsv')[1:]:
    std, sw, en = r[0], r[1], r[2]
    k = re.sub(r'[^a-z ]', '', sw.lower()).strip()
    if k in B:
        site = ' | '.join(e for e, m in B[k]); sm = ' | '.join(m for e, m in B[k])
        mine = toks(en) | toks(std)
        theirs = toks(site) | toks(sm)
        st = 'agree' if (mine & theirs) else 'CONFLICT'
        out.append((st, sw, std, en, site, sm, ''))
    else:
        near = difflib.get_close_matches(k, keys, n=1, cutoff=0.8)
        if near and len(k) > 3:
            nb = B[near[0]][0]
            out.append(('near', sw, std, en, nb[0], nb[1], near[0]))
        else:
            out.append(('absent', sw, std, en, '', '', ''))
from collections import Counter
print(Counter(o[0] for o in out))
with open('tools/sarawak/borneo_report.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(['status', 'sarawak', 'standard_malay', 'pack_english', 'site_english', 'site_malay', 'site_spelling'])
    w.writerows(sorted(out, key=lambda o: (o[0], o[1].lower())))
with open('tools/sarawak/borneo_confirmed.tsv', 'w', encoding='utf-8', newline='') as f:
    w = csv.writer(f, delimiter='\t', lineterminator='\n')
    w.writerow(['sarawak', 'standard_malay'])
    for o in sorted(out, key=lambda o: o[1].lower()):
        if o[0] == 'agree': w.writerow([o[1], o[2]])
