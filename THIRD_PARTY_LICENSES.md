# Third-Party Licenses

Mockonomy itself is licensed under the [Apache License 2.0](LICENSE). The components below
are distributed under their own licenses; the full texts are in the [`licenses/`](licenses/)
directory.

## Fonts

### Inter
- Source: https://github.com/google/fonts/tree/main/ofl/inter (variable font, `wght`/`opsz` axes)
- File: `app/src/main/res/font/inter.ttf`
- License: SIL Open Font License 1.1 — commercial use is allowed; when the font is
  distributed, the copyright notice and the license text must accompany it (they are also
  embedded in the font file's `name` table)
- Full text: [`licenses/inter-OFL.txt`](licenses/inter-OFL.txt)

### JetBrains Mono
- Source: https://github.com/google/fonts/tree/main/ofl/jetbrainsmono (variable font, `wght` axis)
- File: `app/src/main/res/font/jetbrains_mono.ttf`
- License: SIL Open Font License 1.1 — commercial use is allowed; when the font is
  distributed, the copyright notice and the license text must accompany it (they are also
  embedded in the font file's `name` table)
- Full text: [`licenses/jetbrains-mono-OFL.txt`](licenses/jetbrains-mono-OFL.txt)

Both are shipped embedded (the Google Fonts downloadable provider is **not** used), so the
app does not need the internet on first launch.

## Icons

### Phosphor Icons
- Source: https://github.com/phosphor-icons/core (version 2.1.1, `assets/regular` and `assets/fill`)
- Files: `app/src/main/res/drawable/ic_ph_*.xml` — 31 icons, converted from SVG to vector
  drawables with [`scripts/phosphor.py`](scripts/phosphor.py)
- License: MIT — the copyright notice and the license text must be included in all
  distributed copies
- Full text: [`licenses/phosphor-MIT.txt`](licenses/phosphor-MIT.txt)

The design uses Phosphor throughout; only the icons in use are embedded (not the whole set).

## Libraries (Gradle dependencies)

This list is not maintained by hand: the [AboutLibraries](https://github.com/mikepenz/AboutLibraries)
Android plugin (build-only, `offlineMode`) generates `raw/aboutlibraries.json` from the release
dependencies. As of September 2026 that is 126 libraries: 124 under Apache-2.0,
`org.slf4j:slf4j-api` under MIT and `androidx.datastore:datastore-preferences-external-protobuf`
(protobuf) under BSD-3-Clause.

- Full texts: [`licenses/apache-2.0.txt`](licenses/apache-2.0.txt) (a copy of the root `LICENSE`),
  [`licenses/slf4j-MIT.txt`](licenses/slf4j-MIT.txt) (the text shipped in the jar),
  [`licenses/protobuf-BSD-3-Clause.txt`](licenses/protobuf-BSD-3-Clause.txt) (upstream protobuf
  `LICENSE` — the androidx copy has no copyright line)
- `strictMode = FAIL` in `app/build.gradle.kts` only accepts Apache-2.0 and the licenses of those
  two libraries. A new license fails the build: add its text here and its mapping to
  `ui/screens/licenses/LibraryDefinitions.kt`.

## In the app

The APK does not carry the contents of this file — the XML comments in the vector drawables are
dropped when AAPT2 compiles them. The notices are shown under **Settings > Open source licenses**;
the `licenses/` directory is embedded as assets.
