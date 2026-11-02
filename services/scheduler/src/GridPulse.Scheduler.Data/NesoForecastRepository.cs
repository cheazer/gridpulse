using System.Diagnostics.CodeAnalysis;
using Dapper;
using GridPulse.Scheduler.Core;
using Npgsql;

namespace GridPulse.Scheduler.Data;

/// <summary>
/// NESO's carbon intensity forecasts, read from TimescaleDB (ADR-0013). NESO revises its forecast for each half-hour
/// many times; this returns, for each half-hour, the latest revision issued by <c>asOf</c>, the same as-of rule the
/// forecasting service uses (ADR-0011).
/// </summary>
public sealed class NesoForecastRepository(NpgsqlDataSource dataSource)
{
    /// <summary>
    /// Regional forecasts are revised in 3-hour buckets and the national one every half-hour (ADR-0006); two missed
    /// regional updates and something is wrong upstream.
    /// </summary>
    public static readonly TimeSpan StaleAfter = TimeSpan.FromHours(6);

    private const string LatestRevisions = """
        SELECT DISTINCT ON (valid_from)
               valid_from AS ValidFrom, forecast_gco2_per_kwh::float8 AS Forecast, issued_at AS IssuedAt
          FROM carbon_intensity
         WHERE region = @Region AND issued_at IS NOT NULL AND issued_at <= @AsOf
           AND forecast_gco2_per_kwh IS NOT NULL
           AND valid_from >= @From AND valid_from < @To
         ORDER BY valid_from, issued_at DESC
        """;

    public async Task<ForecastSeries> LatestAsync(
        string region,
        DateTimeOffset start,
        DateTimeOffset end,
        DateTimeOffset asOf,
        CancellationToken cancellationToken)
    {
        await using var connection = await dataSource.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var rows = await connection.QueryAsync<Row>(new CommandDefinition(
            LatestRevisions,
            new { Region = region, From = start.UtcDateTime, To = end.UtcDateTime, AsOf = asOf.UtcDateTime },
            cancellationToken: cancellationToken)).ConfigureAwait(false);

        var halfHours = new List<CarbonForecast>();
        DateTimeOffset? latest = null;
        foreach (var row in rows)
        {
            var issued = new DateTimeOffset(row.IssuedAt, TimeSpan.Zero);
            halfHours.Add(new CarbonForecast(new DateTimeOffset(row.ValidFrom, TimeSpan.Zero), row.Forecast));
            latest = latest is null || issued > latest ? issued : latest;
        }

        return new ForecastSeries(
            ForecastSource.Neso, region, halfHours, latest, Stale: latest is null || asOf - latest > StaleAfter);
    }

    // Npgsql returns timestamptz as a UTC DateTime; the conversion to DateTimeOffset is explicit above.
    [SuppressMessage("Performance", "CA1812", Justification = "Instantiated by Dapper")]
    private sealed record Row(DateTime ValidFrom, double Forecast, DateTime IssuedAt);
}
