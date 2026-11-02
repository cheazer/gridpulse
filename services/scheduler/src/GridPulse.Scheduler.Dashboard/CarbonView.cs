using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Dashboard;

/// <summary>One point on the chart: a UK wall-clock time and a value in gCO2/kWh (with a P10-P90 band, if any).</summary>
public sealed record ChartPoint(DateTime UkTime, decimal Value, decimal? Low = null, decimal? High = null);

/// <summary>
/// What the home page shows for a region, worked out from the API's data with no rendering involved, so it can be
/// tested without a browser.
/// </summary>
public sealed record CarbonView(
    string Region,
    CarbonForecast? Now,
    CarbonForecast? CleanestNextDay,
    IReadOnlyList<ChartPoint> Actual,
    IReadOnlyList<ChartPoint> Neso,
    IReadOnlyList<ChartPoint> GridPulse,
    bool NesoStale,
    bool GridPulseStale)
{
    public static CarbonView From(
        string region,
        Timeline neso,
        Timeline? gridPulse,
        IReadOnlyList<CarbonReading> actuals,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(neso);
        ArgumentNullException.ThrowIfNull(actuals);
        var current = HalfHours.Floor(now);
        var nextDay = neso.HalfHours.Where(h => h.Start >= current && h.Start < current.AddHours(24)).ToList();
        return new CarbonView(
            region,
            neso.HalfHours.FirstOrDefault(h => h.Start == current),
            nextDay.Count > 0 ? nextDay.MinBy(h => h.Expected) : null,
            actuals.Select(a => new ChartPoint(UkTime.ToUk(a.Start), (decimal)a.Intensity)).ToList(),
            neso.HalfHours.Select(h => new ChartPoint(UkTime.ToUk(h.Start), (decimal)h.Expected)).ToList(),
            gridPulse?.HalfHours
                .Select(h => new ChartPoint(UkTime.ToUk(h.Start), (decimal)h.Expected, (decimal?)h.Low, (decimal?)h.High))
                .ToList() ?? [],
            neso.Forecast.Stale,
            gridPulse?.Forecast.Stale ?? false);
    }

    /// <summary>
    /// How now compares with the rest of the next 24 hours, from the forecast itself rather than fixed thresholds:
    /// "cleaner than 80% of the next 24 hours" says when to act without needing to know what 150 g/kWh means.
    /// </summary>
    public string? NowComparedWithNextDay(Timeline neso)
    {
        ArgumentNullException.ThrowIfNull(neso);
        if (Now is null)
        {
            return null;
        }

        // Against the other half-hours only: counting now itself, or ties, would understate either answer.
        var others = neso.HalfHours.Where(h => h.Start > Now.Start && h.Start < Now.Start.AddHours(24)).ToList();
        if (others.Count == 0)
        {
            return null;
        }

        var cleanerThan = others.Count(h => h.Expected > Now.Expected);
        var dirtierThan = others.Count(h => h.Expected < Now.Expected);
        return cleanerThan >= dirtierThan
            ? $"cleaner than {Percent(cleanerThan, others.Count)}% of the next 24 hours"
            : $"dirtier than {Percent(dirtierThan, others.Count)}% of the next 24 hours";
    }

    private static int Percent(int count, int total) => (int)Math.Round(100.0 * count / total);
}
