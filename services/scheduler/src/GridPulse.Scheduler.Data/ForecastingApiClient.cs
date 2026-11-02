using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.Net.Http.Json;
using System.Text.Json;
using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Data;

/// <summary>
/// GridPulse's own carbon intensity forecasts, from the forecasting service's API (step 2.6b): the service that owns
/// them, and its contract (ADR-0013). Timeouts, retries and the circuit breaker are added where the client is
/// registered (<see cref="ServiceCollectionExtensions.AddSchedulerData"/>), not here.
/// </summary>
public sealed class ForecastingApiClient(HttpClient http)
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower };

    public async Task<ForecastSeries> CarbonAsync(DateTimeOffset start, DateTimeOffset end, CancellationToken cancellationToken)
    {
        // The API insists on timestamps with a zone ("18:00" is ambiguous on the UK clock), so send UTC with a Z.
        var uri = new Uri(
            string.Create(CultureInfo.InvariantCulture, $"v1/forecasts/carbon?from={Utc(start)}&to={Utc(end)}"),
            UriKind.Relative);
        var body = await http.GetFromJsonAsync<Response>(uri, Json, cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidOperationException("the forecasting API returned an empty body");

        var halfHours = body.HalfHours
            .Select(h => new CarbonForecast(h.ValidFrom, h.P50, h.P10, h.P90))
            .ToList();
        return new ForecastSeries(ForecastSource.GridPulse, Regions.GreatBritain, halfHours, body.LatestIssuedAt, body.Stale);
    }

    private static string Utc(DateTimeOffset time) =>
        Uri.EscapeDataString(time.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture));

    // The forecasting API's response (services/forecasting/src/gridpulse_forecasting/api.py), the parts used here.
    [SuppressMessage("Performance", "CA1812", Justification = "Instantiated by System.Text.Json")]
    private sealed record Response(DateTimeOffset? LatestIssuedAt, bool Stale, IReadOnlyList<HalfHour> HalfHours);

    [SuppressMessage("Performance", "CA1812", Justification = "Instantiated by System.Text.Json")]
    private sealed record HalfHour(DateTimeOffset ValidFrom, double P10, double P50, double P90);
}
