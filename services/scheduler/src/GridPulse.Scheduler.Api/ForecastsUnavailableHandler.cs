using Microsoft.AspNetCore.Diagnostics;
using Npgsql;
using Polly.CircuitBreaker;
using Polly.Timeout;

namespace GridPulse.Scheduler.Api;

/// <summary>
/// Turns "couldn't get forecasts" into 503 Service Unavailable with a problem details body, rather than a 500: the
/// request was fine, a dependency wasn't, and retrying later may work. Covers the forecasting API failing after its
/// retries (HttpRequestException, a timeout, or an open circuit) and the database being unreachable.
/// </summary>
internal sealed partial class ForecastsUnavailableHandler(
    IProblemDetailsService problemDetails, ILogger<ForecastsUnavailableHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext httpContext, Exception exception, CancellationToken cancellationToken)
    {
        var dependency = exception switch
        {
            HttpRequestException or TimeoutRejectedException or BrokenCircuitException => "the forecasting API",
            NpgsqlException => "the database",
            _ => null,
        };
        if (dependency is null)
        {
            return false; // a real bug: let the default handler log it and return 500
        }

        LogUnavailable(logger, dependency, exception.GetType().Name);
        httpContext.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
        httpContext.Response.Headers.RetryAfter = "30";
        return await problemDetails.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = httpContext,
            Exception = exception,
            ProblemDetails =
            {
                Status = StatusCodes.Status503ServiceUnavailable,
                Title = "Forecasts are unavailable",
                Detail = $"Couldn't reach {dependency}. Try again shortly.",
            },
        }).ConfigureAwait(false);
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "Forecasts unavailable: {Dependency} failed with {Exception}")]
    private static partial void LogUnavailable(ILogger logger, string dependency, string exception);
}
