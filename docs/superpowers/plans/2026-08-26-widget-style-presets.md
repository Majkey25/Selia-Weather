# Widget style presets implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add launcher-safe Minimal, Material, Pixel, and Cupertino widget presets plus font selection without breaking manual per-widget customization or resize behavior.

**Architecture:** Presets are pure transformations of the existing `WidgetSettings`. The provider persists the resulting values per widget and applies only platform fonts through text appearances supported by `RemoteViews`. The editor preview consumes the same style value.

**Tech stack:** Kotlin, Compose Material 3, Android `RemoteViews`, XML text appearances, JUnit.

**Spec:** `docs/superpowers/specs/2026-08-24-aladin-weather-adaptive-radar-widget-design.md`

## Global constraints

- Preserve Android 10 support.
- Keep manual colors, opacity, image, field visibility, text size, and alignment.
- Do not bundle or download proprietary Google or Apple fonts.
- Keep the Store icon unchanged.
- Verify compact, standard, tall, and wide launcher sizes.

### Task 1: Add typed presets and font styles

**Files:** `WidgetSettings.kt`, `WidgetSettingsTest.kt`

- [ ] Write failing preset and migration tests.
- [ ] Add `WidgetPreset`, `WidgetFontStyle`, and `widgetPresetSettings()`.
- [ ] Keep unknown stored font values on the system default.
- [ ] Run focused tests.

### Task 2: Persist and render font styles

**Files:** `WeatherWidgetProvider.kt`, `themes.xml`, `widget_adaptive.xml`

- [ ] Persist `font_style` per widget.
- [ ] Apply system, medium, rounded, and light text appearances through `RemoteViews`.
- [ ] Keep text colors and sizes controlled by the existing settings.
- [ ] Run widget and resource tests.

### Task 3: Add the preset-first editor

**Files:** `WidgetEditorScreen.kt`, five string catalogs

- [ ] Add a compact preset row above advanced controls.
- [ ] Add font chips under Layout.
- [ ] Keep every manual control available after applying a preset.
- [ ] Verify preview/provider parity in every size.

### Task 4: Runtime acceptance

- [ ] Install on Huawei.
- [ ] Add a real launcher widget and resize through compact, standard, tall, and wide.
- [ ] Confirm no `Error loading widget`, clipping, stale dimensions, or provider exception.
