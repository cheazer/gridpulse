namespace GridPulse.Scheduler.Dashboard.Tests;

public class UkTimeTests
{
    [Fact]
    public void Summer_times_are_an_hour_ahead_of_UTC()
    {
        var noonUtc = new DateTimeOffset(2030, 7, 1, 12, 0, 0, TimeSpan.Zero);

        Assert.Equal(new DateTime(2030, 7, 1, 13, 0, 0, DateTimeKind.Unspecified), UkTime.ToUk(noonUtc));
        Assert.Equal("Mon 1 Jul 13:00", UkTime.Format(noonUtc, withDate: true));
    }

    [Fact]
    public void The_skipped_spring_hour_is_an_error()
    {
        // Clocks go forward at 01:00 GMT on 31 March 2030: 01:30 never happens.
        Assert.False(UkTime.TryFromUk(new DateTime(2030, 3, 31, 1, 30, 0, DateTimeKind.Unspecified), out _, out var error));
        Assert.Contains("clocks go forward", error, StringComparison.Ordinal);
    }

    [Fact]
    public void The_repeated_autumn_hour_means_its_first_occurrence()
    {
        // Clocks go back at 02:00 BST on 27 October 2030: 01:30 happens at 00:30 and 01:30 UTC; take the earlier.
        Assert.True(UkTime.TryFromUk(new DateTime(2030, 10, 27, 1, 30, 0, DateTimeKind.Unspecified), out var value, out _));
        Assert.Equal(new DateTimeOffset(2030, 10, 27, 0, 30, 0, TimeSpan.Zero), value.ToUniversalTime());
    }

    [Fact]
    public void Ordinary_times_follow_the_season()
    {
        Assert.True(UkTime.TryFromUk(new DateTime(2030, 7, 1, 13, 0, 0, DateTimeKind.Unspecified), out var summer, out _));
        Assert.True(UkTime.TryFromUk(new DateTime(2030, 1, 1, 13, 0, 0, DateTimeKind.Unspecified), out var winter, out _));
        Assert.Equal(new DateTimeOffset(2030, 7, 1, 12, 0, 0, TimeSpan.Zero), summer.ToUniversalTime());
        Assert.Equal(new DateTimeOffset(2030, 1, 1, 13, 0, 0, TimeSpan.Zero), winter.ToUniversalTime());
    }
}
