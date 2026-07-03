package com.example.lending.orchestrator.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * RECONCILIATION JOB (Hòa giải dữ liệu)
 * Vai trò: Đảm bảo tính nhất quán cuối cùng (Eventual Consistency) giữa Orchestrator và Loan Service.
 * Cơ chế: Quét các Saga đã kết thúc thành công/thất bại gần đây và tái phát tán (Re-publish) kết quả 
 * để đảm bảo Loan Service chắc chắn nhận được (Idempotent).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReconciliationJob {

    private final LoanSagaRepository repository;
    private final SagaResultService sagaResultService;

    // Chạy mỗi 30 phút để "vét lưới" các đơn hàng bị lạc
    @Scheduled(cron = "0 0/30 * * * *")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
        name = "sagaReconciliationTask", 
        lockAtLeastFor = "1m", 
        lockAtMostFor = "10m")
    @Transactional(readOnly = true)
    public void reconcileSagas() {
        log.info("🚀 [RECONCILIATION] Bắt đầu tiến trình hòa giải dữ liệu...");
        
        // Tìm các Saga đã xong (COMPLETED/FAILED) trong vòng 1 giờ qua
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        List<LoanSagaEntity> finishedSagas = repository.findFinishedSagasSince(oneHourAgo);

        int count = 0;
        for (LoanSagaEntity saga : finishedSagas) {
            String finalStatus = "COMPLETED".equals(saga.getSagaStatus()) ? "APPROVED" : "REJECTED";
            
            // Re-publish results (Loan Service xử lý idempotent qua processed_messages nên an toàn)
            sagaResultService.sendResultToLoanService(saga, finalStatus);
            count++;
        }

        log.info("✅ [RECONCILIATION] Hoàn tất hòa giải. Đã tái đồng bộ {} đơn vay.", count);
    }
}
