using System.Text.Json.Serialization;

namespace GridPulse.Scheduler.Core;

/// <summary>
/// One way to run the job: when, the average carbon intensity it would run at (gCO2/kWh), and its emissions (kg).
/// Low and high are the P10 and P90 where the forecast has a range.
/// </summary>
public sealed record Window(
    DateTimeOffset Start,
    DateTimeOffset End,
    double Intensity,
    double? IntensityLow,
    double? IntensityHigh,
    double EmissionsKg,
    double? EmissionsLowKg,
    double? EmissionsHighKg);

/// <summary>The answer to a <see cref="ScheduleRequest"/>.</summary>
public abstract record ScheduleOutcome;

/// <summary>
/// The cleanest window, and the earliest one ("run it now") to compare it with.
/// </summary>
/// <param name="Best">The window with the lowest expected emissions; the earliest of any ties.</param>
/// <param name="Earliest">The first window the job could run in: the baseline the saving is measured against.</param>
/// <param name="SavingKg">Expected CO2 saved by running in <paramref name="Best"/> instead of <paramref name="Earliest"/>.</param>
/// <param name="SavingLowKg">
/// The saving if outcomes come in at the low end, comparing like with like (P10 with P10, P90 with P90): forecast errors
/// are strongly correlated hour to hour, so the two windows tend to be wrong in the same direction.
/// </param>
/// <param name="SavingHighKg">The saving if outcomes come in at the high end, compared the same way.</param>
/// <param name="LimitedByForecast">
/// True if the deadline is beyond the last forecast half-hour: only windows inside the forecast were considered.
/// </param>
public sealed record Scheduled(
    Window Best,
    Window Earliest,
    double SavingKg,
    double? SavingLowKg,
    double? SavingHighKg,
    bool LimitedByForecast) : ScheduleOutcome;

[JsonConverter(typeof(JsonStringEnumConverter<NotScheduledReason>))]
public enum NotScheduledReason
{
    /// <summary>The job is longer than the time between its earliest start (or now) and its deadline.</summary>
    [JsonStringEnumMemberName("does-not-fit")]
    DoesNotFit,

    /// <summary>The job would fit, but no window of that length has a forecast for every half-hour.</summary>
    [JsonStringEnumMemberName("no-forecast-coverage")]
    NoForecastCoverage,
}

public sealed record NotScheduled(NotScheduledReason Reason, string Detail) : ScheduleOutcome;
