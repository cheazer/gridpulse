using System.Text.Json.Serialization;

namespace GridPulse.Scheduler.Core;

/// <summary>Whose carbon intensity forecast to schedule against.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<ForecastSource>))]
public enum ForecastSource
{
    /// <summary>GridPulse's own model (Phase 2): national only, with P10-P90 ranges.</summary>
    [JsonStringEnumMemberName("gridpulse")]
    GridPulse,

    /// <summary>NESO's Carbon Intensity forecast: national or any region, a single value per half-hour.</summary>
    [JsonStringEnumMemberName("neso")]
    Neso,
}

/// <summary>A forecast as fetched: its half-hours, and how fresh it is.</summary>
/// <param name="Source">Whose forecast it is.</param>
/// <param name="Region">The region it covers: <see cref="Regions.GreatBritain"/> or one of <see cref="Regions.All"/>.</param>
/// <param name="HalfHours">One forecast per half-hour, in time order; half-hours without one are simply absent.</param>
/// <param name="LatestIssuedAt">When the newest forecast in the series was made; null if the series is empty.</param>
/// <param name="Stale">True if that is too long ago to trust, or there is no forecast at all.</param>
public sealed record ForecastSeries(
    ForecastSource Source,
    string Region,
    IReadOnlyList<CarbonForecast> HalfHours,
    DateTimeOffset? LatestIssuedAt,
    bool Stale);

/// <summary>Where the scheduler gets forecasts from. Core depends on this; the Data project implements it.</summary>
public interface ICarbonForecasts
{
    /// <summary>The latest forecast for each half-hour starting in [<paramref name="windowStart"/>, <paramref name="windowEnd"/>).</summary>
    /// <exception cref="ArgumentException">An unknown region, or a region the source doesn't forecast.</exception>
    Task<ForecastSeries> GetAsync(
        ForecastSource source,
        string region,
        DateTimeOffset windowStart,
        DateTimeOffset windowEnd,
        CancellationToken cancellationToken);
}
