# ČHMÚ fixture sources

Short UTF-8 excerpts preserve the documented JSON `DataCollection` format.

- Station metadata: https://opendata.chmi.cz/meteorology/climate/now/metadata/meta1-20260825.json
- Element metadata: https://opendata.chmi.cz/meteorology/climate/now/metadata/meta2-20260825.json
- Hourly observations: https://opendata.chmi.cz/meteorology/climate/now/data/1h-0-20000-0-11502-20260825.json
- Ten-minute observations: https://opendata.chmi.cz/meteorology/climate/now/data/10m-0-20000-0-11502-20260825.json
- Format documentation: https://opendata.chmi.cz/meteorology/climate/Klimatologicka_data_popis.pdf
- MERGE 1h documentation: https://opendata.chmi.cz/meteorology/weather/radar/radar_description_en.pdf

ČHMÚ open-data licence: https://www.chmi.cz/-/jak-mohu-pou%C5%BE%C3%ADvat-otev%C5%99en%C3%A1-data-%C4%8Dhm%C3%BA-

`T_PASV23_C_OKPR_20260825173000.hdf` is a 2×2 derived ODIM HDF5 fixture. It keeps the
actual 25 August 2026 MERGE1h group/attribute schema and is not a ČHMÚ raster excerpt.
Its SHA-256 is `c0368d4ddbfc81a11378f0c9b05a345266a8ca3101590249e73147faae57ad37`.
