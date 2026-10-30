import numpy as np
import pytest

from gridpulse_forecasting.metrics import interval_coverage, pinball_loss, point_metrics


def test_point_metrics_on_known_errors() -> None:
    # Errors of +100 and -300 MW on actuals of 1,000 and 3,000 MW.
    metrics = point_metrics(actual=[1_000, 3_000], forecast=[1_100, 2_700])

    assert metrics["mae"] == pytest.approx(200)
    assert metrics["rmse"] == pytest.approx(np.sqrt((100**2 + 300**2) / 2))
    assert metrics["mape_pct"] == pytest.approx(10)  # 10% and 10%
    assert metrics["bias"] == pytest.approx(-100)  # too low on average


def test_pinball_loss_charges_misses_by_quantile() -> None:
    # A P90 that is 100 MW too low costs 0.9 x 100; 100 MW too high costs only 0.1 x 100.
    assert pinball_loss([1_000], [900], 0.9) == pytest.approx(90)
    assert pinball_loss([1_000], [1_100], 0.9) == pytest.approx(10)
    # At the median it is half the absolute error.
    assert pinball_loss([1_000, 1_000], [900, 1_100], 0.5) == pytest.approx(50)


def test_interval_coverage_counts_actuals_inside_including_the_edges() -> None:
    assert interval_coverage(actual=[5, 10, 15, 20], lower=[0, 10, 0, 0], upper=[10, 20, 10, 19]) == 0.5


def test_missing_values_are_an_error_not_silently_dropped() -> None:
    with pytest.raises(ValueError, match="complete data"):
        point_metrics([1_000, np.nan], [1_000, 1_000])
