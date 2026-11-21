"""Dump the notes of a Word Power .apkg to notes.tsv for Test.kt: deck name, then the raw fields joined with the Anki separator.
Usage: python3 dump_apkg.py Word_Power_Made_easy_-_Complete.apkg notes.tsv
Tabs, newlines and backslashes are escaped as \\t \\n \\\\ so every note is one line."""
import json, sqlite3, sys, tempfile, zipfile, os

def esc(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

apkg, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apkg) as z, tempfile.TemporaryDirectory() as d:
    name = next(n for n in ("collection.anki21", "collection.anki2") if n in z.namelist())
    z.extract(name, d)
    db = sqlite3.connect(os.path.join(d, name))
    decks = {int(k): v["name"] for k, v in json.loads(db.execute("select decks from col").fetchone()[0]).items()}
    rows = db.execute("select n.flds, c.did from notes n join cards c on c.nid = n.id order by n.id").fetchall()
with open(out, "w", encoding="utf-8") as f:
    for flds, did in rows:
        f.write(esc(decks.get(did, "")) + "\t" + esc(flds) + "\n")
print(len(rows), "notes")
