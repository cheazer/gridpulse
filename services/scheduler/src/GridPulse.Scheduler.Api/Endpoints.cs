using GridPulse.Scheduler.Core;
using Microsoft.AspNetCore.Http.HttpResults;

namespace GridPulse.Scheduler.Api;

internal static class Endpoints
{
    private static readonly TimeSpan LongestJob = TimeSpan.FromDays(7);
    private static readonly TimeSpan DefaultTimeline = TimeSpan.FromHours(48);
    private static readonly TimeSpan LongestTimeline = TimeSpan.FromDays(7);
    private static readonly TimeSpan DefaultActuals = TimeSpan.FromHours(24);

    public static void MapSchedulerEndpoints(this IEndpointRouteBuilder app)
    {
        var v1 = app.MapGroup("/v1");
        v1.MapPost("/schedule", Schedule)
            .WithSummary("The lowest-carbon window for a flexible job, and the CO2 it saves against running it now");
        v1.MapGet("/carbon/{region}", Timeline)
            .WithSummary("The carbon intensity forecast for a region, half-hour by half-hour");
        v1.MapGet("/carbon/{region}/actual", Actuals)
            .WithSummary("Measured carbon intensity, half-hour by half-hour (national only: NESO forecasts regions)");
        v1.MapGet("/regions", () => TypedResults.Ok(Regions.All.Order(StringComparer.Ordinal)))
            .WithSummary("The regions forecasts are available for");
    }

    internal static async Task<Results<Ok<ScheduleResponse>, ValidationProblem, ProblemHttpResult>> Schedule(
        ScheduleJobRequest request,
        ICarbonForecasts forecasts,
        TimeProvider clock,
        CancellationToken cancellationToken)
    {
        var now = clock.GetUtcNow();
        var earliest = request.EarliestStart ?? now;
        var errors = new Dictionary<string, string[]>();
        if (request.DurationMinutes <= 0 || TimeSpan.FromMinutes(request.DurationMinutes) > LongestJob)
        {
            errors["durationMinutes"] = [$"must be between 1 and {LongestJob.TotalMinutes} minutes"];
        }

        if (request.EnergyKwh <= 0 || !double.IsFinite(request.EnergyKwh))
        {
            errors["energyKwh"] = ["must be a positive number"];
        }

        if (request.Deadline <= earliest)
        {
            errors["deadline"] = ["must be after the earliest start (or now, if that's later)"];
        }

        var source = ParseSource(request.Source, request.Region, errors);
        if (errors.Count > 0 || source is null)
        {
            return TypedResults.ValidationProblem(errors);
        }

        var job = new ScheduleRequest(TimeSpan.FromMinutes(request.DurationMinutes), request.EnergyKwh, earliest, request.Deadline);
        var from = HalfHours.Floor(earliest > now ? earliest : now);
        var series = await forecasts.GetAsync(source.Value, request.Region, from, HalfHours.Ceiling(request.Deadline), cancellationToken)
            .ConfigureAwait(false);

        return WindowFinder.Find(series.HalfHours, job, now) switch
        {
            Scheduled s => TypedResults.Ok(new ScheduleResponse(
                s.Best,
                s.Earliest,
                s.SavingKg,
                s.SavingLowKg,
                s.SavingHighKg,
                s.Earliest.EmissionsKg > 0 ? 100 * s.SavingKg / s.Earliest.EmissionsKg : 0,
                s.LimitedByForecast,
                ForecastInfo.From(series))),
            NotScheduled n => TypedResults.Problem(
                title: "The job can't be scheduled",
                detail: n.Detail,
                statusCode: StatusCodes.Status422UnprocessableEntity,
                extensions: new Dictionary<string, object?> { ["reason"] = n.Reason, ["forecast"] = ForecastInfo.From(series) }),
            _ => throw new InvalidOperationException("unknown schedule outcome"),
        };
    }

    internal static async Task<Results<Ok<CarbonTimeline>, ValidationProblem>> Timeline(
        string region,
        ICarbonForecasts forecasts,
        TimeProvider clock,
        CancellationToken cancellationToken,
        string source = "neso",
        string? from = null,
        string? to = null)
    {
        var errors = new Dictionary<string, string[]>();
        DateTimeOffset? Parse(string? text, string name)
        {
            if (text is null)
            {
                return null;
            }

            if (Timestamps.TryParse(text, out var value))
            {
                return value;
            }

            errors[name] = ["needs a UTC offset, e.g. 2030-01-01T18:00:00Z"];
            return null;
        }

        var start = HalfHours.Floor(Parse(from, "from") ?? clock.GetUtcNow());
        var end = HalfHours.Ceiling(Parse(to, "to") ?? start + DefaultTimeline);
        if (end <= start || end - start > LongestTimeline)
        {
            errors["to"] = [$"must be after 'from', and at most {LongestTimeline.TotalDays} days later"];
        }

        var parsed = ParseSource(source, region, errors);
        if (errors.Count > 0 || parsed is null)
        {
            return TypedResults.ValidationProblem(errors);
        }

        var series = await forecasts.GetAsync(parsed.Value, region, start, end, cancellationToken).ConfigureAwait(false);
        return TypedResults.Ok(new CarbonTimeline(ForecastInfo.From(series), series.HalfHours));
    }

    internal static async Task<Results<Ok<CarbonActuals>, ValidationProblem>> Actuals(
        string region,
        ICarbonActuals actuals,
        TimeProvider clock,
        CancellationToken cancellationToken,
        string? from = null,
        string? to = null)
    {
        var errors = new Dictionary<string, string[]>();
        if (!Regions.All.Contains(region))
        {
            errors["region"] = [$"unknown region '{region}'; see GET /v1/regions"];
        }

        var now = clock.GetUtcNow();
        DateTimeOffset end = now, start = now - DefaultActuals;
        if (to is not null && !Timestamps.TryParse(to, out end))
        {
            errors["to"] = ["needs a UTC offset, e.g. 2030-01-01T18:00:00Z"];
        }

        if (from is not null && !Timestamps.TryParse(from, out start))
        {
            errors["from"] = ["needs a UTC offset, e.g. 2030-01-01T18:00:00Z"];
        }
        else if (from is null)
        {
            start = end - DefaultActuals;
        }

        if (errors.Count == 0 && (end <= start || end - start > LongestTimeline))
        {
            errors["to"] = [$"must be after 'from', and at most {LongestTimeline.TotalDays} days later"];
        }

        if (errors.Count > 0)
        {
            return TypedResults.ValidationProblem(errors);
        }

        var readings = await actuals.GetAsync(region, HalfHours.Floor(start), end, cancellationToken).ConfigureAwait(false);
        return TypedResults.Ok(new CarbonActuals(region, readings));
    }

    /// <summary>The forecast source, checked against the region; adds to <paramref name="errors"/> if invalid.</summary>
    private static ForecastSource? ParseSource(string source, string region, Dictionary<string, string[]> errors)
    {
        if (!Regions.All.Contains(region))
        {
            errors["region"] = [$"unknown region '{region}'; see GET /v1/regions"];
        }

        ForecastSource? parsed = source.ToUpperInvariant() switch
        {
            "GRIDPULSE" => ForecastSource.GridPulse,
            "NESO" => ForecastSource.Neso,
            _ => null,
        };
        if (parsed is null)
        {
            errors["source"] = ["must be 'gridpulse' or 'neso'"];
        }
        else if (parsed == ForecastSource.GridPulse && region != Regions.GreatBritain)
        {
            errors["source"] = [$"GridPulse forecasts Great Britain only ('{Regions.GreatBritain}'); use 'neso' for '{region}'"];
        }

        return parsed;
    }
}
