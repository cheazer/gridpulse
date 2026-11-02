using Scalar.AspNetCore;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddOpenApi(options => options.AddDocumentTransformer((document, _, _) =>
{
    document.Info.Title = "GridPulse scheduler";
    document.Info.Description = "Finds the lowest-carbon window for flexible electricity use in Great Britain.";
    return Task.CompletedTask;
}));

var app = builder.Build();

app.MapOpenApi();
app.MapScalarApiReference();

// Liveness: the process is up. Readiness, which checks the forecasting API and the database, comes with them (3.3).
app.MapGet("/health", () => TypedResults.Ok(new { status = "ok" })).WithTags("ops");

app.Run();
