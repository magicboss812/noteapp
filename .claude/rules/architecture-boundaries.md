---
paths:
  - "app/**/*.kt"
  - "core/**/*.kt"
  - "feature/**/*.kt"
  - "**/build.gradle.kts"
---
# Architecture boundaries (violations fail review)
Allowed module dependencies:
- `core:common` -> nothing internal. JVM module (A-002), no `android.*`.
- `core:model` -> core:common. JVM module, no `android.*`.
- `core:format` -> core:model, core:common. JVM module, no `android.*`.
- `core:storage` -> core:format, core:model, core:common.
- `core:ink` -> core:model, core:common.
- `core:text` -> core:model, core:common.
- `core:render` -> core:model, core:ink, core:text, core:common.
- `core:pdf` -> core:render, core:format, core:model, core:common.
- `core:designsystem` -> core:common.
- `core:testing` -> core:model, core:common. JVM module (A-002), used only via `testImplementation`.
- `feature:*` -> any core module. Never another feature.
- `app` -> everything. Contains wiring, navigation, Application, debug automation only.

Inside features: `ui/` (stateless composables) -> `state/` (ViewModel, UiState, reducers) -> core APIs.
- `EditorSession` (feature:editor) owns the document StateFlow, UndoManager, selection, tool state.
- The canvas host view talks to the session only through the narrow `CanvasController` interface. It never sees a ViewModel.
- Documents change only through `EditCommand`s executed by the session. No direct mutation, no second source of truth.
- DI: Hilt, constructor injection, one Hilt module per core library. No service locators, no global singletons outside Hilt.
- New module or new cross-module dependency: AMENDMENTS entry + update `docs/architecture/02-modules.md` first.
