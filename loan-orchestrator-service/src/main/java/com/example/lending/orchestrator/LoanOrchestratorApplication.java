package com.example.lending.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class LoanOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoanOrchestratorApplication.class, args);
    }
}
