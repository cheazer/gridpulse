using System.Globalization;
using System.Text.RegularExpressions;
using GridPulse.Scheduler.Data.Tests;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using Testcontainers.PostgreSql;

[assembly: AssemblyFixture(typeof(DatabaseFixture))]

namespace GridPulse.Scheduler.Data.Tests;

/// <summary>
/// One TimescaleDB container for the test run, migrated as Flyway would (schema grid, files in version order), with
/// the 'scheduler' login created as db-users does: the same image Compose runs, read from infra.yaml.
/// </summary>
public sealed partial class DatabaseFixture : IAsyncLifetime
{
    private const string SchedulerPassword = "scheduler-test-password"; // a throwaway container's, nothing real

    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder(TimescaleImage())
        .WithDatabase("gridpulse")
        .WithUsername("admin")
        .WithPassword("admin")
        .Build();

    public string AdminConnectionString => _container.GetConnectionString();

    /// <summary>The services as the scheduler would build them, logged in as 'scheduler'.</summary>
    public ServiceProvider SchedulerServices(Action<IServiceCollection>? extra = null)
    {
        var admin = new NpgsqlConnectionStringBuilder(AdminConnectionString);
        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["POSTGRES_HOST"] = admin.Host,
                ["TIMESCALE_PORT"] = admin.Port.ToString(CultureInfo.InvariantCulture),
                ["POSTGRES_DB"] = admin.Database,
                ["SCHEDULER_DB_PASSWORD"] = SchedulerPassword,
            })
            .Build();
        var services = new ServiceCollection().AddSchedulerData(configuration);
        extra?.Invoke(services);
        return services.BuildServiceProvider();
    }

    public async ValueTask InitializeAsync()
    {
        await _container.StartAsync();
        await using var connection = new NpgsqlConnection(AdminConnectionString);
        await connection.OpenAsync();
        await Execute(connection, "CREATE SCHEMA grid; SET search_path = grid, public;");
        foreach (var migration in Directory.GetFiles(Path.Combine(RepoRoot(), "db", "migrations"), "V*__*.sql")
                     .OrderBy(path => int.Parse(MigrationVersion().Match(Path.GetFileName(path)).Groups[1].Value, CultureInfo.InvariantCulture)))
        {
            await using var transaction = await connection.BeginTransactionAsync();
            await Execute(connection, await File.ReadAllTextAsync(migration), transaction);
            await transaction.CommitAsync();
        }

        await Execute(connection, $"CREATE ROLE scheduler LOGIN PASSWORD '{SchedulerPassword}' IN ROLE scheduler_reader");
    }

    public async ValueTask DisposeAsync() => await _container.DisposeAsync();

    public async Task ExecuteAsAdmin(string sql)
    {
        await using var connection = new NpgsqlConnection(AdminConnectionString);
        await connection.OpenAsync();
        await Execute(connection, sql);
    }

    private static async Task Execute(NpgsqlConnection connection, string sql, NpgsqlTransaction? transaction = null)
    {
        await using var command = new NpgsqlCommand(sql, connection, transaction);
        await command.ExecuteNonQueryAsync();
    }

    private static string RepoRoot()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "db", "migrations")))
            {
                return dir.FullName;
            }
        }

        throw new InvalidOperationException("can't find the repository root (a folder containing db/migrations)");
    }

    private static string TimescaleImage()
    {
        var infra = File.ReadAllText(Path.Combine(RepoRoot(), "deploy", "compose", "infra.yaml"));
        return ImageLine().Match(infra) is { Success: true } match
            ? match.Groups[1].Value
            : throw new InvalidOperationException("no TimescaleDB image in deploy/compose/infra.yaml");
    }

    [GeneratedRegex(@"^V(\d+)__")]
    private static partial Regex MigrationVersion();

    [GeneratedRegex(@"image:\s*(timescale/timescaledb:\S+)")]
    private static partial Regex ImageLine();
}
