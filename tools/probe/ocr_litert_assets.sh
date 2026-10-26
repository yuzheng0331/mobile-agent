#!/bin/sh
# Makes app/src/main/assets/ocr/litert/ (not in git) from the fetched ONNX models: the shapes
# LiteRtModels.CONFIG expects. Without them the app runs OCR on the CPU (onnxruntime).
#
#   ./gradlew :app:fetchOcrModels
#   tools/probe/ocr_litert_assets.sh <python 3.12 with onnx2tf[tensorflow]==2.6.9>
set -e
PY="$1"
ONNX2TF="$(dirname "$PY")/onnx2tf"
OCR=app/src/main/assets/ocr
TMP=$(mktemp -d)
mkdir -p "$OCR/litert"
build() {
    "$PY" -I tools/probe/ocr_tflite.py "$OCR/$1.onnx" "$TMP/$2.onnx" "$3"
    "$ONNX2TF" -i "$TMP/$2.onnx" -o "$TMP/$2" -n > "$TMP/$2.log" 2>&1 || { cat "$TMP/$2.log"; exit 1; }
    cp "$TMP/$2/$2_float32.tflite" "$OCR/litert/$2.tflite"
}
build det det 1,3,2240,1024
for w in 320 640 960 1280; do build rec "rec$w" "6,3,48,$w"; done
rm -rf "$TMP"
ls -l "$OCR/litert"
