Real responses from the Elexon Insights Solution API (https://data.elexon.co.uk/bmrs/api/v1, BMRS data licence),
recorded at 2026-10-28T21:10:01Z:

- demand-outturn.json: GET /demand/outturn?settlementDateFrom=2026-10-27&settlementDateTo=2026-10-28
- ndf.json: GET /datasets/NDF
- tsdf.json: GET /datasets/TSDF?boundary=N
- fuelhh.json: GET /datasets/FUELHH?settlementDateFrom=2026-10-27&settlementDateTo=2026-10-28
- system-prices-2026-10-27.json, system-prices-2026-10-28.json: GET /balancing/settlement/system-prices/{date}

Tests use 2026-10-28T21:10:01Z as the poll time.
