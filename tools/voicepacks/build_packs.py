#!/usr/bin/env python3
"""Builds the voice-pack release files (used by .github/workflows/voice-packs.yml, can also be run by hand).

For each pack below it downloads the official sherpa-onnx model (a .tar.bz2 from the k2-fsa/sherpa-onnx `tts-models`
release), repackages the files as a zip with the model files at the zip root (the app unpacks a zip into
filesDir/voicepacks/<id>/ and recognises the folder from its file names, see audio/ModelLayout.kt), and writes
`packs.json` (read by audio/VoicePacks.kt) and `RELEASE_NOTES.md` (sources and licences) next to the zips.

Standard library only. Usage: python3 build_packs.py OUT_DIR [--cache DIR]
  --cache DIR  keep the downloaded .tar.bz2 files in DIR and reuse them when present.
"""
import hashlib, json, os, sys, tarfile, urllib.request, zipfile

BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
SKIP_SUFFIX = (".py", ".sh", ".tar.bz2")  # model conversion scripts are not needed on the phone

# languages: language code -> speaker number. Kokoro speaker numbers come from the model's own metadata
# (0-27 English, 28-29 and 53 Spanish, 30 French, 37-41 Japanese, 45-52 Chinese); the pick inside each range is a first guess.
PACKS = [
    dict(id="kokoro-multi", name="Kokoro", engine="kokoro", version="1", src="kokoro-int8-multi-lang-v1_0",
         languages={"en": 3, "es": 28, "fr": 30, "ja": 37, "zh": 45},
         licence="Kokoro-82M, Apache-2.0 (engine and weights). Packaged by sherpa-onnx (Apache-2.0)."),
    dict(id="piper-de", name="Piper German (Thorsten)", engine="piper", version="1", src="vits-piper-de_DE-thorsten-medium",
         languages={"de": 0}, licence="Piper voice trained on the Thorsten dataset, CC0."),
    dict(id="piper-vi", name="Piper Vietnamese (vais1000)", engine="piper", version="1", src="vits-piper-vi_VN-vais1000-medium",
         languages={"vi": 0}, licence="Piper voice trained on the VAIS-1000 dataset, CC-BY-4.0. Credit: VAIS."),
    dict(id="piper-id", name="Piper Indonesian (news_tts)", engine="piper", version="1", src="vits-piper-id_ID-news_tts-medium",
         languages={"id": 0}, licence="Piper voice. The model card inside is a copy of another voice (Malayalam) and gives no dataset licence, so the licence of this voice is unconfirmed."),
    dict(id="mms-th", name="MMS Thai", engine="mms", version="1", src="vits-mms-tha",
         languages={"th": 0}, licence="Meta MMS-TTS, CC-BY-NC-4.0. Credit: Meta. Non-commercial use only."),
    dict(id="mimic3-ko", name="Mimic 3 Korean (kss)", engine="mimic3", version="1", src="vits-mimic3-ko_KO-kss_low",
         languages={"ko": 0}, licence="Mimic 3 voice trained on the KSS dataset (CC-BY-NC-SA-4.0 as published by its author). Non-commercial use only."),
]

def fetch(src, cache):
    name = src + ".tar.bz2"
    path = os.path.join(cache, name)
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        print("download", name, flush=True)
        req = urllib.request.Request(BASE + name, headers={"User-Agent": "fluvour-voicepacks"})
        with urllib.request.urlopen(req, timeout=300) as r, open(path + ".part", "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b: break
                f.write(b)
        os.replace(path + ".part", path)
    return path

def build(pack, cache, out):
    tar_path = fetch(pack["src"], cache)
    zip_path = os.path.join(out, pack["id"] + ".zip")
    n = 0
    with tarfile.open(tar_path, "r:bz2") as t, zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for m in t.getmembers():
            if not m.isfile(): continue
            parts = m.name.split("/")
            if parts[0] in (".", ""): parts = parts[1:]
            if len(parts) < 2 or any(p in ("", "..") for p in parts): continue   # expect one top folder
            rel = "/".join(parts[1:])
            if rel.endswith(SKIP_SUFFIX): continue
            z.writestr(zipfile.ZipInfo(rel, (2026, 1, 1, 0, 0, 0)), t.extractfile(m).read(), zipfile.ZIP_DEFLATED)
            n += 1
    names = set(zipfile.ZipFile(zip_path).namelist())
    if "tokens.txt" not in names or not any(x.endswith(".onnx") for x in names if "/" not in x):
        sys.exit(f"{pack['id']}: zip has no tokens.txt or model at its root")
    if pack["engine"] == "kokoro" and "voices.bin" not in names:
        sys.exit(f"{pack['id']}: Kokoro zip has no voices.bin")
    h = hashlib.sha256()
    with open(zip_path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    print(f"{pack['id']}: {n} files, {os.path.getsize(zip_path)} bytes", flush=True)
    return dict(id=pack["id"], name=pack["name"], engine=pack["engine"], version=pack["version"], url=pack["id"] + ".zip",
                size=os.path.getsize(zip_path), sha256=h.hexdigest(), languages=pack["languages"])

def main():
    args = sys.argv[1:]
    cache = None
    if "--cache" in args:
        i = args.index("--cache"); cache = args[i + 1]; del args[i:i + 2]
    if len(args) != 1: sys.exit(__doc__)
    out = args[0]
    os.makedirs(out, exist_ok=True)
    cache = cache or os.path.join(out, "_cache")
    os.makedirs(cache, exist_ok=True)
    entries = [build(p, cache, out) for p in PACKS]
    with open(os.path.join(out, "packs.json"), "w", encoding="utf-8") as f:
        json.dump(dict(version=1, packs=entries), f, indent=2, ensure_ascii=False)
    lines = ["# Fluvour voice packs", "",
             "Offline voices for the Fluvour app (personal use). Files are repackaged, unchanged, from the official sherpa-onnx `tts-models` release:",
             "https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models", "",
             "| Pack | Languages | Source model | Licence |", "|---|---|---|---|"]
    for p, e in zip(PACKS, entries):
        lines.append(f"| {p['name']} | {', '.join(p['languages'])} | `{p['src']}` | {p['licence']} |")
    lines += ["", "Some of these licences forbid commercial use. Do not distribute the app with these packs.", ""]
    with open(os.path.join(out, "RELEASE_NOTES.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

main()
