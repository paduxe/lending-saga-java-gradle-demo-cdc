package com.example.lending.orchestrator.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaTimeoutScheduler {

    private final LoanSagaRepository repository;
    private final SagaResultService sagaResultService;

    @Scheduled(fixedDelayString = "${lending.saga.timeout-delay:10000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
        name = "checkTimeoutsTask", 
        lockAtLeastFor = "5s", 
        lockAtMostFor = "1m")
    @Transactional
    public void checkTimeouts() {
        List<LoanSagaEntity> expired = repository.findTimedOutRunningSagas(Instant.now());

        for (LoanSagaEntity saga : expired) {
            saga.setSagaStatus("FAILED");
            saga.setCurrentStep("TIMEOUT");
            saga.setErrorReason("SAGA_TIMEOUT");
            saga.setTimeoutAlerted(Boolean.TRUE);
            saga.setUpdatedAt(Instant.now());

            // XẢ ĐĨA & SYNC STATUS VỀ LOAN-SERVICE (Sắc bén!)
            sagaResultService.sendResultToLoanService(saga, "REJECTED");

            log.error("Saga timed out. sagaId={}, loanApplicationId={}, currentStep={}",
                    saga.getSagaId(), saga.getLoanApplicationId(), saga.getCurrentStep());
        }
    }
}
