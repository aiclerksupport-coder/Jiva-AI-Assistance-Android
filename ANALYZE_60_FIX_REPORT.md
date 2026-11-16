# Jiva AI — 60 Analyzer Diagnostics Patch

Input: `FAILED_20261117_015723_05_flutter_analyze.log`, with 60 reported analyzer diagnostics.

## Patch scope
- `FilePicker.platform.pickFiles` moved to v11 static `FilePicker.pickFiles`.
- Merged duplicate `Scaffold.floatingActionButton` declarations into one conditional column, retaining task review and existing phone workflow buttons.
- Moved speech durations into `SpeechListenOptions`.
- Switched deprecated `LlamaEngine.loadModel` to `setModel(LlamaModel(ModelSource.path(...)))`.
- Replaced deprecated `DropdownButtonFormField.value` with `initialValue`.
- Applied braces for previously unbraced `if` bodies in affected Dart source files (including some additional braces in the same files, without intended behavioral changes).
- Updated null-aware collection element, removed unused imports, and used an initializing formal.

No lint rule was disabled. Original modules were preserved; only the Dart files mentioned by the analyzer log were edited.

## Validation
- All 60 issue locations were mapped to source patches.
- ZIP CRC/integrity and original-to-modified SHA-256 manifest checked.
- **Flutter analyze / tests / Android APK compilation were not executable here.** Do not treat this as 60 confirmed cleared issues or a release-ready APK.
- On Windows run `powershell -NoProfile -ExecutionPolicy Bypass -File ".\BUILD_FULL.ps1"` from this root. It will stop with a stage-specific failure log if analyzer or tests fail.
