package com.ateagents.breakhub.probe;

public class DebugParam {

    private String name;
    private String displayName;
    private String javaType;

    public DebugParam() {
    }

    public DebugParam(String name, String displayName, String javaType) {
        this.name = name;
        this.displayName = displayName;
        this.javaType = javaType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getJavaType() {
        return javaType;
    }

    public void setJavaType(String javaType) {
        this.javaType = javaType;
    }
}
