using Microsoft.AspNetCore.Diagnostics;

namespace GridPulse.Scheduler.Api;

/// <summary>
/// A body that can't be read (malformed JSON, a timestamp without a zone) is the client's mistake: 400 with the reason,
/// in every environment. In Development, Minimal APIs throw these rather than answering, so without this handler they
/// would surface as 500s.
/// </summary>
internal sealed class BadRequestHandler(IProblemDetailsService problemDetails) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext httpContext, Exception exception, CancellationToken cancellationToken)
    {
        if (exception is not BadHttpRequestException bad)
        {
            return false;
        }

        httpContext.Response.StatusCode = bad.StatusCode;
        return await problemDetails.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = httpContext,
            Exception = exception,
            ProblemDetails =
            {
                Status = bad.StatusCode,
                Title = "The request couldn't be read",
                // The innermost message is the useful one, e.g. the timestamp converter's "timestamps need a UTC offset".
                Detail = (bad.InnerException?.InnerException ?? bad.InnerException ?? bad).Message,
            },
        }).ConfigureAwait(false);
    }
}
