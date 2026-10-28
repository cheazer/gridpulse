package dev.gridpulse.ingestion.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Uses a fake clock and records sleeps instead of sleeping, so the maths is checked exactly. */
class RateLimiterTest {

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final List<Duration> sleeps = new ArrayList<>();

  private RateLimiter limiter(double perSecond, int burst) {
    return new RateLimiter(perSecond, burst, nanos::get, sleeps::add);
  }

  @Test
  void allowsABurstThenSpacesRequestsAtTheRate() {
    RateLimiter limiter = limiter(10, 2); // one every 100 ms, two back to back

    for (int i = 0; i < 4; i++) {
      limiter.acquire();
    }

    // Requests 1-2 go straight away; 3 and 4 wait for their slots (clock not advanced).
    assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
  }

  @Test
  void idleTimeRefillsTheBurst() {
    RateLimiter limiter = limiter(10, 2);
    limiter.acquire();
    limiter.acquire();

    nanos.addAndGet(Duration.ofSeconds(1).toNanos());
    limiter.acquire();
    limiter.acquire();

    assertThat(sleeps).isEmpty();
  }

  @Test
  void withoutBurstEveryRequestAfterTheFirstWaitsOneInterval() {
    RateLimiter limiter = limiter(2, 1); // one every 500 ms
    limiter.acquire();
    limiter.acquire();
    nanos.addAndGet(Duration.ofMillis(500).toNanos()); // the caller slept
    limiter.acquire();

    assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
  }
}
