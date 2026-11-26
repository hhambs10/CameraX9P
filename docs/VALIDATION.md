# CameraX9P 0.22 beta validation

Tested on November 26, 2026 on an OPPO Find X9 Pro (CPH2791), Android 17, ColorOS 17, firmware `17.0.1.201(EX01)`.

## Signed 0.22 APK

| Check | Result |
| --- | --- |
| Installation | Updated the installed 0.21 beta in place with the existing release certificate. Android retained the original installation timestamp. |
| APK identity | The file installed on the phone matched the SHA-256 of the release asset. |
| JPEG | Published a 4096×3072 JPEG in the CameraX9P album. |
| Video after leaving the viewfinder | Pressing Home during Standard recording produced a published 4096×3072 MP4 lasting 4.47 seconds. |
| Build | Built from the packaged source tree. Android Lint and all 46 core checks passed. |

File SHA-256: `eb5f9164c4440f9afde752017d29150eab34a82bd232eabbf1ce52b071ab1530`. The public signing certificate SHA-256 fingerprint is `8f9012ed5c818dc1382000286b968cf6f34f0c01fa4d4c5caab76f28818b87e8`.

## Security follow-up on an isolated build

- Xpan RAW+JPEG and video export left the temporary capture directory empty. A simulated pending publish was recovered after reopening the app, and expired samples were cleaned up.
- A stalled LUT provider left photo capture available. Its read was interrupted at the 15-second deadline and also by manual cancellation. An unknown-length stream exceeding 32 MiB was rejected. A valid LUT loaded afterwards.
- In six short tests across Standard, Standard 4K/60, Slow Motion, Super Slow Motion, Time-lapse and Star Time-lapse, Camera2 confirmed closure 171–849 ms after the capture screen paused. The time of the last sensor frame was outside this measurement.
- The full-gallery test was deferred.

## Earlier validation on the same model

Earlier builds passed clean installation, capture with audio, opening a saved MP4 in OPPO Photos and a sustained Standard 4K/60 recording. That recording lasted 10 min 9 s at an average 59.87 fps. Earlier feature checks covered focus reset, Xpan RAW + JPEG, self-timer, bracketing, Polaroids, burst, 720p, OG mode, Action, LUT export, Horizon Lock, Time-lapse and both slow-motion profiles. Camera features and performance can vary with firmware, lens and hardware.
