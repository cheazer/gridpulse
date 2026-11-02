using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Core.Tests;

public class CarbonForecastTests
{
    private static readonly DateTimeOffset NineAm = new(2026, 11, 2, 9, 0, 0, TimeSpan.Zero);

    [Fact]
    public void A_forecast_with_a_range_keeps_it_and_says_so()
    {
        var forecast = new CarbonForecast(NineAm, expected: 150, low: 120, high: 190);

        Assert.True(forecast.HasRange);
        Assert.Equal((120.0, 150.0, 190.0), (forecast.Low!.Value, forecast.Expected, forecast.High!.Value));
    }

    [Fact]
    public void A_point_forecast_has_no_range()
    {
        Assert.False(new CarbonForecast(NineAm, expected: 150).HasRange);
    }

    [Fact]
    public void Times_are_normalised_to_utc()
    {
        var forecast = new CarbonForecast(new DateTimeOffset(2026, 6, 2, 10, 0, 0, TimeSpan.FromHours(1)), 150);

        Assert.Equal(TimeSpan.Zero, forecast.Start.Offset);
        Assert.Equal(9, forecast.Start.Hour);
    }

    [Theory]
    [InlineData(160.0, 190.0)] // low above expected
    [InlineData(120.0, 140.0)] // high below expected
    [InlineData(120.0, null)] // one end without the other
    public void Inconsistent_ranges_are_rejected(double? low, double? high)
    {
        Assert.Throws<ArgumentException>(() => new CarbonForecast(NineAm, 150, low, high));
    }

    [Fact]
    public void Starts_off_the_half_hour_grid_are_rejected()
    {
        Assert.Throws<ArgumentException>(() => new CarbonForecast(NineAm.AddMinutes(10), 150));
    }
}
