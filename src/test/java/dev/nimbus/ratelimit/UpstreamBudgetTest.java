package dev.nimbus.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.testutil.MutableClock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class UpstreamBudgetTest {

    private final MutableClock clock = new MutableClock();

    private static void assertDenied(UpstreamBudget.SkipReason reason, long retryAfter, UpstreamBudget.Decision d) {
        assertFalse(d.granted());
        assertEquals(new UpstreamBudget.Decision(reason, retryAfter), d);
    }

    @Test
    void defaultsAreTheConfiguredLimits() {
        NimbusConfig config = NimbusConfig.defaults();

        UpstreamBudget budget = UpstreamBudget.from(config, clock);

        assertEquals(List.of(480L, 4_000L, 8_000L), budget.remaining());
    }

    @Test
    void anEmptyDayBucketDoesNotDrainTheOthers() {
        UpstreamBudget budget = new UpstreamBudget(10, 100, 2, clock);
        assertTrue(budget.tryAcquire().granted());
        assertTrue(budget.tryAcquire().granted());

        for (int i = 0; i < 5; i++) {
            assertDenied(UpstreamBudget.SkipReason.BUDGET, 43_200, budget.tryAcquire());
        }

        assertEquals(List.of(8L, 98L, 0L), budget.remaining(), "all or nothing");
    }

    @Test
    void anEmptyMinuteBucketDoesNotDrainTheOthers() {
        UpstreamBudget budget = new UpstreamBudget(2, 100, 1_000, clock);
        budget.tryAcquire();
        budget.tryAcquire();

        assertDenied(UpstreamBudget.SkipReason.BUDGET, 30, budget.tryAcquire());
        assertEquals(List.of(0L, 98L, 998L), budget.remaining());

        clock.advance(Duration.ofSeconds(30));
        assertTrue(budget.tryAcquire().granted());
        assertEquals(List.of(0L, 97L, 997L), budget.remaining());
    }

    @Test
    void bucketsRefillAtTheirOwnRates() {
        UpstreamBudget budget = new UpstreamBudget(60, 3_600, 86_400, clock);
        for (int i = 0; i < 60; i++) {
            budget.tryAcquire();
        }
        assertEquals(List.of(0L, 3_540L, 86_340L), budget.remaining());

        clock.advance(Duration.ofSeconds(10));

        assertEquals(List.of(10L, 3_550L, 86_350L), budget.remaining(), "one token per second each");
    }

    @Test
    void cooldownDeniesWithoutSpendingUntilItExpires() {
        UpstreamBudget budget = new UpstreamBudget(10, 100, 1_000, clock);
        budget.startCooldown(Duration.ofSeconds(60));

        assertTrue(budget.cooldownActive());
        assertDenied(UpstreamBudget.SkipReason.COOLDOWN, 60, budget.tryAcquire());
        assertEquals(List.of(10L, 100L, 1_000L), budget.remaining(), "no tokens spent");

        clock.advance(Duration.ofMillis(59_500));
        assertDenied(UpstreamBudget.SkipReason.COOLDOWN, 1, budget.tryAcquire());
        clock.advance(Duration.ofMillis(500));
        assertFalse(budget.cooldownActive());
        assertTrue(budget.tryAcquire().granted());
    }

    @Test
    void aLongerCooldownAlreadyRunningIsKept() {
        UpstreamBudget budget = new UpstreamBudget(10, 100, 1_000, clock);
        budget.startCooldown(Duration.ofSeconds(60));
        budget.startCooldown(Duration.ofSeconds(10));

        clock.advance(Duration.ofSeconds(30));
        assertTrue(budget.cooldownActive());

        budget.startCooldown(Duration.ofSeconds(60)); // Extends past the first one.
        clock.advance(Duration.ofSeconds(45));
        assertTrue(budget.cooldownActive());
        clock.advance(Duration.ofSeconds(15));
        assertFalse(budget.cooldownActive());
    }
}
