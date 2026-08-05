# ScoreSync — Table Tennis Video Scoring & Export

[![Java CI](https://github.com/yejinghua32/scoresync/actions/workflows/ci.yml/badge.svg)](https://github.com/yejinghua32/scoresync/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A local table tennis video scoring and export tool: mark scores in-browser with live preview, render videos to scoreboard-overlay MP4s. Single-process (Web UI + SQLite + external FFmpeg), no auth, no cloud.

## Features

- Import local match videos
- Keyboard scoring during playback
- Automatic time-coding of each score
- Post-edit score timing
- Multiple scoreboard templates
- One-click MP4 export with score overlay
- Fully local — no internet required

## Environment

- Java 17
- Maven 3.9+
- Optional: FFmpeg / FFprobe (only needed for MP4 export)

## Run

```powershell
$env:SCORESYNC_DATA_DIR = 'C:\ScoreSyncData'
mvn spring-boot:run
# or: mvn clean package && java -jar target\score-sync-0.0.1-SNAPSHOT.jar
```

Open `http://localhost:5574/`, add a video directory, scan/select a video, create a project, and score with A/D in the editor.

## Keyboard Shortcuts

| Key | Action |
|-----|--------|
| A | Player A scores |
| D | Player B scores |
| Z | Undo last score |
| Space | Play / Pause |
| ← / → | Seek (default 1 sec, configurable via `scoresync.editor.seek-seconds`) |

## Data & Backup

SQLite database lives in `SCORESYNC_DATA_DIR` (default `${user.home}/.scoresync`); videos are registered by path and **never copied**. Export JSON from the editor to backup, import from the video library to restore. Import requires the same video registered with matching size + mtime first.

## Scoreboard Templates & MP4 Export

FFmpeg/FFprobe must be on `PATH` or set via:

```powershell
$env:SCORESYNC_FFMPEG_PATH = 'C:\ffmpeg\bin\ffmpeg.exe'
$env:SCORESYNC_FFPROBE_PATH = 'C:\ffmpeg\bin\ffprobe.exe'
```

FFmpeg is not required at startup — only checked when exporting.

**Workflow:**
1. Select a template (`CLASSIC` or `MODERN`) in the editor's export panel — preview updates live.
2. Play/pause/seek to see the preview refresh with authoritative server state.
3. Click "Start Export" — only one render job allowed at a time (409 + `RENDER_JOB_ACTIVE` otherwise).
4. Progress shown during render; cancel cleans up temp files.
5. On success, download H.264/AAC MP4 matching source resolution/framerate.

Exports go to `${SCORESYNC_DATA_DIR}/exports`, workdir is `${SCORESYNC_DATA_DIR}/render-work`.

## Phase 1 Limits

- Formats: `.mp4` / `.webm` / `.mov` only
- No transcoding — playback depends on browser codecs
- No custom hotkeys
- No cloud sync
