package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;

class DebugControlStartGateTest {

    @Test
    void concurrentStartWaitsOutsideTheLockAndReusesTheSingleCreate() throws Exception {
        Harness harness = harness(Duration.ofHours(1));
        ControlIdentity actor = new ControlIdentity("web", "concurrent-start");
        CompletableFuture<Map<String, Object>> first = CompletableFuture.supplyAsync(
                () -> harness.control.start(actor, () -> "session-concurrent-start"));
        CompletableFuture<Map<String, Object>> second = null;
        try {
            assertThat(harness.remote.createStarted.await(1, TimeUnit.SECONDS)).isTrue();
            second = CompletableFuture.supplyAsync(
                    () -> harness.control.start(actor, () -> "session-concurrent-start"));
            Thread.sleep(50);
            assertThat(second).isNotDone();

            harness.remote.create.complete(healthy("lease-concurrent-start"));

            assertThat(first.get(1, TimeUnit.SECONDS)).containsEntry("result", "started");
            assertThat(second.get(1, TimeUnit.SECONDS))
                    .containsEntry("result", "already_started");
            assertThat(harness.remote.createCount).hasValue(1);
        } finally {
            harness.remote.create.complete(healthy("lease-concurrent-start"));
            first.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            if (second != null) {
                second.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            }
            harness.close();
        }
    }

    @Test
    void currentSessionCannotSwitchWhileAStartAttemptIsPending() throws Exception {
        Harness harness = harness(Duration.ofHours(1));
        ControlIdentity actor = new ControlIdentity("web", "session-gated-start");
        CompletableFuture<Map<String, Object>> starting = CompletableFuture.supplyAsync(
                () -> harness.control.start(actor, () -> "session-gated-start"));
        try {
            assertThat(harness.remote.createStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Throwable switchFailure = CompletableFuture.supplyAsync(
                    () -> catchThrowable(harness.control::requireSessionSwitchAllowed))
                    .get(500, TimeUnit.MILLISECONDS);
            assertThat(switchFailure)
                    .isInstanceOf(ProductException.class)
                    .satisfies(error -> assertThat(((ProductException) error).code())
                            .isEqualTo("SESSION_SWITCH_WHILE_DEBUGGING"));

            harness.remote.create.complete(healthy("lease-session-gated-start"));
            assertThat(starting.get(1, TimeUnit.SECONDS)).containsEntry("result", "started");
            assertThat(harness.remote.createCount).hasValue(1);
        } finally {
            harness.remote.create.complete(healthy("lease-session-gated-start"));
            starting.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            harness.close();
        }
    }

    @Test
    void pendingCreateCannotBlockControlExpiryOrReviveFromALateAcknowledgement() throws Exception {
        Harness harness = harness(Duration.ofMillis(50));
        ControlIdentity actor = new ControlIdentity("web", "expiring-start");
        CompletableFuture<Map<String, Object>> starting = CompletableFuture.supplyAsync(
                () -> harness.control.start(actor, () -> "session-expiring-start"));
        try {
            assertThat(harness.remote.createStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);

            Map<String, Object> control = CompletableFuture.supplyAsync(
                    () -> harness.control.controlSnapshot(Optional.of(actor)))
                    .get(500, TimeUnit.MILLISECONDS);
            assertThat(control).containsEntry("held", false);

            harness.remote.create.complete(healthy("lease-expiring-start"));

            assertStartCancelled(starting);
            assertThat(harness.remote.stopCount).hasValue(1);
            assertThat(harness.control.activeDebuggingSession()).isEmpty();
            assertThat(harness.control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", false);
            verifyNoInteractions(harness.pauses);
        } finally {
            harness.remote.create.complete(healthy("lease-expiring-start"));
            starting.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            harness.close();
        }
    }

    @Test
    void releasedAndReacquiredControlCannotAuthorizeTheOldCreateAcknowledgement() throws Exception {
        Harness harness = harness(Duration.ofHours(1));
        ControlIdentity actor = new ControlIdentity("web", "reacquired-start");
        CompletableFuture<Map<String, Object>> starting = CompletableFuture.supplyAsync(
                () -> harness.control.start(actor, () -> "session-reacquired-start"));
        try {
            assertThat(harness.remote.createStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Map<String, Object> released = CompletableFuture.supplyAsync(
                    () -> harness.control.release(actor))
                    .get(500, TimeUnit.MILLISECONDS);
            assertThat(released).containsEntry("released", true);
            assertThat(harness.control.performWrite(actor, () -> "written")).isEqualTo("written");

            harness.remote.create.complete(healthy("lease-reacquired-start"));

            assertStartCancelled(starting);
            assertThat(harness.remote.stopCount).hasValue(1);
            assertThat(harness.control.activeDebuggingSession()).isEmpty();
            assertThat(harness.control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", true)
                    .containsEntry("owned_by_requester", true);
            verifyNoInteractions(harness.pauses);
        } finally {
            harness.remote.create.complete(healthy("lease-reacquired-start"));
            starting.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            harness.close();
        }
    }

    @Test
    void stopWaitsOutsideTheControlLockAndCancelsThePendingStart() throws Exception {
        Harness harness = harness(Duration.ofHours(1));
        ControlIdentity actor = new ControlIdentity("web", "stopped-start");
        CompletableFuture<Map<String, Object>> starting = CompletableFuture.supplyAsync(
                () -> harness.control.start(actor, () -> "session-stopped-start"));
        CompletableFuture<Map<String, Object>> stopping = null;
        try {
            assertThat(harness.remote.createStarted.await(1, TimeUnit.SECONDS)).isTrue();
            stopping = CompletableFuture.supplyAsync(
                    () -> harness.control.stop(actor, "session-stopped-start"));
            Thread.sleep(50);
            assertThat(stopping).isNotDone();

            harness.remote.create.complete(healthy("lease-stopped-start"));

            assertStartCancelled(starting);
            assertThat(stopping.get(1, TimeUnit.SECONDS))
                    .containsEntry("result", "already_stopped");
            assertThat(harness.remote.stopCount).hasValue(1);
            assertThat(harness.control.activeDebuggingSession()).isEmpty();
            assertThat(harness.control.controlSnapshot(Optional.of(actor)))
                    .containsEntry("held", true)
                    .containsEntry("owned_by_requester", true);
            verifyNoInteractions(harness.pauses);
        } finally {
            harness.remote.create.complete(healthy("lease-stopped-start"));
            starting.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            if (stopping != null) {
                stopping.handle((ignored, error) -> null).get(1, TimeUnit.SECONDS);
            }
            harness.close();
        }
    }

    private static Harness harness(Duration controlTimeout) {
        ControllableScheduler scheduler = new ControllableScheduler();
        PendingCreateRemote remote = new PendingCreateRemote();
        ReportingLeaseCoordinator reportingLease = new ReportingLeaseCoordinator(
                remote,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler,
                scheduler.monotonicClock(),
                scheduler.clock());
        ProductProperties properties = mock(ProductProperties.class);
        when(properties.controlLease()).thenReturn(
                new ProductProperties.ControlLease(controlTimeout));
        PauseService pauses = mock(PauseService.class);
        return new Harness(
                new DebugControlService(properties, reportingLease, pauses),
                reportingLease,
                remote,
                pauses);
    }

    private static void assertStartCancelled(CompletableFuture<?> starting) {
        assertThatThrownBy(() -> starting.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ProductException.class)
                .satisfies(error -> {
                    Throwable cause = error;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    ProductException productError = (ProductException) cause;
                    assertThat(productError.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(productError.code()).isEqualTo("DEBUG_START_CANCELLED");
                });
    }

    private static ReportingLeaseRemote.LeaseAcknowledgement healthy(String leaseId) {
        return new ReportingLeaseRemote.LeaseAcknowledgement(
                leaseId,
                Duration.ofSeconds(30),
                "healthy",
                null);
    }

    private record Harness(
            DebugControlService control,
            ReportingLeaseCoordinator reportingLease,
            PendingCreateRemote remote,
            PauseService pauses) {

        private void close() {
            control.close();
            reportingLease.close();
        }
    }

    private static final class PendingCreateRemote implements ReportingLeaseRemote {

        private final CountDownLatch createStarted = new CountDownLatch(1);
        private final CompletableFuture<LeaseAcknowledgement> create = new CompletableFuture<>();
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();

        @Override
        public CompletableFuture<LeaseAcknowledgement> create() {
            createCount.incrementAndGet();
            createStarted.countDown();
            return create;
        }

        @Override
        public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
            return CompletableFuture.completedFuture(healthy(leaseId));
        }

        @Override
        public CompletableFuture<Void> stop(String leaseId) {
            stopCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
