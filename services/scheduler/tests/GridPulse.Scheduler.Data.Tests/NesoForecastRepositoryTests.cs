using GridPulse.Scheduler.Core;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;

namespace GridPulse.Scheduler.Data.Tests;

/// <summary>Against a real TimescaleDB with every migration applied, as the read-only 'scheduler' login.</summary>
public class NesoForecastRepositoryTests(DatabaseFixture database)
{
    private static readonly DateTimeOffset Ten = new(2030, 1, 1, 10, 0, 0, TimeSpan.Zero);

    private static string Row(string region, DateTimeOffset validFrom, DateTimeOffset? issuedAt, int? forecast, int? actual = null) =>
        $"""
        (gen_random_uuid(), '{validFrom:o}', '{validFrom.AddMinutes(30):o}', NULL, NULL,
         {(issuedAt is null ? "NULL" : $"'{issuedAt:o}'")}, now(), 'neso-carbon-intensity', 'intensity', '{region}', '1.0.0',
         {forecast?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "NULL"},
         {actual?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "NULL"}, NULL)
        """;

    private async Task Insert(params string[] rows) =>
        await database.ExecuteAsAdmin($"INSERT INTO grid.carbon_intensity VALUES {string.Join(",", rows)}");

    [Fact]
    public async Task Each_half_hour_gets_its_latest_revision_issued_by_the_as_of_time()
    {
        await Insert(
            Row("london", Ten, Ten.AddHours(-2), 150),
            Row("london", Ten, Ten.AddHours(-1), 140), // the latest by 09:30
            Row("london", Ten, Ten.AddHours(1), 999), // issued after 09:30: not yet known
            Row("london", Ten.AddMinutes(30), Ten.AddHours(-2), 160),
            Row("yorkshire", Ten, Ten.AddHours(-1), 50), // another region
            Row("london", Ten.AddHours(-1), null, null, actual: 170)); // an actual, not a forecast
        using var services = database.SchedulerServices();

        var series = await services.GetRequiredService<NesoForecastRepository>()
            .LatestAsync("london", Ten, Ten.AddHours(2), asOf: Ten.AddMinutes(-30), TestContext.Current.CancellationToken);

        Assert.Equal([(Ten, 140.0), (Ten.AddMinutes(30), 160.0)], series.HalfHours.Select(h => (h.Start, h.Expected)));
        Assert.Equal(Ten.AddHours(-1), series.LatestIssuedAt);
        Assert.False(series.Stale);
        Assert.All(series.HalfHours, h => Assert.False(h.HasRange));
    }

    [Fact]
    public async Task A_forecast_older_than_six_hours_is_stale_and_none_at_all_is_too()
    {
        var day = Ten.AddDays(3);
        await Insert(Row("wales", day, day.AddHours(-1), 200));
        using var services = database.SchedulerServices();
        var repository = services.GetRequiredService<NesoForecastRepository>();
        var ct = TestContext.Current.CancellationToken;

        Assert.True((await repository.LatestAsync("wales", day, day.AddHours(1), day.AddHours(6), ct)).Stale);
        Assert.False((await repository.LatestAsync("wales", day, day.AddHours(1), day.AddHours(4), ct)).Stale);
        var empty = await repository.LatestAsync("scotland", day, day.AddHours(1), day, ct);
        Assert.Empty(empty.HalfHours);
        Assert.True(empty.Stale);
    }

    [Fact]
    public async Task The_scheduler_login_reads_carbon_intensity_and_nothing_else()
    {
        using var services = database.SchedulerServices();
        await using var connection = await services.GetRequiredService<NpgsqlDataSource>()
            .OpenConnectionAsync(TestContext.Current.CancellationToken);

        await using var demand = new NpgsqlCommand("SELECT count(*) FROM demand", connection);
        var error = await Assert.ThrowsAsync<PostgresException>(() => demand.ExecuteScalarAsync(TestContext.Current.CancellationToken));
        Assert.Equal(PostgresErrorCodes.InsufficientPrivilege, error.SqlState);
    }

    [Fact]
    public async Task Sessions_are_read_only_even_on_the_table_it_may_read()
    {
        using var services = database.SchedulerServices();
        await using var connection = await services.GetRequiredService<NpgsqlDataSource>()
            .OpenConnectionAsync(TestContext.Current.CancellationToken);

        await using var delete = new NpgsqlCommand("DELETE FROM carbon_intensity", connection);
        var error = await Assert.ThrowsAsync<PostgresException>(() => delete.ExecuteNonQueryAsync(TestContext.Current.CancellationToken));
        Assert.Contains(error.SqlState, new[] { PostgresErrorCodes.ReadOnlySqlTransaction, PostgresErrorCodes.InsufficientPrivilege });
    }

    [Fact]
    public async Task Actuals_are_the_rows_without_an_issue_time_and_skip_zero_glitches()
    {
        var day = Ten.AddDays(5);
        await Insert(
            Row("GB", day, null, null, actual: 150),
            Row("GB", day.AddMinutes(30), null, null, actual: 0), // a glitch: never a real GB reading
            Row("GB", day.AddMinutes(60), day.AddMinutes(-60), 120), // a forecast, not an actual
            Row("london", day, null, null, actual: 90)); // another region
        using var services = database.SchedulerServices();

        var readings = await services.GetRequiredService<ICarbonActuals>()
            .GetAsync("GB", day, day.AddHours(2), TestContext.Current.CancellationToken);

        Assert.Equal([(day, 150.0)], readings.Select(r => (r.Start, r.Intensity)));
    }

    [Fact]
    public async Task Core_gets_neso_forecasts_through_its_interface()
    {
        var soon = DateTimeOffset.UtcNow.AddHours(1);
        var start = HalfHours.Floor(soon);
        await Insert(Row("east-england", start, DateTimeOffset.UtcNow.AddMinutes(-20), 88));
        using var services = database.SchedulerServices();

        var series = await services.GetRequiredService<ICarbonForecasts>().GetAsync(
            ForecastSource.Neso, "east-england", start, start.AddHours(1), TestContext.Current.CancellationToken);

        Assert.Equal(88, Assert.Single(series.HalfHours).Expected);
        Assert.Equal(ForecastSource.Neso, series.Source);
    }
}
