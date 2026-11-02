using GridPulse.Scheduler.Core;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Time.Testing;

namespace GridPulse.Scheduler.Api.Tests;

/// <summary>Forecasts the test controls, and records what it was asked for.</summary>
internal sealed class FakeForecasts : ICarbonForecasts
{
    public ForecastSeries? Series { get; set; }

    public Exception? Throws { get; set; }

    public List<(ForecastSource Source, string Region, DateTimeOffset Start, DateTimeOffset End)> Asked { get; } = [];

    public Task<ForecastSeries> GetAsync(
        ForecastSource source, string region, DateTimeOffset windowStart, DateTimeOffset windowEnd, CancellationToken cancellationToken)
    {
        Asked.Add((source, region, windowStart, windowEnd));
        if (Throws is not null)
        {
            throw Throws;
        }

        return Task.FromResult(Series ?? new ForecastSeries(source, region, [], null, Stale: true));
    }
}

internal static class TestApp
{
    /// <summary>"Now" in the tests: 09:10 UTC, between half-hours on purpose.</summary>
    public static readonly DateTimeOffset Now = new(2030, 1, 1, 9, 10, 0, TimeSpan.Zero);

    /// <summary>
    /// The real app in memory, with forecasts and the clock replaced. The database and forecasting API settings
    /// point at closed ports, so readiness sees them down and nothing here reaches a real dependency.
    /// </summary>
    public static WebApplicationFactory<Program> Create(FakeForecasts forecasts) =>
        new WebApplicationFactory<Program>().WithWebHostBuilder(host =>
        {
            host.UseSetting("SCHEDULER_DB_PASSWORD", "unused");
            host.UseSetting("POSTGRES_HOST", "127.0.0.1");
            host.UseSetting("TIMESCALE_PORT", "1");
            host.UseSetting("FORECASTING_API_URL", "http://127.0.0.1:1");
            host.ConfigureTestServices(services =>
            {
                services.AddSingleton<ICarbonForecasts>(forecasts);
                services.AddSingleton<TimeProvider>(new FakeTimeProvider(Now));
            });
        });

    /// <summary>GridPulse-style forecasts from 09:30: one value per half-hour, each with a +/-20 g band.</summary>
    public static ForecastSeries GridPulse(params double[] intensities) => new(
        ForecastSource.GridPulse,
        Regions.GreatBritain,
        intensities.Select((g, i) => new CarbonForecast(Now.AddMinutes(20 + (30 * i)), g, g - 20, g + 20)).ToList(),
        Now.AddHours(-1),
        Stale: false);
}
