package com.ateagents.breakhub.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;

import jakarta.annotation.PreDestroy;

@Service
public class DebugControlService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugControlService.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final Duration leaseTimeout;
    private final ReportingLeaseCoordinator reportingLease;
    private final PauseService pauses;
    private final ScheduledExecutorService expiryScheduler;
    private Lease lease;
    private ScheduledFuture<?> expiryTask;
    private volatile boolean debugging;
    private Instant startedAt;
    private String debuggingSessionId;
    private volatile long debuggingGeneration;
    private long controlGeneration;
    private long nextStartAttemptId;
    private StartAttempt startInFlight;
    private volatile boolean closed;

    public DebugControlService(
            ProductProperties properties,
            ReportingLeaseCoordinator reportingLease,
            PauseService pauses) {
        this.leaseTimeout = properties.controlLease().timeout();
        this.reportingLease = reportingLease;
        this.pauses = pauses;
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "breakhub-control-expiry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.expiryScheduler = scheduler;
    }

    public Map<String, Object> start(
            ControlIdentity actor,
            Supplier<String> currentSessionId) {
        StartAttempt attempt;
        while (true) {
            CompletableFuture<Void> pendingStart = null;
            synchronized (this) {
                requireOpen();
                expireIfNeeded();
                if (startInFlight != null) {
                    if (lease != null) {
                        requireOwner(actor);
                    }
                    pendingStart = startInFlight.completion;
                    attempt = null;
                } else {
                    boolean acquired = claim(actor);
                    String sessionId;
                    try {
                        sessionId = currentSessionId.get();
                    } catch (RuntimeException error) {
                        if (acquired) {
                            clearLease();
                        }
                        throw error;
                    }
                    expireIfNeeded();
                    requireOwner(actor);
                    if (debugging) {
                        renew(actor);
                        return operation(
                                "already_started",
                                false,
                                true,
                                debuggingSessionId,
                                actor);
                    }
                    renew(actor);
                    attempt = new StartAttempt(
                            ++nextStartAttemptId,
                            actor,
                            controlGeneration,
                            lease,
                            sessionId,
                            acquired,
                            new CompletableFuture<>());
                    startInFlight = attempt;
                }
            }
            if (pendingStart == null) {
                break;
            }
            pendingStart.join();
        }

        StartAttempt currentAttempt = attempt;
        try {
            Map<String, Object> result = reportingLease.start(
                    () -> commitStart(currentAttempt, currentSessionId),
                    () -> reportingLeaseExpired(currentAttempt.id),
                    () -> recoveryAllowed(currentAttempt.id));
            ProductException cancelled = null;
            TerminationOutcome cancellationCleanup = null;
            synchronized (this) {
                expireIfNeeded();
                if (!isCommittedAttempt(currentAttempt)
                        || !reportingLease.hasActiveLease()) {
                    cancelled = startCancelled();
                    if (debugging && debuggingGeneration == currentAttempt.id) {
                        cancellationCleanup = terminateDebugging(
                                "reporting_lease_expired",
                                false);
                    }
                }
            }
            if (cancelled != null) {
                addLocalCleanupFailure(cancelled, cancellationCleanup);
                throw cancelled;
            }
            return result;
        } catch (RuntimeException error) {
            TerminationOutcome failureCleanup = null;
            synchronized (this) {
                if (debugging && debuggingGeneration == currentAttempt.id) {
                    failureCleanup = terminateDebugging(
                            "reporting_lease_expired",
                            false);
                }
                if (currentAttempt.acquired
                        && controlGeneration == currentAttempt.controlGeneration
                        && lease == currentAttempt.controlLease) {
                    clearLease();
                }
            }
            addLocalCleanupFailure(error, failureCleanup);
            throw error;
        } finally {
            synchronized (this) {
                if (startInFlight == currentAttempt) {
                    startInFlight = null;
                }
            }
            currentAttempt.completion.complete(null);
        }
    }

    public Map<String, Object> stop(ControlIdentity actor, String sessionId) {
        while (true) {
            CompletableFuture<Void> pendingStart;
            TerminationOutcome termination = null;
            Map<String, Object> result = null;
            synchronized (this) {
                claim(actor);
                pendingStart = invalidateStartLocked();
                if (!debugging) {
                    if (pendingStart == null) {
                        renew(actor);
                        return operation("already_stopped", false, false, sessionId, actor);
                    }
                } else {
                    String stoppedSessionId = debuggingSessionId;
                    termination = terminateDebugging("debug_stopped", false);
                    renew(actor);
                    result = operation("stopped", true, false, stoppedSessionId, actor);
                }
            }
            if (termination != null) {
                awaitTermination(termination);
                if (pendingStart != null) {
                    pendingStart.join();
                }
                return result;
            }
            pendingStart.join();
        }
    }

    public synchronized Map<String, Object> heartbeat(ControlIdentity actor) {
        requireOpen();
        expireIfNeeded();
        requireOwner(actor);
        renew(actor);
        return Map.of(
                "renewed", true,
                "control", controlSnapshot(Optional.of(actor)));
    }

    public Map<String, Object> release(ControlIdentity actor) {
        TerminationOutcome termination;
        Map<String, Object> result;
        synchronized (this) {
            requireOpen();
            expireIfNeeded();
            if (lease == null) {
                return Map.of(
                        "released", false,
                        "result", "already_released",
                        "control", controlSnapshot(Optional.of(actor)));
            }
            requireOwner(actor);
            termination = terminateDebugging("control_released", true);
            result = Map.of(
                    "released", true,
                    "result", "released",
                    "control", controlSnapshot(Optional.of(actor)));
        }
        awaitTermination(termination);
        return result;
    }

    public synchronized <T> T performWrite(ControlIdentity actor, Supplier<T> operation) {
        boolean acquired = claim(actor);
        try {
            T result = operation.get();
            renew(actor);
            return result;
        } catch (RuntimeException error) {
            if (acquired) {
                clearLease();
            }
            throw error;
        }
    }

    public synchronized <T> Optional<T> performWhileDebugging(Function<ActiveDebuggingSession, T> operation) {
        requireOpen();
        expireIfNeeded();
        if (!debugging) {
            return Optional.empty();
        }
        return Optional.ofNullable(operation.apply(new ActiveDebuggingSession(debuggingSessionId, startedAt)));
    }

    public synchronized <T> T performWithDebuggingState(
            Function<Optional<ActiveDebuggingSession>, T> operation) {
        requireOpen();
        expireIfNeeded();
        Optional<ActiveDebuggingSession> active = debugging
                ? Optional.of(new ActiveDebuggingSession(debuggingSessionId, startedAt))
                : Optional.empty();
        return operation.apply(active);
    }

    public synchronized void requireSessionSwitchAllowed() {
        requireOpen();
        expireIfNeeded();
        if (debugging || (startInFlight != null && !startInFlight.invalidated)) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "SESSION_SWITCH_WHILE_DEBUGGING",
                    "调试运行期间不能切换 Current Session");
        }
    }

    public synchronized void touch(ControlIdentity actor) {
        requireOpen();
        expireIfNeeded();
        if (lease != null && lease.actor().equals(actor)) {
            renew(actor);
        }
    }

    public void releaseIfOwner(ControlIdentity actor, String reason) {
        TerminationOutcome termination = null;
        synchronized (this) {
            requireOpen();
            expireIfNeeded();
            if (lease != null && lease.actor().equals(actor)) {
                termination = terminateDebugging(reason, true);
            }
        }
        if (termination != null) {
            awaitTermination(termination);
        }
    }

    public synchronized Map<String, Object> controlSnapshot(Optional<ControlIdentity> requester) {
        expireIfNeeded();
        if (lease == null) {
            return Map.of(
                    "held", false,
                    "controller", "none",
                    "owned_by_requester", false);
        }
        return Map.of(
                "held", true,
                "controller", lease.actor().controller(),
                "owned_by_requester", requester.filter(lease.actor()::equals).isPresent(),
                "expires_at", lease.expiresAt().toString());
    }

    public synchronized Map<String, Object> debuggingSnapshot(String sessionId) {
        expireIfNeeded();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", debugging ? "debugging" : "idle");
        snapshot.put("session_id", debugging ? debuggingSessionId : sessionId);
        if (startedAt != null) {
            snapshot.put("started_at", startedAt.toString());
        }
        snapshot.put("reporting", reportingLease.snapshot());
        return snapshot;
    }

    public synchronized Optional<ActiveDebuggingSession> activeDebuggingSession() {
        expireIfNeeded();
        if (!debugging) {
            return Optional.empty();
        }
        return Optional.of(new ActiveDebuggingSession(debuggingSessionId, startedAt));
    }

    @PreDestroy
    public void close() {
        TerminationOutcome termination;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            termination = terminateDebugging("product_shutdown", true);
            if (termination.localFailure() != null) {
                LOGGER.warn("Pause cleanup failed during product shutdown", termination.localFailure());
            }
        }

        expiryScheduler.shutdownNow();
        try {
            if (!expiryScheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                expiryScheduler.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }

        try {
            awaitReportingStopDuringShutdown(termination.reportingStop());
        } catch (RuntimeException ignored) {
            // Closing stays best-effort after local state and scheduler resources are safe.
        }
    }

    private boolean claim(ControlIdentity actor) {
        requireOpen();
        expireIfNeeded();
        if (lease == null) {
            controlGeneration++;
            lease = new Lease(actor, Instant.now().plus(leaseTimeout));
            scheduleExpiry();
            return true;
        }
        requireOwner(actor);
        return false;
    }

    private Map<String, Object> commitStart(
            StartAttempt attempt,
            Supplier<String> currentSessionId) {
        String commitSessionId = currentSessionId.get();
        synchronized (this) {
            requireOpen();
            expireIfNeeded();
            if (!isCurrentAttempt(attempt)
                    || !attempt.sessionId.equals(commitSessionId)
                    || lease == null
                    || !lease.actor().equals(attempt.actor)
                    || !lease.expiresAt().isAfter(Instant.now())) {
                throw startCancelled();
            }
            debuggingGeneration = attempt.id;
            debugging = true;
            startedAt = Instant.now();
            debuggingSessionId = attempt.sessionId;
            renew(attempt.actor);
            return operation("started", true, true, attempt.sessionId, attempt.actor);
        }
    }

    private boolean isCurrentAttempt(StartAttempt attempt) {
        return startInFlight == attempt
                && !attempt.invalidated
                && controlGeneration == attempt.controlGeneration;
    }

    private boolean isCommittedAttempt(StartAttempt attempt) {
        return isCurrentAttempt(attempt)
                && debugging
                && debuggingGeneration == attempt.id;
    }

    private CompletableFuture<Void> invalidateStartLocked() {
        if (startInFlight == null) {
            return null;
        }
        startInFlight.invalidated = true;
        return startInFlight.completion;
    }

    private void requireOwner(ControlIdentity actor) {
        if (lease == null) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "CONTROL_NOT_HELD",
                    "当前没有可续租的控制实例");
        }
        if (lease.actor().equals(actor)) {
            return;
        }
        String controller = lease.actor().controller();
        throw new ProductException(
                HttpStatus.CONFLICT,
                "web".equals(controller) ? "CONTROLLED_BY_WEB" : "CONTROLLED_BY_MCP",
                "产品当前由" + ("web".equals(controller) ? " Web" : " MCP") + " 控制");
    }

    private void renew(ControlIdentity actor) {
        lease = new Lease(actor, Instant.now().plus(leaseTimeout));
        scheduleExpiry();
    }

    private void scheduleExpiry() {
        if (closed) {
            return;
        }
        if (expiryTask != null) {
            expiryTask.cancel(false);
        }
        long delayMillis = Math.max(1, Duration.between(Instant.now(), lease.expiresAt()).toMillis());
        expiryTask = expiryScheduler.schedule(this::expireFromScheduler, delayMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void expireFromScheduler() {
        if (closed) {
            return;
        }
        try {
            expireIfNeeded();
        } catch (RuntimeException error) {
            LOGGER.warn("Control lease expired but local Pause cleanup failed", error);
        }
        if (lease != null) {
            scheduleExpiry();
        }
    }

    private void expireIfNeeded() {
        if (closed) {
            return;
        }
        if (lease != null && !lease.expiresAt().isAfter(Instant.now())) {
            terminateDebugging("lease_expired", true).throwLocalFailure();
            return;
        }
        if (debugging && reportingLease.expired()) {
            terminateDebugging("reporting_lease_expired", false).throwLocalFailure();
        }
    }

    private TerminationOutcome terminateDebugging(String reason, boolean clearControl) {
        boolean stopReporting = debugging;
        RuntimeException localFailure = null;
        if (stopReporting) {
            try {
                pauses.safeRelease(debuggingSessionId, reason);
            } catch (RuntimeException error) {
                localFailure = error;
            } finally {
                transitionToIdle();
            }
        }
        if (clearControl) {
            clearLease();
        }
        CompletableFuture<Void> reportingStop = stopReporting
                ? reportingLease.stopBestEffort()
                : CompletableFuture.completedFuture(null);
        return new TerminationOutcome(reportingStop, localFailure);
    }

    private synchronized void reportingLeaseExpired(long expectedDebuggingGeneration) {
        if (closed || !debugging || debuggingGeneration != expectedDebuggingGeneration) {
            return;
        }
        terminateDebugging("reporting_lease_expired", false).throwLocalFailure();
    }

    private boolean recoveryAllowed(long expectedDebuggingGeneration) {
        return debugging
                && debuggingGeneration == expectedDebuggingGeneration;
    }

    private void transitionToIdle() {
        debugging = false;
        startedAt = null;
        debuggingSessionId = null;
    }

    private void awaitReportingStop(CompletableFuture<Void> reportingStop) {
        reportingStop.join();
    }

    private void awaitTermination(TerminationOutcome termination) {
        try {
            awaitReportingStop(termination.reportingStop());
        } catch (RuntimeException reportingFailure) {
            if (termination.localFailure() != null) {
                termination.localFailure().addSuppressed(reportingFailure);
                throw termination.localFailure();
            }
            throw reportingFailure;
        }
        termination.throwLocalFailure();
    }

    private static void addLocalCleanupFailure(
            RuntimeException primary,
            TerminationOutcome cleanup) {
        if (cleanup != null && cleanup.localFailure() != null) {
            primary.addSuppressed(cleanup.localFailure());
        }
    }

    private void awaitReportingStopDuringShutdown(CompletableFuture<Void> stop) {
        try {
            stop.get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stop.cancel(true);
        } catch (CancellationException | ExecutionException | TimeoutException error) {
            stop.cancel(true);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new ProductException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PRODUCT_SHUTTING_DOWN",
                    "产品正在关闭，不能再执行写操作");
        }
    }

    private static ProductException startCancelled() {
        return new ProductException(
                HttpStatus.CONFLICT,
                "DEBUG_START_CANCELLED",
                "开始调试期间控制权、Session 或生命周期已经变化");
    }

    private void clearLease() {
        invalidateStartLocked();
        controlGeneration++;
        lease = null;
        if (expiryTask != null) {
            expiryTask.cancel(false);
            expiryTask = null;
        }
    }

    private Map<String, Object> operation(
            String result,
            boolean changed,
            boolean debuggingValue,
            String sessionId,
            ControlIdentity actor) {
        return Map.of(
                "result", result,
                "changed", changed,
                "debugging", debuggingValue,
                "session_id", sessionId,
                "control", controlSnapshot(Optional.of(actor)));
    }

    private record Lease(ControlIdentity actor, Instant expiresAt) {
    }

    private static final class StartAttempt {

        private final long id;
        private final ControlIdentity actor;
        private final long controlGeneration;
        private final Lease controlLease;
        private final String sessionId;
        private final boolean acquired;
        private final CompletableFuture<Void> completion;
        private boolean invalidated;

        private StartAttempt(
                long id,
                ControlIdentity actor,
                long controlGeneration,
                Lease controlLease,
                String sessionId,
                boolean acquired,
                CompletableFuture<Void> completion) {
            this.id = id;
            this.actor = actor;
            this.controlGeneration = controlGeneration;
            this.controlLease = controlLease;
            this.sessionId = sessionId;
            this.acquired = acquired;
            this.completion = completion;
        }
    }

    private record TerminationOutcome(
            CompletableFuture<Void> reportingStop,
            RuntimeException localFailure) {

        private void throwLocalFailure() {
            if (localFailure != null) {
                throw localFailure;
            }
        }
    }

    public record ActiveDebuggingSession(String sessionId, Instant startedAt) {
    }
}
