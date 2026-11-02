namespace GridPulse.Scheduler.Core;

/// <summary>
/// The GB settlement-period grid: electricity is traded, metered and forecast in half-hours starting on :00 and
/// :30. Everything here works in UTC, where every half-hour is the same length; the UK clock (46 or 50 half-hours
/// on clock-change days) only matters when talking to people.
/// </summary>
public static class HalfHours
{
    public static readonly TimeSpan Length = TimeSpan.FromMinutes(30);

    /// <summary>The start of the half-hour containing <paramref name="time"/>, in UTC.</summary>
    public static DateTimeOffset Floor(DateTimeOffset time)
    {
        var utc = time.ToUniversalTime();
        return new DateTimeOffset(utc.Ticks - (utc.Ticks % Length.Ticks), TimeSpan.Zero);
    }

    /// <summary>The first half-hour boundary at or after <paramref name="time"/>, in UTC.</summary>
    public static DateTimeOffset Ceiling(DateTimeOffset time)
    {
        var floor = Floor(time);
        return floor == time ? floor : floor + Length;
    }

    public static bool IsBoundary(DateTimeOffset time) => Floor(time) == time;

    /// <summary>Every half-hour starting in [<paramref name="start"/>, <paramref name="end"/>), in UTC.</summary>
    /// <exception cref="ArgumentException">Either end is not on a half-hour boundary.</exception>
    public static IEnumerable<DateTimeOffset> Between(DateTimeOffset start, DateTimeOffset end)
    {
        if (!IsBoundary(start) || !IsBoundary(end))
        {
            throw new ArgumentException($"{start:o} and {end:o} must both be half-hour boundaries");
        }

        for (var t = start.ToUniversalTime(); t < end; t += Length)
        {
            yield return t;
        }
    }

    /// <summary>How many whole half-hours a duration takes, rounding up: a 40-minute job occupies two.</summary>
    public static int Covering(TimeSpan duration)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(duration, TimeSpan.Zero);
        return (int)Math.Ceiling(duration / Length);
    }
}
