using GridPulse.Scheduler.Dashboard.Components;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Time.Testing;

namespace GridPulse.Scheduler.Dashboard.Tests;

/// <summary>
/// bUnit renders components in memory, without a browser: the pages get a fake API and a fixed clock, and the
/// tests query the markup. The chart is stubbed out: the pages' tests are about the pages, and the chart has its own.
/// </summary>
public abstract class PageTests : IDisposable
{
    protected PageTests()
    {
        Context.ComponentFactories.AddStub<CarbonChart>();
        Context.Services.AddSingleton<ISchedulerApi>(Api);
        Context.Services.AddSingleton<TimeProvider>(new FakeTimeProvider(FakeSchedulerApi.Now));
    }

    protected BunitContext Context { get; } = new();

    internal FakeSchedulerApi Api { get; } = new();

    public void Dispose()
    {
        Context.Dispose();
        GC.SuppressFinalize(this);
    }
}
