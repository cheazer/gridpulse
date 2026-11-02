namespace GridPulse.Scheduler.Core;

/// <summary>Measured carbon intensity for one half-hour, in gCO2/kWh: what actually happened.</summary>
public sealed record CarbonReading(DateTimeOffset Start, double Intensity);

/// <summary>Where measured carbon intensity comes from. Core depends on this; the Data project implements it.</summary>
public interface ICarbonActuals
{
    /// <summary>
    /// Readings for each half-hour starting in [<paramref name="windowStart"/>, <paramref name="windowEnd"/>) that has
    /// one, in time order. NESO publishes actuals nationally; for a region the list is empty.
    /// </summary>
    Task<IReadOnlyList<CarbonReading>> GetAsync(
        string region,
        DateTimeOffset windowStart,
        DateTimeOffset windowEnd,
        CancellationToken cancellationToken);
}
