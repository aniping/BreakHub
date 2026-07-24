package com.ateagents.breakhub.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ReportingChannelTest {

    @Test
    void renewalsSaturateOneProbeAndConcurrentCallsCannotShareIt() throws Exception {
        ReportingChannel channel = new ReportingChannel();
        channel.activate();
        channel.renewAndSnapshot();
        ReportingChannel.Permit failedRequest = channel.tryAcquire();
        channel.failed(failedRequest, "before_request_failed");

        assertThat(channel.snapshot())
                .isEqualTo(new ReportingChannel.Health("degraded", "before_request_failed"));
        assertThat(channel.tryAcquire().allowed()).isFalse();

        channel.renewAndSnapshot();
        channel.renewAndSnapshot();
        channel.renewAndSnapshot();

        int concurrency = 16;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<ReportingChannel.Permit>> attempts = java.util.stream.IntStream
                    .range(0, concurrency)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return channel.tryAcquire();
                    }))
                    .toList();
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReportingChannel.Permit> permits = attempts.stream().map(attempt -> {
                try {
                    return attempt.get(2, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }).toList();

            assertThat(permits).filteredOn(ReportingChannel.Permit::allowed).hasSize(1);
            ReportingChannel.Permit probe = permits.stream()
                    .filter(ReportingChannel.Permit::allowed)
                    .findFirst()
                    .orElseThrow();
            assertThat(probe.probe()).isTrue();
            assertThat(channel.tryAcquire().allowed()).isFalse();

            channel.failed(probe, "before_request_failed");
            assertThat(channel.tryAcquire().allowed()).isFalse();
            channel.renewAndSnapshot();
            ReportingChannel.Permit recoveredProbe = channel.tryAcquire();
            assertThat(recoveredProbe.allowed()).isTrue();
            assertThat(recoveredProbe.probe()).isTrue();

            channel.succeeded(recoveredProbe);
            assertThat(channel.snapshot())
                    .isEqualTo(new ReportingChannel.Health("healthy", null));
            assertThat(channel.tryAcquire())
                    .satisfies(permit -> {
                        assertThat(permit.allowed()).isTrue();
                        assertThat(permit.probe()).isFalse();
                    });
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void stoppedLeaseFencesLateRequestResultsFromTheNextLease() {
        ReportingChannel channel = new ReportingChannel();
        channel.activate();
        ReportingChannel.Permit oldRequest = channel.tryAcquire();

        channel.deactivate();
        channel.activate();
        assertThat(channel.succeeded(oldRequest)).isFalse();
        channel.failed(oldRequest, "wait_request_failed");

        assertThat(channel.snapshot())
                .isEqualTo(new ReportingChannel.Health("healthy", null));
    }

    @Test
    void lateFailureFromTheOldHealthEpochCannotUndoProbeRecovery() {
        ReportingChannel channel = new ReportingChannel();
        channel.activate();
        ReportingChannel.Permit firstFailure = channel.tryAcquire();
        ReportingChannel.Permit lateFailure = channel.tryAcquire();

        channel.failed(firstFailure, "before_request_failed");
        channel.renewAndSnapshot();
        ReportingChannel.Permit probe = channel.tryAcquire();
        assertThat(channel.succeeded(probe)).isTrue();
        channel.failed(lateFailure, "after_request_failed");

        assertThat(channel.snapshot())
                .isEqualTo(new ReportingChannel.Health("healthy", null));
    }
}
