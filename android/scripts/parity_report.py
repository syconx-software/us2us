#!/usr/bin/env python3
"""Ranks the Android screens against the iOS app and writes side-by-side sheets.

Pairs every Android shot with its iOS counterpart and measures how far apart they are, worst first,
so the largest deviations can be fixed first:

  android/build/shots/sweep/android/<theme>-<lang>/<name>.png   the whole app per harness state
                                                                 (app RouteSweepTest; --shots for others)
  android/build/ios-reference/<theme>-<lang>/<name>.png         the iOS app in the same state
                                                                 (simulator capture, same route name)
  ios/design/reference/…                                        the drawn screen, when the shot's sidecar
                                                                 names one ("design") and --design is set

For each pair (status bar 186 px and home indicator 102 px masked, as compare_shots.py does):
  changed   share of pixels whose largest channel difference exceeds --threshold
  mean      mean absolute difference (0–255)
  aligned   the changed share after the best vertical shift within ±--shift px: what is left
            once a uniform offset is forgiven (a large drop from changed to aligned means the
            screen is right but sits a few pixels off)
  bands     the 3 horizontal bands (in dp, 1206 px = 402 dp) where the difference concentrates

Writes <out>/report.json, <out>/report.md (ranked table), and with --sheets <dir> one side-by-side
PNG per pair (iOS | Android | heatmap ×4) plus one contact sheet per route group.

Usage:
  python3 scripts/parity_report.py
  python3 scripts/parity_report.py --only conversation --sheets /tmp/parity --top 40
  python3 scripts/parity_report.py --shots build/shots/android --design
Needs Pillow.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("parity_report.py needs Pillow: python3 -m pip install Pillow")

ANDROID_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = ANDROID_DIR.parent
IOS_CAPTURES = ANDROID_DIR / "build/ios-reference"
DESIGN_REFERENCE = REPO_DIR / "ios/design/reference"
DEFAULT_SHOTS = ANDROID_DIR / "build/shots/sweep/android"
DEFAULT_OUT = ANDROID_DIR / "build/shots/parity"

WIDTH, HEIGHT = 1206, 2622
MASK_TOP, MASK_BOTTOM = 186, 102
PX_PER_DP = 3
VARIANTS = ("dark-tr", "light-tr", "dark-en", "light-en")


@dataclass
class Pair:
    variant: str
    name: str
    group: str
    shot: str
    reference: str
    reference_kind: str
    changed: float = 0.0
    mean: float = 0.0
    aligned: float = 0.0
    shift_px: int = 0
    bands: list = field(default_factory=list)
    sheet: str | None = None


def group_of(name: str) -> str:
    """The route group of a shot name (`conversation_deniz_5.3` → `conversation`)."""
    head = name.split("_", 1)[0]
    return {"newChat": "chats", "requests": "chats", "request": "chats", "invite": "chats", "backup": "settings"}.get(head, head)


def rgb(path: Path) -> Image.Image:
    image = Image.open(path)
    if image.mode in ("RGBA", "LA", "P"):
        image = image.convert("RGBA")
        base = Image.new("RGBA", image.size, (0, 0, 0, 255))
        image = Image.alpha_composite(base, image)
    image = image.convert("RGB")
    if image.size != (WIDTH, HEIGHT):
        image = image.resize((WIDTH, HEIGHT), Image.LANCZOS)
    return image


def content(image: Image.Image) -> Image.Image:
    """The part both platforms draw: without the status bar and the home indicator."""
    return image.crop((0, MASK_TOP, WIDTH, HEIGHT - MASK_BOTTOM))


def channel_max(diff: Image.Image) -> Image.Image:
    r, g, b = diff.split()
    return ImageChops.lighter(ImageChops.lighter(r, g), b)


def changed_share(a: Image.Image, b: Image.Image, threshold: int) -> tuple[float, float, Image.Image]:
    diff = channel_max(ImageChops.difference(a, b))
    histogram = diff.histogram()
    total = a.width * a.height
    over = sum(histogram[threshold + 1:])
    mean = sum(i * n for i, n in enumerate(histogram)) / total
    return 100.0 * over / total, mean, diff


def best_shift(ios: Image.Image, android: Image.Image, threshold: int, limit: int) -> tuple[float, int]:
    """The smallest changed share over vertical shifts of the Android picture within ±limit px."""
    best = (changed_share(ios, android, threshold)[0], 0)
    if limit <= 0:
        return best
    # Compare on a band that both shifted pictures cover.
    for dy in range(-limit, limit + 1):
        if dy == 0:
            continue
        top = max(0, dy)
        bottom = ios.height + min(0, dy)
        a = ios.crop((0, top, ios.width, bottom))
        b = android.crop((0, top - dy, android.width, bottom - dy))
        share = changed_share(a, b, threshold)[0]
        if share < best[0]:
            best = (share, dy)
    return best


def bands(diff: Image.Image, threshold: int, count: int = 3) -> list[dict]:
    """Where the difference concentrates: the densest 12 dp rows, merged into bands (dp from the top)."""
    mask = diff.point(lambda v: 255 if v > threshold else 0)
    row_px = 12 * PX_PER_DP
    rows = []
    for top in range(0, mask.height, row_px):
        box = mask.crop((0, top, mask.width, min(mask.height, top + row_px)))
        share = box.histogram()[255] / (box.width * box.height)
        rows.append((share, top))
    hot = sorted((r for r in rows if r[0] > 0.01), reverse=True)[: count * 3]
    merged: list[list[float]] = []
    for share, top in sorted(hot, key=lambda r: r[1]):
        start = (top + MASK_TOP) / PX_PER_DP
        end = start + 12
        if merged and start <= merged[-1][1] + 0.1:
            merged[-1][1] = end
            merged[-1][2] = max(merged[-1][2], share)
        else:
            merged.append([start, end, share])
    merged.sort(key=lambda m: -m[2])
    return [{"from_dp": round(m[0]), "to_dp": round(m[1]), "share": round(100 * m[2], 1)} for m in merged[:count]]


def label_font(size: int) -> ImageFont.ImageFont:
    for candidate in ("/System/Library/Fonts/SFNSMono.ttf", "/System/Library/Fonts/Menlo.ttc", "DejaVuSansMono.ttf"):
        try:
            return ImageFont.truetype(candidate, size)
        except OSError:
            continue
    return ImageFont.load_default()


def side_by_side(ios: Image.Image, android: Image.Image, diff: Image.Image, title: str, path: Path) -> None:
    scale = 3
    w, h = WIDTH // scale, HEIGHT // scale
    heat = diff.point(lambda v: min(255, v * 4))
    columns = [ios.resize((w, h), Image.LANCZOS), android.resize((w, h), Image.LANCZOS), Image.merge("RGB", (heat, heat, heat)).resize((w, h))]
    gap, header = 12, 34
    sheet = Image.new("RGB", (3 * w + 2 * gap, h + header), (34, 35, 44))
    draw = ImageDraw.Draw(sheet)
    draw.text((8, 8), title, fill=(235, 235, 240), font=label_font(14))
    for index, column in enumerate(columns):
        sheet.paste(column, (index * (w + gap), header))
    for index, label in enumerate(("iOS", "Android", "diff ×4")):
        draw.text((index * (w + gap) + 6, header + 4), label, fill=(255, 210, 90), font=label_font(12))
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path)


def contact_sheet(pairs: list[Pair], path: Path, columns: int = 4) -> None:
    """Many side-by-sides on one page (iOS | Android per cell), for a quick read of a whole group."""
    if not pairs:
        return
    cell_w, cell_h = 2 * (WIDTH // 6) + 6, HEIGHT // 6 + 30
    rows = (len(pairs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * (cell_w + 16), rows * (cell_h + 12)), (34, 35, 44))
    draw = ImageDraw.Draw(sheet)
    font = label_font(11)
    for index, pair in enumerate(pairs):
        x = (index % columns) * (cell_w + 16)
        y = (index // columns) * (cell_h + 12)
        ios = rgb(Path(pair.reference)).resize((WIDTH // 6, HEIGHT // 6), Image.LANCZOS)
        android = rgb(Path(pair.shot)).resize((WIDTH // 6, HEIGHT // 6), Image.LANCZOS)
        sheet.paste(ios, (x, y + 28))
        sheet.paste(android, (x + WIDTH // 6 + 6, y + 28))
        draw.text((x + 2, y + 2), f"{pair.variant}/{pair.name}", fill=(235, 235, 240), font=font)
        draw.text((x + 2, y + 15), f"{pair.changed:.1f}% · aligned {pair.aligned:.1f}%", fill=(255, 210, 90), font=font)
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path)


def reference_for(png: Path, variant: str, use_design: bool) -> tuple[Path | None, str | None]:
    if use_design:
        sidecar = png.with_suffix(".json")
        design = json.loads(sidecar.read_text(encoding="utf-8")).get("design") if sidecar.is_file() else None
        if design:
            theme = variant.split("-")[0]
            for candidate in (
                DESIGN_REFERENCE / ("light/" if theme == "light" else "") / f"{design}.png",
                DESIGN_REFERENCE / "urgent" / (f"{design}-light.png" if theme == "light" else f"{design}.png"),
            ):
                if candidate.is_file():
                    return candidate, "design"
    capture = IOS_CAPTURES / variant / png.name
    if capture.is_file():
        return capture, "ios-capture"
    return None, None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--shots", type=Path, default=DEFAULT_SHOTS, help="folder of <theme>-<lang>/ shot folders")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="where report.json and report.md go")
    parser.add_argument("--sheets", type=Path, default=None, help="also write side-by-side and contact sheets here")
    parser.add_argument("--only", action="append", default=[], help="substring of the shot name; repeatable")
    parser.add_argument("--threshold", type=int, default=24, help="per-channel difference that counts as changed")
    parser.add_argument("--shift", type=int, default=6, help="vertical shift in px forgiven by the aligned score")
    parser.add_argument("--top", type=int, default=50, help="rows in report.md")
    parser.add_argument("--design", action="store_true", help="prefer the drawn screen named by a shot's sidecar")
    args = parser.parse_args()

    shots_dir = args.shots if args.shots.is_absolute() else (Path.cwd() / args.shots)
    pairs: list[Pair] = []
    missing: list[str] = []
    for variant in VARIANTS:
        folder = shots_dir / variant
        if not folder.is_dir():
            continue
        for png in sorted(folder.glob("*.png")):
            if args.only and not any(token in png.stem for token in args.only):
                continue
            reference, kind = reference_for(png, variant, args.design)
            if reference is None:
                missing.append(f"{variant}/{png.stem}")
                continue
            ios, android = content(rgb(reference)), content(rgb(png))
            changed, mean, diff = changed_share(ios, android, args.threshold)
            aligned, shift = best_shift(ios, android, args.threshold, args.shift) if changed > 0.5 else (changed, 0)
            pair = Pair(
                variant=variant, name=png.stem, group=group_of(png.stem), shot=str(png), reference=str(reference),
                reference_kind=kind, changed=round(changed, 2), mean=round(mean, 2), aligned=round(aligned, 2),
                shift_px=shift, bands=bands(diff, args.threshold),
            )
            if args.sheets:
                full = ImageChops.difference(rgb(reference), rgb(png))
                full_diff = channel_max(full)
                full_diff.paste(0, (0, 0, WIDTH, MASK_TOP))
                full_diff.paste(0, (0, HEIGHT - MASK_BOTTOM, WIDTH, HEIGHT))
                sheet = args.sheets / "pairs" / variant / f"{png.stem}.png"
                side_by_side(rgb(reference), rgb(png), full_diff, f"{variant}/{png.stem}  changed {changed:.2f}%  aligned {aligned:.2f}% (dy {shift})", sheet)
                pair.sheet = str(sheet)
            pairs.append(pair)

    pairs.sort(key=lambda p: (-p.aligned, -p.changed))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "report.json").write_text(json.dumps([asdict(p) for p in pairs], indent=1, ensure_ascii=False), encoding="utf-8")

    lines = [
        "# Android ↔ iOS parity",
        "",
        f"{len(pairs)} pairs compared, {len(missing)} shots without an iOS counterpart. Threshold {args.threshold}; "
        f"aligned = changed after the best vertical shift within ±{args.shift} px.",
        "",
        "| # | shot | changed % | aligned % | dy px | mean | where (dp: share %) |",
        "|---|---|---|---|---|---|---|",
    ]
    for index, pair in enumerate(pairs[: args.top], start=1):
        where = ", ".join(f"{b['from_dp']}–{b['to_dp']}: {b['share']}" for b in pair.bands)
        lines.append(f"| {index} | {pair.variant}/{pair.name} | {pair.changed:.2f} | {pair.aligned:.2f} | {pair.shift_px} | {pair.mean:.2f} | {where} |")
    groups: dict[str, list[Pair]] = {}
    for pair in pairs:
        groups.setdefault(pair.group, []).append(pair)
    lines += ["", "## By group", "", "| group | pairs | median changed % | median aligned % | worst |", "|---|---|---|---|---|"]
    for group, members in sorted(groups.items()):
        changed = sorted(p.changed for p in members)
        aligned = sorted(p.aligned for p in members)
        worst = max(members, key=lambda p: p.aligned)
        lines.append(f"| {group} | {len(members)} | {changed[len(changed) // 2]:.2f} | {aligned[len(aligned) // 2]:.2f} | {worst.variant}/{worst.name} ({worst.aligned:.2f}) |")
    if missing:
        lines += ["", "## Without an iOS counterpart", "", ", ".join(missing)]
    (args.out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    if args.sheets:
        for group, members in groups.items():
            for variant in VARIANTS:
                chosen = [p for p in members if p.variant == variant]
                contact_sheet(chosen, args.sheets / "groups" / f"{group}-{variant}.png")

    for pair in pairs[: args.top]:
        print(f"{pair.aligned:6.2f}%  (raw {pair.changed:6.2f}%, dy {pair.shift_px:+d})  {pair.variant}/{pair.name}")
    print(f"{len(pairs)} pairs, {len(missing)} without a counterpart → {args.out / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
