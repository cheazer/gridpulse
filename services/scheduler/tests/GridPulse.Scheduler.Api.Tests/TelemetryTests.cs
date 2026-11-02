using System.Net.Http.Json;
using GridPulse.Scheduler.Telemetry;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.Metrics.Testing;

namespace GridPulse.Scheduler.Api.Tests;

/// <summary>The scheduler's own metrics, read with a MetricCollector as an exporter would see them.</summary>
public class TelemetryTests
{
    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public async Task A_scheduled_job_is_counted_and_its_saving_recorded()
    {
        var forecasts = new FakeForecasts { Series = TestApp.GridPulse(300, 280, 100, 120, 250, 260) };
        await using var app = TestApp.Create(forecasts);
        var meters = app.Services.GetRequiredService<System.Diagnostics.Metrics.IMeterFactory>();
        using var schedules = new MetricCollector<long>(meters, SchedulerMetrics.MeterName, "gridpulse.scheduler.schedules");
        using var saving = new MetricCollector<double>(meters, SchedulerMetrics.MeterName, "gridpulse.scheduler.saving");

        await Post(app, deadline: "2030-01-01T12:00:00Z");

        var count = Assert.Single(schedules.GetMeasurementSnapshot());
        Assert.Equal(1, count.Value);
        Assert.Equal("gridpulse", count.Tags["source"]);
        Assert.Equal("scheduled", count.Tags["outcome"]);
        Assert.Equal(false, count.Tags["stale"]);
        Assert.Equal(9.0, Assert.Single(saving.GetMeasurementSnapshot()).Value, precision: 9);
    }

    [Fact]
    public async Task A_job_that_cannot_be_placed_is_counted_with_its_reason_and_saves_nothing()
    {
        var forecasts = new FakeForecasts { Series = TestApp.GridPulse(300, 280, 100, 120, 250, 260) };
        await using var app = TestApp.Create(forecasts);
        var meters = app.Services.GetRequiredService<System.Diagnostics.Metrics.IMeterFactory>();
        using var schedules = new MetricCollector<long>(meters, SchedulerMetrics.MeterName, "gridpulse.scheduler.schedules");
        using var saving = new MetricCollector<double>(meters, SchedulerMetrics.MeterName, "gridpulse.scheduler.saving");

        await Post(app, deadline: "2030-01-01T09:40:00Z"); // 30 minutes away: a 60-minute job doesn't fit

        Assert.Equal("does-not-fit", Assert.Single(schedules.GetMeasurementSnapshot()).Tags["outcome"]);
        Assert.Empty(saving.GetMeasurementSnapshot());
    }

    [Theory]
    [InlineData("/health", true)]
    [InlineData("/ready", true)]
    [InlineData("/READY", true)]
    [InlineData("/v1/schedule", false)]
    [InlineData("/healthy-grid", false)]
    public void Health_probes_are_left_out_of_traces(string path, bool probe) =>
        Assert.Equal(probe, TelemetryExtensions.IsProbe(new PathString(path)));

    private static async Task Post(Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactory<Program> app, string deadline)
    {
        using var client = app.CreateClient();
        var job = new { durationMinutes = 60, energyKwh = 50, deadline, region = "GB", source = "gridpulse" };
        using var response = await client.PostAsJsonAsync(new Uri("/v1/schedule", UriKind.Relative), job, Ct);
    }
}
