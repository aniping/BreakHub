package com.ateagents.breakhub.config;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

public final class FileOnlyConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String COMMAND_LINE_ARGS = "commandLineArgs";
    private static final String SPRING_APPLICATION_JSON = "spring.application.json";
    private static final String TRUSTED_CONFIG_LOCATION = "breakHubConfigLocation";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        PropertySource<?> commandLine = environment.getPropertySources().get(COMMAND_LINE_ARGS);
        Object configLocation = commandLine == null ? null : commandLine.getProperty("spring.config.location");
        environment.getPropertySources().remove(COMMAND_LINE_ARGS);
        environment.getPropertySources().remove(SPRING_APPLICATION_JSON);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (configLocation != null) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    TRUSTED_CONFIG_LOCATION,
                    Map.of("spring.config.location", configLocation)));
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }
}
