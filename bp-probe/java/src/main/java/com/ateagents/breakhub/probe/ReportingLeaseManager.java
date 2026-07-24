package com.ateagents.breakhub.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReportingLeaseManager implements AutoCloseable {

    public static final int LEASE_TIMEOUT_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(ReportingLeaseManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INVALID_REQUEST = "INVALID_REPORTING_LEASE_REQUEST";
    private static final String LEASE_NOT_FOUND = "REPORTING_LEASE_NOT_FOUND";
    private static final String LEASE_ALREADY_ACTIVE = "REPORTING_LEASE_ALREADY_ACTIVE";
    private static final String LEASE_CONFLICT = "REPORTING_LEASE_CONFLICT";

    private final Object mutex = new Object();
    private final long timeoutNanos;
    private final ScheduledExecutorService scheduler;
    private final Supplier<String> leaseIds;
    private final Runnable cancelActiveRequests;
    private final LongSupplier nanoTime;
    private final ReportingChannel reportingChannel;

    private ActiveLease activeLease;
    private ScheduledFuture<?> expirationTask;
    private String lastClosedLeaseId;
    private long nextGeneration;
    private boolean closed;

    public ReportingLeaseManager() {
        this(Duration.ofSeconds(LEASE_TIMEOUT_SECONDS), newScheduler(),
                () -> UUID.randomUUID().toString(), DebugClient::cancelActiveRequests,
                System::nanoTime);
    }

    ReportingLeaseManager(Duration timeout, ScheduledExecutorService scheduler,
            Supplier<String> leaseIds, Runnable cancelActiveRequests, LongSupplier nanoTime) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Reporting lease timeout must be positive");
        }
        this.timeoutNanos = timeout.toNanos();
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.leaseIds = Objects.requireNonNull(leaseIds, "leaseIds");
        this.cancelActiveRequests = Objects.requireNonNull(cancelActiveRequests,
                "cancelActiveRequests");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.reportingChannel = ReportingChannel.shared();
        DebuggerSettings.enabled = false;
        reportingChannel.deactivate();
    }

    public HttpResult handle(String requestBody) {
        LeaseRequest request = parseRequest(requestBody);
        if (request == null) {
            return error(400, INVALID_REQUEST, "Invalid reporting lease request");
        }
        if (request.enabled()) {
            return request.leaseId() == null ? create() : renew(request.leaseId());
        }
        return stop(request.leaseId());
    }

    private HttpResult create() {
        synchronized (mutex) {
            if (closed) {
                return closedResult();
            }
            expireIfPastDeadlineLocked();
            if (activeLease != null) {
                return error(409, LEASE_ALREADY_ACTIVE, "A reporting lease is already active");
            }

            String leaseId = Objects.requireNonNull(leaseIds.get(), "lease ID");
            long generation = ++nextGeneration;
            activeLease = new ActiveLease(leaseId, generation, deadlineFromNow());
            DebuggerSettings.enabled = true;
            ReportingChannel.Health health = reportingChannel.activate();
            replaceExpirationTask(activeLease);
            return success("created", true, true, leaseId, health);
        }
    }

    private HttpResult renew(String leaseId) {
        synchronized (mutex) {
            if (closed) {
                return closedResult();
            }
            expireIfPastDeadlineLocked();
            if (activeLease == null) {
                return error(404, LEASE_NOT_FOUND, "Reporting lease not found");
            }
            if (!activeLease.id().equals(leaseId)) {
                return error(409, LEASE_CONFLICT, "Reporting lease does not match the active lease");
            }

            activeLease = new ActiveLease(leaseId, ++nextGeneration, deadlineFromNow());
            replaceExpirationTask(activeLease);
            ReportingChannel.Health health = reportingChannel.renewAndSnapshot();
            return success("renewed", false, true, leaseId, health);
        }
    }

    private HttpResult stop(String leaseId) {
        synchronized (mutex) {
            if (closed) {
                return closedResult();
            }
            expireIfPastDeadlineLocked();
            if (activeLease == null) {
                if (leaseId.equals(lastClosedLeaseId)) {
                    return success("already_stopped", false, false, null,
                            reportingChannel.snapshot());
                }
                return error(404, LEASE_NOT_FOUND, "Reporting lease not found");
            }
            if (!activeLease.id().equals(leaseId)) {
                return error(409, LEASE_CONFLICT, "Reporting lease does not match the active lease");
            }

            deactivateAndCancelLocked();
            return success("stopped", true, false, null, reportingChannel.snapshot());
        }
    }

    private void replaceExpirationTask(ActiveLease expectedLease) {
        if (expirationTask != null) {
            expirationTask.cancel(false);
        }
        long delayNanos = Math.max(0, expectedLease.deadlineNanos() - nanoTime.getAsLong());
        expirationTask = scheduler.schedule(
                () -> expire(expectedLease.id(), expectedLease.generation()),
                delayNanos, TimeUnit.NANOSECONDS);
    }

    private void expire(String expectedLeaseId, long expectedGeneration) {
        synchronized (mutex) {
            if (activeLease == null
                    || activeLease.generation() != expectedGeneration
                    || !activeLease.id().equals(expectedLeaseId)) {
                return;
            }

            long remainingNanos = activeLease.deadlineNanos() - nanoTime.getAsLong();
            if (remainingNanos > 0) {
                expirationTask = scheduler.schedule(
                        () -> expire(expectedLeaseId, expectedGeneration),
                        remainingNanos, TimeUnit.NANOSECONDS);
                return;
            }

            deactivateAndCancelLocked();
        }
    }

    private void expireIfPastDeadlineLocked() {
        if (activeLease != null && nanoTime.getAsLong() >= activeLease.deadlineNanos()) {
            deactivateAndCancelLocked();
        }
    }

    private void deactivateAndCancelLocked() {
        ActiveLease closingLease = activeLease;
        DebuggerSettings.enabled = false;
        reportingChannel.deactivate();
        if (expirationTask != null) {
            expirationTask.cancel(false);
            expirationTask = null;
        }
        cancelRegisteredRequests();
        lastClosedLeaseId = closingLease.id();
        activeLease = null;
    }

    private long deadlineFromNow() {
        return nanoTime.getAsLong() + timeoutNanos;
    }

    private LeaseRequest parseRequest(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return null;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestBody);
            if (root == null || !root.isObject()) {
                return null;
            }

            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!"enabled".equals(field) && !"lease_id".equals(field)) {
                    return null;
                }
            }

            JsonNode enabledNode = root.get("enabled");
            if (enabledNode == null || !enabledNode.isBoolean()) {
                return null;
            }

            String leaseId = null;
            if (root.has("lease_id")) {
                JsonNode leaseIdNode = root.get("lease_id");
                if (leaseIdNode == null || !leaseIdNode.isTextual()) {
                    return null;
                }
                leaseId = leaseIdNode.textValue();
                if (leaseId.isBlank() || leaseId.length() > 200) {
                    return null;
                }
            }

            boolean enabled = enabledNode.booleanValue();
            if (!enabled && leaseId == null) {
                return null;
            }
            return new LeaseRequest(enabled, leaseId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private HttpResult success(
            String result,
            boolean changed,
            boolean enabled,
            String leaseId,
            ReportingChannel.Health health) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("result", result);
        body.put("changed", changed);
        body.put("enabled", enabled);
        body.put("lease_timeout_seconds", LEASE_TIMEOUT_SECONDS);
        body.put("reporting_status", health.status());
        if (health.lastError() != null) {
            body.put("last_error", health.lastError());
        }
        if (leaseId != null) {
            body.put("lease_id", leaseId);
        }
        return new HttpResult(200, Collections.unmodifiableMap(body));
    }

    private HttpResult error(int statusCode, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return new HttpResult(statusCode, Collections.unmodifiableMap(body));
    }

    private HttpResult closedResult() {
        return error(404, LEASE_NOT_FOUND, "Reporting lease manager is closed");
    }

    private void cancelRegisteredRequests() {
        try {
            cancelActiveRequests.run();
        } catch (RuntimeException e) {
            log.warn("[BreakHub] failed to cancel active reporting requests", e);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (mutex) {
            if (closed) {
                return;
            }
            closed = true;
            if (activeLease != null) {
                deactivateAndCancelLocked();
            } else {
                DebuggerSettings.enabled = false;
                reportingChannel.deactivate();
                if (expirationTask != null) {
                    expirationTask.cancel(false);
                    expirationTask = null;
                }
            }
        }

        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("[BreakHub] reporting lease scheduler did not terminate promptly");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "reporting-lease-expiry");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public record HttpResult(int statusCode, Map<String, Object> body) {
    }

    private record LeaseRequest(boolean enabled, String leaseId) {
    }

    private record ActiveLease(String id, long generation, long deadlineNanos) {
    }
}
