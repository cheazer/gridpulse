using GridPulse.Scheduler.Core;

namespace GridPulse.Scheduler.Core.Tests;

public class WindowFinderTests
{
    private static readonly DateTimeOffset Midnight = new(2026, 11, 2, 0, 0, 0, TimeSpan.Zero);

    private static DateTimeOffset At(double hours) => Midnight.AddHours(hours);

    /// <summary>Point forecasts, one per half-hour from midnight.</summary>
    private static List<CarbonForecast> Forecast(params double[] intensities) =>
        intensities.Select((g, i) => new CarbonForecast(At(i * 0.5), g)).ToList();

    private static ScheduleRequest Job(double hours, double kwh = 10, double earliest = 0, double deadline = 24) =>
        new(TimeSpan.FromHours(hours), kwh, At(earliest), At(deadline));

    private static Scheduled Schedule(List<CarbonForecast> forecast, ScheduleRequest job, double nowHours = 0)
    {
        var outcome = WindowFinder.Find(forecast, job, At(nowHours));
        return Assert.IsType<Scheduled>(outcome);
    }

    [Fact]
    public void Picks_the_cleanest_window_and_compares_it_with_running_now()
    {
        // 1-hour job: the cleanest pair of half-hours is 02:00-03:00 (100, 120).
        var result = Schedule(Forecast(300, 280, 250, 260, 100, 120, 200, 220), Job(1, kwh: 50, deadline: 4));

        Assert.Equal(At(2), result.Best.Start);
        Assert.Equal(At(3), result.Best.End);
        Assert.Equal(110, result.Best.Intensity, precision: 9);
        Assert.Equal(At(0), result.Earliest.Start);
        // 50 kWh at 110 g/kWh is 5.5 kg; at 290 g/kWh, 14.5 kg.
        Assert.Equal(5.5, result.Best.EmissionsKg, precision: 9);
        Assert.Equal(9.0, result.SavingKg, precision: 9);
    }

    [Fact]
    public void A_partial_last_half_hour_counts_for_the_share_of_it_the_job_uses()
    {
        // 40 minutes: all of one half-hour and a third of the next. Starting at 00:00: (100 + 400 / 3) / (4 / 3) = 175.
        var result = Schedule(Forecast(100, 400, 400, 400), Job(40 / 60.0, deadline: 2));

        Assert.Equal(At(0), result.Best.Start);
        Assert.Equal(At(0) + TimeSpan.FromMinutes(40), result.Best.End);
        Assert.Equal(175, result.Best.Intensity, precision: 9);
    }

    [Fact]
    public void Equally_clean_windows_go_to_the_earliest_so_the_job_finishes_sooner()
    {
        var result = Schedule(Forecast(200, 100, 100, 200, 100, 100), Job(1, deadline: 3));

        Assert.Equal(At(0.5), result.Best.Start);
    }

    [Theory]
    [InlineData(0.2, 0, 0.5)] // an earliest start between boundaries rounds up to the next half-hour
    [InlineData(0, 1.1, 1.5)] // so does "now", when it is later than the earliest start
    public void Never_starts_before_the_later_of_the_earliest_start_and_now(double earliest, double now, double expected)
    {
        var result = Schedule(Forecast(50, 50, 50, 50, 300, 300, 300, 300), Job(0.5, earliest: earliest, deadline: 4), now);

        Assert.Equal(At(expected), result.Earliest.Start);
        Assert.True(result.Best.Start >= At(expected));
    }

    [Fact]
    public void Finishes_by_the_deadline()
    {
        // The cleanest half-hour (03:00) would end at 03:30, after a 03:15 deadline; the next cleanest is 02:30.
        var result = Schedule(Forecast(300, 300, 300, 300, 300, 250, 10), Job(0.5, deadline: 3.25));

        Assert.True(result.Best.End <= At(3.25));
        Assert.Equal(At(2.5), result.Best.Start);
    }

    [Fact]
    public void A_job_longer_than_the_time_available_does_not_fit()
    {
        var outcome = WindowFinder.Find(Forecast(100, 100, 100, 100), Job(3, earliest: 0, deadline: 2), At(0));

        Assert.Equal(NotScheduledReason.DoesNotFit, Assert.IsType<NotScheduled>(outcome).Reason);
    }

    [Fact]
    public void Windows_never_include_a_half_hour_without_a_forecast()
    {
        // 01:00 is missing, and it is surrounded by the cleanest half-hours.
        var forecast = Forecast(300, 300, 0, 50, 50, 300, 300);
        forecast.RemoveAt(2);

        var result = Schedule(forecast, Job(1, deadline: 3.5));

        Assert.Equal(At(1.5), result.Best.Start); // 01:30-02:30, the clean pair after the gap
    }

    [Fact]
    public void Gaps_everywhere_mean_no_window_rather_than_a_guess()
    {
        var forecast = new List<CarbonForecast> { new(At(0), 100), new(At(1), 100), new(At(2), 100) };

        var outcome = WindowFinder.Find(forecast, Job(1, deadline: 3), At(0));

        Assert.Equal(NotScheduledReason.NoForecastCoverage, Assert.IsType<NotScheduled>(outcome).Reason);
    }

    [Fact]
    public void A_deadline_beyond_the_forecast_is_searched_as_far_as_the_forecast_goes_and_says_so()
    {
        var result = Schedule(Forecast(300, 200, 100, 150), Job(0.5, deadline: 24));

        Assert.True(result.LimitedByForecast);
        Assert.Equal(At(1), result.Best.Start);
        Assert.False(Schedule(Forecast(300, 200, 100, 150), Job(0.5, deadline: 2)).LimitedByForecast);
    }

    [Fact]
    public void Ranges_carry_through_and_the_saving_compares_like_with_like()
    {
        var forecast = new List<CarbonForecast>
        {
            new(At(0), 300, low: 250, high: 360),
            new(At(0.5), 100, low: 80, high: 130),
        };

        var result = Schedule(forecast, Job(0.5, kwh: 1000, deadline: 1));

        Assert.Equal((80.0, 130.0), (result.Best.EmissionsLowKg!.Value, result.Best.EmissionsHighKg!.Value));
        Assert.Equal(200, result.SavingKg, precision: 9);
        // P10 with P10: 250 - 80 = 170 kg; P90 with P90: 360 - 130 = 230 kg.
        Assert.Equal(170, result.SavingLowKg!.Value, precision: 9);
        Assert.Equal(230, result.SavingHighKg!.Value, precision: 9);
    }

    [Fact]
    public void Point_forecasts_give_a_saving_without_a_range()
    {
        var result = Schedule(Forecast(300, 100), Job(0.5, deadline: 1));

        Assert.Null(result.SavingLowKg);
        Assert.Null(result.Best.IntensityLow);
    }

    [Fact]
    public void Two_forecasts_for_one_half_hour_are_an_error()
    {
        var forecast = Forecast(100, 200);
        forecast.Add(new CarbonForecast(At(0), 150));

        Assert.Throws<ArgumentException>(() => WindowFinder.Find(forecast, Job(0.5, deadline: 1), At(0)));
    }

    // ---------------------------------------------------------------- against brute force

    /// <summary>
    /// The prefix-sum search must agree with a deliberately naive one: for every start, add up how many minutes the
    /// job overlaps each half-hour and average intensity by those minutes. Random forecasts with gaps and mixed
    /// ranges, random durations (including partial half-hours), random earliest starts, deadlines and "now".
    /// </summary>
    [Fact]
    public void Agrees_with_brute_force_on_random_cases()
    {
        var random = new Random(20261102);
        for (var case_ = 0; case_ < 500; case_++)
        {
            var forecast = new List<CarbonForecast>();
            var withRanges = random.Next(2) == 0;
            for (var i = 0; i < 96; i++)
            {
                if (random.NextDouble() < 0.05)
                {
                    continue; // a gap
                }

                var expected = random.Next(20, 400);
                forecast.Add(withRanges
                    ? new CarbonForecast(At(i * 0.5), expected, expected - random.Next(0, 40), expected + random.Next(0, 60))
                    : new CarbonForecast(At(i * 0.5), expected));
            }

            var duration = TimeSpan.FromMinutes(random.Next(10, 600));
            var earliest = Midnight.AddMinutes(random.Next(0, 24 * 60));
            var deadline = earliest + duration + TimeSpan.FromMinutes(random.Next(-60, 40 * 60));
            if (deadline <= earliest)
            {
                continue;
            }

            var job = new ScheduleRequest(duration, random.Next(1, 500), earliest, deadline);
            var now = Midnight.AddMinutes(random.Next(0, 24 * 60));

            var actual = WindowFinder.Find(forecast, job, now);
            var expectedBest = BruteForce(forecast, job, now);

            if (expectedBest is null)
            {
                Assert.IsType<NotScheduled>(actual);
                continue;
            }

            var scheduled = Assert.IsType<Scheduled>(actual);
            Assert.Equal(expectedBest.Value.Start, scheduled.Best.Start);
            Assert.Equal(expectedBest.Value.Intensity, scheduled.Best.Intensity, precision: 9);
        }
    }

    private static (DateTimeOffset Start, double Intensity)? BruteForce(
        List<CarbonForecast> forecast, ScheduleRequest job, DateTimeOffset now)
    {
        var byStart = forecast.ToDictionary(f => f.Start);
        (DateTimeOffset Start, double Intensity)? best = null;
        var from = HalfHours.Ceiling(job.EarliestStart > now ? job.EarliestStart : now);
        for (var start = from; start + job.Duration <= job.Deadline; start += HalfHours.Length)
        {
            var end = start + job.Duration;
            double weighted = 0;
            var covered = true;
            for (var slot = start; slot < end; slot += HalfHours.Length)
            {
                if (!byStart.TryGetValue(slot, out var f))
                {
                    covered = false;
                    break;
                }

                var overlapMinutes = ((end < slot + HalfHours.Length ? end : slot + HalfHours.Length) - slot).TotalMinutes;
                weighted += f.Expected * overlapMinutes;
            }

            if (!covered)
            {
                continue;
            }

            var intensity = weighted / job.Duration.TotalMinutes;
            if (best is null || intensity < best.Value.Intensity - 1e-9)
            {
                best = (start, intensity);
            }
        }

        return best;
    }
}
