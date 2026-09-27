---
paths:
  - "feature/**/*.kt"
  - "core/designsystem/**"
  - "app/src/**/*.kt"
---
# Compose UI rules
- Use design tokens only (`FolioTheme.colors`, `.type`, `.space`, `.shapes`, `.motion`). No raw colors or dp in features except named layout constants.
- Stateless composables with hoisted state. `@Preview` light + dark for every screen-level composable.
- UiState classes are `@Immutable` and use `ImmutableList`/`ImmutableMap`. Stable keys in lazy lists; `Modifier.animateItem()` for reorders.
- Motion only through `FolioMotion` specs (11-design-system.md#motion). Nothing longer than 350 ms.
- Touch targets >= 44 dp; icon buttons need content descriptions.
- The canvas is an `AndroidView` hosting `CanvasHostView`. Never draw document content or ink with Compose state per frame.
- Editor text fields must disable system stylus handwriting (see text-engine rule).
- Every new screen state gets a Roborazzi test (landscape + portrait where layout differs).
- Layout adapts via window size class; test Pad 7 landscape (expanded) and portrait.
- Hardware keyboard: shortcuts go through the editor `ShortcutRegistry`, not ad-hoc `onKeyEvent` handlers.
