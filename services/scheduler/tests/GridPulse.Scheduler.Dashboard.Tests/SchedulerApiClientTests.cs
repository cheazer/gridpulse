using System.Net;
using System.Text;
using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Dashboard.Tests;

/// <summary>The client against canned HTTP responses: what it sends, and how it words what comes back.</summary>
public class SchedulerApiClientTests
{
    private static readonly JobRequest Job = new(
        180, 50, new DateTimeOffset(2030, 7, 1, 13, 0, 0, TimeSpan.FromHours(1)), Regions.GreatBritain, ForecastSource.GridPulse);

    [Fact]
    public async Task The_deadline_is_sent_in_UTC_and_the_source_by_name()
    {
        using var handler = new Canned(HttpStatusCode.UnprocessableEntity, """{"reason":"does-not-fit"}""");

        await Client(handler).ScheduleAsync(Job, Xunit.TestContext.Current.CancellationToken);

        Assert.Contains("\"deadline\":\"2030-07-01T12:00:00Z\"", handler.Body, StringComparison.Ordinal);
        Assert.Contains("\"source\":\"gridpulse\"", handler.Body, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(HttpStatusCode.UnprocessableEntity, """{"reason":"does-not-fit"}""", "doesn't fit before its deadline")]
    [InlineData(HttpStatusCode.UnprocessableEntity, """{"reason":"no-forecast-coverage"}""", "no forecast covering")]
    [InlineData(HttpStatusCode.BadRequest, """{"errors":{"energyKwh":["must be positive"]}}""", "energyKwh: must be positive.")]
    [InlineData(HttpStatusCode.ServiceUnavailable, "", "unavailable right now")]
    public async Task Problems_are_put_into_words(HttpStatusCode status, string body, string expected)
    {
        using var handler = new Canned(status, body);

        var result = await Client(handler).ScheduleAsync(Job, Xunit.TestContext.Current.CancellationToken);

        Assert.Null(result.Schedule);
        Assert.Contains(expected, result.Problem, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Timelines_parse_the_API_s_json()
    {
        using var handler = new Canned(HttpStatusCode.OK, """
            {"forecast":{"source":"gridpulse","region":"GB","latestIssuedAt":"2030-07-01T11:00:00Z","stale":false},
             "halfHours":[{"start":"2030-07-01T12:00:00Z","expected":120.5,"low":100,"high":140}]}
            """);

        var timeline = await Client(handler).TimelineAsync(Regions.GreatBritain, ForecastSource.GridPulse, Xunit.TestContext.Current.CancellationToken);

        Assert.Equal(ForecastSource.GridPulse, timeline.Forecast.Source);
        var expected = new CarbonForecast(new DateTimeOffset(2030, 7, 1, 12, 0, 0, TimeSpan.Zero), 120.5, 100, 140);
        Assert.Equal(expected, Assert.Single(timeline.HalfHours));
        Assert.EndsWith("/v1/carbon/GB?source=gridpulse", handler.Uri, StringComparison.Ordinal);
    }

    private static SchedulerApiClient Client(Canned handler) =>
        new(new HttpClient(handler, disposeHandler: false) { BaseAddress = new Uri("http://scheduler/") });

    private sealed class Canned(HttpStatusCode status, string body) : HttpMessageHandler
    {
        public string? Body { get; private set; }

        public string? Uri { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Uri = request.RequestUri!.ToString();
            Body = request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        }
    }
}
