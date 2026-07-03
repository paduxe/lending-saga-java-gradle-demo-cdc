package com.example.lending.loan.job;

import com.example.lending.loan.loan.LoanApplicationEntity;
import com.example.lending.loan.loan.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * RECONCILIATION JOB (CHIẾN THẦN ĐỐI SOÁT)
 * Đảm bảo tính nhất quán cuối cùng (Eventual Consistency)
 * Tránh việc Đơn vay bị treo ở PENDING do mất mát Message Kafka hoặc Network Glitches.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private final LoanApplicationRepository loanRepository;
    private final RestTemplate restTemplate = new RestTemplateBuilder().build();
    private static final String ORCHESTRATOR_URL = "http://loan-orchestrator-service:8080/api/sagas/";

    @Scheduled(cron = "0 0/15 * * * *") // Chạy mỗi 15 phút (Mẻ lưới vét định kỳ)
    public void reconcilePendingLoans() {
        log.info("Starting Reconciliation Job...");

        // Tìm các đơn PENDING lâu hơn 10 phút (Khả năng cao đã gãy luồng Event)
        Instant threshold = Instant.now().minus(Duration.ofMinutes(10));
        List<LoanApplicationEntity> pendingLoans = loanRepository.findAll().stream()
                .filter(l -> "PENDING".equals(l.getStatus()) && l.getCreatedAt().isBefore(threshold))
                .toList();

        if (pendingLoans.isEmpty()) {
            log.info("No pending loans found for reconciliation.");
            return;
        }

        log.info("Reconciling {} pending loans...", pendingLoans.size());

        for (LoanApplicationEntity loan : pendingLoans) {
            try {
                // Gọi API sang Orchestrator để hỏi trạng thái thật của Saga
                Map sagaResponse = restTemplate.getForObject(ORCHESTRATOR_URL + loan.getSagaId(), Map.class);
                if (sagaResponse != null) {
                    String sagaStatus = (String) sagaResponse.get("sagaStatus");
                    
                    if ("COMPLETED".equals(sagaStatus)) {
                         log.warn("RECONCILIATION FIXED: Loan {} was PENDING but Saga is COMPLETED. Syncing status to APPROVED.", loan.getLoanApplicationId());
                         loan.setStatus("APPROVED");
                         loanRepository.save(loan);
                    } else if ("FAILED".equals(sagaStatus)) {
                         log.warn("RECONCILIATION FIXED: Loan {} was PENDING but Saga is FAILED. Syncing status to REJECTED.", loan.getLoanApplicationId());
                         loan.setStatus("REJECTED");
                         loanRepository.save(loan);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to reconcile loan {}: {}", loan.getLoanApplicationId(), e.getMessage());
            }
        }
        log.info("Reconciliation Job finished.");
    }
}
