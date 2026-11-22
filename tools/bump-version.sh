#!/usr/bin/env bash
# Naikkan versi PinaPos di satu tempat: version.json + sw.js + APP_BUILD di index.html.
# Pakai:  tools/bump-version.sh 1.4.1 "Perbaikan struk dan laporan"   [folder, default: kasirku]
# Setelah itu commit & push; perangkat yang online akan memberi tahu / memperbarui otomatis.
set -euo pipefail
V="${1:?Versi baru, contoh: 1.4.1}"; NOTES="${2:-Perbaikan dan peningkatan.}"; DIR="${3:-kasirku}"
[[ "$V" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || { echo "Format versi harus angka titik, mis. 1.4.1"; exit 1; }
python3 - "$V" "$NOTES" "$DIR" <<'PY'
import json, re, sys
v, notes, d = sys.argv[1:4]
json.dump({"version": v, "notes": notes}, open(f"{d}/version.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(f"{d}/version.json", "a").write("\n")
for f, pat, rep in [(f"{d}/sw.js", r"const CACHE = '[^']*';", f"const CACHE = 'pinapos-demo-v{v}';"),
                    (f"{d}/index.html", r"const APP_BUILD = '[^']*';", f"const APP_BUILD = '{v}';")]:
    s = open(f, encoding="utf-8").read(); n, c = re.subn(pat, rep, s, count=1)
    assert c == 1, f"pola tidak ditemukan di {f}"
    open(f, "w", encoding="utf-8").write(n)
print(f"Versi -> {v}")
PY
