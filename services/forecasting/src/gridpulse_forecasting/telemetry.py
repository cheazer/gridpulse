"""OpenTelemetry for the forecasting service (step 4.1): traces and metrics over OTLP/HTTP.

Configured by the standard environment variables, like every other GridPulse service: OTEL_EXPORTER_OTLP_ENDPOINT
names the collector, and OTEL_RESOURCE_ATTRIBUTES, OTEL_METRIC_EXPORT_INTERVAL and the rest work as documented.
With no endpoint set nothing is configured, so tests and local runs don't try to reach a collector that isn't there;
instruments created through the global API then do nothing.
"""

from __future__ import annotations

import os

from opentelemetry import metrics, trace
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.psycopg import PsycopgInstrumentor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

# The instrumentation scope for GridPulse's own spans and metrics.
SCOPE = "gridpulse.forecasting"

# The stable HTTP semantic conventions: http.server.request.duration in seconds, as .NET and the Java agent emit, not
# the older http.server.duration in milliseconds. One dashboard query then covers every service. Read when the
# instrumentation first runs, so it's set on import.
os.environ.setdefault("OTEL_SEMCONV_STABILITY_OPT_IN", "http")


def configure(service_name: str) -> bool:
    """Export traces and metrics as ``service_name`` if a collector is configured; True if one is.

    Also traces every psycopg query: the pool's connections and the job's are both made through psycopg.
    """
    if not os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        return False
    # Resource.create also reads OTEL_RESOURCE_ATTRIBUTES, e.g. the deployment environment set by Kubernetes.
    resource = Resource.create({"service.name": service_name})
    tracer_provider = TracerProvider(resource=resource)
    # Batched, on a background thread: a request never waits for the collector.
    tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(tracer_provider)
    metrics.set_meter_provider(
        MeterProvider(resource=resource, metric_readers=[PeriodicExportingMetricReader(OTLPMetricExporter())])
    )
    PsycopgInstrumentor().instrument(enable_commenter=False)
    return True


def flush() -> None:
    """Send everything still buffered. A short-lived job calls this before exiting, or its spans are lost."""
    tracer_provider = trace.get_tracer_provider()
    if isinstance(tracer_provider, TracerProvider):
        tracer_provider.shutdown()
    meter_provider = metrics.get_meter_provider()
    if isinstance(meter_provider, MeterProvider):
        meter_provider.shutdown()
