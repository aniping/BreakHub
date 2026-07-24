package com.example.instrumentdemo.controller;

import com.example.instrumentdemo.controller.dto.ControlParams;
import com.example.instrumentdemo.controller.dto.InitParams;
import com.ateagents.breakhub.probe.ReportingLeaseManager;
import com.example.instrumentdemo.model.ValueResult;
import com.example.instrumentdemo.service.InstrumentService;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo")
public class InstrumentController {
    private static final Logger log = LoggerFactory.getLogger(InstrumentController.class);
    private final InstrumentService instrumentService;
    private final ReportingLeaseManager reportingLeaseManager;

    public InstrumentController(InstrumentService instrumentService,
            ReportingLeaseManager reportingLeaseManager) {
        this.instrumentService = instrumentService;
        this.reportingLeaseManager = reportingLeaseManager;
    }

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @PostMapping("/debugger/enabled")
    public ResponseEntity<Map<String, Object>> setDebuggerEnabled(
            @RequestBody(required = false) String body) {
        ReportingLeaseManager.HttpResult result = reportingLeaseManager.handle(body);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    @PostMapping("/initialize")
    public ValueResult initialize(
            @RequestBody InitParams params) {
        log.info("[BreakHub] REST 仪表对象: {} 编号: {} 命令: {}", params.getInstType(), params.getSlotId(), "INIT");
        return instrumentService.instrumentInitialize(params.getInstType(), params.getSlotId(), null);
    }

    @PostMapping("/control")
    public ValueResult control(
            @RequestBody ControlParams params) {
        log.info("[BreakHub] REST 仪表对象: {} 编号: {} 命令: {}", params.getInstType(), params.getSlotId(), params.getCmdName());
        return instrumentService.instrumentControl(params.getInstType(), params.getCmdName(), params.getSlotId(), params.getParams());
    }
}
