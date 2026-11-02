using GridPulse.Scheduler.Core;
using GridPulse.Scheduler.Dashboard.Components.Pages;

namespace GridPulse.Scheduler.Dashboard.Tests;

public class ScheduleTests : PageTests
{
    private static readonly DateTimeOffset Start = new(2030, 1, 2, 2, 0, 0, TimeSpan.Zero);

    [Fact]
    public void The_deadline_defaults_to_this_time_tomorrow_on_the_hour()
    {
        var page = Context.Render<Schedule>();

        Assert.Equal("2030-01-02T09:00:00", page.Find("input[aria-label=Deadline]").GetAttribute("value"));
    }

    [Fact]
    public void Submits_the_job_and_shows_the_best_window_and_saving()
    {
        Api.Schedule = new ScheduleResult(
            new ScheduleResponse(
                new Window(Start, Start.AddHours(3), 100, 90, 110, 15, 13.5, 16.5),
                new Window(FakeSchedulerApi.Now, FakeSchedulerApi.Now.AddHours(3), 200, 190, 210, 30, 28.5, 31.5),
                15,
                12,
                18,
                50,
                LimitedByForecast: false,
                new ForecastInfo(ForecastSource.GridPulse, Regions.GreatBritain, FakeSchedulerApi.Now, Stale: false)),
            null);
        var page = Context.Render<Schedule>();

        page.Find("input[aria-label=Hours]").Change("3");
        page.Find("input[aria-label=Energy]").Change("100");
        page.Find("form").Submit();

        var job = Assert.Single(Api.Jobs);
        var deadline = new DateTimeOffset(2030, 1, 2, 9, 0, 0, TimeSpan.Zero);
        Assert.Equal(new JobRequest(180, 100, deadline, Regions.GreatBritain, ForecastSource.GridPulse), job);
        var result = page.Find("[aria-label=Result]").TextContent;
        Assert.Contains("Run it Wed 2 Jan 02:00 to Wed 05:00", result, StringComparison.Ordinal);
        Assert.Contains("15.0 kg CO2", result, StringComparison.Ordinal);
        Assert.Contains("(50%)", result, StringComparison.Ordinal);
        Assert.Contains("between 12.0 and 18.0 kg", result, StringComparison.Ordinal);
    }

    [Fact]
    public void Regions_are_scheduled_on_the_NESO_forecast()
    {
        var page = Context.Render<Schedule>();

        page.Find("select[aria-label=Region]").Change("london");
        page.Find("form").Submit();

        Assert.Equal(ForecastSource.Neso, Assert.Single(Api.Jobs).Source);
    }

    [Fact]
    public void A_deadline_in_the_skipped_spring_hour_is_refused_before_calling_the_API()
    {
        var page = Context.Render<Schedule>();

        page.Find("input[aria-label=Deadline]").Change("2030-03-31T01:30");
        page.Find("form").Submit();

        Assert.Empty(Api.Jobs);
        Assert.Contains("clocks go forward", page.Find(".problem").TextContent, StringComparison.Ordinal);
    }

    [Fact]
    public void The_API_s_explanation_is_shown_when_there_is_no_schedule()
    {
        Api.Schedule = new ScheduleResult(null, "The job doesn't fit before its deadline.");
        var page = Context.Render<Schedule>();

        page.Find("form").Submit();

        Assert.Equal("The job doesn't fit before its deadline.", page.Find(".problem").TextContent);
        Assert.Empty(page.FindAll("[aria-label=Result]"));
    }

    [Fact]
    public void Invalid_input_is_caught_by_the_form()
    {
        var page = Context.Render<Schedule>();

        page.Find("input[aria-label=Hours]").Change("0");
        page.Find("form").Submit();

        Assert.Empty(Api.Jobs);
        Assert.Contains("Between half an hour and a week.", page.Markup, StringComparison.Ordinal);
    }
}
