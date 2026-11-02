using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Dashboard;

public sealed record ForecastInfo(ForecastSource Source, string Region, DateTimeOffset? LatestIssuedAt, bool Stale);

public sealed record Timeline(ForecastInfo Forecast, IReadOnlyList<CarbonForecast> HalfHours);

public sealed record ScheduleResponse(
    Window Best,
    Window RunNow,
    double SavingKg,
    double? SavingLowKg,
    double? SavingHighKg,
    double SavingPercent,
    bool LimitedByForecast,
    ForecastInfo Forecast);

/// <summary>A schedule, or why there isn't one, worded for the person who asked.</summary>
public sealed record ScheduleResult(ScheduleResponse? Schedule, string? Problem);

public sealed record JobRequest(int DurationMinutes, double EnergyKwh, DateTimeOffset Deadline, string Region, ForecastSource Source);

/// <summary>What the dashboard needs from the scheduling API; the pages depend on this, tests fake it.</summary>
public interface ISchedulerApi
{
    Task<IReadOnlyList<string>> RegionsAsync(CancellationToken cancellationToken);

    Task<Timeline> TimelineAsync(string region, ForecastSource source, CancellationToken cancellationToken);

    Task<IReadOnlyList<CarbonReading>> ActualsAsync(string region, CancellationToken cancellationToken);

    Task<ScheduleResult> ScheduleAsync(JobRequest job, CancellationToken cancellationToken);
}

/// <summary>
/// The scheduling API, over HTTP, as any client would use it (ADR-0013). Retries and timeouts are added where it is
/// registered (Program.cs).
/// </summary>
public sealed class SchedulerApiClient(HttpClient http) : ISchedulerApi
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public async Task<IReadOnlyList<string>> RegionsAsync(CancellationToken cancellationToken) =>
        await http.GetFromJsonAsync<List<string>>(new Uri("v1/regions", UriKind.Relative), Json, cancellationToken).ConfigureAwait(false)
        ?? [];

    public async Task<Timeline> TimelineAsync(string region, ForecastSource source, CancellationToken cancellationToken)
    {
        var uri = new Uri($"v1/carbon/{Uri.EscapeDataString(region)}?source={Name(source)}", UriKind.Relative);
        return await http.GetFromJsonAsync<Timeline>(uri, Json, cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidOperationException("the scheduling API returned an empty timeline");
    }

    public async Task<IReadOnlyList<CarbonReading>> ActualsAsync(string region, CancellationToken cancellationToken)
    {
        var uri = new Uri($"v1/carbon/{Uri.EscapeDataString(region)}/actual", UriKind.Relative);
        var body = await http.GetFromJsonAsync<ActualsBody>(uri, Json, cancellationToken).ConfigureAwait(false);
        return body?.HalfHours ?? [];
    }

    public async Task<ScheduleResult> ScheduleAsync(JobRequest job, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(job);
        HttpResponseMessage response;
        try
        {
            response = await http.PostAsJsonAsync(
                new Uri("v1/schedule", UriKind.Relative),
                new
                {
                    durationMinutes = job.DurationMinutes,
                    energyKwh = job.EnergyKwh,
                    deadline = job.Deadline.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture),
                    region = job.Region,
                    source = Name(job.Source),
                },
                Json,
                cancellationToken).ConfigureAwait(false);
        }
        catch (HttpRequestException)
        {
            return new ScheduleResult(null, Unavailable);
        }

        using (response)
        {
            if (response.IsSuccessStatusCode)
            {
                return new ScheduleResult(
                    await response.Content.ReadFromJsonAsync<ScheduleResponse>(Json, cancellationToken).ConfigureAwait(false),
                    null);
            }

            if (response.StatusCode == HttpStatusCode.ServiceUnavailable)
            {
                return new ScheduleResult(null, Unavailable);
            }

            var problem = await response.Content.ReadFromJsonAsync<Problem>(Json, cancellationToken).ConfigureAwait(false);
            return new ScheduleResult(null, Explain(problem));
        }
    }

    private const string Unavailable = "Forecasts are unavailable right now. Try again in a minute.";

    private static string Name(ForecastSource source) => source == ForecastSource.Neso ? "neso" : "gridpulse";

    /// <summary>The API's problem details, put into words: the reason if it gave one, else each field's error.</summary>
    private static string Explain(Problem? problem) => problem switch
    {
        { Reason: "does-not-fit" } => "The job doesn't fit before its deadline. Allow more time, or make it shorter.",
        { Reason: "no-forecast-coverage" } => "There's no forecast covering that window yet. Try a deadline within the next 48 hours.",
        { Errors.Count: > 0 } => string.Join(" ", problem.Errors.Select(e => $"{e.Key}: {string.Join(", ", e.Value)}.")),
        { Detail: { } detail } => detail,
        _ => "The scheduling API couldn't handle that request.",
    };

    private sealed record ActualsBody(string Region, List<CarbonReading> HalfHours);

    private sealed record Problem(string? Title, string? Detail, string? Reason, Dictionary<string, string[]>? Errors);
}
