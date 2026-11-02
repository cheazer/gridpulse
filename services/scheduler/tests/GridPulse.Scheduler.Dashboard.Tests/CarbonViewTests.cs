using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Dashboard.Tests;

public class CarbonViewTests
{
    private static readonly DateTimeOffset Now = new(2030, 7, 1, 12, 10, 0, TimeSpan.Zero);

    private static Timeline Neso(params double[] values)
    {
        var start = HalfHours.Floor(Now);
        return new Timeline(
            new ForecastInfo(ForecastSource.Neso, Regions.GreatBritain, Now, Stale: false),
            [.. values.Select((v, i) => new CarbonForecast(start.AddMinutes(30 * i), v))]);
    }

    [Fact]
    public void The_cleanest_half_hour_is_looked_for_within_the_next_24_hours_only()
    {
        // 47 half-hours of 100, a 50 at the end of the day, then a cleaner 10 just beyond it.
        var neso = Neso([.. Enumerable.Repeat(100.0, 47), 50, 10]);

        var view = CarbonView.From(Regions.GreatBritain, neso, null, [], Now);

        Assert.Equal(50, view.CleanestNextDay!.Expected);
        Assert.Equal(100, view.Now!.Expected);
    }

    [Fact]
    public void Points_are_plotted_on_the_UK_clock_with_GridPulse_s_band()
    {
        var gridPulse = new Timeline(
            new ForecastInfo(ForecastSource.GridPulse, Regions.GreatBritain, Now, Stale: true),
            [new CarbonForecast(HalfHours.Floor(Now), 100, 80, 130)]);

        var view = CarbonView.From(Regions.GreatBritain, Neso(100), gridPulse, [], Now);

        var expected = new ChartPoint(new DateTime(2030, 7, 1, 13, 0, 0, DateTimeKind.Unspecified), 100, 80, 130);
        Assert.Equal(expected, Assert.Single(view.GridPulse));
        Assert.True(view.GridPulseStale);
        Assert.False(view.NesoStale);
    }

    [Theory]
    [InlineData(new[] { 50.0, 100, 100, 100 }, "cleaner than 100% of the next 24 hours")]
    [InlineData(new[] { 150.0, 100, 100, 200 }, "dirtier than 67% of the next 24 hours")]
    [InlineData(new[] { 100.0, 100, 50, 150 }, "cleaner than 33% of the next 24 hours")]
    public void Now_is_compared_with_the_rest_of_the_day(double[] values, string expected)
    {
        var neso = Neso(values);

        Assert.Equal(expected, CarbonView.From(Regions.GreatBritain, neso, null, [], Now).NowComparedWithNextDay(neso));
    }

    [Fact]
    public void No_forecast_for_now_means_no_comparison()
    {
        var neso = Neso(100, 90);

        var view = CarbonView.From(Regions.GreatBritain, neso, null, [], Now.AddHours(-1));

        Assert.Null(view.Now);
        Assert.Null(view.NowComparedWithNextDay(neso));
    }
}
