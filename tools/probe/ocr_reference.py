"""Write gui-agent's Python OCR results for screenshots, as the reference for core's OcrTest.

    python ocr_reference.py <gui-agent/src> <model dir> <out.json> <screenshot> ...

The JSON holds real screen text: write it outside the repository and never commit it.
Then run `OCR_REFERENCE=<out.json> ./gradlew :core:test --tests '*OcrTest*'`.
"""

import json
import sys

sys.path.insert(0, sys.argv[1])
import cv2  # noqa: E402
import numpy as np  # noqa: E402
from gui_agent.perception.ocr_onnx_backend import OnnxOcrEngine  # noqa: E402

engine = OnnxOcrEngine(sys.argv[2])
shots = []
for path in sys.argv[4:]:
    result = engine.predict(cv2.imread(path))[0]
    hits = []
    for poly, text in zip(result["rec_polys"], result["rec_texts"]):
        p = np.asarray(poly).reshape(-1, 2)
        box = [int(p[:, 0].min()), int(p[:, 1].min()), int(p[:, 0].max()), int(p[:, 1].max())]
        hits.append({"box": box, "text": text})
    shots.append({"file": path, "hits": hits})
with open(sys.argv[3], "w", encoding="utf-8") as out:
    json.dump(shots, out, ensure_ascii=False)
print(f"{len(shots)} screenshots, {sum(len(s['hits']) for s in shots)} boxes")
