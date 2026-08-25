# Czech ensemble research

Locked research package for the verified Czech weather-model registry.

```powershell
uv sync --project research --frozen
uv run --project research pytest
uv run --project research ruff check .
uv run --project research pyright
```

`python -m aladin_ensemble.sources.probe --output model-registry.json` performs a
small live coverage and archive probe. It stops before any large download when
the configured request budget exceeds the Open-Meteo Free API limit.
