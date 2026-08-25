# Czech ensemble research

Locked research package for the verified Czech weather-model registry.

```powershell
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv sync --project research --frozen
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pytest
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research ruff check .
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research pyright
& 'C:\Users\mates\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m uv run --project research python -m aladin_ensemble.sources.probe --output research/model-registry.json
```

The HTTP-request estimator sends every configured Czech location and required variable in each
endpoint request: two probes per candidate, plus one planned issued-run request per
candidate/date/run. Its formula is `candidates × (2 + dates × runs)`. Locations and variables
remain printed for audit. The guard requires HTTP requests to be strictly below `10,000`;
`10,000` itself fails closed. Provider quota can use fractional request units, so this HTTP count
does not replace checking the provider's current quota terms before a production run.

Successful probes record the endpoint, sorted non-secret request parameters, requested and
provider model IDs, and per-location response metadata. Definitive model exclusions are kept
separately from operational failures. The output is `complete` only when every attempted probe
finished without an operational failure; otherwise it is `incomplete` and the CLI exits nonzero.
