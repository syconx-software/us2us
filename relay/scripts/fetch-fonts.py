#!/usr/bin/env python3
"""Re-downloads the website's fonts into public/fonts (run by hand; the files
are committed). The css2 API answers a modern browser with woff2 files split
by unicode-range; the site keeps the latin and latin-ext subsets. Turkish
needs only five letters from latin-ext (ğ Ğ ş Ş İ; ı is in latin), so the
script also cuts those out of each latin-ext file into a few-KB
`<font>-tr.woff2` (needs fontTools and brotli: `pip install fonttools
brotli`). Turkish pages preload that file instead of the whole latin-ext one.
The @font-face rules are in public/css/site.css; if Google bumps a font
version (v20, v3, v6), rename the files there too.
Licences: SIL OFL 1.1, public/fonts/OFL-*.txt (from github.com/google/fonts).

    python3 scripts/fetch-fonts.py             # download, then cut the -tr files
    python3 scripts/fetch-fonts.py --tr-only   # only cut the -tr files again
"""

import re
import sys
import urllib.request
from pathlib import Path

UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'
FONTS = {
    'inter-v20': 'family=Inter:wght@400;500;600;700',
    'doto-v3': 'family=Doto:ROND,wght@0..100,500..900',
    'martian-mono-v6': 'family=Martian+Mono:wght@400;500',
}
OUT = Path(__file__).resolve().parent.parent / 'public' / 'fonts'
# Keep in step with the unicode-range of the "-tr" @font-face rules in site.css.
TURKISH = 'U+011E-011F,U+0130,U+015E-015F'


def fetch(url: str) -> bytes:
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=30).read()


def download() -> None:
    written = set()
    for name, query in FONTS.items():
        css = fetch(f'https://fonts.googleapis.com/css2?{query}&display=swap').decode()
        # Variable fonts: every weight of a subset points at the same file.
        for subset, body in re.findall(r'/\* ([\w-]+) \*/\s*@font-face \{(.*?)\}', css, re.S):
            target = OUT / f'{name}-{subset}.woff2'
            if subset not in ('latin', 'latin-ext') or target in written:
                continue
            target.write_bytes(fetch(re.search(r'url\((.*?)\)', body).group(1)))
            written.add(target)
            print(f'{target.name}: {target.stat().st_size} bytes')


def cut_turkish() -> None:
    from fontTools import subset  # pip install fonttools brotli

    for name in FONTS:
        source, target = OUT / f'{name}-latin-ext.woff2', OUT / f'{name}-tr.woff2'
        # Keeps the variation axes, kerning and the layout features of the
        # kept glyphs; drops hinting, which woff2 browsers do not use.
        subset.main([
            str(source), f'--unicodes={TURKISH}', '--flavor=woff2', f'--output-file={target}',
            '--layout-features=*', '--no-hinting', '--desubroutinize', '--name-IDs=*', '--name-legacy', '--name-languages=*',
        ])
        print(f'{target.name}: {target.stat().st_size} bytes')


if __name__ == '__main__':
    if '--tr-only' not in sys.argv:
        download()
    cut_turkish()
