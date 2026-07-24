package com.ateagents.breakhub.probe;

import java.util.List;
import java.util.Map;

public class BeforeCallRequest {

    private String callId;
    private String objectName;
    private String cmdName;
    private Integer slotId;
    private Map<String, Object> params;
    private String serviceName;
    private String className;
    private String methodName;
    private String displayName;
    private String description;
    private String threadName;
    private long timestamp;
    private Map<String, Object> args;
    private Map<String, Object> rawArgs;
    private List<DebugParam> parameterMeta;

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public String getCmdName() {
        return cmdName;
    }

    public void setCmdName(String cmdName) {
        this.cmdName = cmdName;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public void setSlotId(Integer slotId) {
        this.slotId = slotId;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public void setArgs(Map<String, Object> args) {
        this.args = args;
    }

    public Map<String, Object> getRawArgs() {
        return rawArgs;
    }

    public void setRawArgs(Map<String, Object> rawArgs) {
        this.rawArgs = rawArgs;
    }

    public List<DebugParam> getParameterMeta() {
        return parameterMeta;
    }

    public void setParameterMeta(List<DebugParam> parameterMeta) {
        this.parameterMeta = parameterMeta;
    }
}
