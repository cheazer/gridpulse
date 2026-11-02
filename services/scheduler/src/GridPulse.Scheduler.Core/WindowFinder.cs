namespace GridPulse.Scheduler.Core;

/// <summary>
/// Finds the lowest-carbon window for a job, in one pass over the forecast (ADR-0013).
/// </summary>
/// <remarks>
/// A job of duration d occupies n = ceil(d / 30 min) half-hours, the last one only partly: a 1h40m job uses all of
/// three and two-thirds of the fourth. Its average intensity is the weighted mean over those half-hours. With
/// prefix sums over the forecast, every candidate start costs O(1), so the search is O(m) in the number of forecast
/// half-hours rather than O(m x n). Candidates start on half-hour boundaries, the grid forecasts are made on.
/// </remarks>
public static class WindowFinder
{
    public static ScheduleOutcome Find(IReadOnlyList<CarbonForecast> forecast, ScheduleRequest request, DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(forecast);
        ArgumentNullException.ThrowIfNull(request);

        var slots = HalfHours.Covering(request.Duration);
        // The share of the last half-hour the job uses: 1 when the duration is a whole number of half-hours.
        var lastShare = (request.Duration - (HalfHours.Length * (slots - 1))) / HalfHours.Length;
        var weight = slots - 1 + lastShare;

        var firstStart = HalfHours.Ceiling(request.EarliestStart > now ? request.EarliestStart : now);
        var lastStart = HalfHours.Floor(request.Deadline - request.Duration);
        if (lastStart < firstStart)
        {
            return new NotScheduled(
                NotScheduledReason.DoesNotFit,
                $"a {request.Duration} job can't start at or after {firstStart:u} and finish by {request.Deadline:u}");
        }

        var byStart = Index(forecast);
        var span = HalfHours.Between(firstStart, lastStart + (HalfHours.Length * slots)).ToArray();
        var sums = new PrefixSums(span.Select(t => byStart.GetValueOrDefault(t)).ToArray());

        Window? earliest = null;
        Window? best = null;
        var candidates = (int)((lastStart - firstStart) / HalfHours.Length) + 1;
        for (var k = 0; k < candidates; k++)
        {
            if (!sums.Covers(k, slots))
            {
                continue; // a half-hour of this window has no forecast
            }

            var window = sums.Window(k, slots, lastShare, weight, span[k], request);
            earliest ??= window;
            if (best is null || window.EmissionsKg < best.EmissionsKg)
            {
                best = window; // strictly lower: of equally clean windows, the earliest wins and the job finishes sooner
            }
        }

        if (best is null || earliest is null)
        {
            return new NotScheduled(
                NotScheduledReason.NoForecastCoverage,
                $"no {slots}-half-hour window between {firstStart:u} and {request.Deadline:u} is fully forecast");
        }

        var lastForecastEnd = forecast.Max(f => f.Start) + HalfHours.Length;
        var (savingLow, savingHigh) = SavingRange(best, earliest);
        return new Scheduled(
            best,
            earliest,
            earliest.EmissionsKg - best.EmissionsKg,
            savingLow,
            savingHigh,
            LimitedByForecast: lastForecastEnd < request.Deadline);
    }

    /// <summary>
    /// The saving's range, like with like: forecast errors are strongly correlated from one half-hour to the next, so
    /// both windows tend to come in low together or high together. The saving is worked out at each quantile level
    /// the forecast has (P10 with P10, P50 with P50, P90 with P90) and the range spans all three. Bands are often
    /// lopsided (a P10 far below the P50, a P90 just above it), so the P10 and P90 savings alone can both fall on the
    /// same side of the expected one; a range that left the expected saving out would contradict itself.
    /// </summary>
    private static (double? Low, double? High) SavingRange(Window best, Window earliest)
    {
        if (best.EmissionsLowKg is not { } bestLow || earliest.EmissionsLowKg is not { } earliestLow
            || best.EmissionsHighKg is not { } bestHigh || earliest.EmissionsHighKg is not { } earliestHigh)
        {
            return (null, null);
        }

        double[] savings = [earliestLow - bestLow, earliest.EmissionsKg - best.EmissionsKg, earliestHigh - bestHigh];
        return (savings.Min(), savings.Max());
    }

    private static Dictionary<DateTimeOffset, CarbonForecast> Index(IReadOnlyList<CarbonForecast> forecast)
    {
        var byStart = new Dictionary<DateTimeOffset, CarbonForecast>(forecast.Count);
        foreach (var f in forecast)
        {
            if (!byStart.TryAdd(f.Start, f))
            {
                throw new ArgumentException($"two forecasts for the half-hour starting {f.Start:u}", nameof(forecast));
            }
        }

        return byStart;
    }

    /// <summary>Running totals over the forecast half-hours, so any window's sum is one subtraction.</summary>
    private sealed class PrefixSums
    {
        private readonly CarbonForecast?[] _slots;
        private readonly double[] _expected;
        private readonly double[] _low;
        private readonly double[] _high;
        private readonly int[] _missing;
        private readonly int[] _withoutRange;

        public PrefixSums(CarbonForecast?[] slots)
        {
            _slots = slots;
            var n = slots.Length;
            _expected = new double[n + 1];
            _low = new double[n + 1];
            _high = new double[n + 1];
            _missing = new int[n + 1];
            _withoutRange = new int[n + 1];
            for (var i = 0; i < n; i++)
            {
                var f = slots[i];
                _expected[i + 1] = _expected[i] + (f?.Expected ?? 0);
                _low[i + 1] = _low[i] + (f?.Low ?? 0);
                _high[i + 1] = _high[i] + (f?.High ?? 0);
                _missing[i + 1] = _missing[i] + (f is null ? 1 : 0);
                _withoutRange[i + 1] = _withoutRange[i] + (f is { HasRange: true } ? 0 : 1);
            }
        }

        public bool Covers(int start, int count) => _missing[start + count] == _missing[start];

        public Window Window(int start, int count, double lastShare, double weight, DateTimeOffset at, ScheduleRequest job)
        {
            var last = _slots[start + count - 1]!;
            double Mean(double[] sums, double lastValue) =>
                (sums[start + count - 1] - sums[start] + (lastShare * lastValue)) / weight;

            var intensity = Mean(_expected, last.Expected);
            var hasRange = _withoutRange[start + count] == _withoutRange[start];
            double? low = hasRange ? Mean(_low, last.Low!.Value) : null;
            double? high = hasRange ? Mean(_high, last.High!.Value) : null;
            // kWh x gCO2/kWh = g; /1000 for kg.
            var kg = job.EnergyKwh / 1000;
            return new Window(at, at + job.Duration, intensity, low, high, intensity * kg, low * kg, high * kg);
        }
    }
}
