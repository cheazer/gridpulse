using ApexCharts;
using GridPulse.Scheduler.Dashboard.Components;

namespace GridPulse.Scheduler.Dashboard.Tests;

/// <summary>
/// The chart draws in the browser, through ApexCharts' JavaScript, which bUnit doesn't run (loose JS interop answers
/// every call with a default). This checks the Blazor side: the component and its options render without throwing.
/// </summary>
public class CarbonChartTests
{
    [Fact]
    public void Renders_with_and_without_GridPulse_s_band()
    {
        using var context = new BunitContext();
        context.JSInterop.Mode = JSRuntimeMode.Loose;
        context.Services.AddApexCharts();
        var start = new DateTime(2030, 7, 1, 13, 0, 0, DateTimeKind.Unspecified);
        ChartPoint[] points = [new(start, 100, 80, 120), new(start.AddMinutes(30), 90, 70, 110)];

        var withBand = context.Render<CarbonChart>(p => p.Add(c => c.Neso, points).Add(c => c.GridPulse, points).Add(c => c.Title, "GB"));
        var nesoOnly = context.Render<CarbonChart>(p => p.Add(c => c.Neso, points));

        Assert.NotEmpty(withBand.FindAll(".chart"));
        Assert.NotEmpty(nesoOnly.FindAll(".chart"));
    }
}
