# Changelog

## 0.2.0 — 2026-08-29

### Added

- Configurable hardware recording key and double/triple-press actions.
- TalkBack-safe shortcut scheme that never requires holding both Android volume keys.
- Camera switching while the recording key remains held, with automatic continuation.
- Import of existing device videos into the local gallery.
- Per-video timestamp overlay editor with drag, pinch, slider, presets, styles, and on/off.
- Safe regeneration of completed videos after title, caption, or timestamp changes.

### Changed

- Visible application name is now Vibe.now.
- Primary camera controls moved to the lower, thumb-reachable region.
- Android UI now follows Material 3 conventions.
- iOS UI now follows SwiftUI / SF Symbols / native button conventions.
- Embedded metadata namespace moved to `app.vibenow.*` schema version 2.

### Compatibility

- Android application ID, iOS bundle ID, target names, and original on-device storage paths remain unchanged so v0.1 test data can be upgraded in place.
