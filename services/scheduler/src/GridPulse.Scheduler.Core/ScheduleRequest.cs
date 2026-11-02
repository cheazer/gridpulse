namespace GridPulse.Scheduler.Core;

/// <summary>
/// A flexible job: it uses <see cref="EnergyKwh"/> evenly over <see cref="Duration"/>, can start at
/// <see cref="EarliestStart"/> or later, and must finish by <see cref="Deadline"/>.
/// </summary>
public sealed record ScheduleRequest
{
    public ScheduleRequest(TimeSpan duration, double energyKwh, DateTimeOffset earliestStart, DateTimeOffset deadline)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(duration, TimeSpan.Zero);
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(energyKwh, 0);
        if (deadline <= earliestStart)
        {
            throw new ArgumentException($"the deadline {deadline:o} must be after the earliest start {earliestStart:o}");
        }

        Duration = duration;
        EnergyKwh = energyKwh;
        EarliestStart = earliestStart.ToUniversalTime();
        Deadline = deadline.ToUniversalTime();
    }

    public TimeSpan Duration { get; }

    public double EnergyKwh { get; }

    public DateTimeOffset EarliestStart { get; }

    public DateTimeOffset Deadline { get; }
}
