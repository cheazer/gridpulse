namespace GridPulse.Scheduler.Core;

/// <summary>
/// The Carbon Intensity API's regions, as the ingestion service stores them (ADR-0004): Great Britain, its three
/// nations, and the 14 distribution network operator regions.
/// </summary>
public static class Regions
{
    public const string GreatBritain = "GB";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.Ordinal)
    {
        GreatBritain,
        "england",
        "scotland",
        "wales",
        "north-scotland",
        "south-scotland",
        "north-west-england",
        "north-east-england",
        "yorkshire",
        "north-wales-merseyside",
        "south-wales",
        "west-midlands",
        "east-midlands",
        "east-england",
        "south-west-england",
        "south-england",
        "london",
        "south-east-england",
    };
}
