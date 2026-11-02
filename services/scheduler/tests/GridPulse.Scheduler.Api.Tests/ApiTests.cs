using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Api.Tests;

/// <summary>The real app, in memory: WebApplicationFactory runs Program.cs with a test server instead of Kestrel.</summary>
public class ApiTests
{
    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    private static async Task<(HttpStatusCode Status, JsonElement Body)> Post(FakeForecasts forecasts, object request)
    {
        await using var app = TestApp.Create(forecasts);
        using var client = app.CreateClient();
        using var response = await client.PostAsJsonAsync(new Uri("/v1/schedule", UriKind.Relative), request, Ct);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(Ct));
        return (response.StatusCode, json.RootElement.Clone());
    }

    private static object Job(int minutes = 60, double kwh = 50, string deadline = "2030-01-01T12:00:00Z", string region = "GB", string source = "gridpulse") =>
        new { durationMinutes = minutes, energyKwh = kwh, deadline, region, source };

    [Fact]
    public async Task Schedules_the_cleanest_window_and_reports_the_saving_with_its_range()
    {
        // From 09:30: 300, 280, 100, 120, 250, 260. The cleanest hour is 10:30-11:30 (110 g/kWh).
        var forecasts = new FakeForecasts { Series = TestApp.GridPulse(300, 280, 100, 120, 250, 260) };

        var (status, body) = await Post(forecasts, Job());

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("2030-01-01T10:30:00+00:00", body.GetProperty("best").GetProperty("start").GetString());
        Assert.Equal("2030-01-01T09:30:00+00:00", body.GetProperty("runNow").GetProperty("start").GetString());
        // 50 kWh: 14.5 kg now (290 g/kWh), 5.5 kg at best (110 g/kWh).
        Assert.Equal(9.0, body.GetProperty("savingKg").GetDouble(), precision: 9);
        Assert.Equal(100 * 9.0 / 14.5, body.GetProperty("savingPercent").GetDouble(), precision: 9);
        Assert.Equal(9.0, body.GetProperty("savingLowKg").GetDouble(), precision: 9); // a +/-20 g band shifts both alike
        Assert.Equal("gridpulse", body.GetProperty("forecast").GetProperty("source").GetString());
        Assert.False(body.GetProperty("forecast").GetProperty("stale").GetBoolean());
    }

    [Fact]
    public async Task Asks_for_forecasts_from_the_current_half_hour_to_the_deadline()
    {
        var forecasts = new FakeForecasts { Series = TestApp.GridPulse(200, 200, 200, 200, 200, 200) };

        await Post(forecasts, Job(deadline: "2030-01-01T11:45:00Z"));

        var asked = Assert.Single(forecasts.Asked);
        Assert.Equal((ForecastSource.GridPulse, "GB"), (asked.Source, asked.Region));
        Assert.Equal(new DateTimeOffset(2030, 1, 1, 9, 0, 0, TimeSpan.Zero), asked.Start); // 09:10 floored
        Assert.Equal(new DateTimeOffset(2030, 1, 1, 12, 0, 0, TimeSpan.Zero), asked.End); // 11:45 ceilinged
    }

    [Fact]
    public async Task Regions_use_nesos_forecasts()
    {
        var forecasts = new FakeForecasts
        {
            Series = new ForecastSeries(ForecastSource.Neso, "london", [new CarbonForecast(TestApp.Now.AddMinutes(20), 150)], TestApp.Now, false),
        };

        var (status, body) = await Post(forecasts, Job(minutes: 30, region: "london", source: "neso"));

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal((ForecastSource.Neso, "london"), (forecasts.Asked[0].Source, forecasts.Asked[0].Region));
        Assert.Equal(JsonValueKind.Null, body.GetProperty("savingLowKg").ValueKind); // NESO's forecast has no range
    }

    [Fact]
    public async Task A_job_that_cannot_be_scheduled_is_a_422_with_the_reason()
    {
        var (status, body) = await Post(new FakeForecasts(), Job(minutes: 300, deadline: "2030-01-01T11:00:00Z"));

        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("does-not-fit", body.GetProperty("reason").GetString());
        Assert.Equal("The job can't be scheduled", body.GetProperty("title").GetString());
    }

    [Fact]
    public async Task No_forecast_is_a_422_saying_so_not_a_made_up_window()
    {
        var (status, body) = await Post(new FakeForecasts(), Job());

        Assert.Equal(HttpStatusCode.UnprocessableEntity, status);
        Assert.Equal("no-forecast-coverage", body.GetProperty("reason").GetString());
        Assert.True(body.GetProperty("forecast").GetProperty("stale").GetBoolean());
    }

    [Theory]
    [InlineData(0, 50.0, "2030-01-01T12:00:00Z", "GB", "gridpulse", "durationMinutes")]
    [InlineData(60, -1.0, "2030-01-01T12:00:00Z", "GB", "gridpulse", "energyKwh")]
    [InlineData(60, 50.0, "2030-01-01T08:00:00Z", "GB", "gridpulse", "deadline")] // before now
    [InlineData(60, 50.0, "2030-01-01T12:00:00Z", "atlantis", "neso", "region")]
    [InlineData(60, 50.0, "2030-01-01T12:00:00Z", "london", "gridpulse", "source")] // GridPulse is GB only
    [InlineData(60, 50.0, "2030-01-01T12:00:00Z", "GB", "met-office", "source")]
    public async Task Invalid_requests_are_400s_naming_the_field(int minutes, double kwh, string deadline, string region, string source, string field)
    {
        var forecasts = new FakeForecasts();

        var (status, body) = await Post(forecasts, Job(minutes, kwh, deadline, region, source));

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.True(body.GetProperty("errors").TryGetProperty(field, out _), $"no error for {field}: {body}");
        Assert.Empty(forecasts.Asked); // rejected before any forecast was fetched
    }

    [Fact]
    public async Task Timestamps_without_a_zone_are_rejected_with_the_reason()
    {
        var (status, body) = await Post(new FakeForecasts(), Job(deadline: "2030-01-01T12:00:00"));

        Assert.Equal(HttpStatusCode.BadRequest, status);
        Assert.Contains("UTC offset", body.GetProperty("detail").GetString(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(typeof(HttpRequestException))]
    [InlineData(typeof(Npgsql.NpgsqlException))]
    public async Task Unreachable_forecasts_are_a_503_to_retry_not_a_500(Type failure)
    {
        var forecasts = new FakeForecasts { Throws = (Exception)Activator.CreateInstance(failure)! };
        await using var app = TestApp.Create(forecasts);
        using var client = app.CreateClient();

        using var response = await client.PostAsJsonAsync(new Uri("/v1/schedule", UriKind.Relative), Job(), Ct);

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.NotNull(response.Headers.RetryAfter);
    }

    [Fact]
    public async Task The_carbon_timeline_defaults_to_nesos_next_48_hours()
    {
        var forecasts = new FakeForecasts();
        await using var app = TestApp.Create(forecasts);
        using var client = app.CreateClient();

        using var response = await client.GetAsync(new Uri("/v1/carbon/yorkshire", UriKind.Relative), Ct);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var asked = Assert.Single(forecasts.Asked);
        Assert.Equal((ForecastSource.Neso, "yorkshire"), (asked.Source, asked.Region));
        Assert.Equal(TimeSpan.FromHours(48), asked.End - asked.Start);
    }

    [Fact]
    public async Task Regions_are_listed()
    {
        await using var app = TestApp.Create(new FakeForecasts());
        using var client = app.CreateClient();

        var regions = await client.GetFromJsonAsync<string[]>(new Uri("/v1/regions", UriKind.Relative), Ct);

        Assert.Equal(18, regions!.Length);
        Assert.Contains("london", regions);
    }

    [Fact]
    public async Task Health_answers_without_any_dependencies()
    {
        await using var app = TestApp.Create(new FakeForecasts());
        using var client = app.CreateClient();

        var response = await client.GetAsync(new Uri("/health", UriKind.Relative), Ct);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Readiness_fails_without_the_database_and_names_each_dependency()
    {
        await using var app = TestApp.Create(new FakeForecasts());
        using var client = app.CreateClient();

        using var response = await client.GetAsync(new Uri("/ready", UriKind.Relative), Ct);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(Ct));

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        var checks = json.RootElement.GetProperty("checks");
        Assert.Equal("Unhealthy", checks.GetProperty("database").GetProperty("status").GetString());
        // The forecasting API is down too, but on its own that only degrades the service.
        Assert.Equal("Degraded", checks.GetProperty("forecasting-api").GetProperty("status").GetString());
    }

    [Fact]
    public async Task OpenApi_document_describes_the_endpoints()
    {
        await using var app = TestApp.Create(new FakeForecasts());
        using var client = app.CreateClient();

        var json = await client.GetStringAsync(new Uri("/openapi/v1.json", UriKind.Relative), Ct);
        using var document = JsonDocument.Parse(json);

        Assert.Equal("GridPulse scheduler", document.RootElement.GetProperty("info").GetProperty("title").GetString());
        var paths = document.RootElement.GetProperty("paths");
        Assert.True(paths.TryGetProperty("/v1/schedule", out _));
        Assert.True(paths.TryGetProperty("/v1/carbon/{region}", out _));
    }
}
