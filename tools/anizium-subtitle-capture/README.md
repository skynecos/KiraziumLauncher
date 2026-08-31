# Kirazium Anizium Subtitle Capture

Android helper used to open Anizium in an embedded WebView, let the user sign in normally, and capture only the media metadata returned by the authorized `/anime/source` response.

The helper intentionally does **not** store or expose account passwords, API keys, Authorization headers, cookies, or other session secrets. It exports only sanitized playback metadata:

- selected video URL candidates (`groups[].items[].link`)
- subtitle entries (`subtitles[].name`, `group`, `link`)
- episode identifiers when present

The next step is to feed the selected MP4 + Turkish subtitle into a separate FFmpeg hardsub pipeline.
