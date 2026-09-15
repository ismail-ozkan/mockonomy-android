#!/usr/bin/env python3
"""Phosphor SVG -> Android vector drawable."""
import re
import sys
import urllib.request
from pathlib import Path

VERSION = "2.1.1"
BASE = f"https://unpkg.com/@phosphor-icons/core@{VERSION}/assets"
OUT = Path(sys.argv[1])

REGULAR = [
    "arrow-left", "arrow-right", "arrows-left-right", "brackets-curly",
    "caret-down", "caret-right", "check-circle", "circle-dashed",
    "clipboard-text", "copy", "dots-three-vertical", "file-arrow-up",
    "file-code", "funnel", "git-branch", "info", "magnifying-glass",
    "plus", "pulse", "question", "stack", "swap", "warning", "x",
]
FILL = ["play", "stop", "plugs-connected", "check-circle", "x-circle"]

# Directional icons must be mirrored in RTL (the manifest sets supportsRtl=true).
AUTO_MIRRORED = {"arrow-left", "arrow-right", "caret-right", "arrows-left-right"}

PATH_RE = re.compile(r"<path\b([^>]*)/>")
ATTR_RE = re.compile(r'([a-z-]+)="([^"]*)"')

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Phosphor Icons {version} · {weight}/{name} · MIT — see THIRD_PARTY_LICENSES.md -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="256"
    android:viewportHeight="256"{mirrored}>
{paths}
</vector>
"""


def fetch(url: str) -> str:
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode("utf-8")


def convert(name: str, weight: str) -> str:
    slug = name if weight == "regular" else f"{name}-fill"
    svg = fetch(f"{BASE}/{weight}/{slug}.svg")
    paths = []
    for attrs in PATH_RE.findall(svg):
        parsed = dict(ATTR_RE.findall(attrs))
        data = parsed.get("d")
        if not data:
            raise SystemExit(f"{slug}: <path> without d=")
        lines = [
            "    <path",
            f'        android:pathData="{data}"',
            '        android:fillColor="#FF000000"',
        ]
        if parsed.get("fill-rule") == "evenodd":
            lines.append('        android:fillType="evenOdd"')
        lines[-1] += " />"
        paths.append("\n".join(lines))
    if not paths:
        raise SystemExit(f"{slug}: no <path> found")
    return TEMPLATE.format(
        version=VERSION,
        weight=weight,
        name=slug,
        mirrored='\n    android:autoMirrored="true"' if name in AUTO_MIRRORED else "",
        paths="\n".join(paths),
    )


def main() -> None:
    written = []
    for weight, names in (("regular", REGULAR), ("fill", FILL)):
        for name in names:
            suffix = "" if weight == "regular" else "_fill"
            file_name = f"ic_ph_{name.replace('-', '_')}{suffix}.xml"
            (OUT / file_name).write_text(convert(name, weight))
            written.append(file_name)
    print(f"{len(written)} drawable written")


main()
