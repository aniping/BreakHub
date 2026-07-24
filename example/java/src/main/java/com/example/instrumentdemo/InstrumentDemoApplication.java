package com.example.instrumentdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.example.instrumentdemo",
        "com.ateagents.breakhub.probe"
})
public class InstrumentDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(InstrumentDemoApplication.class, args);
    }
}
