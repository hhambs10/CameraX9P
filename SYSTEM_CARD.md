# CameraX9P System Card

**Developer:** hambs  
**Version:** 0.22 public beta  
**Target:** OPPO Find X9 Pro (CPH2791) with Android 17 and ColorOS 17

## Intended use

CameraX9P is an Android camera app for on-device photo and video capture. The interface exposes controls for framing, focus, exposure, zoom, video profile, audio source and optional color processing. It follows the Android system language and supports English, Italian, Spanish, French, German and Hindi. English is the fallback.

## Capture and processing

Android Camera2 supplies the viewfinder and sensor data. The app encodes video locally and publishes finished files via Android MediaStore. On the tested model, Standard 16:9 4K/60 on the main camera uses a GPU relay to feed the encoder and viewfinder from one Camera2 stream. Clip processing and optional SDR LUT application occur on the device. Imported LUTs are selected through the Android document picker. LUTs and Monochrome affect JPEG and MP4 exports when enabled. RAW DNG retains sensor data.

## Data and permissions

Camera permission enables the viewfinder and capture. Microphone permission is requested for video with a selected audio input. Captures are saved to the relevant CameraX9P albums. The release manifest has no Internet permission. CameraX9P has no accounts, analytics, cloud processing or runtime AI features. The generated viewfinder scenes in this repository are used for documentation.

Interactive captures use a private temporary directory. The app clears the session after export. When gallery publishing fails, files already prepared for publishing are available for an explicit retry within 24 hours, subject to Android cache eviction. Imported SDR .cube files are limited to 32 MiB and 8,192 characters per line. Import runs independently of capture, supports cancellation and times out after 15 seconds. The app restores the validated private copy on launch. When the viewfinder leaves the foreground, every recording profile receives a stop signal.

## Validation and limits

The distribution-signed 0.22 APK was tested as an in-place update from 0.21 on CPH2791. The security follow-up used an isolated build on the same device, with checks for capture storage, stalled LUT imports and camera release. Earlier tests verified clean installation, sustained 4K/60 recording and selected photo and video workflows. [Validation results](docs/VALIDATION.md).

Lens and stream capabilities vary across models and firmware. The verified still capture path reaches 12.6 MP. Native OEM high-resolution capture at 50 MP or 200 MP and simultaneous front/rear recording are outside this beta. Slow-motion rates are nominal and actual cadence can vary. The verified scope is the target device above.

## Licensing

Original materials are released under the [PolyForm Noncommercial License 1.0.0](LICENSE). [Project notice](NOTICE.md) and [third-party notices](THIRD_PARTY_NOTICES.md).
