using System.Net;
using System.Text;
using GridPulse.Scheduler.Core;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace GridPulse.Scheduler.Data.Tests;

/// <summary>
/// The client exactly as registered (resilience handler included), with the network replaced by a stub that answers
/// from a script: so retries, what is and isn't retried, and the JSON mapping are all tested for real.
/// </summary>
public class ForecastingApiClientTests
{
    private const string Body = """
        {"task": "carbon", "unit": "gCO2/kWh", "window_from": "2030-01-01T10:00:00Z", "window_to": "2030-01-01T11:00:00Z",
         "latest_issued_at": "2030-01-01T06:00:00Z", "stale": false,
         "half_hours": [
           {"valid_from": "2030-01-01T10:00:00Z", "valid_to": "2030-01-01T10:30:00Z", "p10": 120.0, "p50": 150.0,
            "p90": 190.0, "issued_at": "2030-01-01T06:00:00Z", "model": "lightgbm-cqr", "model_version": "gridpulse-carbon/v1"},
           {"valid_from": "2030-01-01T10:30:00Z", "valid_to": "2030-01-01T11:00:00Z", "p10": 110.0, "p50": 140.0,
            "p90": 170.0, "issued_at": "2030-01-01T06:00:00Z", "model": "lightgbm-cqr", "model_version": "gridpulse-carbon/v1"}
         ]}
        """;

    private static readonly DateTimeOffset Ten = new(2030, 1, 1, 10, 0, 0, TimeSpan.Zero);

    private sealed class ScriptedServer(params HttpStatusCode[] script) : HttpMessageHandler
    {
        public List<Uri> Requests { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(request.RequestUri!);
            var status = script[Math.Min(Requests.Count - 1, script.Length - 1)];
            return Task.FromResult(new HttpResponseMessage(status)
            {
                Content = new StringContent(status == HttpStatusCode.OK ? Body : "{}", Encoding.UTF8, "application/json"),
            });
        }
    }

    private static (ForecastingApiClient Client, ScriptedServer Server, ServiceProvider Services) Client(params HttpStatusCode[] script)
    {
        var server = new ScriptedServer(script);
        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["FORECASTING_API_URL"] = "http://forecasting.test:8000",
                ["SCHEDULER_DB_PASSWORD"] = "unused",
            })
            .Build();
        var services = new ServiceCollection()
            .AddSchedulerData(configuration, resilience => resilience.Retry.Delay = TimeSpan.FromMilliseconds(1));
        services.AddHttpClient<ForecastingApiClient>().ConfigurePrimaryHttpMessageHandler(() => server);
        var provider = services.BuildServiceProvider();
        return (provider.GetRequiredService<ForecastingApiClient>(), server, provider);
    }

    [Fact]
    public async Task Maps_the_median_and_the_p10_p90_band_and_asks_in_utc()
    {
        var (client, server, services) = Client(HttpStatusCode.OK);
        using var _ = services;

        var series = await client.CarbonAsync(Ten.ToOffset(TimeSpan.FromHours(1)), Ten.AddHours(1), TestContext.Current.CancellationToken);

        var first = series.HalfHours[0];
        Assert.Equal((Ten, 150.0, 120.0, 190.0), (first.Start, first.Expected, first.Low!.Value, first.High!.Value));
        Assert.Equal(2, series.HalfHours.Count);
        Assert.Equal(new DateTimeOffset(2030, 1, 1, 6, 0, 0, TimeSpan.Zero), series.LatestIssuedAt);
        Assert.False(series.Stale);
        Assert.Equal(
            "http://forecasting.test:8000/v1/forecasts/carbon?from=2030-01-01T10%3A00%3A00Z&to=2030-01-01T11%3A00%3A00Z",
            Assert.Single(server.Requests).AbsoluteUri);
    }

    [Fact]
    public async Task Transient_failures_are_retried()
    {
        var (client, server, services) = Client(HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway, HttpStatusCode.OK);
        using var _ = services;

        var series = await client.CarbonAsync(Ten, Ten.AddHours(1), TestContext.Current.CancellationToken);

        Assert.Equal(3, server.Requests.Count);
        Assert.Equal(2, series.HalfHours.Count);
    }

    [Fact]
    public async Task A_bad_request_is_not_retried()
    {
        var (client, server, services) = Client(HttpStatusCode.UnprocessableEntity);
        using var _ = services;

        await Assert.ThrowsAsync<HttpRequestException>(() => client.CarbonAsync(Ten, Ten.AddHours(1), TestContext.Current.CancellationToken));
        Assert.Single(server.Requests);
    }

    [Fact]
    public async Task A_service_that_stays_down_fails_after_the_retries()
    {
        var (client, server, services) = Client(HttpStatusCode.ServiceUnavailable);
        using var _ = services;

        await Assert.ThrowsAsync<HttpRequestException>(() => client.CarbonAsync(Ten, Ten.AddHours(1), TestContext.Current.CancellationToken));
        Assert.Equal(4, server.Requests.Count); // the first try and three retries
    }

    [Theory]
    [InlineData(ForecastSource.GridPulse, "london")] // GridPulse forecasts Great Britain only
    [InlineData(ForecastSource.Neso, "atlantis")]
    public async Task Unknown_or_unsupported_regions_are_rejected_before_any_call(ForecastSource source, string region)
    {
        var (_, server, services) = Client(HttpStatusCode.OK);
        using var __ = services;

        await Assert.ThrowsAsync<ArgumentException>(() => services.GetRequiredService<ICarbonForecasts>()
            .GetAsync(source, region, Ten, Ten.AddHours(1), TestContext.Current.CancellationToken));
        Assert.Empty(server.Requests);
    }
}
