Real responses from Open-Meteo (https://open-meteo.com, CC BY 4.0, free for non-commercial use), recorded at 2026-10-28T22:24:36Z
for 8 locations (London, Birmingham, Manchester, Leeds, Edinburgh, Cardiff, Norwich, Aberdeen):

- forecast.json: GET https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&hourly=temperature_2m,wind_speed_10m,wind_speed_100m,shortwave_radiation,cloud_cover&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT&forecast_days=3
- archive.json: GET https://archive-api.open-meteo.com/v1/archive?latitude=...&longitude=...&hourly=temperature_2m,wind_speed_10m,wind_speed_100m,shortwave_radiation,cloud_cover&wind_speed_unit=ms&timeformat=unixtime&timezone=GMT&start_date=2026-10-18&end_date=2026-10-27

Tests use 2026-10-28T22:24:36Z as the poll time.
- forecast-single.json: the London entry of forecast.json on its own, the shape Open-Meteo returns for one location
- previous-runs.json: GET https://previous-runs-api.open-meteo.com/v1/forecast for the 8 locations, *_previous_day1 variables, 2024-10-29 to 2024-10-30
