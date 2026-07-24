package com.ateagents.breakhub.probe;

import java.util.Map;

final class TestDebugMethodInfos {

    private TestDebugMethodInfos() {
    }

    static DebugMethodInfo commonMethodData(
            String object,
            String command,
            String methodName,
            Integer slotId,
            Map<String, Object> params) {
        return new DebugMethodInfo()
                .objectName(object)
                .slotId(slotId)
                .cmdName(command)
                .description(methodName)
                .params(params)
                .serviceName("probe-test")
                .className("ProbeTestService")
                .methodName(methodName)
                .arg("params", params)
                .param("params", "操作传参", "java.util.Map");
    }
}
