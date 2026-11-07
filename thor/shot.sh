#!/bin/bash
# thor/shot.sh <name> : screenshots of both Thor screens -> thor/shots/<name>-top.png / -bottom.png
# (+ small previews in $PREVIEW_DIR if set). Bottom screen = physical display of display 4.
D="$(cd "$(dirname "$0")" && pwd)/shots"; mkdir -p "$D"; [ -f "$HOME/.config/thorheroes/env" ] && . "$HOME/.config/thorheroes/env"; A="adb"  # ANDROID_SERIAL picks the device
BOTTOM=$($A shell dumpsys display | grep -o "displayId=4, uniqueId='local:[0-9]*" | head -1 | grep -o '[0-9]*$')
$A exec-out screencap -p > "$D/$1-top.png"
$A exec-out screencap -p -d "$BOTTOM" > "$D/$1-bottom.png"
if [ -n "$PREVIEW_DIR" ]; then
  sips -Z 1000 "$D/$1-top.png" --out "$PREVIEW_DIR/$1-top.png" >/dev/null
  sips -Z 700 "$D/$1-bottom.png" --out "$PREVIEW_DIR/$1-bottom.png" >/dev/null
fi
echo "$D/$1-top.png $D/$1-bottom.png"
