# NAScroll

## About NAScroll

NAScroll is a read-only Android viewer designed for securely browsing digital content stored on a Synology NAS.

It provides reader-focused viewing for web novels, webtoons, and business documents, including TXT, EPUB, JPG, PNG, and PDF files. Content is streamed directly from the NAS. The app does not provide user-facing permanent downloads, sharing, editing, or deletion of NAS content; temporary cache files may be created by Android to support reading and are removed by the system or when the app cache is cleared.

NAScroll is intended for private, non-commercial use, especially by digital content professionals who need a convenient way to review internal files on mobile devices.

The app is not an official Synology product.

**Current version:** `1.2.1` (`versionCode 4`)

> This project is currently maintained as a personal-use application. It is not an official Synology product and has not been tested against every DSM, Android, or EPUB combination.

## Features

- Sign in to a Synology NAS using the DSM File Station API
- Browse shared folders and nested directories with natural sorting
- Read web novels, webtoons, and business documents in image folders (JPG/PNG), EPUB, TXT, and PDF formats
- EPUB table of contents, chapter navigation, reflowed page turns, bookmarks, highlights, and reading-position restore
- Reader appearance controls for theme, font, size, margins, line spacing, alignment, brightness, and tap zones
- TXT paged or continuous reading modes
- PDF page navigation or continuous scrolling
- Local reading notes, bookmarks, highlights, and progress
- Temporary on-device caching for smoother reading; no user-facing permanent download workflow

## Requirements

### For users

- Android 7.0 (API 24) or newer
- A Synology NAS with File Station WebAPI enabled and reachable from the device
- A DSM account with read permission for the folders to be browsed

### For development

- JDK 17
- Android SDK 34
- Gradle Wrapper (included in this repository)
- Android Studio Hedgehog or newer is recommended

## Installation

1. Download the current APK from the [NAScroll 1.2.1 GitHub Release](https://github.com/rolloll/NAScroll/releases/tag/v1.2.1), or build one locally as described below.
2. Install the APK on an Android 7.0+ device. Sideloading may require enabling installation from the file manager used to open the APK.
3. Enter the NAS base URL, DSM account, and password on the login screen.
4. Browse to a file and select it to open the appropriate reader.

The NAS URL must start with `http://` or `https://`. HTTPS is strongly recommended when the NAS is accessed outside a trusted local network.

## App updates

When the app starts, it checks GitHub at most once per day for `update.json`. If its `versionCode`
is newer than the installed app, a dialog opens with a button that launches the APK download URL.
Network failures are ignored so offline reading and NAS access are not interrupted.

To publish an update, increment `versionCode` and `versionName` in `app/build.gradle.kts`, build the
APK, upload it to the repository, and update `update.json` on the default branch. Keep the JSON
`versionCode` equal to the APK's Android version code and set `apkUrl` to the public GitHub download
URL. The APK must be signed with the same key as the installed app for an in-place Android update;
the included debug APK is intended for local sideloading only.

## Reader usage

### General

- Use the reader settings button to change typography, color theme, margins, spacing, alignment, and brightness.
- Use the viewer settings button to configure tap zones and paging behavior.
- Reading progress is saved per file on the device.

### EPUB

- Open the table of contents to jump between chapters.
- Tap the configured page edge to move between reflowed pages; chapter edges move to the adjacent chapter.
- Select text and use the highlight action to save a highlight.
- Use the bookmark/notes action to save the current position and revisit bookmarks or highlights.

EPUB files are reflowed by Android WebView. DRM-protected books, malformed XHTML, and books that depend on unsupported browser features may not render correctly.

### TXT and PDF

- TXT files support continuous scrolling and paged reading.
- PDF files support page-by-page viewing and continuous scrolling. PDF rendering is provided by Android's built-in `PdfRenderer`.

## Build

Clone the repository and run the Gradle Wrapper from the project root:

```bash
./gradlew :app:assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/NAScroll-debug.apk
```

For a release build:

```bash
./gradlew :app:assembleRelease
```

Before distributing a release APK, configure a private signing key in the local Gradle environment. Never commit a keystore, passwords, or `local.properties` to GitHub. The repository intentionally does not contain release signing configuration.

With the current repository configuration, the release APK is **unsigned** and cannot be installed or published until it is signed with the maintainer's private key.

## Project structure

```text
app/src/main/java/com/feelyeon/nasviewer/
  LoginActivity.kt       NAS login and session setup
  SynologyApi.kt         File Station API client
  BrowserActivity.kt     NAS folder and file browser
  EpubParser.kt          EPUB container, OPF, spine, and NCX parsing
  EpubReaderActivity.kt  EPUB WebView reader and pagination
  TextReaderActivity.kt  TXT reader
  PdfReaderActivity.kt   PDF reader
  AnnotationDb.kt        Local bookmarks, highlights, and progress
  Prefs.kt               Local app preferences
app/src/main/res/         Layouts, themes, icons, and reader resources
```

## Privacy and security

- NAS credentials are stored in plain Android `SharedPreferences` so the app can keep the user signed in. This is a deliberate personal-use trade-off, not secure credential storage for a shared or rooted device.
- Use a dedicated DSM account with the minimum read permissions required. Do not use a DSM administrator account unless necessary.
- Prefer HTTPS with a certificate trusted by the device. Do not expose DSM directly to the public internet without appropriate firewall, VPN, and account controls.
- The app uses the NAS for read-only File Station operations (login, list, thumbnail, and stream/download-to-cache). Bookmarks, highlights, and reading progress stay in the app's local SQLite database and are not written back to the NAS.
- Temporary reader cache files may remain on the device until Android removes them or the app cache is cleared. Treat the device cache as sensitive if the content is sensitive.
- Two-factor authentication flows and DRM-protected content are not supported by this release.

## Known limitations

- EPUB pagination is dynamic and can vary with screen size, Android WebView version, font availability, and reader settings.
- Complex EPUB CSS, embedded scripts, unusual fonts, ruby annotations, malformed markup, and fixed-layout EPUBs may require additional compatibility work.
- There is no cross-device synchronization for settings, progress, bookmarks, or highlights.
- The app depends on Synology File Station API behavior and network availability. NAS login, TLS, reverse-proxy, firewall, and permission errors must be diagnosed on the NAS/network side.

## Development workflow

1. Keep credentials, NAS URLs, APKs, keystores, and machine-specific files out of commits.
2. Make focused changes and document user-visible behavior in `CHANGELOG.md`.
3. Run `./gradlew :app:assembleDebug` before opening a pull request.
4. Test on at least one Android 7.0+ device and one recent Android device when changing readers or WebView pagination.

## License

No open-source license has been selected yet. Until a license is added by the copyright holder, the source remains under default copyright and should not be redistributed or reused as if it were MIT/Apache/GPL licensed.

## Release checklist

- Update `versionCode` and `versionName` in `app/build.gradle.kts`.
- Add release notes to `CHANGELOG.md`.
- Run a clean debug/release build and smoke-test login, browsing, and each reader type.
- Verify that no credentials, NAS-specific URLs, keystores, or generated APKs are staged.
- Create a GitHub Release with a signed APK only after reviewing the signing and privacy implications.
