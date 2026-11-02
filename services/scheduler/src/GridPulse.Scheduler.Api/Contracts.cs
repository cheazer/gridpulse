using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Api;

/// <summary>A flexible job to schedule. Times need a UTC offset ("Z" or "+01:00"): "18:00" alone is ambiguous.</summary>
/// <param name="DurationMinutes">How long the job runs, 1 minute to 7 days.</param>
/// <param name="EnergyKwh">The electricity it uses over that time, spread evenly.</param>
/// <param name="Deadline">When it must have finished.</param>
/// <param name="EarliestStart">When it can start at the earliest; now if omitted.</param>
/// <param name="Region">"GB" (the default) or one of the regions from GET /v1/regions.</param>
/// <param name="Source">"gridpulse" (the default; GB only, with ranges) or "neso" (any region).</param>
internal sealed record ScheduleJobRequest(
    int DurationMinutes,
    double EnergyKwh,
    DateTimeOffset Deadline,
    DateTimeOffset? EarliestStart = null,
    string Region = Regions.GreatBritain,
    string Source = "gridpulse");

/// <summary>Where the forecast came from and how fresh it is.</summary>
/// <param name="Source">"gridpulse" or "neso".</param>
/// <param name="Region">The region the forecast covers.</param>
/// <param name="LatestIssuedAt">When the newest forecast used was made.</param>
/// <param name="Stale">True if the newest forecast is too old to trust: the schedule still stands, but treat it with care.</param>
internal sealed record ForecastInfo(ForecastSource Source, string Region, DateTimeOffset? LatestIssuedAt, bool Stale)
{
    public static ForecastInfo From(ForecastSeries series) =>
        new(series.Source, series.Region, series.LatestIssuedAt, series.Stale);
}

/// <summary>The cleanest time to run the job, against running it as soon as possible.</summary>
/// <param name="Best">The window with the lowest expected emissions.</param>
/// <param name="RunNow">The earliest window the job could run in: the comparison.</param>
/// <param name="SavingKg">Expected CO2 saved by running in Best instead, in kg.</param>
/// <param name="SavingLowKg">
/// The low end of the saving's range (GridPulse forecasts only), comparing the two windows' P10s and their P90s:
/// forecast errors move together from hour to hour, so both windows tend to come in low or high together.
/// </param>
/// <param name="SavingHighKg">The high end of the saving's range.</param>
/// <param name="SavingPercent">The same, as a percentage of RunNow's emissions.</param>
/// <param name="LimitedByForecast">True if the deadline is past the forecast: only forecast windows were considered.</param>
/// <param name="Forecast">Which forecast was used, and how fresh it was.</param>
internal sealed record ScheduleResponse(
    Window Best,
    Window RunNow,
    double SavingKg,
    double? SavingLowKg,
    double? SavingHighKg,
    double SavingPercent,
    bool LimitedByForecast,
    ForecastInfo Forecast);

internal sealed record CarbonTimeline(ForecastInfo Forecast, IReadOnlyList<CarbonForecast> HalfHours);

/// <summary>Measured carbon intensity: what actually happened. Empty for regions, which NESO only forecasts.</summary>
internal sealed record CarbonActuals(string Region, IReadOnlyList<CarbonReading> HalfHours);
