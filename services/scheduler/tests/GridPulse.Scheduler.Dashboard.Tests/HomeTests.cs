using GridPulse.Scheduler.Core;
using GridPulse.Scheduler.Dashboard.Components.Pages;

namespace GridPulse.Scheduler.Dashboard.Tests;

public class HomeTests : PageTests
{
    [Fact]
    public void Home_page_asks_the_question_the_product_answers()
    {
        var page = Context.Render<Home>();

        Assert.Equal("When is the grid cleanest?", page.Find("h1").TextContent);
    }

    [Fact]
    public async Task Shows_now_and_the_cleanest_half_hour_of_the_next_day()
    {
        // 200 now, falling by 1 each half-hour: the cleanest of the next 48 is the last, 153, at 08:30 tomorrow.
        var page = Context.Render<Home>();

        await page.WaitForAssertionAsync(() =>
        {
            Assert.Contains("200", page.Find("[aria-label=Now] .card-value").TextContent, StringComparison.Ordinal);
            Assert.Contains("dirtier than 100%", page.Find("[aria-label=Now]").TextContent, StringComparison.Ordinal);
            var cleanest = page.Find("[aria-label='Cleanest in the next 24 hours']").TextContent;
            Assert.Contains("153", cleanest, StringComparison.Ordinal);
            Assert.Contains("Wed 08:30", cleanest, StringComparison.Ordinal);
        });
    }

    [Fact]
    public async Task Great_Britain_gets_GridPulse_and_regions_get_NESO_only()
    {
        var page = Context.Render<Home>();
        await page.WaitForAssertionAsync(() => Assert.Equal(2, Api.Timelines.Count));

        page.Find("select").Change("london");

        await page.WaitForAssertionAsync(() => Assert.Equal(("london", ForecastSource.Neso), Api.Timelines[^1]));
        Assert.Equal(3, Api.Timelines.Count);
        Assert.Contains("regions show forecasts only", page.Markup, StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_stale_forecast_is_flagged()
    {
        Api.NesoStale = true;

        var page = Context.Render<Home>();

        await page.WaitForAssertionAsync(() =>
            Assert.Contains("NESO's forecast is out of date", page.Find(".warning").TextContent, StringComparison.Ordinal));
    }

    [Fact]
    public async Task An_outage_says_so_instead_of_breaking()
    {
        Api.Down = true;

        var page = Context.Render<Home>();

        await page.WaitForAssertionAsync(() =>
            Assert.Equal("Forecasts are unavailable right now. Try again in a minute.", page.Find(".problem").TextContent));
    }

    [Theory]
    [InlineData("GB", "Great Britain")]
    [InlineData("north-wales-merseyside", "North Wales Merseyside")]
    public void Regions_have_readable_names(string region, string label) => Assert.Equal(label, Home.Label(region));
}
