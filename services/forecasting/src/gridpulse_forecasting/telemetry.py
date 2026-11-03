"""OpenTelemetry for the forecasting service (step 4.1): traces and metrics over OTLP/HTTP, and logs that carry them.

Configured by the standard environment variables, like every other GridPulse service: OTEL_EXPORTER_OTLP_ENDPOINT
names the collector, and OTEL_RESOURCE_ATTRIBUTES, OTEL_METRIC_EXPORT_INTERVAL and the rest work as documented.
With no endpoint set nothing is configured, so tests and local runs don't try to reach a collector that isn't there;
instruments created through the global API then do nothing.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import os
import sys

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


# Python's level names as Logback (and so the Java and .NET services) writes them.
_LEVELS = {"WARNING": "WARN", "CRITICAL": "FATAL"}

# Loggers uvicorn sets up with handlers of its own; pointed at the root logger, they're written as JSON too.
_UVICORN_LOGGERS = ("uvicorn", "uvicorn.error", "uvicorn.access")


class EcsFormatter(logging.Formatter):
    """One line of Elastic Common Schema JSON per record, the shape the Java and .NET services write too.

    ``trace.id`` and ``span.id`` are the span the record was logged in, left out when there isn't one. Handlers format
    on the thread that logged, so the current span is still the caller's.
    """

    def __init__(self, service_name: str) -> None:
        super().__init__()
        self.service_name = service_name

    def format(self, record: logging.LogRecord) -> str:
        line: dict[str, object] = {
            "@timestamp": dt.datetime.fromtimestamp(record.created, dt.UTC).isoformat().replace("+00:00", "Z"),
            "log": {"level": _LEVELS.get(record.levelname, record.levelname), "logger": record.name},
            "service": {"name": self.service_name},
            "message": record.getMessage(),
        }
        span = trace.get_current_span().get_span_context()
        if span.is_valid:
            line["trace.id"] = trace.format_trace_id(span.trace_id)
            line["span.id"] = trace.format_span_id(span.span_id)
        if record.exc_info and record.exc_info[1] is not None:
            error = record.exc_info[1]
            line["error"] = {
                "type": f"{type(error).__module__}.{type(error).__qualname__}",
                "message": str(error),
                "stack_trace": self.formatException(record.exc_info),
            }
        line["ecs"] = {"version": "8.11"}
        # ensure_ascii off: "£" and "₂" stay readable. json.dumps escapes newlines, so a record is always one line.
        # Compact separators, as the Java and .NET services write.
        return json.dumps(line, ensure_ascii=False, separators=(",", ":"))


def configure_logging(service_name: str, level: int = logging.INFO) -> None:
    """Log every record, uvicorn's included, to stdout as ECS JSON lines. Safe to call more than once."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(EcsFormatter(service_name))
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)
    for name in _UVICORN_LOGGERS:
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True


def flush() -> None:
    """Send everything still buffered. A short-lived job calls this before exiting, or its spans are lost."""
    tracer_provider = trace.get_tracer_provider()
    if isinstance(tracer_provider, TracerProvider):
        tracer_provider.shutdown()
    meter_provider = metrics.get_meter_provider()
    if isinstance(meter_provider, MeterProvider):
        meter_provider.shutdown()
