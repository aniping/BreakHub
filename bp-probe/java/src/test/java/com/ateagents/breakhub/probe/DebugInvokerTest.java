package com.ateagents.breakhub.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebugInvokerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private BreakHubProbe probe;
    private String leaseId;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (probe != null) {
            probe.close();
        }
    }

    @Test
    void buildBeforeCallRequestPromotesBusinessParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "AUTO");
        params.put("power", -10);

        DebugMethodInfo methodInfo = TestDebugMethodInfos.commonMethodData("SA", "start", "instrumentControl", 1, params);

        BeforeCallRequest request = DebugInvoker.buildBeforeCallRequest("call-1", methodInfo);

        assertEquals("call-1", request.getCallId());
        assertEquals("SA", request.getObjectName());
        assertEquals("start", request.getCmdName());
        assertEquals(1, request.getSlotId());
        assertEquals(params, request.getParams());
        assertEquals("SA", request.getRawArgs().get("objectName"));
        assertEquals("start", request.getRawArgs().get("cmdName"));
        assertEquals(1, request.getRawArgs().get("slotId"));
        assertEquals(params, request.getRawArgs().get("params"));
        assertEquals(false, request.getRawArgs().containsKey("instType"));
        assertEquals("instrumentControl", request.getMethodName());
        assertNotNull(request.getParameterMeta());
    }

    @Test
    void invokesCallbackWithTheSameMapAfterContinuedBeforeInjection() throws Exception {
        startDebuggerServer(true, """
                {"mode":"MANUAL","power":-10,
                 "request":{"count":2,"label":"new"},"tags":[{"any":"json"},null]}
                """, new AtomicInteger(), new AtomicInteger());
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("count", 1);
        nested.put("label", "old");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "AUTO");
        params.put("power", -10);
        params.put("request", nested);
        params.put("tags", java.util.List.of(1, 2));
        AtomicReference<Map<String, Object>> callbackMap = new AtomicReference<>();

        Map<String, Object> result = probe.invoke(methodInfo(params), () -> {
            callbackMap.set(params);
            return params;
        });

        assertSame(params, callbackMap.get());
        assertSame(params, result);
        assertThat(params)
                .containsEntry("mode", "MANUAL")
                .containsEntry("power", -10);
        @SuppressWarnings("unchecked")
        Map<String, Object> injectedRequest = (Map<String, Object>) params.get("request");
        assertThat(injectedRequest)
                .containsEntry("count", 2)
                .containsEntry("label", "new");
        assertThat((java.util.List<?>) params.get("tags")).hasSize(2);
        assertThat(((java.util.List<?>) params.get("tags")).get(0)).isEqualTo(Map.of("any", "json"));
        assertThat(((java.util.List<?>) params.get("tags")).get(1)).isNull();
    }

    @Test
    void doesNotWaitWhenBeforeDoesNotPause() throws Exception {
        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        startDebuggerServer(false, "{}", beforeCalls, waitCalls);
        Map<String, Object> params = new LinkedHashMap<>(Map.of("mode", "AUTO"));

        String result = probe.invoke(methodInfo(params), () -> "executed");

        assertThat(result).isEqualTo("executed");
        assertThat(beforeCalls).hasValue(1);
        assertThat(waitCalls).hasValue(0);
    }

    @Test
    void invalidReturnedStructureDoesNotWriteAnything() throws Exception {
        startDebuggerServer(true, """
                {"mode":{"unexpected":true},"extra":"not-allowed"}
                """, new AtomicInteger(), new AtomicInteger());
        CountingMap params = new CountingMap(Map.of("mode", "AUTO"));

        Map<String, Object> result = probe.invoke(methodInfo(params), () -> params);

        assertSame(params, result);
        assertThat(params).containsEntry("mode", "AUTO");
        assertThat(params.putCount()).isZero();
    }

    @Test
    void normalizesOnlyExactNumericChangesToTheOriginalJavaType() throws Exception {
        Map<String, Object> exact = new LinkedHashMap<>(Map.of("count", 1));

        BeforeParameterInjector.apply(
                exact, OBJECT_MAPPER.readTree("{\"count\":2.0}"), "instrumentControl");

        assertThat(exact.get("count")).isInstanceOf(Integer.class).isEqualTo(2);

        CountingMap fractional = new CountingMap(Map.of("count", 1));
        BeforeParameterInjector.apply(
                fractional, OBJECT_MAPPER.readTree("{\"count\":2.5}"), "instrumentControl");

        assertThat(fractional).containsEntry("count", 1);
        assertThat(fractional.putCount()).isZero();
    }

    @Test
    void immutableMapFailsOpenWithoutLeakingValuesOrDisablingLaterInjection()
            throws Exception {
        startDebuggerServer(true, """
                {"mode":"MANUAL","secret":"injected-secret"}
                """, new AtomicInteger(), new AtomicInteger());
        Map<String, Object> immutable = Map.of("mode", "AUTO", "secret", "original-secret");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        Map<String, Object> immutableResult;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            immutableResult = probe.invoke(methodInfo(immutable), () -> immutable);
        } finally {
            System.setOut(originalOut);
        }

        assertSame(immutable, immutableResult);
        assertThat(immutable).containsEntry("mode", "AUTO");
        assertThat(reportingEnabled()).isTrue();
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .doesNotContain("original-secret")
                .doesNotContain("injected-secret");

        Map<String, Object> mutable = new LinkedHashMap<>(immutable);
        Map<String, Object> mutableResult = probe.invoke(methodInfo(mutable), () -> mutable);

        assertSame(mutable, mutableResult);
        assertThat(mutable)
                .containsEntry("mode", "MANUAL")
                .containsEntry("secret", "injected-secret");
    }

    @Test
    void writeFailureRollsBackOriginalReferencesBeforeFailingOpen() throws Exception {
        startDebuggerServer(true, """
                {"first":"new-1","second":"new-2"}
                """, new AtomicInteger(), new AtomicInteger());
        Object firstOriginal = new String("old-1");
        Object secondOriginal = new String("old-2");
        FailingMap params = new FailingMap(firstOriginal, secondOriginal, false);
        AtomicBoolean callbackCalled = new AtomicBoolean();

        Map<String, Object> result = probe.invoke(methodInfo(params), () -> {
            callbackCalled.set(true);
            return params;
        });

        assertThat(callbackCalled).isTrue();
        assertSame(params, result);
        assertSame(firstOriginal, params.get("first"));
        assertSame(secondOriginal, params.get("second"));
    }

    @Test
    void unrecoverableMapPollutionStopsTheBusinessCallback() throws Exception {
        startDebuggerServer(true, """
                {"first":"new-1","second":"new-2"}
                """, new AtomicInteger(), new AtomicInteger());
        FailingMap params = new FailingMap("old-1", "old-2", true);
        AtomicBoolean callbackCalled = new AtomicBoolean();

        assertThatThrownBy(() -> probe.invoke(methodInfo(params), () -> {
            callbackCalled.set(true);
            return params;
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("BREAKHUB_PARAMETER_MAP_CORRUPTED");
        assertThat(callbackCalled).isFalse();
    }

    @Test
    void convertsContinuedAfterContentBackToTheBusinessResultType() throws Exception {
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        AtomicInteger legacyAfterCalls = new AtomicInteger();
        startAfterDebuggerServer(true, """
                {"code":0,"message":"injected result","data":{"mode":"AUTO"}}
                """, afterCalls, waitCalls, legacyAfterCalls);
        LinkedHashMap<String, Object> originalData = new LinkedHashMap<>();
        originalData.put("mode", "AUTO");
        ValueResult original = ValueResult.success("original result", originalData);

        ValueResult result = probe.invoke(methodInfo(new LinkedHashMap<>()), () -> original);

        assertThat(result).isNotSameAs(original);
        assertThat(result).isInstanceOf(ValueResult.class);
        assertThat(result.message()).isEqualTo("injected result");
        assertThat(result.data()).isEqualTo(Map.of("mode", "AUTO"));
        assertThat(afterCalls).hasValue(1);
        assertThat(waitCalls).hasValue(1);
        assertThat(legacyAfterCalls).hasValue(0);
    }

    @Test
    void returnsTheOriginalResultWithoutWaitingWhenAfterDoesNotPause() throws Exception {
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        startAfterDebuggerServer(false, "{}", afterCalls, waitCalls, new AtomicInteger());
        ValueResult original = ValueResult.success("original result");

        ValueResult result = probe.invoke(methodInfo(new LinkedHashMap<>()), () -> original);

        assertSame(original, result);
        assertThat(afterCalls).hasValue(1);
        assertThat(waitCalls).hasValue(0);
    }

    @Test
    void unsafeAfterConversionReturnsTheOriginalResult() throws Exception {
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        startAfterDebuggerServer(true, """
                {"code":"not-a-number","message":"injected result","data":null}
                """, afterCalls, waitCalls, new AtomicInteger());
        ValueResult original = ValueResult.success("original result");

        ValueResult result = probe.invoke(methodInfo(new LinkedHashMap<>()), () -> original);

        assertSame(original, result);
        assertThat(afterCalls).hasValue(1);
        assertThat(waitCalls).hasValue(1);
    }

    @Test
    void afterReportingFailureReturnsTheOriginalResultWithoutDisablingReporting() throws Exception {
        AtomicInteger afterCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":true,"wait_required":false}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.createContext("/api/business/interactions/after", exchange -> {
            afterCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        openProbe(200, 500, 2000);
        ValueResult original = ValueResult.success("original result");

        ValueResult result = probe.invoke(methodInfo(new LinkedHashMap<>()), () -> original);

        assertSame(original, result);
        assertThat(afterCalls).hasValue(1);
        assertThat(reportingEnabled()).isTrue();
    }

    @Test
    void cancelledAfterWaitReturnsTheOriginalResult() throws Exception {
        CountDownLatch waitStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicReference<String> pausePoint = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":true,"wait_required":false}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.createContext("/api/business/interactions/after", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"completed","tracked":true,
                     "proceed":false,"wait_required":true,"lifecycle":"completed"}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.createContext("/api/business/interactions/wait", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            pausePoint.set(request.path("pause_point").asText());
            waitStarted.countDown();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
                respond(exchange, 200, """
                        {"tracked":true,"proceed":true,"released":true,"result":"continued",
                         "interaction_id":"%s","pause_point":"after","content_kind":"result",
                         "content":{"code":0,"message":"late","data":null}}
                        """.formatted(request.path("interaction_id").asText()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.start();
        openProbe(200, 500, 10000);
        ValueResult original = ValueResult.success("original result");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ValueResult> invocation = executor.submit(() -> probe.invoke(
                    methodInfo(new LinkedHashMap<>()),
                    () -> original));
            assertTrue(waitStarted.await(2, TimeUnit.SECONDS));

            probe.close();

            assertSame(original, invocation.get(2, TimeUnit.SECONDS));
            assertThat(pausePoint).hasValue("after");
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void leaseExpiryCancelsBeforeAndAfterWaitsAndKeepsOriginalContent() throws Exception {
        CountDownLatch waitsStarted = new CountDownLatch(2);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicInteger beforeReports = new AtomicInteger();
        AtomicInteger afterReports = new AtomicInteger();
        AtomicReference<String> afterInteractionId = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newFixedThreadPool(6);
        server.setExecutor(serverExecutor);
        server.createContext("/api/business/interactions/before", exchange -> {
            beforeReports.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            boolean pausesBefore = "before-pause".equals(request.path("command").asText());
            if (!pausesBefore) afterInteractionId.set(request.path("interaction_id").asText());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":%s,"wait_required":%s}
                    """.formatted(
                            request.path("interaction_id").asText(),
                            !pausesBefore,
                            pausesBefore));
        });
        server.createContext("/api/business/interactions/after", exchange -> {
            afterReports.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            boolean pausesAfter = request.path("interaction_id").asText()
                    .equals(afterInteractionId.get());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"completed","tracked":true,
                     "proceed":%s,"wait_required":%s,"lifecycle":"completed"}
                    """.formatted(
                            request.path("interaction_id").asText(),
                            !pausesAfter,
                            pausesAfter));
        });
        server.createContext("/api/business/interactions/wait", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            String pausePoint = request.path("pause_point").asText();
            waitsStarted.countDown();
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
                String content = "before".equals(pausePoint)
                        ? "{\"mode\":\"late-change\"}"
                        : "{\"code\":0,\"message\":\"late-change\",\"data\":null}";
                respond(exchange, 200, """
                        {"tracked":true,"proceed":true,"released":true,"result":"continued",
                         "interaction_id":"%s","pause_point":"%s","content_kind":"%s",
                         "content":%s}
                        """.formatted(
                                request.path("interaction_id").asText(),
                                pausePoint,
                                "before".equals(pausePoint) ? "params" : "result",
                                content));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.start();

        ProbeConfig config = new ProbeConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "business-token",
                200,
                500,
                10000);
        ReportingChannel reportingChannel = new ReportingChannel();
        DebugClient client = new DebugClient(config, reportingChannel);
        DebugInvoker invoker = new DebugInvoker(client, reportingChannel);
        AtomicLong now = new AtomicLong();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        ReportingLeaseManager lease = new ReportingLeaseManager(
                Duration.ofSeconds(30), scheduler, () -> "expiring-lease",
                client::cancelActiveRequests, now::get, reportingChannel);
        String leaseId = (String) lease.handle("{\"enabled\":true}").body().get("lease_id");
        LinkedHashMap<String, Object> beforeParams = new LinkedHashMap<>(Map.of("mode", "original"));
        AtomicReference<Map<String, Object>> businessParams = new AtomicReference<>();
        ValueResult afterOriginal = ValueResult.success("original result");
        ExecutorService invocations = Executors.newFixedThreadPool(2);
        try {
            Future<ValueResult> beforeInvocation = invocations.submit(() -> invoker.invoke(
                    TestDebugMethodInfos.commonMethodData(
                            "Lease", "before-pause", "beforeMethod", 1, beforeParams),
                    () -> {
                        businessParams.set(new LinkedHashMap<>(beforeParams));
                        return ValueResult.success("before completed");
                    }));
            Future<ValueResult> afterInvocation = invocations.submit(() -> invoker.invoke(
                    TestDebugMethodInfos.commonMethodData(
                            "Lease", "after-pause", "afterMethod", 1, new LinkedHashMap<>()),
                    () -> afterOriginal));
            assertThat(waitsStarted.await(2, TimeUnit.SECONDS)).isTrue();

            now.set(Duration.ofSeconds(30).toNanos());
            ReportingLeaseManager.HttpResult expired = lease.handle(
                    "{\"enabled\":true,\"lease_id\":\"" + leaseId + "\"}");

            assertThat(expired.statusCode()).isEqualTo(404);
            assertThat(expired.body()).containsEntry("code", "REPORTING_LEASE_NOT_FOUND");
            beforeInvocation.get(2, TimeUnit.SECONDS);
            assertSame(afterOriginal, afterInvocation.get(2, TimeUnit.SECONDS));
            assertThat(businessParams.get()).containsExactlyEntriesOf(Map.of("mode", "original"));
            assertThat(beforeParams).containsExactlyEntriesOf(Map.of("mode", "original"));
            assertThat(reportingChannel.isActive()).isFalse();

            int beforeCountAfterExpiry = beforeReports.get();
            int afterCountAfterExpiry = afterReports.get();
            ValueResult direct = ValueResult.success("direct");
            assertSame(direct, invoker.invoke(
                    TestDebugMethodInfos.commonMethodData(
                            "Lease", "disabled", "disabledMethod", 1, new LinkedHashMap<>()),
                    () -> direct));
            assertThat(beforeReports).hasValue(beforeCountAfterExpiry);
            assertThat(afterReports).hasValue(afterCountAfterExpiry);
        } finally {
            releaseServer.countDown();
            invocations.shutdownNow();
            lease.close();
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void lossyOrStructurallyInvalidAfterContentKeepsTheOriginalResult() throws Exception {
        ValueResult original = ValueResult.success("original result");

        for (String content : java.util.List.of(
                "{\"code\":1.5,\"message\":\"changed\",\"data\":null}",
                "{\"code\":0,\"message\":\"changed\"}",
                "{\"code\":0,\"message\":\"changed\",\"data\":null,\"extra\":true}")) {
            assertSame(original, AfterResultConverter.convert(
                    original,
                    OBJECT_MAPPER.readTree(content),
                    "instrumentControl"));
        }

        ArrayList<Integer> integerList = new ArrayList<>(List.of(1));
        assertSame(integerList, AfterResultConverter.convert(
                integerList,
                OBJECT_MAPPER.readTree("[\"wrong-type\"]"),
                "instrumentControl"));
        assertSame(integerList, AfterResultConverter.convert(
                integerList,
                OBJECT_MAPPER.readTree("[1,2]"),
                "instrumentControl"));

        ArrayList<Integer> emptyList = new ArrayList<>();
        assertSame(emptyList, AfterResultConverter.convert(
                emptyList,
                OBJECT_MAPPER.readTree("[1]"),
                "instrumentControl"));

        ArrayList<ArrayList<Integer>> nestedList = new ArrayList<>();
        nestedList.add(new ArrayList<>(List.of(1)));
        assertSame(nestedList, AfterResultConverter.convert(
                nestedList,
                OBJECT_MAPPER.readTree("[[\"wrong-type\"]]"),
                "instrumentControl"));
    }

    @Test
    void weaklyTypedAfterContentKeepsNestedJavaRuntimeTypes() throws Exception {
        LinkedHashMap<String, Long> rootMap = new LinkedHashMap<>();
        rootMap.put("count", 1L);
        assertSame(rootMap, AfterResultConverter.convert(
                rootMap,
                OBJECT_MAPPER.readTree("{\"count\":2}"),
                "instrumentControl"));

        LinkedHashMap<String, Long> longData = new LinkedHashMap<>();
        longData.put("count", 1L);
        ValueResult longResult = ValueResult.success("old", longData);
        assertSame(longResult, AfterResultConverter.convert(
                longResult,
                OBJECT_MAPPER.readTree("""
                        {"code":0,"message":"new","data":{"count":2}}
                        """),
                "instrumentControl"));

        LinkedHashMap<String, Object> listData = new LinkedHashMap<>();
        listData.put("counts", new ArrayList<>(List.of(1L)));
        ValueResult listResult = ValueResult.success("old", listData);
        assertSame(listResult, AfterResultConverter.convert(
                listResult,
                OBJECT_MAPPER.readTree("""
                        {"code":0,"message":"new","data":{"counts":[2]}}
                        """),
                "instrumentControl"));

        ValueResult customResult = ValueResult.success("old", new CustomData("AUTO"));
        assertSame(customResult, AfterResultConverter.convert(
                customResult,
                OBJECT_MAPPER.readTree("""
                        {"code":0,"message":"new","data":{"mode":"MANUAL"}}
                        """),
                "instrumentControl"));
    }

    @Test
    void afterConversionRejectsStatefulOrConfiguredContainers() throws Exception {
        StatefulMap stateful = new StatefulMap();
        stateful.put("mode", "AUTO");
        stateful.hidden = 7L;
        assertSame(stateful, AfterResultConverter.convert(
                stateful,
                OBJECT_MAPPER.readTree("{\"mode\":\"MANUAL\"}"),
                "instrumentControl"));

        LinkedHashMap<String, Object> accessOrdered =
                new LinkedHashMap<>(16, 0.75f, true);
        accessOrdered.put("mode", "AUTO");
        assertSame(accessOrdered, AfterResultConverter.convert(
                accessOrdered,
                OBJECT_MAPPER.readTree("{\"mode\":\"MANUAL\"}"),
                "instrumentControl"));

        TreeMap<String, Object> reverseOrdered =
                new TreeMap<>(Comparator.reverseOrder());
        reverseOrdered.put("first", "A");
        reverseOrdered.put("second", "B");
        assertSame(reverseOrdered, AfterResultConverter.convert(
                reverseOrdered,
                OBJECT_MAPPER.readTree("{\"first\":\"changed\",\"second\":\"B\"}"),
                "instrumentControl"));
    }

    @Test
    void afterConversionDoesNotDiscardNonSerializedBusinessState() throws Exception {
        HiddenStateResult original = new HiddenStateResult();
        original.value = "old";
        original.hidden = 7L;
        original.transientState = new CustomData("AUTO");

        HiddenStateResult result = AfterResultConverter.convert(
                original,
                OBJECT_MAPPER.readTree("{\"value\":\"new\"}"),
                "instrumentControl");

        assertSame(original, result);
        assertThat(result.hidden).isEqualTo(7L);
        assertThat(result.transientState).isEqualTo(new CustomData("AUTO"));
    }

    @Test
    void afterConversionDoesNotTrustTransformingBeanAccessors() throws Exception {
        NormalizingBean original = new NormalizingBean();
        original.value = "MiXeD";
        original.name = "old";

        NormalizingBean result = AfterResultConverter.convert(
                original,
                OBJECT_MAPPER.readTree("{\"value\":\"MIXED\",\"name\":\"new\"}"),
                "instrumentControl");

        assertSame(original, result);
        assertThat(result.value).isEqualTo("MiXeD");
        assertThat(result.name).isEqualTo("old");

        NormalizingRecord originalRecord = new NormalizingRecord("MiXeD", "old");
        assertSame(originalRecord, AfterResultConverter.convert(
                originalRecord,
                OBJECT_MAPPER.readTree("{\"value\":\"MIXED\",\"name\":\"new\"}"),
                "instrumentControl"));
    }

    @Test
    void businessExceptionsNeverEnterTheNormalAfterPath() throws Exception {
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicInteger waitCalls = new AtomicInteger();
        AtomicInteger legacyAfterCalls = new AtomicInteger();
        startAfterDebuggerServer(false, "{}", afterCalls, waitCalls, legacyAfterCalls);
        IllegalStateException runtimeFailure = new IllegalStateException("runtime failure");

        assertThatThrownBy(() -> probe.invoke(
                methodInfo(new LinkedHashMap<>()),
                () -> {
                    throw runtimeFailure;
                }))
                .isSameAs(runtimeFailure);
        assertThat(afterCalls).hasValue(0);
        assertThat(waitCalls).hasValue(0);
        assertThat(legacyAfterCalls).hasValue(0);

        Exception checkedFailure = new Exception("checked failure");
        try {
            probe.invoke(methodInfo(new LinkedHashMap<>()), () -> {
                throw checkedFailure;
            });
            throw new AssertionError("checked exception should have been wrapped");
        } catch (RuntimeException wrapped) {
            assertSame(checkedFailure, wrapped.getCause());
        }
        assertThat(afterCalls).hasValue(0);
        assertThat(waitCalls).hasValue(0);
        assertThat(legacyAfterCalls).hasValue(0);
    }

    private void startDebuggerServer(
            boolean waitRequired,
            String waitContent,
            AtomicInteger beforeCalls,
            AtomicInteger waitCalls) throws Exception {
        AtomicReference<String> interactionId = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            beforeCalls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            interactionId.set(request.path("interaction_id").asText());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":%s,"wait_required":%s}
                    """.formatted(interactionId.get(), !waitRequired, waitRequired));
        });
        server.createContext("/api/business/interactions/wait", exchange -> {
            waitCalls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            String reportedId = request.path("interaction_id").asText();
            respond(exchange, 200, """
                    {"tracked":true,"proceed":true,"released":true,"result":"continued",
                     "interaction_id":"%s","pause_point":"before","content_kind":"params",
                     "content":%s}
                    """.formatted(reportedId, waitContent));
        });
        server.createContext("/api/business/interactions/after", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"completed","tracked":true,
                     "proceed":true,"wait_required":false,"lifecycle":"completed"}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.start();
        openProbe(200, 500, 2000);
    }

    private void startAfterDebuggerServer(
            boolean waitRequired,
            String waitContent,
            AtomicInteger afterCalls,
            AtomicInteger waitCalls,
            AtomicInteger legacyAfterCalls) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":true,"wait_required":false}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.createContext("/api/business/interactions/after", exchange -> {
            afterCalls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"completed","tracked":true,
                     "proceed":%s,"wait_required":%s,"lifecycle":"completed"}
                    """.formatted(
                            request.path("interaction_id").asText(),
                            !waitRequired,
                            waitRequired));
        });
        server.createContext("/api/business/interactions/wait", exchange -> {
            waitCalls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, """
                    {"tracked":true,"proceed":true,"released":true,"result":"continued",
                     "interaction_id":"%s","pause_point":"after","content_kind":"result",
                     "content":%s}
                    """.formatted(request.path("interaction_id").asText(), waitContent));
        });
        server.createContext("/api/calls/after", exchange -> {
            legacyAfterCalls.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        server.start();
        openProbe(200, 500, 2000);
    }

    private static DebugMethodInfo methodInfo(Map<String, Object> params) {
        return TestDebugMethodInfos.commonMethodData("SA", "start", "instrumentControl", 1, params);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void openProbe(
            int connectTimeoutMs,
            int readTimeoutMs,
            int breakpointTimeoutMs) throws Exception {
        probe = BreakHubProbe.open(new ProbeConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "business-token",
                connectTimeoutMs,
                readTimeoutMs,
                breakpointTimeoutMs));
        leaseId = OBJECT_MAPPER.readTree(
                probe.handleLease("{\"enabled\":true}").responseBody())
                .path("lease_id")
                .asText();
    }

    private boolean reportingEnabled() throws Exception {
        LeaseResult renewed = probe.handleLease(
                "{\"enabled\":true,\"lease_id\":\"" + leaseId + "\"}");
        return OBJECT_MAPPER.readTree(renewed.responseBody()).path("enabled").asBoolean();
    }

    private static class CountingMap extends AbstractMap<String, Object> {

        protected final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        private int putCount;

        private CountingMap(Map<String, Object> initial) {
            values.putAll(initial);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return values.entrySet();
        }

        @Override
        public Object put(String key, Object value) {
            putCount++;
            return values.put(key, value);
        }

        int putCount() {
            return putCount;
        }
    }

    private static final class FailingMap extends CountingMap {

        private final boolean failRollback;

        private FailingMap(Object first, Object second, boolean failRollback) {
            super(orderedValues(first, second));
            this.failRollback = failRollback;
        }

        private static Map<String, Object> orderedValues(Object first, Object second) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("first", first);
            values.put("second", second);
            return values;
        }

        @Override
        public Object put(String key, Object value) {
            if ("new-2".equals(value)) {
                throw new UnsupportedOperationException("injected-secret");
            }
            if (failRollback && "old-1".equals(value)) {
                throw new UnsupportedOperationException("original-secret");
            }
            return super.put(key, value);
        }
    }

    private record CustomData(String mode) {
    }

    private static final class StatefulMap extends LinkedHashMap<String, Object> {

        private Object hidden;
    }

    private static final class HiddenStateResult {

        public String value;

        @JsonIgnore
        public Object hidden;

        public transient Object transientState;
    }

    private static final class NormalizingBean {

        private String value;
        private String name;

        public String getValue() {
            return value == null ? null : value.toUpperCase();
        }

        public void setValue(String value) {
            this.value = value == null ? null : value.toLowerCase();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private record NormalizingRecord(String value, String name) {

        @Override
        public String value() {
            return value == null ? null : value.toUpperCase();
        }
    }
}
