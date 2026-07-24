package com.ateagents.breakhub.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReportingLeaseManagerTest {

    private ReportingLeaseManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        DebuggerSettings.enabled = false;
        ReportingChannel.shared().deactivate();
    }

    @Test
    void createRenewStopAndFenceOlderLeaseIds() {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        manager = manager(Duration.ofSeconds(5), () -> "lease-" + ids.incrementAndGet(),
                cancellations::incrementAndGet);

        ReportingLeaseManager.HttpResult created = manager.handle("{\"enabled\":true}");
        String firstLeaseId = (String) created.body().get("lease_id");

        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body())
                .containsEntry("result", "created")
                .containsEntry("changed", true)
                .containsEntry("enabled", true)
                .containsEntry("lease_timeout_seconds", 30)
                .containsEntry("reporting_status", "healthy")
                .containsEntry("lease_id", "lease-1");
        assertTrue(DebuggerSettings.enabled);

        assertError(manager.handle("{\"enabled\":true}"), 409,
                "REPORTING_LEASE_ALREADY_ACTIVE");
        assertError(manager.handle(request(true, "wrong")), 409,
                "REPORTING_LEASE_CONFLICT");
        assertError(manager.handle(request(false, "wrong")), 409,
                "REPORTING_LEASE_CONFLICT");

        ReportingLeaseManager.HttpResult renewed = manager.handle(request(true, firstLeaseId));

        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(renewed.body())
                .containsEntry("result", "renewed")
                .containsEntry("changed", false)
                .containsEntry("lease_id", firstLeaseId);

        ReportingLeaseManager.HttpResult stopped = manager.handle(request(false, firstLeaseId));

        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(stopped.body())
                .containsEntry("result", "stopped")
                .containsEntry("changed", true)
                .containsEntry("enabled", false)
                .containsEntry("reporting_status", "idle")
                .doesNotContainKey("lease_id");
        assertFalse(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(1);

        ReportingLeaseManager.HttpResult repeatedStop = manager.handle(request(false, firstLeaseId));

        assertThat(repeatedStop.statusCode()).isEqualTo(200);
        assertThat(repeatedStop.body())
                .containsEntry("result", "already_stopped")
                .containsEntry("changed", false);
        assertThat(cancellations).hasValue(1);
        assertError(manager.handle(request(true, firstLeaseId)), 404,
                "REPORTING_LEASE_NOT_FOUND");

        ReportingLeaseManager.HttpResult second = manager.handle("{\"enabled\":true}");
        String secondLeaseId = (String) second.body().get("lease_id");

        assertThat(secondLeaseId).isEqualTo("lease-2").isNotEqualTo(firstLeaseId);
        assertError(manager.handle(request(false, firstLeaseId)), 409,
                "REPORTING_LEASE_CONFLICT");
        assertThat(manager.handle(request(false, secondLeaseId)).statusCode()).isEqualTo(200);
        assertThat(cancellations).hasValue(2);
    }

    @Test
    void rejectsMalformedOrAmbiguousRequestsBeforeChangingState() {
        manager = manager(Duration.ofSeconds(5), () -> "lease", () -> {
        });

        List<String> invalidBodies = List.of(
                "",
                "null",
                "[]",
                "{}",
                "{\"enabled\":null}",
                "{\"enabled\":\"true\"}",
                "{\"enabled\":false}",
                "{\"enabled\":true,\"lease_id\":null}",
                "{\"enabled\":true,\"lease_id\":\"   \"}",
                "{\"enabled\":true,\"extra\":1}",
                "{not-json}");

        for (String body : invalidBodies) {
            assertError(manager.handle(body), 400, "INVALID_REPORTING_LEASE_REQUEST");
        }
        assertError(manager.handle(null), 400, "INVALID_REPORTING_LEASE_REQUEST");
        assertFalse(DebuggerSettings.enabled);
    }

    @Test
    void expirationDisablesReportingAndCancelsRequestsOnce() throws Exception {
        CountDownLatch cancellation = new CountDownLatch(1);
        manager = manager(Duration.ofMillis(80), () -> "expiring-lease", cancellation::countDown);
        manager.handle("{\"enabled\":true}");

        assertTrue(cancellation.await(2, TimeUnit.SECONDS));
        assertFalse(DebuggerSettings.enabled);

        ReportingLeaseManager.HttpResult repeatedStop = manager.handle(
                request(false, "expiring-lease"));
        assertThat(repeatedStop.statusCode()).isEqualTo(200);
        assertThat(repeatedStop.body()).containsEntry("result", "already_stopped");
        assertThat(cancellation.getCount()).isZero();
    }

    @Test
    void renewalReplacesTheOldExpirationTaskWithoutTimingAssumptions() {
        ScheduledThreadPoolExecutor scheduler = scheduler();
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler,
                () -> "renewed-lease", () -> {
                }, System::nanoTime);
        manager.handle("{\"enabled\":true}");

        assertThat(scheduler.getQueue()).hasSize(1);
        manager.handle(request(true, "renewed-lease"));

        assertThat(scheduler.getQueue()).hasSize(1);
        assertTrue(DebuggerSettings.enabled);
    }

    @Test
    void leaseExpiresAtTheExactDeadlineAndCannotBeRenewed() {
        AtomicLong now = new AtomicLong();
        AtomicInteger cancellations = new AtomicInteger();
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler(),
                () -> "expired-lease", cancellations::incrementAndGet, now::get);
        manager.handle("{\"enabled\":true}");

        now.set(Duration.ofSeconds(10).toNanos());
        assertThat(manager.handle(request(true, "expired-lease")).statusCode()).isEqualTo(200);

        now.set(Duration.ofSeconds(40).toNanos() - 1);
        assertError(manager.handle(request(true, "wrong")), 409,
                "REPORTING_LEASE_CONFLICT");
        assertTrue(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(0);

        now.set(Duration.ofSeconds(40).toNanos());
        ReportingLeaseManager.HttpResult result = manager.handle(
                request(true, "expired-lease"));

        assertError(result, 404, "REPORTING_LEASE_NOT_FOUND");
        assertFalse(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void renewalReportsSanitizedDegradedHealthAndProbeRecovery() {
        manager = manager(Duration.ofSeconds(5), () -> "internal-lease-id", () -> {
        });
        manager.handle("{\"enabled\":true}");
        ReportingChannel channel = ReportingChannel.shared();
        ReportingChannel.Permit request = channel.tryAcquire();
        channel.failed(request, "before_request_failed");

        ReportingLeaseManager.HttpResult degraded = manager.handle(
                request(true, "internal-lease-id"));

        assertThat(degraded.body())
                .containsOnlyKeys("success", "result", "changed", "enabled",
                        "lease_timeout_seconds", "reporting_status", "last_error", "lease_id")
                .containsEntry("enabled", true)
                .containsEntry("reporting_status", "degraded")
                .containsEntry("last_error", "before_request_failed")
                .containsEntry("lease_id", "internal-lease-id");
        assertThat(degraded.body().get("last_error").toString())
                .doesNotContain("http://", "token", "internal-lease-id");

        ReportingChannel.Permit probe = channel.tryAcquire();
        assertThat(probe.allowed()).isTrue();
        assertThat(probe.probe()).isTrue();
        channel.succeeded(probe);

        ReportingLeaseManager.HttpResult recovered = manager.handle(
                request(true, "internal-lease-id"));
        assertThat(recovered.body())
                .containsOnlyKeys("success", "result", "changed", "enabled",
                        "lease_timeout_seconds", "reporting_status", "lease_id")
                .containsEntry("reporting_status", "healthy")
                .doesNotContainKey("last_error");
    }

    @Test
    void oldLeaseCleanupFinishesBeforeANewLeaseCanStart() throws Exception {
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch allowCancellationToFinish = new CountDownLatch(1);
        CountDownLatch newLeaseAttempted = new CountDownLatch(1);
        AtomicInteger ids = new AtomicInteger();
        manager = manager(Duration.ofSeconds(5), () -> "lease-" + ids.incrementAndGet(), () -> {
            cancellationStarted.countDown();
            try {
                allowCancellationToFinish.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String firstLeaseId = (String) manager.handle("{\"enabled\":true}").body().get("lease_id");

        ExecutorService operations = Executors.newFixedThreadPool(2);
        try {
            Future<ReportingLeaseManager.HttpResult> stop = operations.submit(
                    () -> manager.handle(request(false, firstLeaseId)));
            assertTrue(cancellationStarted.await(2, TimeUnit.SECONDS));

            Future<ReportingLeaseManager.HttpResult> start = operations.submit(() -> {
                newLeaseAttempted.countDown();
                return manager.handle("{\"enabled\":true}");
            });
            assertTrue(newLeaseAttempted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> start.get(500, TimeUnit.MILLISECONDS));

            allowCancellationToFinish.countDown();
            assertThat(stop.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(start.get(2, TimeUnit.SECONDS).body())
                    .containsEntry("result", "created")
                    .containsEntry("lease_id", "lease-2");
            assertTrue(DebuggerSettings.enabled);
        } finally {
            allowCancellationToFinish.countDown();
            operations.shutdownNow();
        }
    }

    @Test
    void closePreventsReportingFromBeingStartedAgain() {
        manager = manager(Duration.ofSeconds(5), () -> "lease", () -> {
        });
        manager.handle("{\"enabled\":true}");

        manager.close();
        ReportingLeaseManager.HttpResult result = manager.handle("{\"enabled\":true}");

        assertThat(result.statusCode()).isNotEqualTo(200);
        assertFalse(DebuggerSettings.enabled);
    }

    @Test
    void closeDoesNotRepeatCancellationAfterTheLeaseWasAlreadyStopped() {
        AtomicInteger cancellations = new AtomicInteger();
        manager = manager(Duration.ofSeconds(30), () -> "stopped-before-close",
                cancellations::incrementAndGet);
        manager.handle("{\"enabled\":true}");

        manager.handle(request(false, "stopped-before-close"));
        manager.close();

        assertThat(cancellations).hasValue(1);
        assertFalse(DebuggerSettings.enabled);
    }

    @Test
    void expiryStopAndCloseCompetitionCancelsTheCurrentGenerationOnce() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler(),
                () -> "competing-lease", cancellations::incrementAndGet, now::get);
        manager.handle("{\"enabled\":true}");
        now.set(Duration.ofSeconds(30).toNanos());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService operations = Executors.newFixedThreadPool(3);
        try {
            Future<?> expiry = operations.submit(() -> {
                await(start);
                manager.handle(request(true, "competing-lease"));
            });
            Future<?> stop = operations.submit(() -> {
                await(start);
                manager.handle(request(false, "competing-lease"));
            });
            Future<?> close = operations.submit(() -> {
                await(start);
                manager.close();
            });

            start.countDown();
            expiry.get(2, TimeUnit.SECONDS);
            stop.get(2, TimeUnit.SECONDS);
            close.get(2, TimeUnit.SECONDS);
        } finally {
            operations.shutdownNow();
        }

        assertThat(cancellations).hasValue(1);
        assertFalse(DebuggerSettings.enabled);
        assertThat(manager.handle("{\"enabled\":true}").statusCode()).isNotEqualTo(200);
    }

    @Test
    void aCancelledExpirationCannotCleanUpTheRenewedGeneration() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger cancellations = new AtomicInteger();
        CopyOnWriteArrayList<Runnable> expirationCommands = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    expirationCommands.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler,
                () -> "renewed-generation", cancellations::incrementAndGet, now::get);
        manager.handle("{\"enabled\":true}");
        Runnable oldExpiration = expirationCommands.get(0);

        now.set(Duration.ofSeconds(10).toNanos());
        manager.handle(request(true, "renewed-generation"));
        Runnable currentExpiration = expirationCommands.get(1);
        oldExpiration.run();

        assertTrue(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(0);

        now.set(Duration.ofSeconds(40).toNanos());
        currentExpiration.run();

        assertFalse(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void closeWaitsForTheLeaseSchedulerToTerminate() {
        ScheduledThreadPoolExecutor scheduler = scheduler();
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler,
                () -> "closing-scheduler", () -> {
                }, System::nanoTime);
        manager.handle("{\"enabled\":true}");

        manager.close();

        assertThat(scheduler.isTerminated()).isTrue();
        assertThat(scheduler.getQueue()).isEmpty();
    }

    @Test
    void oldRequestsAndExpirationCannotAffectAReplacementLease() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        CopyOnWriteArrayList<Runnable> expirationCommands = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    expirationCommands.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler,
                () -> "lease-" + ids.incrementAndGet(), cancellations::incrementAndGet, now::get);

        String oldLeaseId = (String) manager.handle("{\"enabled\":true}").body().get("lease_id");
        Runnable oldExpiration = expirationCommands.get(0);
        manager.handle(request(false, oldLeaseId));
        now.set(Duration.ofSeconds(10).toNanos());
        String currentLeaseId = (String) manager.handle("{\"enabled\":true}").body().get("lease_id");

        assertError(manager.handle(request(true, oldLeaseId)), 409,
                "REPORTING_LEASE_CONFLICT");
        assertError(manager.handle(request(false, oldLeaseId)), 409,
                "REPORTING_LEASE_CONFLICT");
        now.set(Duration.ofSeconds(30).toNanos());
        oldExpiration.run();

        assertTrue(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(1);
        assertThat(expirationCommands).hasSize(2);
        assertThat(manager.handle(request(true, currentLeaseId)).body())
                .containsEntry("result", "renewed")
                .containsEntry("lease_id", currentLeaseId);
    }

    @Test
    void unobservedCreatedLeaseStillExpiresAtItsOwnDeadline() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger cancellations = new AtomicInteger();
        CopyOnWriteArrayList<Runnable> expirationCommands = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    expirationCommands.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        manager = new ReportingLeaseManager(Duration.ofSeconds(30), scheduler,
                () -> "unobserved-lease", cancellations::incrementAndGet, now::get);

        manager.handle("{\"enabled\":true}");
        now.set(Duration.ofSeconds(30).toNanos());
        expirationCommands.get(0).run();

        assertFalse(DebuggerSettings.enabled);
        assertThat(cancellations).hasValue(1);
        assertError(manager.handle(request(true, "unobserved-lease")), 404,
                "REPORTING_LEASE_NOT_FOUND");
    }

    @Test
    void aFreshManagerDoesNotRecognizeAnOldProcessLease() {
        manager = manager(Duration.ofSeconds(5), () -> "old-process-lease", () -> {
        });
        String oldLeaseId = (String) manager.handle("{\"enabled\":true}").body().get("lease_id");
        manager.close();

        manager = manager(Duration.ofSeconds(5), () -> "new-process-lease", () -> {
        });

        assertError(manager.handle(request(true, oldLeaseId)), 404,
                "REPORTING_LEASE_NOT_FOUND");
        assertError(manager.handle(request(false, oldLeaseId)), 404,
                "REPORTING_LEASE_NOT_FOUND");
    }

    private ReportingLeaseManager manager(Duration timeout, java.util.function.Supplier<String> ids,
            Runnable cancelRequests) {
        return new ReportingLeaseManager(timeout, scheduler(), ids, cancelRequests, System::nanoTime);
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private void assertError(ReportingLeaseManager.HttpResult result, int statusCode, String code) {
        assertThat(result.statusCode()).isEqualTo(statusCode);
        assertThat(result.body())
                .containsOnlyKeys("code", "message")
                .containsEntry("code", code)
                .doesNotContainKey("lease_id");
    }

    private String request(boolean enabled, String leaseId) {
        return "{\"enabled\":" + enabled + ",\"lease_id\":\"" + leaseId + "\"}";
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
