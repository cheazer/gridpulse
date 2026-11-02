using ApexCharts;
using GridPulse.Scheduler.Dashboard;
using GridPulse.Scheduler.Dashboard.Components;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddRazorComponents().AddInteractiveServerComponents();
builder.Services.AddApexCharts();
builder.Services.AddSingleton(TimeProvider.System);

// The scheduling API, as any client would call it: retries with backoff on transient failures, timeouts, and a circuit
// breaker, the same standard pipeline the scheduler uses for the forecasting API.
var schedulerApi = builder.Configuration["SCHEDULER_API_URL"] ?? "http://localhost:8100";
builder.Services
    .AddHttpClient<ISchedulerApi, SchedulerApiClient>(client => client.BaseAddress = new Uri(schedulerApi.TrimEnd('/') + "/"))
    .AddStandardResilienceHandler();

var app = builder.Build();

if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/error", createScopeForErrors: true);
}

app.UseAntiforgery();
app.MapStaticAssets();
app.MapRazorComponents<App>().AddInteractiveServerRenderMode();
app.MapGet("/health", () => TypedResults.Ok(new { status = "ok" }));

app.Run();
