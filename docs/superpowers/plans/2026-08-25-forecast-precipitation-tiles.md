# Forecast precipitation tiles implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce and display a clearly labelled Czech 1–24-hour forecast-precipitation map from reviewed ensemble weights without presenting it as observed radar.

**Architecture:** A Python tile job reads aligned model precipitation on a fixed Czech grid, applies production weights, interpolates only inside the validated domain, and writes a timestamped static manifest plus rolling raster tiles. A static host deploys the latest immutable artifact set. The Android radar WebView adds a separate Forecast tab that validates the manifest and never mixes forecast tiles with ČHMÚ observation frames.

**Tech Stack:** Existing research Python package, NumPy/SciPy, static PNG/WebP tiles, GitHub Actions/Pages proof-of-concept, Android local WebView.

**Spec:** `docs/superpowers/specs/2026-08-25-czech-calibrated-ensemble-design.md`

**Consumes:** reviewed research weights and Android ensemble model registry.

## Global constraints

- Do not deploy until API-call, licence, action-time, storage, bandwidth, and update-latency gates pass.
- Forecast frames always say **Forecast precipitation** and show run age/confidence.
- Keep observed radar and nowcast unchanged.
- Never commit rolling forecast binaries to git history.
- No extrapolation outside the Czech validated grid/domain.
- Missing/stale model data produces an incomplete/degraded manifest, not zero rain.

---

### Task 1: Prove the fixed-grid tile generator

**Files:**
- Create: `research/src/aladin_ensemble/tiles.py`
- Create: `research/src/aladin_ensemble/tile_manifest.py`
- Create: `research/tests/test_tiles.py`
- Create: `research/tests/fixtures/tiles/`

**Interfaces:**
- Produces: 24 hourly frames and `manifest.json` with run/valid times, bounds, zooms, source count, confidence, weight version, hashes, and degraded state.

- [ ] Define a fixed Czech grid and prove point/domain coverage before download.
- [ ] Write RED interpolation, no-data mask, edge, colour-scale, timestamp, and deterministic manifest tests.
- [ ] Blend precipitation using the exact production segment for each lead.
- [ ] Generate one tiny fixture tile pyramid.
- [ ] Verify colour values against legend breakpoints and alpha outside domain.
- [ ] Record execution time, peak memory, bytes, and API-call estimate.
- [ ] Commit `feat(research): generate forecast precipitation tiles`.

---

### Task 2: Add a fail-closed static deployment proof

**Files:**
- Create: `.github/workflows/forecast-tiles.yml`
- Create: `scripts/verify-forecast-tiles.ps1`
- Modify: Pages deployment workflow/config without committing forecast binaries.
- Create: `docs/research/forecast-tile-operations.md`

- [ ] Use a manually dispatched workflow first. Do not enable schedule until one complete run passes budget/licence checks.
- [ ] Download/cache sources, generate into an ephemeral directory, validate every file/hash, then upload a Pages artifact.
- [ ] Deploy manifest last so clients never see partial frames.
- [ ] Retain only the current and previous complete run in the deployment artifact.
- [ ] Add concurrency cancellation so a newer run replaces an older queued run.
- [ ] Record attribution and failure state.
- [ ] Measure public URLs, cache headers, total bytes, action minutes, and API usage.
- [ ] Enable a three-hour schedule only after explicit operational approval.
- [ ] Commit `ci: add forecast tile proof`.

---

### Task 3: Add the Android Forecast precipitation tab

**Files:**
- Modify: `app/src/main/assets/radar.html`
- Modify: `RadarScreen.kt`, five catalogs, and `RadarScreenTest.kt`

- [ ] Add a base-mode switch: Observed and Forecast.
- [ ] Observed retains rain/cloud/lightning and nowcast behaviour.
- [ ] Forecast loads the validated manifest, 1–24-hour slider, tile frames, confidence, run time, validity time, and degraded state.
- [ ] Reject manifest schema/hash/bounds/source-age failures and keep Observed usable.
- [ ] Keep CSP allow-list restricted to the configured static tile origin and ČHMÚ.
- [ ] Add request tokens for manifest and tile races.
- [ ] Localise every new label in five catalogs; preserve lowercase `nowcast`.
- [ ] Verify offline/error/partial/stale/latest and rapid mode-switch flows.
- [ ] Commit `feat(radar): add 24-hour precipitation forecast`.

---

### Task 4: Operational and release gate

**Files:**
- Modify: research report, README, PRIVACY, NOTICE, CHANGELOG, Pages, Play metadata, screenshots, and runbooks.

- [ ] Compare generated forecast map against ČHMÚ radar/rain-gauge holdout using spatial scores.
- [ ] Confirm the tile source and API licence permit the intended Play distribution.
- [ ] Confirm free-tier use remains below limits with expected users; otherwise block rollout and select paid/self-hosted/direct-open-data path.
- [ ] Verify attribution, source age, data sharing, retention, and cache disclosures.
- [ ] Run 72 hours of scheduled freshness monitoring before release.
- [ ] Perform signed API29/API35/Huawei radar/forecast-map acceptance.
- [ ] Require broad final review and green CI.
- [ ] Commit `docs: document forecast precipitation service`.
