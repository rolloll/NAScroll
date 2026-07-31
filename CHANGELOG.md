# Changelog

## 1.1.0 - 2026-07-31

### Added

- Reader appearance controls for EPUB, TXT, and PDF readers
- Reader themes, font family, font size, margins, line spacing, alignment, brightness, and tap-zone settings
- EPUB table of contents, bookmarks, highlights, and reading notes
- TXT continuous/paged mode and PDF continuous/page mode
- Cache staleness checks using NAS file size and modification time

### Fixed

- EPUB page turns now snap to complete rendered lines instead of cutting through a line at the viewport edge
- EPUB pagination ignores zero-width whitespace fragments and merges multiple rectangles from the same visual line
- EPUB chapter-end paging now advances to the next chapter instead of stopping at the final page margin
- EPUB reader appearance changes invalidate cached line geometry after text reflow
- EPUB page turns preserve fractional line coordinates and keep the top mask fixed, preventing the first line of a new page from being hidden

### Notes

- This is a personal-use release and has not been tested against every EPUB layout or DSM configuration.
- The version bump is `versionCode 2`, `versionName 1.1.0`.
