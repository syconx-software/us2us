#!/usr/bin/env python3
"""Converts the iOS String Catalogs (.xcstrings) into Android string resources.

One pass over every catalogue of the iOS app and of Us2usKit writes, under
designsystem/src/main/res/:
  values/strings_<table>.xml      English (also the fallback for any other device language)
  values-tr/strings_<table>.xml   Turkish
  values*/strings_l10n.xml        the language code each folder stands for
and designsystem/src/main/kotlin/com/syconx/us2us/ui/l10n/StringIndex.gen.kt
(every catalogue key and its resource, for lookups by iOS key and for the tests).

Resource names: the table prefix, then the key without its trailing ` %…`
argument tokens, every run of other characters than letters and digits as `_`,
lower case. A key that already starts with the table's name is not prefixed twice:
  Lock.xcstrings      "lock.code.wrong.warning %lld %@"  → plurals lock_code_wrong_warning
  Chats.xcstrings     "newChat.info %@ %@"               → string  chats_newchat_info
  Localizable.xcstrings "chats.title"                     → string  common_chats_title

Formats: %lld → %d, %@ → %s; positional (%1$d, %2$s) whenever a key takes more
than one argument. Plural variations become <plurals> (one / other).

Run from anywhere:  python3 android/scripts/xcstrings_to_android.py
                    python3 android/scripts/xcstrings_to_android.py --check
--check fails if an output is stale, two keys map to one name, a language is
missing or a translation's arguments do not match its key.
The output is committed; never edit it by hand — change the catalogue and re-run.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_DIR = SCRIPT_DIR.parent
REPO_DIR = ANDROID_DIR.parent
RES_DIR = ANDROID_DIR / "designsystem" / "src" / "main" / "res"
KOTLIN_OUT = (
    ANDROID_DIR / "designsystem" / "src" / "main" / "kotlin" / "com" / "syconx" / "us2us" / "ui" / "l10n" / "StringIndex.gen.kt"
)

# (Kotlin name, resource prefix, catalogue). The order is the order of StringTable.
TABLES = [
    ("Common", "common", "ios/App/Resources/Localizable.xcstrings"),
    ("InfoPlist", "infoplist", "ios/App/Resources/InfoPlist.xcstrings"),
    ("Onboarding", "onboarding", "ios/App/Sources/Features/Onboarding/Onboarding.xcstrings"),
    ("Lock", "lock", "ios/App/Sources/Features/Lock/Lock.xcstrings"),
    ("Chats", "chats", "ios/App/Sources/Features/Chats/Chats.xcstrings"),
    ("Conversation", "conversation", "ios/App/Sources/Features/Conversation/Conversation.xcstrings"),
    ("Contact", "contact", "ios/App/Sources/Features/Contact/Contact.xcstrings"),
    ("Verification", "verification", "ios/App/Sources/Features/Verification/Verification.xcstrings"),
    ("Settings", "settings", "ios/App/Sources/Features/Settings/Settings.xcstrings"),
    ("Invite", "invite", "ios/App/Sources/Features/Invite/Invite.xcstrings"),
    ("Report", "report", "ios/App/Sources/Features/Report/Report.xcstrings"),
    ("Scene3D", "scene3d", "ios/App/Sources/Scene3D/Scene3D.xcstrings"),
    ("Kit", "kit", "ios/Us2usKit/Sources/Us2usKit/Resources/Localizable.xcstrings"),
]

# English is values/ (the fallback), Turkish values-tr/.
LANGUAGES = [("en", "values"), ("tr", "values-tr")]
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")

# An iOS format specifier: %%, or %[n$](lld|ld|d|@).
SPEC = re.compile(r"%(?:(\d+)\$)?(lld|ld|d|@|%)")
ANY_PERCENT = re.compile(r"%")
ARG_SUFFIX = re.compile(r"(?:\s+%\S*)+$")


class CatalogError(Exception):
    pass


# ---------------------------------------------------------------------------
# Names


def base_key(key: str) -> str:
    """The key without its trailing ` %lld %@` argument tokens."""
    return ARG_SUFFIX.sub("", key)


def resource_name(prefix: str, key: str) -> str:
    name = re.sub(r"[^A-Za-z0-9]+", "_", base_key(key)).strip("_").lower()
    if not name:
        raise CatalogError(f"{prefix}: key {key!r} has no name")
    return name if name.startswith(prefix + "_") else f"{prefix}_{name}"


def key_arg_types(key: str) -> list[str] | None:
    """The argument types the key declares in its suffix ('d' or 's'), or None without a suffix."""
    m = ARG_SUFFIX.search(key)
    if not m:
        return None
    types = []
    for token in m.group(0).split():
        spec = SPEC.fullmatch(token)
        if not spec or spec.group(2) == "%":
            raise CatalogError(f"key {key!r}: unknown argument token {token!r}")
        types.append("s" if spec.group(2) == "@" else "d")
    return types


# ---------------------------------------------------------------------------
# Values


def parse_args(value: str, where: str) -> list[tuple[int | None, str]]:
    """The arguments of a value in order of appearance: (explicit position or None, 'd'/'s')."""
    args = []
    for m in SPEC.finditer(value):
        if m.group(2) != "%":
            args.append((int(m.group(1)) if m.group(1) else None, "s" if m.group(2) == "@" else "d"))
    # Every % must belong to a specifier we understand.
    stripped = SPEC.sub("", value)
    if ANY_PERCENT.search(stripped):
        raise CatalogError(f"{where}: unsupported format in {value!r}")
    return args


def positions(args: list[tuple[int | None, str]], where: str) -> list[tuple[int, str]]:
    """Explicit positions where given, otherwise counted in order (as Foundation does)."""
    out = []
    implicit = 0
    for explicit, kind in args:
        if explicit is None:
            implicit += 1
            out.append((implicit, kind))
        else:
            out.append((explicit, kind))
    seen = {}
    for p, kind in out:
        if p in seen and seen[p] != kind:
            raise CatalogError(f"{where}: argument {p} used as two types")
        seen[p] = kind
    return out


def android_format(value: str, expected: list[str], where: str) -> str:
    """The value with Android specifiers: %d/%s, positional when the key takes several arguments."""
    args = positions(parse_args(value, where), where)
    for p, kind in args:
        if p < 1 or p > len(expected):
            raise CatalogError(f"{where}: argument %{p}$ but the key takes {len(expected)}")
        if expected[p - 1] != kind:
            raise CatalogError(f"{where}: argument {p} is {kind}, the key declares {expected[p - 1]}")
    if not expected and "%" in value:
        # A literal % in a string that is never formatted would show as written; none exist.
        raise CatalogError(f"{where}: '%' in a string without arguments")
    positional = len(expected) > 1
    implicit = 0

    def repl(m: re.Match) -> str:
        nonlocal implicit
        if m.group(2) == "%":
            return "%%"
        if m.group(1):
            p = int(m.group(1))
        else:
            implicit += 1
            p = implicit
        kind = "s" if m.group(2) == "@" else "d"
        return f"%{p}${kind}" if positional else f"%{kind}"

    return SPEC.sub(repl, value)


def escape(value: str) -> str:
    """Android string-resource text: XML-escaped, quotes and backslashes escaped, white space kept."""
    s = value.replace("\\", "\\\\")
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace('"', '\\"').replace("'", "\\'")
    s = s.replace("\n", "\\n").replace("\t", "\\t")
    # aapt2 trims and collapses runs of spaces; keep each one that would be lost.
    s = re.sub(r"(?<= ) ", "\\\\u0020", s)
    s = re.sub(r"^ ", "\\\\u0020", s)
    s = re.sub(r" $", "\\\\u0020", s)
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def comment_text(text: str) -> str:
    """Text safe inside an XML comment."""
    text = " ".join(text.split())
    while "--" in text:
        text = text.replace("--", "-\u2010")
    return text.rstrip("-")


# ---------------------------------------------------------------------------
# Catalogues


class Entry:
    def __init__(self, table: str, key: str, name: str, comment: str | None, arg_types: list[str]):
        self.table = table
        self.key = key
        self.name = name
        self.comment = comment
        self.arg_types = arg_types
        # language → str (string) or dict category → str (plural)
        self.values: dict[str, object] = {}

    @property
    def is_plural(self) -> bool:
        return any(isinstance(v, dict) for v in self.values.values())


def unit_value(unit: dict, where: str) -> str:
    su = unit.get("stringUnit")
    if not isinstance(su, dict) or "value" not in su:
        raise CatalogError(f"{where}: no stringUnit value")
    if su.get("state") not in (None, "translated"):
        raise CatalogError(f"{where}: state {su.get('state')!r}")
    return su["value"]


def load_table(kotlin: str, prefix: str, path: str) -> list[Entry]:
    catalog = json.loads((REPO_DIR / path).read_text(encoding="utf-8"))
    entries = []
    for key in sorted(catalog["strings"]):
        item = catalog["strings"][key]
        where = f"{path} › {key!r}"
        locs = item.get("localizations") or {}
        declared = key_arg_types(key)
        if declared is None:
            # Keys without an argument suffix (Us2usKit) declare their arguments in the English value.
            en = locs.get("en")
            sample = unit_value(en, where) if en and "stringUnit" in en else ""
            if en and "variations" in en:
                sample = unit_value(en["variations"]["plural"]["other"], where)
            used = dict(positions(parse_args(sample, where), where))
            if sorted(used) != list(range(1, len(used) + 1)):
                raise CatalogError(f"{where}: arguments {sorted(used)} leave a gap")
            declared = [used[p] for p in sorted(used)]
        entry = Entry(kotlin, key, resource_name(prefix, key), item.get("comment"), declared)
        for lang, _ in LANGUAGES:
            loc = locs.get(lang)
            if loc is None:
                raise CatalogError(f"{where}: no {lang}")
            if "variations" in loc:
                variations = loc["variations"]
                if set(variations) != {"plural"}:
                    raise CatalogError(f"{where}: unsupported variations {sorted(variations)}")
                plural = {}
                for cat, unit in variations["plural"].items():
                    if cat not in PLURAL_CATEGORIES:
                        raise CatalogError(f"{where}: plural category {cat!r}")
                    plural[cat] = android_format(unit_value(unit, f"{where} [{lang}/{cat}]"), declared, f"{where} [{lang}/{cat}]")
                if "other" not in plural:
                    raise CatalogError(f"{where}: plural without 'other' in {lang}")
                entry.values[lang] = plural
            else:
                entry.values[lang] = android_format(unit_value(loc, f"{where} [{lang}]"), declared, f"{where} [{lang}]")
        kinds = {isinstance(v, dict) for v in entry.values.values()}
        if len(kinds) != 1:
            raise CatalogError(f"{where}: plural in one language only")
        if entry.is_plural and "d" not in declared:
            raise CatalogError(f"{where}: plural without a number argument")
        entries.append(entry)
    return entries


def load_all() -> list[tuple[tuple[str, str, str], list[Entry]]]:
    tables = [(t, load_table(*t)) for t in TABLES]
    names: dict[tuple[str, str], str] = {}
    for (_, _, path), entries in tables:
        for e in entries:
            kind = "plurals" if e.is_plural else "string"
            # Android keeps strings and plurals apart, but one name for both would confuse; refuse it.
            for other_kind in ("string", "plurals"):
                clash = names.get((other_kind, e.name))
                if clash:
                    raise CatalogError(f"name clash: {e.name} for {clash} and {path} › {e.key!r}")
            names[(kind, e.name)] = f"{path} › {e.key!r}"
    return tables


# ---------------------------------------------------------------------------
# Output


def xml_file(lang: str, path: str, entries: list[Entry]) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- GENERATED by android/scripts/xcstrings_to_android.py from {path} ({lang}).",
        "     Do not edit by hand: change the catalogue and re-run the script. -->",
        "<resources>",
    ]
    for e in entries:
        note = e.key
        if lang == "en" and e.comment:
            note += " — " + e.comment
        lines.append(f"    <!-- {comment_text(note)} -->")
        value = e.values[lang]
        if isinstance(value, dict):
            lines.append(f'    <plurals name="{e.name}">')
            for cat in PLURAL_CATEGORIES:
                if cat in value:
                    lines.append(f'        <item quantity="{cat}">{escape(value[cat])}</item>')
            lines.append("    </plurals>")
        else:
            lines.append(f'    <string name="{e.name}">{escape(value)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def language_file(lang: str) -> str:
    return "\n".join(
        [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<!-- GENERATED by android/scripts/xcstrings_to_android.py. Do not edit by hand. -->",
            "<resources>",
            "    <!-- The language this resource folder is in: the language the UI resolved to (L10n.uiLanguage). -->",
            f'    <string name="l10n_language">{lang}</string>',
            "</resources>",
        ]
    ) + "\n"


def kotlin_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def kotlin_file(tables: list[tuple[tuple[str, str, str], list[Entry]]]) -> str:
    out = [
        "// GENERATED by android/scripts/xcstrings_to_android.py from the iOS string catalogues.",
        "// Do not edit by hand: change a catalogue and re-run the script.",
        "",
        "package com.syconx.us2us.ui.l10n",
        "",
        "import com.syconx.us2us.ui.R",
        "",
        "/**",
        " * The string tables of the iOS app (`StringTable` and the default `Localizable` table), InfoPlist and",
        " * Us2usKit. Each became `strings_<prefix>.xml`; its resources are named `<prefix>_<key>`.",
        " */",
        "enum class StringTable(",
        "    /** The resource-name prefix and file suffix (`strings_<prefix>.xml`). */",
        "    val prefix: String,",
        "    /** The iOS catalogue it was converted from, relative to the repository. */",
        "    val catalogue: String,",
        ") {",
    ]
    for (kotlin, prefix, path), _ in tables:
        out.append(f"    {kotlin}({kotlin_string(prefix)}, {kotlin_string(path)}),")
    out += [
        "    ;",
        "",
        "    /** The resource of an iOS key of this table (with or without its ` %…` suffix), or null. */",
        "    fun find(key: String): StringRef? = StringIndex.find(this, key)",
        "",
        "    /** Every key of this table, as written in its catalogue, with its resource and argument types. */",
        "    val strings: List<StringEntry> get() = StringIndex.entries(this)",
        "}",
        "",
        "internal object StringIndex {",
        "    fun find(table: StringTable, key: String): StringRef? {",
        "        val base = L10n.baseKey(key)",
        "        return when (table) {",
    ]
    for (kotlin, _, _), _ in tables:
        out.append(f"            StringTable.{kotlin} -> find{kotlin}(base)")
    out += [
        "        }",
        "    }",
        "",
        "    fun entries(table: StringTable): List<StringEntry> = when (table) {",
    ]
    for (kotlin, _, _), _ in tables:
        out.append(f"        StringTable.{kotlin} -> entries{kotlin}()")
    out += ["    }"]
    for (kotlin, _, _), entries in tables:
        out += ["", f"    private fun find{kotlin}(key: String): StringRef? = when (key) {{"]
        for e in entries:
            ref = f"StringRef.Plural(R.plurals.{e.name})" if e.is_plural else f"StringRef.Text(R.string.{e.name})"
            out.append(f"        {kotlin_string(base_key(e.key))} -> {ref}")
        out += ["        else -> null", "    }"]
        out += ["", f"    private fun entries{kotlin}(): List<StringEntry> = listOf("]
        for e in entries:
            ref = f"StringRef.Plural(R.plurals.{e.name})" if e.is_plural else f"StringRef.Text(R.string.{e.name})"
            out.append(f"        StringEntry({kotlin_string(e.key)}, {ref}, {kotlin_string(''.join(e.arg_types))}),")
        out += ["    )"]
    out += ["}", ""]
    return "\n".join(out)


def outputs() -> dict[Path, str]:
    tables = load_all()
    files: dict[Path, str] = {}
    for lang, folder in LANGUAGES:
        for (_, prefix, path), entries in tables:
            files[RES_DIR / folder / f"strings_{prefix}.xml"] = xml_file(lang, path, entries)
        files[RES_DIR / folder / "strings_l10n.xml"] = language_file(lang)
    files[KOTLIN_OUT] = kotlin_file(tables)
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if an output is stale or a catalogue cannot be converted")
    args = parser.parse_args()
    try:
        files = outputs()
    except CatalogError as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
    if args.check:
        stale = [p for p, text in files.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
        # A strings_*.xml left over from a table that no longer exists is stale too.
        for _, folder in LANGUAGES:
            for p in sorted((RES_DIR / folder).glob("strings_*.xml")):
                if p not in files and not p.name.startswith("strings_android_"):
                    stale.append(p)
        if stale:
            for p in stale:
                print(f"stale: {p.relative_to(ANDROID_DIR)}", file=sys.stderr)
            sys.exit(1)
        print(f"strings are up to date ({sum(1 for p in files if p.suffix == '.xml')} resource files)")
        return
    for path, text in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(f"wrote {path.relative_to(ANDROID_DIR)}")


if __name__ == "__main__":
    main()
