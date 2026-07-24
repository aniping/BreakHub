package com.ateagents.breakhub.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DebugMethodInfo {

    private String objectName;
    private Integer slotId;
    private String cmdName;
    private String serviceName;
    private String className;
    private String methodName;
    private String displayName;
    private String description;

    private Map<String, Object> params = new LinkedHashMap<>();
    private Map<String, Object> args = new LinkedHashMap<>();
    private List<DebugParam> parameterMeta = new ArrayList<>();

    public DebugMethodInfo objectName(String objectName) {
        this.objectName = objectName;
        return this;
    }

    public DebugMethodInfo cmdName(String cmdName) {
        this.cmdName = cmdName;
        return this;
    }

    public DebugMethodInfo slotId(Integer slotId) {
        this.slotId = slotId;
        return this;
    }

    public DebugMethodInfo serviceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    public DebugMethodInfo className(String className) {
        this.className = className;
        return this;
    }

    public DebugMethodInfo methodName(String methodName) {
        this.methodName = methodName;
        return this;
    }

    public DebugMethodInfo displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public DebugMethodInfo description(String description) {
        this.description = description;
        return this;
    }

    public DebugMethodInfo params(Map<String, Object> params) {
        this.params = params == null ? new LinkedHashMap<>() : params;
        return this;
    }

    public DebugMethodInfo arg(String name, Object value) {
        this.args.put(name, value);
        return this;
    }

    public DebugMethodInfo param(String name, String displayName, String javaType) {
        this.parameterMeta.add(new DebugParam(name, displayName, javaType));
        return this;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public void setSlotId(Integer slotId) {
        this.slotId = slotId;
    }

    public String getCmdName() {
        return cmdName;
    }

    public void setCmdName(String cmdName) {
        this.cmdName = cmdName;
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

    public Map<String, Object> getArgs() {
        return args;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public void setArgs(Map<String, Object> args) {
        this.args = args;
    }

    public List<DebugParam> getParameterMeta() {
        return parameterMeta;
    }

    public void setParameterMeta(List<DebugParam> parameterMeta) {
        this.parameterMeta = parameterMeta;
    }
}
