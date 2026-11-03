using System.Buffers;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Logging.Console;
using Microsoft.Extensions.Options;

namespace GridPulse.Scheduler.Telemetry;

/// <summary>
/// Writes each log entry as one line of Elastic Common Schema JSON, the same shape as Spring Boot's ECS format in the
/// Java services and the formatter in the Python ones, so one log pipeline reads all three:
/// <code>
/// {"@timestamp":"…","log":{"level":"WARN","logger":"…"},"service":{"name":"scheduler-api"},"message":"…",
///  "trace.id":"…","span.id":"…","error":{"type":"…","message":"…","stack_trace":"…"},"ecs":{"version":"8.11"}}
/// </code>
/// <c>trace.id</c> and <c>span.id</c> come from <see cref="Activity.Current"/>, the span the line was logged in, and
/// are left out when there isn't one. The console logger calls this on the logging thread before queueing the line
/// for output, so the current activity is still the caller's.
/// </summary>
internal sealed class EcsConsoleFormatter(IOptions<EcsConsoleFormatterOptions> options) : ConsoleFormatter(FormatterName)
{
    public const string FormatterName = "ecs";

    // Logs aren't HTML: keep "£", "₂" and quotes readable rather than \uXXXX-escaped.
    private static readonly JsonWriterOptions JsonOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public override void Write<TState>(in LogEntry<TState> logEntry, IExternalScopeProvider? scopeProvider, TextWriter textWriter)
    {
        ArgumentNullException.ThrowIfNull(textWriter);
        var message = logEntry.Formatter(logEntry.State, logEntry.Exception);
        if (string.IsNullOrEmpty(message) && logEntry.Exception is null)
        {
            return;
        }

        var buffer = new ArrayBufferWriter<byte>();
        using (var json = new Utf8JsonWriter(buffer, JsonOptions))
        {
            json.WriteStartObject();
            json.WriteString("@timestamp", DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture));
            json.WriteStartObject("log");
            json.WriteString("level", Level(logEntry.LogLevel));
            json.WriteString("logger", logEntry.Category);
            json.WriteEndObject();
            json.WriteStartObject("service");
            json.WriteString("name", options.Value.ServiceName);
            json.WriteEndObject();
            json.WriteString("message", message);

            if (Activity.Current is { } activity)
            {
                json.WriteString("trace.id", activity.TraceId.ToHexString());
                json.WriteString("span.id", activity.SpanId.ToHexString());
            }

            if (logEntry.Exception is { } exception)
            {
                json.WriteStartObject("error");
                json.WriteString("type", exception.GetType().FullName);
                json.WriteString("message", exception.Message);
                json.WriteString("stack_trace", exception.ToString());
                json.WriteEndObject();
            }

            json.WriteStartObject("ecs");
            json.WriteString("version", "8.11");
            json.WriteEndObject();
            json.WriteEndObject();
        }

        textWriter.WriteLine(Encoding.UTF8.GetString(buffer.WrittenSpan));
    }

    /// <summary>The level names Logback (and so the Java services) uses.</summary>
    private static string Level(LogLevel level) => level switch
    {
        LogLevel.Trace => "TRACE",
        LogLevel.Debug => "DEBUG",
        LogLevel.Information => "INFO",
        LogLevel.Warning => "WARN",
        LogLevel.Error => "ERROR",
        LogLevel.Critical => "FATAL",
        _ => level.ToString().ToUpperInvariant(),
    };
}

/// <summary>Options for <see cref="EcsConsoleFormatter"/>.</summary>
internal sealed class EcsConsoleFormatterOptions : ConsoleFormatterOptions
{
    /// <summary>The service.name each line carries, the same one its traces and metrics carry.</summary>
    public string ServiceName { get; set; } = "unknown";
}
