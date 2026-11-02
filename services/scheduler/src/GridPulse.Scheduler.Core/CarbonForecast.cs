namespace GridPulse.Scheduler.Core;

/// <summary>
/// Forecast carbon intensity for one half-hour, in gCO2/kWh. <see cref="Low"/> and <see cref="High"/> are the
/// P10 and P90 where the source gives them (GridPulse's own forecasts do; NESO's are a single value).
/// </summary>
public sealed record CarbonForecast
{
    public CarbonForecast(DateTimeOffset start, double expected, double? low = null, double? high = null)
    {
        if (!HalfHours.IsBoundary(start))
        {
            throw new ArgumentException($"{start:o} is not a half-hour boundary", nameof(start));
        }

        if (low > expected || high < expected || (low is null) != (high is null))
        {
            throw new ArgumentException($"need low <= expected <= high, both or neither: {low}, {expected}, {high}");
        }

        Start = start.ToUniversalTime();
        Expected = expected;
        Low = low;
        High = high;
    }

    public DateTimeOffset Start { get; }

    /// <summary>The point forecast: GridPulse's P50, or NESO's forecast.</summary>
    public double Expected { get; }

    public double? Low { get; }

    public double? High { get; }

    public bool HasRange => Low is not null;
}
