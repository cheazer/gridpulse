using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Data;

/// <summary>Core's <see cref="ICarbonForecasts"/>: each source from where it lives.</summary>
public sealed class CarbonForecasts(ForecastingApiClient gridPulse, NesoForecastRepository neso, TimeProvider clock)
    : ICarbonForecasts
{
    public Task<ForecastSeries> GetAsync(
        ForecastSource source,
        string region,
        DateTimeOffset windowStart,
        DateTimeOffset windowEnd,
        CancellationToken cancellationToken)
    {
        if (!Regions.All.Contains(region))
        {
            throw new ArgumentException($"unknown region '{region}'", nameof(region));
        }

        return source switch
        {
            ForecastSource.GridPulse when region == Regions.GreatBritain => gridPulse.CarbonAsync(windowStart, windowEnd, cancellationToken),
            ForecastSource.GridPulse => throw new ArgumentException(
                $"GridPulse forecasts Great Britain only ('{Regions.GreatBritain}'); use NESO's for '{region}'", nameof(region)),
            ForecastSource.Neso => neso.LatestAsync(region, windowStart, windowEnd, clock.GetUtcNow(), cancellationToken),
            _ => throw new ArgumentOutOfRangeException(nameof(source), source, "unknown forecast source"),
        };
    }
}
