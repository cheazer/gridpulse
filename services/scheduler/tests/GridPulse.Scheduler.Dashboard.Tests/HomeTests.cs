using GridPulse.Scheduler.Dashboard.Components.Pages;

namespace GridPulse.Scheduler.Dashboard.Tests;

/// <summary>bUnit renders components in memory, without a browser, and lets tests query the markup.</summary>
public class HomeTests
{
    [Fact]
    public void Home_page_asks_the_question_the_product_answers()
    {
        using var context = new BunitContext();

        var page = context.Render<Home>();

        Assert.Equal("When is the grid cleanest?", page.Find("h1").TextContent);
    }
}
