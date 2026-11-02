using System.Diagnostics.CodeAnalysis;
using Dapper;
using GridPulse.Scheduler.Core;
using Npgsql;

namespace GridPulse.Scheduler.Data;

/// <summary>NESO's measured carbon intensity, read from TimescaleDB: the rows without an issue time (ADR-0004).</summary>
public sealed class NesoActualsRepository(NpgsqlDataSource dataSource) : ICarbonActuals
{
    // A zero is a glitch, not a measurement: GB's grid never runs at zero carbon (the forecasting service drops it too).
    private const string Actuals = """
        SELECT valid_from AS ValidFrom, actual_gco2_per_kwh::float8 AS Intensity
          FROM carbon_intensity
         WHERE region = @Region AND issued_at IS NULL AND actual_gco2_per_kwh > 0
           AND valid_from >= @From AND valid_from < @To
         ORDER BY valid_from
        """;

    public async Task<IReadOnlyList<CarbonReading>> GetAsync(
        string region,
        DateTimeOffset windowStart,
        DateTimeOffset windowEnd,
        CancellationToken cancellationToken)
    {
        if (!Regions.All.Contains(region))
        {
            throw new ArgumentException($"unknown region '{region}'", nameof(region));
        }

        await using var connection = await dataSource.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        var rows = await connection.QueryAsync<Row>(new CommandDefinition(
            Actuals,
            new { Region = region, From = windowStart.UtcDateTime, To = windowEnd.UtcDateTime },
            cancellationToken: cancellationToken)).ConfigureAwait(false);
        return rows.Select(r => new CarbonReading(new DateTimeOffset(r.ValidFrom, TimeSpan.Zero), r.Intensity)).ToList();
    }

    [SuppressMessage("Performance", "CA1812", Justification = "Instantiated by Dapper")]
    private sealed record Row(DateTime ValidFrom, double Intensity);
}
