package com.ateagents.breakhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InteractionRetentionConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "breakhub.data-directory=unused",
                    "breakhub.equipment.id=test",
                    "breakhub.equipment.display-name=test",
                    "breakhub.equipment.debugger-switch.url=http://127.0.0.1:9/debugger",
                    "breakhub.security.web-username=admin",
                    "breakhub.security.web-password=test",
                    "breakhub.security.gateway-token=test",
                    "breakhub.security.business-client-token=test",
                    "breakhub.control-lease.timeout=30m",
                    "breakhub.interaction.pause-timeout=25m",
                    "breakhub.interaction.max-payload-size=16MB");

    @Test
    void existingConfigurationDefaultsTo18000Records() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProductProperties.class).interaction().maxRecords()).isEqualTo(18000);
        });
    }

    @Test
    void configuredRecordLimitIsBound() {
        runner.withPropertyValues("breakhub.interaction.max-records=7").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProductProperties.class).interaction().maxRecords()).isEqualTo(7);
        });
    }

    @Test
    void nonPositiveOrNonIntegerRecordLimitsPreventStartup() {
        for (String value : new String[] { "0", "-1", "1.5" }) {
            runner.withPropertyValues("breakhub.interaction.max-records=" + value).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("max-records");
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProductProperties.class)
    static class PropertiesConfiguration {
    }
}
