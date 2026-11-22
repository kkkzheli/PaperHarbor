# PaperHarbor · 纸港

**English** · [简体中文](README.zh-CN.md)

A notebook for Android that needs no account, no network and no key. Everything is drawn, typed,
recognised, recorded and converted on the device, and the app declares neither `INTERNET` nor
`ACCESS_NETWORK_STATE`.

PaperHarbor 1.1 is an independent implementation by **kkkzheli**. It is not affiliated with,
licensed by, or derived from Goodnotes; no code, artwork or subscription from that product is used.

## What it does

- **Shelf** — covers show a style and colour you choose for each notebook; every card lists its
  page count, its size on disk and the full date and time it was last saved; search across titles,
  typed page text, imported document text and recognised text; sort by recently edited, title or
  page count.
- **Notebook covers** — four constructions (spine, band, label, plain) and eight colours, picked
  when the notebook is created or later from the long-press sheet, with a live preview.
- **Paper** — five patterns (ruled, grid, dot, blank, Cornell) and six paper tints, chosen per
  notebook and changed per page from *More → Page style*.
- **Pens** — a hard pen with pressure taper, and a soft brush whose width follows real drawing
  speed (measured from event timing, so it reacts to your hand rather than the sample rate) and
  pressure, with a long smooth entry and exit. Curves are refined so a thick stroke stays a
  curve; a translucent highlighter has its own opacity control.
- **Eraser** — remove whole strokes, or erase only the area you sweep across; area erasing cuts
  shapes and thick strokes cleanly, with rounded ends instead of nibbled edges.
- **Shapes and the magic pen** — line, arrow, rectangle, circle, ellipse, triangle, diamond,
  pentagon, star and hexagon; the magic pen turns a rough sketch into a straight line, circle,
  ellipse, rectangle, diamond or triangle, and its chooser can force the target shape instead of
  guessing. It keeps your own stroke when nothing matches.
- **Text blocks** — tap the page with the text tool to place editable text with four typefaces,
  size, colour, bold, italic, underline and left/centre/right alignment. *Formula* typesets the
  LaTeX in the box — stacked fractions, radicals whose overline covers the radicand, and limits
  above and below `\sum`, `\prod` and `\lim` — in the chosen typeface. *Scan* reads the page with
  the bundled OCR, *Translate* hands the text to another installed app.
- **Pictures** — insert a photo or screenshot into the page, move it, duplicate it or delete it.
- **Roam** — a dedicated mode where one finger moves the page, a double tap zooms and a pinch
  works as usual, so a dense page can be inspected without switching tools.
- **Lasso** — select strokes, then drag to move, pull a corner to scale proportionally, or use the
  grip above the selection to rotate; duplicate and delete live in a small bubble.
- **Palette** — hue/saturation wheel, brightness slider, eight swatches and recent colours.
- **Pages** — thumbnails, bookmarks, patterns and tints, duplicate, reorder, delete, jump to the
  next bookmark.
- **Text and OCR** — typed page text is searchable; printed horizontal Simplified Chinese and
  English are recognised on-device by Tesseract4Android with the bundled `chi_sim` and `eng` models.
- **Study and audio** — two-sided flashcards with self-rating, and local AAC recordings.
- **Import** — PDF, images, `.docx` and `.phnote`. A PDF or Word document becomes a notebook whose
  original content stays read-only and is annotated with ink, text blocks and pictures.
- **Export and share** — PDF, one page as PNG (saved straight into `Pictures/PaperHarbor` on
  Android 10+, no picker and no permission), an editable `.phnote`, or a `.docx` whose pages are
  the rendered pages with your annotations. The share sheet sends any of them to another installed
  app through a read-only provider.
- **Settings** — light/dark/system theme, six accent colours, language (system, Chinese, English),
  stylus-only drawing, pressure sensitivity, highlighter opacity, area-erase default, new-note
  paper and cover defaults, storage usage, preview-cache clearing, and links to the author, the
  repository and the releases page.
- **Languages** — Simplified Chinese and English, following the device language or chosen in
  Settings.

## Formats

| Format | Import | Annotate | Export |
|---|---|---|---|
| `.phnote` | yes, creates a new notebook | yes, ink stays editable | yes — the editable master copy |
| PDF | yes, the pages are rendered | yes | yes — flattened PDF that keeps the original page format |
| `.docx` | yes, the text is laid out into pages | yes | yes — `.docx` with each annotated page as a page image |
| PNG / image | yes, as its own notebook page | yes | yes — current page as PNG |
| `.doc` (legacy) | no — save it as `.docx` or PDF first | — | — |

PaperHarbor never edits the text or layout of an imported document. The original file is not
modified either: annotation happens on top, and the export creates a new file.

## Boundaries

- Translation is not built in: *Translate* opens the system share sheet so another app (if you
  installed one) can translate the text. This app itself never goes online.
- A `.docx` import keeps readable text, not Word's layout: tables are flattened, images and styling
  are dropped, pagination is recalculated for the page size.
- A PDF or `.docx` export flattens annotations. Only `.phnote` can be reopened and edited as ink.
- `.goodnotes` documents are **not** imported. Export old material to PDF and annotate that.
- No account, cloud sync, collaboration, content store, remote AI, Office conversion, synced
  handwriting playback or speech transcription.
- **Export a backup before uninstalling.** There is no cloud copy, and `.phnote` is not encrypted —
  it contains your writing and recordings, so store it carefully.

## Install

Download `PaperHarbor-1.1.2.apk` from the releases page and sideload it. It is a release build
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
in [docs/verification-1.1.2.md](docs/verification-1.1.2.md).

## Licence and attribution

Application code, vector icons and the launcher artwork are original work by **kkkzheli**. The OCR
engine, its language data and their dependencies keep their own licences — see
`app/src/main/assets/ocr/NOTICE.txt` and the `licenses/` folder. This repository contains no real
user notes, no recordings, no signing keys and no copy of the original iOS app.
