using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Core.Tests;

public class HalfHoursTests
{
    private static DateTimeOffset Utc(string iso) => DateTimeOffset.Parse(iso, System.Globalization.CultureInfo.InvariantCulture);

    [Theory]
    [InlineData("2026-11-02T09:00:00Z", "2026-11-02T09:00:00Z")]
    [InlineData("2026-11-02T09:29:59Z", "2026-11-02T09:00:00Z")]
    [InlineData("2026-11-02T09:30:00Z", "2026-11-02T09:30:00Z")]
    // 10:45 BST is 09:45 UTC: the grid is UTC whatever the offset of the input.
    [InlineData("2026-06-02T10:45:00+01:00", "2026-06-02T09:30:00Z")]
    public void Floor_returns_the_utc_half_hour_containing_the_time(string time, string expected)
    {
        var floor = HalfHours.Floor(Utc(time));

        Assert.Equal(Utc(expected), floor);
        Assert.Equal(TimeSpan.Zero, floor.Offset);
    }

    [Theory]
    [InlineData("2026-11-02T09:00:00Z", "2026-11-02T09:00:00Z")]
    [InlineData("2026-11-02T09:00:01Z", "2026-11-02T09:30:00Z")]
    public void Ceiling_returns_the_first_boundary_at_or_after_the_time(string time, string expected)
    {
        Assert.Equal(Utc(expected), HalfHours.Ceiling(Utc(time)));
    }

    [Fact]
    public void Between_lists_every_half_hour_in_a_half_open_range()
    {
        var halfHours = HalfHours.Between(Utc("2026-11-02T09:00:00Z"), Utc("2026-11-02T10:30:00Z")).ToList();

        Assert.Equal(["09:00", "09:30", "10:00"], halfHours.Select(t => t.ToString("HH:mm", null)));
    }

    [Theory]
    [InlineData("2026-03-29", 46)] // clocks go forward: a 23-hour UK day
    [InlineData("2026-06-01", 48)]
    [InlineData("2026-10-25", 50)] // clocks go back: a 25-hour UK day
    public void A_uk_day_has_46_48_or_50_half_hours_because_the_grid_is_utc(string day, int expected)
    {
        var london = TimeZoneInfo.FindSystemTimeZoneById("Europe/London");
        var date = DateOnly.Parse(day, System.Globalization.CultureInfo.InvariantCulture);
        DateTimeOffset StartOf(DateOnly d)
        {
            var local = d.ToDateTime(TimeOnly.MinValue);
            return new DateTimeOffset(local, london.GetUtcOffset(local));
        }

        var count = HalfHours.Between(StartOf(date), StartOf(date.AddDays(1))).Count();

        Assert.Equal(expected, count);
    }

    [Fact]
    public void Between_rejects_ends_that_are_not_on_the_grid()
    {
        Assert.Throws<ArgumentException>(() =>
            HalfHours.Between(Utc("2026-11-02T09:10:00Z"), Utc("2026-11-02T10:00:00Z")).ToList());
    }

    [Theory]
    [InlineData(30, 1)]
    [InlineData(40, 2)] // a 40-minute job occupies two half-hours
    [InlineData(180, 6)]
    public void Covering_rounds_a_duration_up_to_whole_half_hours(int minutes, int expected)
    {
        Assert.Equal(expected, HalfHours.Covering(TimeSpan.FromMinutes(minutes)));
    }

    [Fact]
    public void Covering_rejects_a_zero_duration()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => HalfHours.Covering(TimeSpan.Zero));
    }
}
