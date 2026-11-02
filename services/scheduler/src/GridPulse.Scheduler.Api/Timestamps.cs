using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace GridPulse.Scheduler.Api;

/// <summary>
/// Timestamps must say which clock they're on: "Z" or an offset like "+01:00". By default .NET reads
/// "2030-01-01T18:00" as the server's local time, which on a UK clock-change day can mean two different instants.
/// </summary>
internal static partial class Timestamps
{
    public static bool TryParse(string? text, out DateTimeOffset value)
    {
        value = default;
        return text is not null
            && WithZone().IsMatch(text)
            && DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out value);
    }

    [GeneratedRegex(@"T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$", RegexOptions.IgnoreCase)]
    private static partial Regex WithZone();
}

internal sealed class ZonedDateTimeOffsetConverter : JsonConverter<DateTimeOffset>
{
    public override DateTimeOffset Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        Timestamps.TryParse(reader.GetString(), out var value)
            ? value
            : throw new JsonException("timestamps need a UTC offset, e.g. 2030-01-01T18:00:00Z or 2030-06-01T18:00:00+01:00");

    public override void Write(Utf8JsonWriter writer, DateTimeOffset value, JsonSerializerOptions options) =>
        writer.WriteStringValue(value);
}
