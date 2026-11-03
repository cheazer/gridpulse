using System.Diagnostics;
using System.Text.Json;
using GridPulse.Scheduler.Telemetry;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace GridPulse.Scheduler.Api.Tests;

/// <summary>Log lines in the ECS shape the Java and Python services write too.</summary>
public class EcsConsoleFormatterTests
{
    [Fact]
    public void A_line_logged_inside_a_span_carries_its_trace_and_span_ids()
    {
        using var activity = new Activity("schedule").Start();

        var line = Format(LogLevel.Warning, "Forecasts unavailable: the database failed");

        Assert.Equal("WARN", line.GetProperty("log").GetProperty("level").GetString());
        Assert.Equal("GridPulse.Test", line.GetProperty("log").GetProperty("logger").GetString());
        Assert.Equal("scheduler-api", line.GetProperty("service").GetProperty("name").GetString());
        Assert.Equal("Forecasts unavailable: the database failed", line.GetProperty("message").GetString());
        Assert.Equal(activity.TraceId.ToHexString(), line.GetProperty("trace.id").GetString());
        Assert.Equal(activity.SpanId.ToHexString(), line.GetProperty("span.id").GetString());
        Assert.True(DateTime.TryParse(line.GetProperty("@timestamp").GetString(), out _));
    }

    [Fact]
    public void A_line_outside_any_span_has_no_trace_fields()
    {
        var line = Format(LogLevel.Information, "Now listening");

        Assert.Equal("INFO", line.GetProperty("log").GetProperty("level").GetString());
        Assert.False(line.TryGetProperty("trace.id", out _));
        Assert.False(line.TryGetProperty("span.id", out _));
    }

    [Fact]
    public void An_exception_goes_in_the_error_fields()
    {
        var exception = new InvalidOperationException("no forecasts");

        var error = Format(LogLevel.Error, "Request failed", exception).GetProperty("error");

        Assert.Equal("System.InvalidOperationException", error.GetProperty("type").GetString());
        Assert.Equal("no forecasts", error.GetProperty("message").GetString());
        Assert.Contains("no forecasts", error.GetProperty("stack_trace").GetString(), StringComparison.Ordinal);
    }

    [Fact]
    public void Each_entry_is_exactly_one_line_even_when_the_message_has_newlines()
    {
        var output = Write(LogLevel.Information, "first\nsecond £5 CO₂");

        Assert.Single(output.TrimEnd('\r', '\n').Split('\n'));
        Assert.Contains("£5 CO₂", output, StringComparison.Ordinal); // readable, not \u-escaped
    }

    private static JsonElement Format(LogLevel level, string message, Exception? exception = null) =>
        JsonDocument.Parse(Write(level, message, exception)).RootElement.Clone();

    private static string Write(LogLevel level, string message, Exception? exception = null)
    {
        var formatter = new EcsConsoleFormatter(Options.Create(new EcsConsoleFormatterOptions { ServiceName = "scheduler-api" }));
        var entry = new LogEntry<string>(level, "GridPulse.Test", default, message, exception, (state, _) => state);
        using var writer = new StringWriter();
        formatter.Write(entry, scopeProvider: null, writer);
        return writer.ToString();
    }
}
