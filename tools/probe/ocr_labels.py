"""Ground-truth OCR labels from captures that have both screen.png and tree.xml.

    python ocr_labels.py <out.json> <capture dir> ...

Each visible `text` node becomes a label (text + bounds): the accessibility tree holds exactly
what the app drew. Skipped: our own overlay, ellipsized text (the screen shows less than the
tree), punctuation-only text, and text the screenshot doesn't show: boxes under 12 px, or flat
pixels (covered by a dimmed overlay or menu). That check looks at pixels only, never at OCR. Apps with an empty tree (WeChat) need hand labels in the same
format: {"file", "labels": [{"text", "box": [l, t, r, b]}], "absent": ["word that must not be read"]}.
Labels hold real screen text: write them outside the repository (tools/probe/out/ is ignored).
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

shots = []
for capture in sys.argv[2:]:
    screen, tree = os.path.join(capture, "screen.png"), os.path.join(capture, "tree.xml")
    if not (os.path.isfile(screen) and os.path.isfile(tree)):
        continue
    image = Image.open(screen).convert("L")
    labels = []
    for node in ET.parse(tree).getroot().iter("node"):
        text = (node.get("text") or "").strip()
        if not text or node.get("visible-to-user") == "false" or node.get("package") == "dev.phoneagent.app":
            continue
        if "…" in text or "..." in text or not any(c.isalnum() for c in text):
            continue
        box = [int(v) for v in re.findall(r"-?\d+", node.get("bounds", ""))]
        if len(box) != 4 or box[2] - box[0] < 12 or box[3] - box[1] < 12:
            continue
        if ImageStat.Stat(image.crop(tuple(box))).stddev[0] < 12:
            continue
        labels.append({"text": text, "box": box})
    if labels:
        shots.append({"file": os.path.abspath(screen), "labels": labels, "absent": []})
with open(sys.argv[1], "w", encoding="utf-8") as out:
    json.dump(shots, out, ensure_ascii=False)
print(f"{len(shots)} screenshots, {sum(len(s['labels']) for s in shots)} labels")
