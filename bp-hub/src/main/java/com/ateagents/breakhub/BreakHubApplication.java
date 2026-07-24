package com.ateagents.breakhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class BreakHubApplication {

    public static void main(String[] args) {
        application().run(args);
    }

    static SpringApplication application() {
        return new SpringApplication(BreakHubApplication.class);
    }
}
