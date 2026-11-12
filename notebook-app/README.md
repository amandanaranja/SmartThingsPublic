# Guernsey Hill Notebook

An Android notebook that is two layers: hers (pen, stickers, notes, photos, paper, fonts, and where
the boxes sit) and the Day Book's (the day's information, poured into those boxes every day whether
or not she decorated). This folder is the **pen-feel test**: one page, native low-latency ink, a
sample day in movable frames, and the decorating tools. No sync, no accounts, no scheduled tasks yet.

## What is here

- `app/src/main/java/com/guernseyhill/notebook/page/NotebookView.kt` — the page: paper, content
  frames, finished ink, stickers, live stroke. Routes the stylus to ink and fingers to everything else.
- `page/ContentLayer.kt` — the day's information drawn into frames; frames move, resize and hide in
  "Boxes" mode.
- `page/StickerLayer.kt` — emoji, sticky notes, photos and washi tape; one finger drags, two turn and grow.
- `ink/InkTools.kt` — brushes (pressure pen, marker, highlighter), palette, and the stroke save format.
- `model/PageModel.kt` — what is saved per day, as JSON in app storage.
- `ui/NotebookScreen.kt` — the Compose chrome: date bar, tool rows, trays.
- `app/src/main/assets/sample_day.json` — the stand-in for what the overnight runs will write.

## Building

Needs the Android SDK (platform 35, build-tools 35) and JDK 17+. Then:

    gradle :app:assembleDebug

The APK lands in `app/build/outputs/apk/debug/`. Install it on the tablet with "install from file".

## Stack

Kotlin, Jetpack Compose for the chrome, classic Views for the page, and `androidx.ink` for the pen
(front-buffered low-latency rendering, pressure and tilt, motion prediction).
