"""Stage-1 feasibility probe: can the target apps be driven from their UI tree?

Reads the current screen of a USB-connected phone through uiautomator2 and
reports, for every clickable element, whether it can be found without
coordinates (own text/content-desc, a child's text, or a readable
resource-id). It also flags suspected obfuscated ids, near-empty trees,
WebViews and black (screenshot-protected) screens.

    python probe.py snap wechat_chat_list     # capture + analyse current screen
    python probe.py launch com.xingin.xhs     # start an app by package name
    python probe.py toast --seconds 8         # wait for a toast such as "已复制"
    python probe.py report                    # summarise every capture so far
    python probe.py analyze out/xxx/tree.xml  # re-analyse a saved tree offline

Captures land in ./out/ and contain real screen content: keep them local.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"

# Short resource-id names that are real words, not obfuscation.
COMMON_SHORT_IDS = {
    "back",
    "body",
    "btn",
    "chat",
    "close",
    "content",
    "desc",
    "edit",
    "head",
    "icon",
    "img",
    "info",
    "input",
    "item",
    "list",
    "logo",
    "main",
    "menu",
    "more",
    "name",
    "nick",
    "root",
    "send",
    "tab",
    "tag",
    "text",
    "time",
    "tips",
    "title",
    "top",
    "user",
    "view",
}
MIN_USEFUL_NODES = 15


def short(text: str, limit: int = 16) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def id_name(resource_id: str) -> str:
    return resource_id.split(":id/", 1)[-1] if resource_id else ""


def is_suspicious_id(resource_id: str) -> bool:
    name = id_name(resource_id)
    return bool(name) and len(name) <= 4 and name.lower() not in COMMON_SHORT_IDS


@dataclass
class Target:
    how: str  # id | text | desc | child-text | none
    label: str
    cls: str
    bounds: str
    resource_id: str


@dataclass
class ScreenReport:
    package: str = ""
    activity: str = ""
    nodes: int = 0
    nodes_with_text: int = 0
    ids_total: int = 0
    ids_suspicious: int = 0
    has_webview: bool = False
    clickable: int = 0
    locatable: int = 0
    by_how: dict[str, int] = field(default_factory=dict)
    screen_black: bool | None = None
    dump_ms: int | None = None
    targets: list[Target] = field(default_factory=list)

    @property
    def locatable_ratio(self) -> float:
        return self.locatable / self.clickable if self.clickable else 0.0

    def verdict(self) -> str:
        if self.nodes < MIN_USEFUL_NODES or self.nodes_with_text == 0:
            if self.screen_black:
                return "读不到：树几乎为空且截图被禁止"
            return "树几乎为空：只能靠 OCR/模板"
        if self.locatable_ratio >= 0.8:
            return "树可用"
        return "部分可用：其余目标需要 OCR/模板"


def _subtree_label(node: ET.Element) -> str:
    for child in node.iter():
        if child is node:
            continue
        text = child.get("text") or child.get("content-desc") or ""
        if text.strip():
            return text
    return ""


def analyze(xml_text: str) -> ScreenReport:
    root = ET.fromstring(xml_text)
    report = ScreenReport()
    for node in root.iter("node"):
        report.nodes += 1
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        rid = node.get("resource-id") or ""
        cls = node.get("class") or ""
        if text or desc:
            report.nodes_with_text += 1
        if rid:
            report.ids_total += 1
            report.ids_suspicious += is_suspicious_id(rid)
        if "WebView" in cls:
            report.has_webview = True
        if node.get("clickable") != "true" and node.get("long-clickable") != "true":
            continue
        report.clickable += 1
        if text:
            how, label = "text", text
        elif desc:
            how, label = "desc", desc
        elif child := _subtree_label(node):
            how, label = "child-text", child
        elif rid and not is_suspicious_id(rid):
            how, label = "id", id_name(rid)
        else:
            how, label = "none", ""
        report.by_how[how] = report.by_how.get(how, 0) + 1
        if how != "none":
            report.locatable += 1
        report.targets.append(
            Target(
                how,
                short(label),
                cls.rsplit(".", 1)[-1],
                node.get("bounds") or "",
                rid,
            )
        )
    return report


def is_black(image: object) -> bool:
    from PIL import ImageStat

    stat = ImageStat.Stat(image.convert("L"))  # type: ignore[attr-defined]
    return stat.mean[0] < 8 and stat.stddev[0] < 4


def print_report(label: str, report: ScreenReport) -> None:
    print(f"== {label}  [{report.package}/{report.activity}]")
    print(
        f"   节点 {report.nodes}，有文字 {report.nodes_with_text}，"
        f"resource-id {report.ids_total}（疑似混淆 {report.ids_suspicious}）"
        + ("，含 WebView" if report.has_webview else "")
    )
    print(
        f"   可点击 {report.clickable}，可定位 {report.locatable}"
        f"（{report.locatable_ratio:.0%}）  {report.by_how}"
    )
    if report.screen_black is not None:
        print(f"   截图：{'黑屏（可能禁止截图）' if report.screen_black else '正常'}")
    if report.dump_ms is not None:
        print(f"   读树耗时 {report.dump_ms} ms（电脑经 ADB，手机端会快得多）")
    print(f"   结论：{report.verdict()}")
    for target in report.targets:
        mark = "  " if target.how != "none" else "!!"
        print(
            f"   {mark} {target.how:<10} {target.label:<16} "
            f"{target.cls:<14} {target.bounds}"
        )


def connect():  # noqa: ANN201 - uiautomator2 is imported lazily
    import uiautomator2 as u2

    return u2.connect()


def cmd_snap(args: argparse.Namespace) -> int:
    device = connect()
    current = device.app_current()
    started = time.perf_counter()
    xml_text = device.dump_hierarchy()
    dump_ms = round((time.perf_counter() - started) * 1000)
    image = device.screenshot()

    report = analyze(xml_text)
    report.package = current.get("package", "")
    report.activity = current.get("activity", "")
    report.dump_ms = dump_ms
    report.screen_black = is_black(image)

    folder = OUT / f"{datetime.now():%Y%m%d-%H%M%S}_{args.label}"
    folder.mkdir(parents=True)
    (folder / "tree.xml").write_text(xml_text, encoding="utf-8")
    image.save(folder / "screen.png")
    data = asdict(report) | {"label": args.label, "verdict": report.verdict()}
    (folder / "report.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print_report(args.label, report)
    print(f"   已保存到 {folder}")
    return 0


def cmd_launch(args: argparse.Namespace) -> int:
    device = connect()
    device.app_start(args.package, wait=True)
    time.sleep(1.5)  # probe only: give the launcher animation time to finish
    current = device.app_current()
    ok = current.get("package") == args.package
    foreground = f"{current.get('package')}/{current.get('activity')}"
    print(f"{'成功' if ok else '失败'}：前台是 {foreground}")
    return 0 if ok else 1


def cmd_toast(args: argparse.Namespace) -> int:
    device = connect()
    device.clear_toast()
    print(f"请在 {args.seconds} 秒内在手机上点击操作（例如『复制』）……")
    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline:
        if message := device.last_toast:
            print(f"捕获到 toast：{message}")
            return 0
        time.sleep(0.2)
    print("没有捕获到 toast：复制成功可能需要别的方式确认")
    return 1


def cmd_report(_: argparse.Namespace) -> int:
    reports = sorted(OUT.glob("*/report.json"))
    if not reports:
        print("还没有任何采集，先运行 snap")
        return 1
    print(f"{'界面':<28}{'可定位':>8}{'疑似混淆ID':>12}{'黑屏':>6}  结论")
    for path in reports:
        data = json.loads(path.read_text(encoding="utf-8"))
        clickable = data["clickable"] or 1
        share = data["locatable"] / clickable
        ratio = f"{data['locatable']}/{data['clickable']} {share:.0%}"
        ids = f"{data['ids_suspicious']}/{data['ids_total']}"
        black = "是" if data["screen_black"] else "否"
        print(f"{data['label']:<28}{ratio:>12}{ids:>10}{black:>6}  {data['verdict']}")
    return 0


A11Y_PACKAGE = "dev.phoneagent.probe"
UIAUTOMATOR_PACKAGE = "com.github.uiautomator"


def _a11y_log(device) -> list[str]:  # noqa: ANN001 - adbutils device
    output = device.shell(["run-as", A11Y_PACKAGE, "cat", "files/probe.log"])
    return [] if "No such file" in output else output.splitlines()


def cmd_a11y(args: argparse.Namespace) -> int:
    import adbutils

    # Several devices (e.g. wireless debugging listed twice): pick via ANDROID_SERIAL.
    device = adbutils.adb.device(serial=os.environ.get("ANDROID_SERIAL"))
    # uiautomator2's agent can suppress other accessibility services while it runs.
    device.shell(["am", "force-stop", UIAUTOMATOR_PACKAGE])
    seen = len(_a11y_log(device))
    device.shell(
        ["am", "broadcast", "-a", f"{A11Y_PACKAGE}.CMD", "-p", A11Y_PACKAGE]
        + ["--es", "cmd", args.cmd, "--es", "arg", args.arg]
    )
    expected = {
        "dump": " dump ",
        "click": " act ",
        "long": " act ",
        "tap": " act ",
        "longtap": " act ",
        "toast": " toast ",
        "shot": " shot ",
        "ocrbench": " ocr ",
    }
    # Model loading and three detection runs take far longer than a tap.
    deadline = time.monotonic() + (120 if args.cmd == "ocrbench" else 8)
    result = ""
    while time.monotonic() < deadline and not result:
        time.sleep(0.3)
        new_lines = _a11y_log(device)[seen:]
        result = next((ln for ln in new_lines if expected[args.cmd] in ln), "")
    if not result:
        print("没有收到结果：确认 A11y Probe 的无障碍服务已开启")
        return 1
    print(result)
    if args.cmd == "shot" and "file=" in result:
        return _pull_and_ocr(device, result, args.arg or "screen")
    if args.cmd != "dump":
        return 0
    name = result.rsplit("file=", 1)[1].strip()
    xml_text = device.shell(["run-as", A11Y_PACKAGE, "cat", f"files/dumps/{name}"])
    label = f"{args.arg or 'screen'}_a11y"
    report = analyze(xml_text)
    folder = OUT / f"{datetime.now():%Y%m%d-%H%M%S}_{label}"
    folder.mkdir(parents=True)
    (folder / "tree.xml").write_text(xml_text, encoding="utf-8")
    data = asdict(report) | {"label": label, "verdict": report.verdict()}
    (folder / "report.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print_report(label, report)
    return 0


def _pull_and_ocr(device, result: str, label: str) -> int:  # noqa: ANN001
    import base64

    name = result.rsplit("file=", 1)[1].strip()
    encoded = device.shell(["run-as", A11Y_PACKAGE, "base64", f"files/shots/{name}"])
    folder = OUT / f"{datetime.now():%Y%m%d-%H%M%S}_{label}_shot"
    folder.mkdir(parents=True)
    png = folder / "screen.png"
    png.write_bytes(base64.b64decode("".join(encoded.split())))
    print(f"   截图已保存到 {png}")
    status = run_ocr(png)
    if found := last_bubble(png):
        print(f"   最后一个气泡中心（按版面找，不靠文字）：{found[0]},{found[1]}")
    return status


def _ocr_engine():  # noqa: ANN202 - gui-agent's OnnxOcrEngine, imported lazily
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))
    from gui_agent.perception.ocr_onnx_backend import OnnxOcrEngine

    model_dir = os.environ.get("GUI_AGENT_OCR_ONNX_MODEL_DIR") or str(
        Path(os.environ["LOCALAPPDATA"]) / "gui-agent" / "paddleocr-small-onnx"
    )
    return OnnxOcrEngine(model_dir)


def run_ocr(png: Path) -> int:
    import cv2

    started = time.perf_counter()
    engine = _ocr_engine()
    loaded = time.perf_counter()
    image = cv2.imread(str(png))
    result = engine.predict(image)[0]
    done = time.perf_counter()
    print(
        f"   OCR（电脑 CPU）：加载 {round((loaded - started) * 1000)} ms，"
        f"识别 {round((done - loaded) * 1000)} ms，共 {len(result['rec_texts'])} 段文字"
    )
    rows = []
    for text, score, poly in zip(
        result["rec_texts"], result["rec_scores"], result["rec_polys"], strict=True
    ):
        xs = [float(p[0]) for p in poly]
        ys = [float(p[1]) for p in poly]
        center = (round(sum(xs) / len(xs)), round(sum(ys) / len(ys)))
        rows.append((center[1], center[0], text, score))
    for y, x, text, score in sorted(rows):
        print(f"   {x:>5},{y:<5} {score:.2f}  {short(text, 30)}")
    return 0


def cmd_ocr(args: argparse.Namespace) -> int:
    return run_ocr(Path(args.png))


def last_bubble(png: Path) -> tuple[int, int, tuple[int, int, int, int]] | None:
    """Centre and box of the lowest message bubble, found by layout not by text.

    Chat bubbles are filled shapes that differ from the uniform background.
    Timestamps (small, centred), avatars and floating buttons (near the
    edges) are excluded; the title bar and input bar are cut off.
    """
    import cv2
    import numpy

    image = cv2.imread(str(png))
    height, width = image.shape[:2]
    top = int(0.10 * height)
    background = numpy.median(image[top : int(0.9 * height)].reshape(-1, 3), axis=0)
    full = numpy.abs(image.astype(numpy.int16) - background).max(axis=2) > 12
    # The input bar (and the keyboard under it) is the first full-width band
    # that differs from the chat background.
    rows = full[:, int(0.05 * width) : int(0.95 * width)].mean(axis=1)
    bands = numpy.flatnonzero(rows[int(0.2 * height) :] > 0.95)
    bottom = int(0.2 * height) + int(bands[0]) if bands.size else int(0.92 * height)
    mask = full[top:bottom].astype(numpy.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, numpy.ones((5, 5), numpy.uint8))
    count, _, stats, _ = cv2.connectedComponentsWithStats(mask)
    best = None
    for left, y, w, h, pixels in stats[1:count]:
        cx = left + w / 2
        if pixels < 2000 or not 0.12 * width < cx < 0.88 * width:
            continue  # noise, avatars, floating buttons
        if abs(cx - width / 2) < 0.1 * width and h < 70:
            continue  # timestamp
        if best is None or y + h > best[1] + best[3]:
            best = (int(left), int(y), int(w), int(h))
    if best is None:
        return None
    left, y, w, h = best
    return left + w // 2, top + y + h // 2, (left, top + y, w, h)


def cmd_bubble(args: argparse.Namespace) -> int:
    found = last_bubble(Path(args.png))
    if found is None:
        print("没有找到消息气泡")
        return 1
    x, y, box = found
    print(f"最后一个气泡中心 {x},{y}  范围 x={box[0]} y={box[1]} w={box[2]} h={box[3]}")
    return 0


def cmd_analyze(args: argparse.Namespace) -> int:
    xml_text = Path(args.tree).read_text(encoding="utf-8")
    print_report(Path(args.tree).parent.name, analyze(xml_text))
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("snap").add_argument("label")
    sub.add_parser("launch").add_argument("package")
    sub.add_parser("toast").add_argument("--seconds", type=float, default=8.0)
    sub.add_parser("report")
    sub.add_parser("analyze").add_argument("tree")
    a11y = sub.add_parser("a11y")
    a11y.add_argument(
        "cmd",
        choices=[
            "dump",
            "click",
            "long",
            "toast",
            "shot",
            "tap",
            "longtap",
            "ocrbench",
        ],
    )
    a11y.add_argument("arg", nargs="?", default="")
    sub.add_parser("ocr").add_argument("png")
    sub.add_parser("bubble").add_argument("png")
    args = parser.parse_args(argv)
    handlers = {
        "snap": cmd_snap,
        "launch": cmd_launch,
        "toast": cmd_toast,
        "report": cmd_report,
        "analyze": cmd_analyze,
        "a11y": cmd_a11y,
        "ocr": cmd_ocr,
        "bubble": cmd_bubble,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
