package com.example.instrumentdemo.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstrumentControllerDebuggerSwitchTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void debuggerSwitchExposesTheReportingLeaseContractWithoutAuthentication() {
        ResponseEntity<Map<String, Object>> invalid = postJson("{\"enabled\":true,\"unexpected\":1}");

        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(invalid.getBody())
                .containsOnlyKeys("code", "message")
                .containsEntry("code", "INVALID_REPORTING_LEASE_REQUEST");

        ResponseEntity<Map<String, Object>> created = postJson("{\"enabled\":true}");

        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(created.getBody())
                .containsOnlyKeys("success", "result", "changed", "enabled",
                        "lease_timeout_seconds", "reporting_status", "lease_id")
                .containsEntry("success", true)
                .containsEntry("result", "created")
                .containsEntry("changed", true)
                .containsEntry("enabled", true)
                .containsEntry("lease_timeout_seconds", 30)
                .containsEntry("reporting_status", "healthy")
                .containsKey("lease_id");
        String leaseId = (String) created.getBody().get("lease_id");
        assertThat(leaseId).isNotBlank();

        ResponseEntity<Map<String, Object>> duplicateStart = postJson("{\"enabled\":true}");

        assertThat(duplicateStart.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicateStart.getBody())
                .containsOnlyKeys("code", "message")
                .containsEntry("code", "REPORTING_LEASE_ALREADY_ACTIVE")
                .doesNotContainKey("lease_id");

        ResponseEntity<Map<String, Object>> renewed = postJson(request(true, leaseId));

        assertThat(renewed.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(renewed.getBody())
                .containsOnlyKeys("success", "result", "changed", "enabled",
                        "lease_timeout_seconds", "reporting_status", "lease_id")
                .containsEntry("result", "renewed")
                .containsEntry("changed", false)
                .containsEntry("enabled", true)
                .containsEntry("lease_id", leaseId);

        ResponseEntity<Map<String, Object>> stopped = postJson(request(false, leaseId));

        assertThat(stopped.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(stopped.getBody())
                .containsOnlyKeys("success", "result", "changed", "enabled",
                        "lease_timeout_seconds", "reporting_status")
                .containsEntry("result", "stopped")
                .containsEntry("changed", true)
                .containsEntry("enabled", false)
                .containsEntry("reporting_status", "idle")
                .doesNotContainKey("lease_id");

        ResponseEntity<Map<String, Object>> repeatedStop = postJson(request(false, leaseId));

        assertThat(repeatedStop.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(repeatedStop.getBody())
                .containsEntry("result", "already_stopped")
                .containsEntry("changed", false)
                .containsEntry("enabled", false);

        ResponseEntity<Map<String, Object>> staleRenew = postJson(request(true, leaseId));

        assertThat(staleRenew.getStatusCode().value()).isEqualTo(404);
        assertThat(staleRenew.getBody())
                .containsOnlyKeys("code", "message")
                .containsEntry("code", "REPORTING_LEASE_NOT_FOUND");
    }

    private ResponseEntity<Map<String, Object>> postJson(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/demo/debugger/enabled", HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
                });
    }

    private String request(boolean enabled, String leaseId) {
        return "{\"enabled\":" + enabled + ",\"lease_id\":\"" + leaseId + "\"}";
    }
}
