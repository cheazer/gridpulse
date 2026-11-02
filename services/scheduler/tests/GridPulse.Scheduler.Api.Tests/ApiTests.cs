using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Mvc.Testing;

namespace GridPulse.Scheduler.Api.Tests;

/// <summary>The real app, in memory: WebApplicationFactory runs Program.cs with a test server instead of Kestrel.</summary>
public class ApiTests
{
    [Fact]
    public async Task Health_answers_without_any_dependencies()
    {
        await using var app = new WebApplicationFactory<Program>();
        using var client = app.CreateClient();

        var response = await client.GetAsync(new Uri("/health", UriKind.Relative), TestContext.Current.CancellationToken);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task OpenApi_document_describes_the_service()
    {
        await using var app = new WebApplicationFactory<Program>();
        using var client = app.CreateClient();

        var json = await client.GetStringAsync(new Uri("/openapi/v1.json", UriKind.Relative), TestContext.Current.CancellationToken);
        using var document = JsonDocument.Parse(json);

        Assert.Equal("GridPulse scheduler", document.RootElement.GetProperty("info").GetProperty("title").GetString());
        Assert.True(document.RootElement.GetProperty("paths").TryGetProperty("/health", out _));
    }
}
