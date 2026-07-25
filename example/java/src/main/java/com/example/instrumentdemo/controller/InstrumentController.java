package com.example.instrumentdemo.controller;

import com.example.instrumentdemo.controller.dto.ControlParams;
import com.example.instrumentdemo.controller.dto.InitParams;
import com.ateagents.breakhub.probe.BreakHubProbe;
import com.ateagents.breakhub.probe.LeaseResult;
import com.example.instrumentdemo.model.ValueResult;
import com.example.instrumentdemo.service.InstrumentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo")
public class InstrumentController {
    private static final Logger log = LoggerFactory.getLogger(InstrumentController.class);
    private final InstrumentService instrumentService;
    private final BreakHubProbe probe;

    public InstrumentController(InstrumentService instrumentService,
            BreakHubProbe probe) {
        this.instrumentService = instrumentService;
        this.probe = probe;
    }

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @PostMapping("/debugger/enabled")
    public ResponseEntity<String> setDebuggerEnabled(
            @RequestBody(required = false) String body) {
        LeaseResult result = probe.handleLease(body);
        return ResponseEntity.status(result.statusCode())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(result.responseBody());
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
