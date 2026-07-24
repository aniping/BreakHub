package com.example.instrumentdemo.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.instrumentdemo.InstrumentDemoApplication;

class InstrumentDemoRestartHttpTest {

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void restartForgetsTheOldLeaseAndFencesItFromTheNewLease() {
        String oldLeaseId;
        try (ConfigurableApplicationContext first = startDemo()) {
            ResponseEntity<Map<String, Object>> created = post(first, "{\"enabled\":true}");

            assertThat(created.getStatusCode().value()).isEqualTo(200);
            oldLeaseId = (String) created.getBody().get("lease_id");
            assertThat(oldLeaseId).isNotBlank();
        }

        try (ConfigurableApplicationContext second = startDemo()) {
            assertError(post(second, request(true, oldLeaseId)), 404,
                    "REPORTING_LEASE_NOT_FOUND");
            assertError(post(second, request(false, oldLeaseId)), 404,
                    "REPORTING_LEASE_NOT_FOUND");

            ResponseEntity<Map<String, Object>> created = post(second, "{\"enabled\":true}");
            assertThat(created.getStatusCode().value()).isEqualTo(200);
            String newLeaseId = (String) created.getBody().get("lease_id");
            assertThat(newLeaseId).isNotBlank().isNotEqualTo(oldLeaseId);

            assertError(post(second, request(true, oldLeaseId)), 409,
                    "REPORTING_LEASE_CONFLICT");
            assertError(post(second, request(false, oldLeaseId)), 409,
                    "REPORTING_LEASE_CONFLICT");

            ResponseEntity<Map<String, Object>> renewed = post(
                    second, request(true, newLeaseId));
            assertThat(renewed.getStatusCode().value()).isEqualTo(200);
            assertThat(renewed.getBody())
                    .containsEntry("result", "renewed")
                    .containsEntry("enabled", true)
                    .containsEntry("lease_id", newLeaseId);
        }
    }

    private ConfigurableApplicationContext startDemo() {
        return new SpringApplicationBuilder(InstrumentDemoApplication.class)
                .run("--server.port=0", "--spring.main.banner-mode=off");
    }

    private ResponseEntity<Map<String, Object>> post(
            ConfigurableApplicationContext context, String body) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                "http://127.0.0.1:" + port + "/api/demo/debugger/enabled",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private void assertError(
            ResponseEntity<Map<String, Object>> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody())
                .containsOnlyKeys("code", "message")
                .containsEntry("code", code)
                .doesNotContainKey("lease_id");
    }

    private String request(boolean enabled, String leaseId) {
        return "{\"enabled\":" + enabled + ",\"lease_id\":\"" + leaseId + "\"}";
    }
}
