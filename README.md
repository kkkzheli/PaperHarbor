# PaperHarbor · 纸港

[English](README.md) · **简体中文**

A notebook for Android that needs no account, no network and no key. Everything is drawn, typed,
recognised and recorded on the device, and the app declares neither `INTERNET` nor
`ACCESS_NETWORK_STATE`.

PaperHarbor 1.0 is an independent implementation by **kkkzheli**. It is not affiliated with,
licensed by, or derived from Goodnotes; no code, artwork or subscription from that product is used.

## What it does

- **Shelf** — cover thumbnails, title, page count and date for every notebook; search across
  titles, typed page text and recognised text; import PDF, images or a PaperHarbor backup; sort by
  recently edited, title or page count.
- **Pens** — a hard pen with pressure taper, a soft brush whose width follows speed and pressure,
  and a translucent highlighter. Tapping the active pen button opens the chooser and the width
  slider with a live sample.
- **Eraser** — remove whole strokes, or erase only the area you sweep across. Both modes share a
  radius control with a preview.
- **Shapes and the magic pen** — line, arrow, rectangle, circle, ellipse, triangle, diamond,
  pentagon, star and hexagon from one chooser; the magic pen turns a rough sketch into a straight
  line, a circle, an ellipse, a rectangle or a triangle, and keeps your own stroke when nothing
  matches.
- **Lasso** — select strokes, then drag to move, pull a corner to scale proportionally, or use the
  grip above the selection to rotate. A small bubble offers duplicate and delete, and *Select all
  ink* lives in the More sheet.
- **Palette** — hue/saturation wheel with a separate brightness slider, eight swatches that can be
  long-pressed to store the current colour, and a recent-colours row.
- **Pages** — thumbnail grid, bookmarks, ruled/grid/blank templates, duplicate, reorder, delete,
  and jump to the next bookmark.
- **Text and OCR** — typed page text is searchable; printed horizontal Simplified Chinese and
  English are recognised on-device by Tesseract4Android with the bundled `chi_sim` and `eng` models.
- **Study and audio** — two-sided flashcards with self-rating, and local AAC recordings that can be
  replayed or removed. The microphone is requested only when you start recording.
- **Export and backup** — PDF, a single page as PNG, or a `.phnote` archive that keeps ink, text,
  cards and recordings editable. Restoring creates a new notebook and never overwrites the original.
- **Settings** — light, dark or system theme, stylus-only drawing, pressure sensitivity, area-erase
  default, paper for new notes, local storage usage, and links to the author and this project.
- **Languages** — Simplified Chinese and English, following the device language.

## Boundaries

- `.goodnotes` documents are **not** imported. Export old material to PDF and annotate that.
- No account, cloud sync, collaboration, content store, remote AI, Office conversion, synced
  handwriting playback or speech transcription.
- A PDF export flattens pages and annotations; it is not a substitute for the editable `.phnote`
  backup. OCR text is not written as an invisible text layer into exported PDFs.
- **Export a backup before uninstalling.** There is no cloud copy, and `.phnote` is not encrypted —
  it contains your writing and recordings, so store it carefully.

## Install

Download `PaperHarbor-1.0.0.apk` from the releases page and sideload it. It is a release build
(`debuggable=false`) signed with a locally generated key; the package is
`io.github.kkkzheli.paperharbor` and Android 8.0 (API 26) or newer is required.

The signing key and its password are never uploaded — only the public certificate travels inside
the APK. To build your own copy with your own key, see [docs/building.md](docs/building.md) and the
wiki page *Build and signing*.

## Build

Requires JDK 17 or newer, Android SDK Platform 34 and Build Tools 34, with `ANDROID_HOME` or a
`sdk.dir` entry in `local.properties`.

```powershell
.\gradlew.bat --offline assembleDebug assembleRelease
```

```powershell
python -I .\tools\verify-apk.py .\app\build\outputs\apk\release\app-release.apk --build-tools "$env:ANDROID_HOME\build-tools\34.0.0"
```

`verify-apk.py` fails unless the packaged permissions are exactly `RECORD_AUDIO`, checks the
signature and prints the SHA-256. Instrumentation tests run on a dedicated device or emulator with
`connectedDebugAndroidTest`; `tools/offline-smoke.py` drives the real UI in airplane mode and
`tools/layout-check.py` checks portrait, landscape and 360 dp. Acceptance notes for this build are
in [docs/verification-1.0.0.md](docs/verification-1.0.0.md).

## Licence and attribution

Application code, vector icons and the launcher artwork are original work by **kkkzheli**. The OCR
engine, its language data and their dependencies keep their own licences — see
`app/src/main/assets/ocr/NOTICE.txt` and the `licenses/` folder. This repository contains no real
user notes, no recordings, no signing keys and no copy of the original iOS app.
