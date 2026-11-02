using System.Diagnostics.Metrics;
using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Api;

/// <summary>
/// What the scheduler does for its users, as metrics: how many jobs it schedules or turns down and why, and how much
/// CO2 the scheduled ones save. Request counts and latencies come from ASP.NET Core's own meters.
/// </summary>
internal sealed class SchedulerMetrics
{
    public const string MeterName = "GridPulse.Scheduler";

    private readonly Counter<long> schedules;
    private readonly Histogram<double> saving;

    public SchedulerMetrics(IMeterFactory meterFactory)
    {
        var meter = meterFactory.Create(MeterName);
        schedules = meter.CreateCounter<long>(
            "gridpulse.scheduler.schedules",
            unit: "{job}",
            description: "Jobs the scheduler was asked to place, by forecast source and outcome");
        saving = meter.CreateHistogram<double>(
            "gridpulse.scheduler.saving",
            unit: "kg",
            description: "CO2 saved by running a scheduled job in its best window rather than now",
            advice: new InstrumentAdvice<double> { HistogramBucketBoundaries = [0.1, 0.5, 1, 2, 5, 10, 20, 50, 100] });
    }

    public void Record(ForecastSource source, bool stale, ScheduleOutcome outcome)
    {
        // Low-cardinality tags only: each distinct combination is a separate time series in Prometheus.
        var sourceTag = new KeyValuePair<string, object?>("source", source == ForecastSource.GridPulse ? "gridpulse" : "neso");
        var staleTag = new KeyValuePair<string, object?>("stale", stale);
        switch (outcome)
        {
            case Scheduled s:
                schedules.Add(1, sourceTag, staleTag, new("outcome", "scheduled"));
                saving.Record(s.SavingKg, sourceTag);
                break;
            case NotScheduled n:
                // The same words the API returns as the problem's "reason".
                var reason = n.Reason switch
                {
                    NotScheduledReason.DoesNotFit => "does-not-fit",
                    NotScheduledReason.NoForecastCoverage => "no-forecast-coverage",
                    _ => throw new ArgumentOutOfRangeException(nameof(outcome), n.Reason, "unknown reason"),
                };
                schedules.Add(1, sourceTag, staleTag, new("outcome", reason));
                break;
            default:
                throw new ArgumentOutOfRangeException(nameof(outcome), outcome, "unknown schedule outcome");
        }
    }
}
