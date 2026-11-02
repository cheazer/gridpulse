using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Dashboard.Tests;

/// <summary>An in-memory scheduling API: canned answers, and a record of what the page asked for.</summary>
internal sealed class FakeSchedulerApi : ISchedulerApi
{
    public static readonly DateTimeOffset Now = new(2030, 1, 1, 9, 10, 0, TimeSpan.Zero);

    public bool Down { get; set; }

    public List<double> NesoValues { get; set; } = [.. Enumerable.Range(0, 96).Select(i => 200.0 - i)];

    public bool NesoStale { get; set; }

    public ScheduleResult Schedule { get; set; } = new(null, "not set up");

    public List<JobRequest> Jobs { get; } = [];

    public List<(string Region, ForecastSource Source)> Timelines { get; } = [];

    public Task<IReadOnlyList<string>> RegionsAsync(CancellationToken cancellationToken) =>
        Task.FromResult<IReadOnlyList<string>>([.. Regions.All]);

    public Task<Timeline> TimelineAsync(string region, ForecastSource source, CancellationToken cancellationToken)
    {
        if (Down)
        {
            throw new HttpRequestException("down");
        }

        Timelines.Add((region, source));
        var start = HalfHours.Floor(Now);
        var halfHours = NesoValues
            .Select((v, i) => source == ForecastSource.Neso
                ? new CarbonForecast(start.AddMinutes(30 * i), v)
                : new CarbonForecast(start.AddMinutes(30 * i), v, v - 10, v + 10))
            .ToList();
        var stale = source == ForecastSource.Neso && NesoStale;
        return Task.FromResult(new Timeline(new ForecastInfo(source, region, Now.AddHours(-1), stale), halfHours));
    }

    public Task<IReadOnlyList<CarbonReading>> ActualsAsync(string region, CancellationToken cancellationToken) =>
        Task.FromResult<IReadOnlyList<CarbonReading>>(
            region == Regions.GreatBritain ? [new CarbonReading(HalfHours.Floor(Now).AddMinutes(-30), 190)] : []);

    public Task<ScheduleResult> ScheduleAsync(JobRequest job, CancellationToken cancellationToken)
    {
        Jobs.Add(job);
        return Task.FromResult(Schedule);
    }
}
