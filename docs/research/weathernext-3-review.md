# WeatherNext 3 integration review

Reviewed 5 September 2026. WeatherNext 3 is a candidate data source, not an active Selia Weather provider.

## Verified capabilities

Google documents hourly initialization with 64 ensemble members. Station-trained temperature and dew point use a 0.05-degree grid, approximately 5 km. Gridded surface variables, including precipitation, use 0.1 degrees, approximately 10 km. Synoptic runs at 00, 06, 12, and 18 UTC extend to 15 days. Interim hourly runs extend to 48 hours. These are model-grid resolutions, not guarantees of field-scale accuracy. [Model guide](https://developers.google.com/weathernext/guides/models)

Google reports precipitation gains using Brier score and CRPS against named observational benchmarks. Those scores are not a promise that every location's rainfall amount is 50% more accurate. [Research and benchmarks](https://developers.google.com/weathernext/guides/research)

## Access and distribution constraints

Operational access requires an allowlist request. Google states a typical review time of five to seven business days. No access request or paid Cloud resource was created during this review. [Access guide](https://developers.google.com/weathernext/guides/access-forecast)

The full Zarr ensemble bucket uses Requester Pays. The precomputed statistics bucket does not. A global ensemble can contain hundreds of gigabytes, so a future adapter must select the required coordinates, variables, and lead times before loading. Cloud query, compute, storage, and transfer costs must be checked separately. [Cloud Storage guide](https://developers.google.com/weathernext/guides/gcs)

Real-time and future data use separate experimental terms. Simple rehosting, spatial subsets, fixed percentage adjustments, and custom combinations of runs do not qualify as value-added services under the linked terms. A public raw-data feed or arbitrary weighted relay therefore cannot be assumed permitted. Data whose valid time is at least one hour in the past is described as CC BY 4.0. An old initialization time does not make still-future predictions historical. Obtain confirmation for the intended production use before distributing real-time WeatherNext outputs. [Data terms](https://storage.googleapis.com/weathernext-public/terms-of-use.pdf)

Managed Cloud inference also has restrictions on competing products. Those terms are distinct from the licenses for older open-source model code and weights. This review does not establish a license for training or distributing a replacement WeatherNext service. [Disclaimers and model terms](https://developers.google.com/weathernext/guides/disclaimers)

## Selia Weather implementation direction

The useful on-device component is a small, validated post-processing model. It can learn regional bias and blend weights by variable and forecast lead without running a global atmospheric neural network on a phone.

The research workflow must retain each forecast before its valid time, record actual retrieval and initialization times separately, and later match it to independent station or radar observations. A model-corrected current value is not independent truth. Temperature, wind vectors, precipitation occurrence, and precipitation amounts need separate evaluation. Sparse regions need a declared fallback, not fitted weights based on one recent observation.

The existing training and holdout workflow remains the acceptance path. Frozen live captures can improve future evidence, but they do not become validated calibration weights merely because they exist. WeatherNext can join the comparison after access, product-use permission, real data, unit conversions, and publication latency are verified. Current release behavior remains diagnostic blending with fresh observations and no claim of worldwide superiority.
