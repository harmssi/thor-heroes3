#!/bin/bash
# Regenerate the roe-support mod (JSON only, derived from VCMI's own config) and pack it as
# android/res/raw/roe_support.zip. The LODs are only used to check which art names exist;
# nothing from them ends up in the zip (campaigns are converted on the device instead).
# usage: thor/make_roe_mod.sh <H3bitmap.lod> <H3sprite.lod> [<H3pbitma.lod>]
set -euo pipefail
ARGS=(); for a in "$@"; do ARGS+=("$(cd "$(dirname "$a")" && pwd)/$(basename "$a")"); done
cd "$(dirname "$0")/roe-bridge"
python3 gen_mod.py "${ARGS[@]}" >/dev/null
rm -rf mod/content/data
python3 - <<'PY'
import os, zipfile
with zipfile.ZipFile('../../android/res/raw/roe_support.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk('mod'):
        for f in sorted(files):
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, 'mod'))
PY
unzip -l ../../android/res/raw/roe_support.zip | tail -1
