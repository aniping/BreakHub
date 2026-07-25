package com.ateagents.breakhub.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class DebugInvoker {

    private final DebugClient client;
    private final ReportingChannel reportingChannel;

    DebugInvoker(DebugClient client, ReportingChannel reportingChannel) {
        this.client = Objects.requireNonNull(client, "client");
        this.reportingChannel = Objects.requireNonNull(reportingChannel, "reportingChannel");
    }

    <T> T invoke(DebugMethodInfo methodInfo, DebugCallable<T> callable) {
        if (!reportingChannel.isActive()) {
            return callBusiness(callable);
        }

        String callId = UUID.randomUUID().toString();

        BeforeCallRequest beforeRequest = buildBeforeCallRequest(callId, methodInfo);

        WaitResponse completedWait = null;

        try {
            BeforeCallResponse beforeResponse = client.beforeCall(beforeRequest);
            if (beforeResponse != null && beforeResponse.shouldWait(callId)) {
                System.out.println("[BreakHub] breakpoint hit, waiting. callId="
                        + callId
                        + ", method="
                        + methodInfo.getMethodName());

                completedWait = client.waitContinue(callId);
                System.out.println("[BreakHub] wait finished. callId="
                        + callId
                        + ", result="
                        + (completedWait == null ? null : completedWait.getResult()));
            }
        } catch (Exception e) {
            System.out.println("[BreakHub] before-call failed, continue business. errorType="
                    + e.getClass().getSimpleName());
        }

        if (completedWait != null && completedWait.hasApplicableBeforeContent(callId)) {
            BeforeParameterInjector.apply(
                    methodInfo.getParams(),
                    completedWait.getContent(),
                    methodInfo.getMethodName());
        }

        T originalResult;
        try {
            originalResult = callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return completeAfter(callId, methodInfo, originalResult);
    }

    static BeforeCallRequest buildBeforeCallRequest(String callId, DebugMethodInfo methodInfo) {
        BeforeCallRequest request = new BeforeCallRequest();
        request.setCallId(callId);
        request.setObjectName(methodInfo.getObjectName());
        request.setCmdName(methodInfo.getCmdName());
        request.setSlotId(methodInfo.getSlotId());
        request.setParams(nonNullMap(methodInfo.getParams()));
        request.setServiceName(methodInfo.getServiceName());
        request.setClassName(methodInfo.getClassName());
        request.setMethodName(methodInfo.getMethodName());
        request.setDisplayName(methodInfo.getDisplayName());
        request.setDescription(methodInfo.getDescription());
        request.setThreadName(Thread.currentThread().getName());
        request.setTimestamp(System.currentTimeMillis());
        Map<String, Object> args = businessArgs(methodInfo);
        request.setArgs(args);
        request.setRawArgs(args);
        request.setParameterMeta(methodInfo.getParameterMeta());
        return request;
    }

    private static Map<String, Object> businessArgs(DebugMethodInfo methodInfo) {
        Map<String, Object> args = new LinkedHashMap<>(nonNullMap(methodInfo.getArgs()));
        args.remove("instType");
        args.put("objectName", methodInfo.getObjectName());
        args.put("cmdName", methodInfo.getCmdName());
        args.put("slotId", methodInfo.getSlotId());
        args.put("params", nonNullMap(methodInfo.getParams()));
        return args;
    }

    private static Map<String, Object> nonNullMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : value;
    }

    private static AfterCallRequest buildAfterSuccessRequest(String callId, Object result) {
        AfterCallRequest request = new AfterCallRequest();
        request.setCallId(callId);
        request.setResult(result);
        return request;
    }

    private <T> T completeAfter(
            String callId,
            DebugMethodInfo methodInfo,
            T originalResult) {
        try {
            AfterCallResponse afterResponse = client.afterCall(
                    buildAfterSuccessRequest(callId, originalResult));
            if (afterResponse != null && afterResponse.shouldWait(callId)) {
                System.out.println("[BreakHub] after breakpoint hit, waiting. callId="
                        + callId
                        + ", method="
                        + methodInfo.getMethodName());
                WaitResponse waitResponse = client.waitContinue(callId, "after");
                System.out.println("[BreakHub] after wait finished. callId="
                        + callId
                        + ", result="
                        + (waitResponse == null ? null : waitResponse.getResult()));
                if (waitResponse != null && waitResponse.hasApplicableAfterContent(callId)) {
                    return AfterResultConverter.convert(
                            originalResult,
                            waitResponse.getContent(),
                            methodInfo.getMethodName());
                }
            }
        } catch (Exception error) {
            System.out.println("[BreakHub] after-call failed, use original result. method="
                    + methodInfo.getMethodName()
                    + ", errorType="
                    + error.getClass().getSimpleName());
        }
        return originalResult;
    }

    private static <T> T callBusiness(DebugCallable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
