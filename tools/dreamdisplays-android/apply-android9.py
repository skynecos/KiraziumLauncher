#!/usr/bin/env python3
from pathlib import Path
from urllib.request import urlopen

# Compatibility shim for the subtitle-stable Dream Displays branch.
# That branch already uses the ABI-stable numeric SWS_BILINEAR value (2), while
# the original Android9 patch expects the older ffmpeg-next bindgen spelling.
# Convert only that exact line before running the proven Android9 patch body.
scale = Path.cwd() / "native/lav/src/scale.rs"
text = scale.read_text()
old = "const SCALE_FLAGS: c_int = ffi::SwsFlags::SWS_BILINEAR as c_int;"
stable = "const SCALE_FLAGS: c_int = 2;"
patched = "const SCALE_FLAGS: c_int = ffmpeg::software::scaling::Flags::BILINEAR.bits();"

if stable in text:
    scale.write_text(text.replace(stable, old, 1))
elif old not in text and patched not in text:
    raise SystemExit("Unsupported SCALE_FLAGS form in native/lav/src/scale.rs")

# Execute the last known-good Android9 patch implementation verbatim. Pinning
# this URL avoids recursion after this compatibility wrapper becomes branch HEAD.
url = (
    "https://raw.githubusercontent.com/skynecos/KiraziumLauncher/"
    "1d3ea87d183d5b6afc6727db7f9f8741fc3d9506/"
    "tools/dreamdisplays-android/apply-android9.py"
)
source = urlopen(url, timeout=30).read().decode("utf-8")
exec(compile(source, url, "exec"), {"__name__": "__main__"})
