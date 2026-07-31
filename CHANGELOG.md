# Changelog

## 1.2.4 - 2026-07-31

### Fixed

- TXT 페이지 모드의 텍스트 선택 메뉴에도 하이라이트 기능을 연결했습니다.

## 1.2.3 - 2026-07-31

### Added

- EPUB 텍스트를 길게 눌러 선택한 뒤 하이라이트를 저장할 수 있습니다.
- TXT 텍스트 선택 메뉴에서 하이라이트를 저장할 수 있습니다.
- EPUB 상단 하이라이트 목록 버튼으로 현재 파일의 하이라이트를 모아 볼 수 있습니다.
- 독서 노트에서 하이라이트와 책갈피를 개별 삭제할 수 있습니다.

## 1.2.2 - 2026-07-31

### Fixed

- 자동 로그인 직후 업데이트 확인이 취소되어 알림이 표시되지 않던 문제를 수정했습니다.
- 설정 화면에서 `업데이트 확인`을 눌러 즉시 최신 버전을 확인할 수 있습니다.

## 1.2.1 - 2026-07-31

### Added

- `뷰어 설정`에 `스크롤` 선택지를 추가했습니다.
- 스크롤을 선택하지 않은 페이지 넘김 모드에서는 EPUB 본문 스크롤을 차단합니다.

### Fixed

- 밝은 설정 시트에서 너무 밝게 보이던 UI 글자 색상을 진한 색으로 조정했습니다.

# 1.2.0 - 2026-07-31

### Added

- GitHub update check once per day when the app starts
- Update dialog with a direct APK download link and release notes
- `update.json` release metadata for automated version comparison

### Notes

- The update check is best-effort and does not block offline use or NAS access.
- The version bump is `versionCode 3`, `versionName 1.2.0`.

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
