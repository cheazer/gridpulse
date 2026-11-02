using System.Globalization;

namespace GridPulse.Scheduler.Dashboard;

/// <summary>
/// The dashboard talks to people on the UK clock; everything behind it is UTC. Converting between them is where
/// clock changes bite: one spring hour never happens, one autumn hour happens twice.
/// </summary>
public static class UkTime
{
    private static readonly TimeZoneInfo London = TimeZoneInfo.FindSystemTimeZoneById("Europe/London");

    public static DateTime ToUk(DateTimeOffset time) => TimeZoneInfo.ConvertTime(time, London).DateTime;

    /// <summary>e.g. "Tue 02:00", or "Tue 3 Nov 02:00" with <paramref name="withDate"/>.</summary>
    public static string Format(DateTimeOffset time, bool withDate = false) =>
        ToUk(time).ToString(withDate ? "ddd d MMM HH:mm" : "ddd HH:mm", CultureInfo.InvariantCulture);

    /// <summary>
    /// A wall-clock time typed by someone in the UK, as an instant. The skipped spring hour is an error, since that
    /// time never happens; the repeated autumn hour takes its first occurrence (BST), the earlier and safer deadline.
    /// </summary>
    public static bool TryFromUk(DateTime local, out DateTimeOffset value, out string? error)
    {
        local = DateTime.SpecifyKind(local, DateTimeKind.Unspecified);
        if (London.IsInvalidTime(local))
        {
            value = default;
            error = $"{local:HH:mm} doesn't happen on {local:d MMM}: the clocks go forward an hour";
            return false;
        }

        var offset = London.IsAmbiguousTime(local)
            ? London.GetAmbiguousTimeOffsets(local).Max()
            : London.GetUtcOffset(local);
        value = new DateTimeOffset(local, offset);
        error = null;
        return true;
    }
}
