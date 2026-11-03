"""The API's telemetry, read back through in-memory exporters: what a collector would receive."""

from __future__ import annotations

import datetime as dt
import json
import logging
import sys
from typing import Any

from fastapi.testclient import TestClient
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from test_api import FakeRepository

from gridpulse_forecasting import telemetry
from gridpulse_forecasting.api import create_app


class Telemetry:
    def __init__(self) -> None:
        self.spans = InMemorySpanExporter()
        self.tracer_provider = TracerProvider()
        self.tracer_provider.add_span_processor(SimpleSpanProcessor(self.spans))
        self.reader = InMemoryMetricReader()
        self.meter_provider = MeterProvider(metric_readers=[self.reader])

    def client(self, repository: FakeRepository) -> TestClient:
        app = create_app(lambda: repository, tracer_provider=self.tracer_provider, meter_provider=self.meter_provider)
        return TestClient(app)

    def points(self, name: str) -> list[Any]:
        data = self.reader.get_metrics_data()
        return [
            point
            for resource in (data.resource_metrics if data else [])
            for scope in resource.scope_metrics
            for metric in scope.metrics
            if metric.name == name
            for point in metric.data.data_points
        ]


def test_forecast_age_is_reported_per_task() -> None:
    now = dt.datetime.now(dt.UTC)
    issues = {"demand": now - dt.timedelta(hours=2), "carbon": now - dt.timedelta(hours=8)}
    otel = Telemetry()

    with otel.client(FakeRepository(issues=issues)):
        ages = {p.attributes["task"]: p.value for p in otel.points("gridpulse.forecast.age")}

    assert set(ages) == {"demand", "carbon"}
    assert abs(ages["demand"] - 2 * 3600) < 60
    assert abs(ages["carbon"] - 8 * 3600) < 60


def test_forecast_age_is_left_out_while_the_database_is_down() -> None:
    otel = Telemetry()

    with otel.client(FakeRepository(healthy=False, issues={"demand": dt.datetime.now(dt.UTC)})):
        assert otel.points("gridpulse.forecast.age") == []


def test_requests_are_traced_but_health_probes_are_not() -> None:
    otel = Telemetry()

    with otel.client(FakeRepository()) as client:
        client.get("/health")
        client.get("/ready")
        client.get("/v1/forecasts/carbon")

    servers = [s for s in otel.spans.get_finished_spans() if s.parent is None]
    assert [s.name for s in servers] == ["GET /v1/forecasts/{task}"]
    assert servers[0].attributes is not None
    assert servers[0].attributes["http.route"] == "/v1/forecasts/{task}"
    # The stable name and unit, the same as the .NET and Java services report.
    assert [p.count for p in otel.points("http.server.request.duration")] == [1]


def test_nothing_is_configured_without_a_collector(monkeypatch: Any) -> None:
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)

    assert telemetry.configure("forecasting-api") is False


def ecs_line(record: logging.LogRecord) -> dict[str, Any]:
    line = telemetry.EcsFormatter("forecast-job").format(record)
    assert "\n" not in line
    parsed: dict[str, Any] = json.loads(line)
    return parsed


def record(message: str, level: int = logging.INFO, exc_info: Any = None) -> logging.LogRecord:
    return logging.LogRecord("gridpulse_forecasting.forecast", level, __file__, 1, message, None, exc_info)


def test_a_line_logged_inside_a_span_carries_its_trace_and_span_ids() -> None:
    tracer = TracerProvider().get_tracer("test")

    with tracer.start_as_current_span("forecast demand") as span:
        line = ecs_line(record("demand: stored 96 half-hours", logging.WARNING))

    context = span.get_span_context()
    assert line["log"] == {"level": "WARN", "logger": "gridpulse_forecasting.forecast"}
    assert line["service"] == {"name": "forecast-job"}
    assert line["message"] == "demand: stored 96 half-hours"
    assert line["trace.id"] == f"{context.trace_id:032x}"
    assert line["span.id"] == f"{context.span_id:016x}"
    assert line["@timestamp"].endswith("Z")


def test_a_line_outside_any_span_has_no_trace_fields() -> None:
    line = ecs_line(record("Application startup complete."))

    assert line["log"]["level"] == "INFO"
    assert "trace.id" not in line
    assert "span.id" not in line


def test_an_exception_goes_in_the_error_fields() -> None:
    try:
        raise LookupError("no champion")
    except LookupError:
        line = ecs_line(record("forecast failed", logging.ERROR, sys.exc_info()))

    assert line["error"]["type"] == "builtins.LookupError"
    assert line["error"]["message"] == "no champion"
    assert "no champion" in line["error"]["stack_trace"]


def test_a_multi_line_message_stays_one_readable_line() -> None:
    line = telemetry.EcsFormatter("forecast-job").format(record("first\nsecond £5 CO₂"))

    assert "\n" not in line
    assert '"message":"first\\nsecond £5 CO₂"' in line


def test_uvicorn_logs_go_through_the_same_formatter() -> None:
    root = logging.getLogger()
    saved = root.handlers, root.level
    try:
        telemetry.configure_logging("forecasting-api")

        [handler] = root.handlers
        assert isinstance(handler.formatter, telemetry.EcsFormatter)
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
            assert logging.getLogger(name).handlers == []
            assert logging.getLogger(name).propagate
    finally:
        root.handlers, root.level = saved[0], saved[1]
