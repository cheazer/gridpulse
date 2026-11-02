using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Core.Tests;

public class ScheduleRequestTests
{
    private static readonly DateTimeOffset Nine = new(2026, 11, 2, 9, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Times_are_normalised_to_utc()
    {
        var job = new ScheduleRequest(TimeSpan.FromHours(1), 10, Nine.ToOffset(TimeSpan.FromHours(1)), Nine.AddHours(5));

        Assert.Equal(TimeSpan.Zero, job.EarliestStart.Offset);
        Assert.Equal(Nine, job.EarliestStart);
    }

    [Fact]
    public void Rejects_a_deadline_before_the_earliest_start()
    {
        Assert.Throws<ArgumentException>(() => new ScheduleRequest(TimeSpan.FromHours(1), 10, Nine, Nine.AddHours(-1)));
    }

    [Theory]
    [InlineData(0, 10)]
    [InlineData(60, 0)]
    [InlineData(60, -5)]
    public void Rejects_non_positive_durations_and_energy(int minutes, double kwh)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            new ScheduleRequest(TimeSpan.FromMinutes(minutes), kwh, Nine, Nine.AddHours(5)));
    }
}
