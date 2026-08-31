# Kirazium Dream Displays FFmpeg

This directory builds the first Android playback prototype for Dream Displays 1.9.5.

## Why a custom helper is required

Kirazium Launcher already redirects child processes named `ffmpeg` to the installed
`git.mojo.ffmpeg` helper package. The stock helper starts correctly, but its FFmpeg
configuration has no TLS backend. Dream Displays resolves YouTube and CDN media to
HTTPS URLs, so playback ends with `Unrecoverable stream failure` before a frame is
produced.

The test build keeps the package ID expected by the launcher and adds:

- GnuTLS for HTTPS inputs;
- Android zlib for compressed manifests and responses;
- Android MediaCodec support for the later hardware-decoding client patch;
- ARM64 only, to keep the prototype build and APK smaller.

## Phone installation

The test APK uses a different signing key from the stock MojoLauncher helper. Uninstall
only `MojoLauncher FFmpeg Plugin`, install the generated Kirazium helper APK, then fully
stop and reopen Kirazium Launcher. Do not uninstall Kirazium Launcher or Dream Displays.

