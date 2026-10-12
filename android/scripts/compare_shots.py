#!/usr/bin/env python3
"""Compares the Android screenshots with the iOS references and writes a review page.

Shots (one folder per theme and language, as ShotHost and run-route.sh write them):
  android/build/shots/android/<theme>-<lang>/<name>.png   JVM shots (Robolectric, Roborazzi)
  android/build/shots/device/<theme>-<lang>/<name>.png    emulator shots (run-route.sh)
Each shot may have a <name>.json sidecar; its "design" field names the drawn screen it shows.

References, in this order:
  1. the drawn screen (ios/design/reference/, light/, urgent/) when the shot is one
     (its name or its sidecar's "design" is a reference name such as 3.2-kod-yanlis)
     and the language matches the drawing (section 09 is English, the rest Turkish);
  2. the iOS simulator capture android/build/ios-reference/<theme>-<lang>/<name>.png.

The top 186 px (status bar, Dynamic Island) and the bottom 102 px (home indicator) are masked:
the references draw iOS chrome there. Outputs:
  android/build/shots/compare/<source>/<theme>-<lang>/<name>-diff.png   heatmap over the reference
  android/build/shots/compare/<source>/<theme>-<lang>/<name>-side.png   reference | android | heatmap
  android/build/shots/compare/summary.json                              metrics for every shot
  android/build/shots/index.html                                        the review page, worst first

Usage:
  scripts/compare_shots.py                      # JVM shots
  scripts/compare_shots.py --source device      # emulator shots
  scripts/compare_shots.py --source all --only 3.2 --fail-above 5
Needs Pillow (python3 -m pip install Pillow).
"""

from __future__ import annotations

import argparse
import fnmatch
import html
import json
import os
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps, ImageStat
except ImportError:  # pragma: no cover
    sys.exit("compare_shots.py needs Pillow: python3 -m pip install Pillow")

ANDROID_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = ANDROID_DIR.parent
DESIGN_REFERENCE = REPO_DIR / "ios/design/reference"
IOS_CAPTURES = ANDROID_DIR / "build/ios-reference"
SHOTS_DIR = ANDROID_DIR / "build/shots"
SOURCES = {"android": SHOTS_DIR / "android", "device": SHOTS_DIR / "device"}

WIDTH, HEIGHT = 1206, 2622
MASK_TOP, MASK_BOTTOM = 186, 102

# A drawn screen in another language or theme than the one it was drawn in.
EQUIVALENT = {
    ("4.1-sohbet-listesi", "en"): "9.1-chats-en",
    ("5.1-sohbet", "en"): "9.2-chat-en",
    ("4.1-sohbet-listesi-urgent", "en"): "9.1-chats-en-urgent",
    ("5.1-sohbet-urgent", "en"): "9.2-chat-en-urgent",
}


@dataclass
class Result:
    source: str
    variant: str
    name: str
    shot: str
    reference: str | None = None
    reference_kind: str | None = None  # design | ios-capture
    design: str | None = None
    changed_percent: float | None = None
    mean_error: float | None = None
    max_error: int | None = None
    resized: bool = False
    diff: str | None = None
    side: str | None = None
    note: str | None = None
    meta: dict = field(default_factory=dict)


def design_names() -> set[str]:
    names = set()
    for folder in (DESIGN_REFERENCE, DESIGN_REFERENCE / "light", DESIGN_REFERENCE / "urgent"):
        names.update(p.stem for p in folder.glob("*.png") if not p.stem.startswith("overview"))
    return {n.removesuffix("-light") for n in names}


def design_language(stem: str) -> str:
    return "en" if stem.startswith("9.") else "tr"


def design_reference(stem: str, theme: str, lang: str) -> Path | None:
    stem = EQUIVALENT.get((stem, lang), stem)
    if design_language(stem) != lang:
        return None
    if stem.startswith("8."):  # section 08 is the light theme of 4.1, 5.1 and 3.2
        candidates = [DESIGN_REFERENCE / f"{stem}.png", DESIGN_REFERENCE / f"urgent/{stem}.png"] if theme == "light" else []
    elif theme == "dark":
        candidates = [DESIGN_REFERENCE / f"{stem}.png", DESIGN_REFERENCE / f"urgent/{stem}.png"]
    else:
        candidates = [DESIGN_REFERENCE / f"light/{stem}.png", DESIGN_REFERENCE / f"urgent/{stem}-light.png"]
    return next((c for c in candidates if c.is_file()), None)


def find_reference(name: str, design: str | None, variant: str) -> tuple[Path | None, str | None, str | None]:
    theme, _, lang = variant.partition("-")
    stems = design_names()
    stem = design if design else (name if name in stems else None)
    if stem:
        ref = design_reference(stem, theme, lang)
        if ref:
            return ref, "design", stem
    capture = IOS_CAPTURES / variant / f"{name}.png"
    if capture.is_file():
        return capture, "ios-capture", stem
    return None, None, stem


def rgb(path: Path) -> Image.Image:
    image = Image.open(path)
    if image.mode in ("RGBA", "LA", "P"):
        image = image.convert("RGBA")
        background = Image.new("RGBA", image.size, (0, 0, 0, 255))
        image = Image.alpha_composite(background, image)
    return image.convert("RGB")


def masked(image: Image.Image) -> Image.Image:
    out = image.copy()
    draw = ImageDraw.Draw(out)
    draw.rectangle((0, 0, out.width, MASK_TOP - 1), fill=(0, 0, 0))
    draw.rectangle((0, out.height - MASK_BOTTOM, out.width, out.height), fill=(0, 0, 0))
    return out


def label_font(size: int) -> ImageFont.ImageFont:
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # Pillow < 10.1
        return ImageFont.load_default()


def compare(shot_path: Path, ref_path: Path, threshold: int, result: Result, out_dir: Path) -> None:
    shot, ref = rgb(shot_path), rgb(ref_path)
    if ref.size != (WIDTH, HEIGHT):
        result.note = f"reference is {ref.size[0]}×{ref.size[1]}"
    if shot.size != ref.size:
        result.resized = True
        result.note = (result.note + "; " if result.note else "") + f"shot {shot.size[0]}×{shot.size[1]} resized"
        shot = shot.resize(ref.size, Image.LANCZOS)

    difference = ImageChops.difference(masked(ref), masked(shot))
    r, g, b = difference.split()
    worst = ImageChops.lighter(ImageChops.lighter(r, g), b)  # per-pixel max channel difference
    area = ref.width * (ref.height - MASK_TOP - MASK_BOTTOM)
    changed = worst.point(lambda v: 255 if v > threshold else 0).histogram()[255]
    result.changed_percent = round(100.0 * changed / area, 3)
    result.mean_error = round(ImageStat.Stat(worst).sum[0] / area, 3)
    result.max_error = worst.getextrema()[1]

    # Heatmap: the reference dimmed to grey, differences from yellow to red on top.
    amplified = worst.point(lambda v: 0 if v <= threshold else min(255, 64 + v * 3))
    heat = ImageOps.colorize(amplified, black="#000000", mid="#ffd60a", white="#ff2d55")
    base = ImageOps.grayscale(ref).point(lambda v: int(v * 0.35)).convert("RGB")
    heatmap = Image.composite(heat, base, amplified)
    band = Image.new("RGB", (ref.width, 1), (90, 200, 250))
    for y in (MASK_TOP - 1, ref.height - MASK_BOTTOM):
        heatmap.paste(band, (0, y))

    out_dir.mkdir(parents=True, exist_ok=True)
    diff_path = out_dir / f"{shot_path.stem}-diff.png"
    heatmap.save(diff_path, optimize=False)

    # Side by side at 1× (402 × 874 each) with labels.
    scale = 3
    w, h = ref.width // scale, ref.height // scale
    header = 28
    side = Image.new("RGB", (w * 3 + 16, h + header), (24, 26, 36))
    draw = ImageDraw.Draw(side)
    font = label_font(15)
    for i, (title, image) in enumerate(
        (("iOS " + (result.reference_kind or ""), ref), ("Android", shot), (f"diff {result.changed_percent}%", heatmap))
    ):
        x = i * (w + 8)
        side.paste(image.resize((w, h), Image.LANCZOS), (x, header))
        draw.text((x + 6, 6), title, fill=(233, 233, 237), font=font)
    side_path = out_dir / f"{shot_path.stem}-side.png"
    side.save(side_path, optimize=False)
    result.diff = str(diff_path)
    result.side = str(side_path)


VARIANTS = {f"{theme}-{lang}" for theme in ("dark", "light") for lang in ("tr", "en")}


def collect(sources: list[str], only: list[str]) -> list[tuple[str, str, Path]]:
    shots = []
    for source in sources:
        root = SOURCES[source]
        if not root.is_dir():
            continue
        # Only <theme>-<lang> folders hold screens; other folders (a module's own probes) are skipped.
        for variant_dir in sorted(p for p in root.iterdir() if p.is_dir() and p.name in VARIANTS):
            for png in sorted(variant_dir.glob("*.png")):
                key = f"{variant_dir.name}/{png.stem}"
                if only and not any(fnmatch.fnmatch(png.stem, pattern) or fnmatch.fnmatch(key, pattern) or pattern in png.stem for pattern in only):
                    continue
                shots.append((source, variant_dir.name, png))
    return shots


def write_index(results: list[Result], path: Path) -> None:
    def rel(p: str | None) -> str:
        return html.escape(os.path.relpath(p, path.parent)) if p else ""

    rows = []
    for r in results:
        metric = "—" if r.changed_percent is None else f"{r.changed_percent:.2f}%"
        level = "none" if r.changed_percent is None else ("ok" if r.changed_percent < 2 else "warn" if r.changed_percent < 10 else "bad")
        image = f'<a href="{rel(r.side)}"><img loading="lazy" src="{rel(r.side)}" alt=""></a>' if r.side else (
            f'<a href="{rel(r.shot)}"><img loading="lazy" class="solo" src="{rel(r.shot)}" alt=""></a>'
        )
        details = [
            f"<b>{html.escape(r.name)}</b>",
            f"{html.escape(r.source)} · {html.escape(r.variant)}",
            f"reference: {html.escape(r.reference_kind or 'none')}" + (f" · {html.escape(Path(r.reference).name)}" if r.reference else ""),
        ]
        if r.design:
            details.append(f"design: {html.escape(r.design)}")
        if r.mean_error is not None:
            details.append(f"mean {r.mean_error} · max {r.max_error}")
        if r.note:
            details.append(html.escape(r.note))
        if r.meta.get("test"):
            details.append(f"<small>{html.escape(r.meta['test'])}</small>")
        search = html.escape(f"{r.name} {r.variant} {r.source} {r.design or ''} {r.reference_kind or 'none'}".lower())
        rows.append(
            f'<tr data-search="{search}"><td class="m {level}">{metric}</td><td>{"<br>".join(details)}</td><td>{image}</td></tr>'
        )
    compared = [r for r in results if r.changed_percent is not None]
    summary = f"{len(results)} shots · {len(compared)} compared · {len(results) - len(compared)} without a reference"
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>us2us shot parity</title>
<style>
:root {{ --bg:#f3f5fe; --fg:#292b31; --muted:#6b6e7a; --line:#dcdfea; --ok:#2f9e6a; --warn:#c98a00; --bad:#d6334f; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#161826; --fg:#e9e9ed; --muted:#9a9cab; --line:#2c2f42; }} }}
body {{ margin:0; padding:16px; background:var(--bg); color:var(--fg); font:14px/1.45 -apple-system, system-ui, sans-serif; }}
h1 {{ font-size:18px; margin:0 0 4px; }} p {{ color:var(--muted); margin:0 0 12px; }}
input {{ width:100%; max-width:420px; padding:8px 10px; border-radius:8px; border:1px solid var(--line); background:transparent; color:inherit; margin-bottom:12px; }}
table {{ border-collapse:collapse; width:100%; }} td {{ border-top:1px solid var(--line); padding:10px 8px; vertical-align:top; }}
td.m {{ font-weight:600; white-space:nowrap; font-variant-numeric:tabular-nums; }}
.ok {{ color:var(--ok); }} .warn {{ color:var(--warn); }} .bad {{ color:var(--bad); }} .none {{ color:var(--muted); }}
img {{ max-width:min(100%, 960px); height:auto; border-radius:6px; }} img.solo {{ max-width:240px; }}
small {{ color:var(--muted); }}
</style></head><body>
<h1>Android ↔ iOS screenshots</h1>
<p>{html.escape(summary)}. Top {MASK_TOP} px and bottom {MASK_BOTTOM} px masked (iOS chrome). Worst first.</p>
<input id="q" type="search" placeholder="Filter (name, dark-tr, design, device …)" autofocus>
<table><tbody id="rows">
{chr(10).join(rows)}
</tbody></table>
<script>
const q = document.getElementById('q');
q.addEventListener('input', () => {{
  const terms = q.value.toLowerCase().split(/\\s+/).filter(Boolean);
  for (const row of document.querySelectorAll('#rows tr')) {{
    row.hidden = !terms.every(t => row.dataset.search.includes(t));
  }}
}});
</script>
</body></html>
"""
    path.write_text(page, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", choices=["android", "device", "all"], default="android")
    parser.add_argument("--only", action="append", default=[], help="shot name pattern (glob or substring); repeatable")
    parser.add_argument("--threshold", type=int, default=24, help="per-channel difference (0–255) that counts as changed")
    parser.add_argument("--fail-above", type=float, default=None, help="exit 1 when any compared shot differs by more than this percent")
    parser.add_argument("--shots-dir", type=Path, default=None, help="another shots folder (default android/build/shots)")
    args = parser.parse_args()
    if args.shots_dir:
        global SHOTS_DIR, SOURCES
        SHOTS_DIR = args.shots_dir.resolve()
        SOURCES = {"android": SHOTS_DIR / "android", "device": SHOTS_DIR / "device"}

    sources = ["android", "device"] if args.source == "all" else [args.source]
    shots = collect(sources, args.only)
    if not shots:
        print(f"no shots under {', '.join(str(SOURCES[s]) for s in sources)}", file=sys.stderr)
        return 1

    results: list[Result] = []
    for source, variant, png in shots:
        sidecar = png.with_suffix(".json")
        meta = json.loads(sidecar.read_text(encoding="utf-8")) if sidecar.is_file() else {}
        result = Result(source=source, variant=variant, name=png.stem, shot=str(png), meta=meta)
        reference, kind, stem = find_reference(png.stem, meta.get("design"), variant)
        result.design = stem
        if reference is None:
            result.note = "no iOS reference for this state yet"
        else:
            result.reference, result.reference_kind = str(reference), kind
            compare(png, reference, args.threshold, result, SHOTS_DIR / "compare" / source / variant)
        results.append(result)

    results.sort(key=lambda r: (r.changed_percent is None, -(r.changed_percent or 0), r.variant, r.name))
    summary_path = SHOTS_DIR / "compare" / "summary.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps([asdict(r) for r in results], indent=1, ensure_ascii=False), encoding="utf-8")
    index = SHOTS_DIR / "index.html"
    write_index(results, index)

    compared = [r for r in results if r.changed_percent is not None]
    for r in compared:
        print(f"{r.changed_percent:7.2f}%  {r.source}/{r.variant}/{r.name}  ← {r.reference_kind} {Path(r.reference).name}")
    missing = len(results) - len(compared)
    print(f"{len(results)} shots, {len(compared)} compared, {missing} without a reference → {index}")
    if args.fail_above is not None and any(r.changed_percent > args.fail_above for r in compared):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
