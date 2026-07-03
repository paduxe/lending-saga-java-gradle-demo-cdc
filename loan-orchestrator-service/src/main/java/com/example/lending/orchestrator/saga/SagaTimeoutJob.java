package com.example.lending.orchestrator.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class SagaTimeoutJob {

    private final LoanSagaRepository sagaRepository;
    private final SagaCacheService sagaCacheService;

    // Chạy mỗi 5 phút một lần để tổng kiểm tra Sổ đỏ
    @Scheduled(cron = "0 */5 * * * *")
    // Dùng Shedlock đảm bảo dù có 100 Nodes Orchestrator đang chạy song song, 
    // thì chỉ duy nhất 1 thằng được phép cầm cờ chạy Job này (tránh đụng độ DB)
    @SchedulerLock(name = "sagaTimeoutCheckTask", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    @Transactional
    public void checkForTimedOutSagas() {
        log.info("⏰ Bắt đầu quét các Hồ sơ Vay vốn (Saga) bị câm điếc (Timeout)...");

        // Tìm những Saga khởi tạo cách đây quá 3 ngày mà vẫn Đang Chạy (Bị kẹt ở KYC hoặc Credit)
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        
        List<LoanSagaEntity> timedOutSagas = sagaRepository.findBySagaStatusAndUpdatedAtBefore("RUNNING", threeDaysAgo);

        if (timedOutSagas.isEmpty()) {
            log.info("✅ Không phát hiện Saga nào bị kẹt Timeout.");
            return;
        }

        for (LoanSagaEntity saga : timedOutSagas) {
            log.warn("🔥 PHÁT HIỆN SAGA BỊ KẸT (Khả năng do Service con sập): {}", saga.getSagaId());
            
            // Xử lý Hủy Hồ Sơ Khẩn Cấp
            saga.setCurrentStep("TIMEOUT_ROLLBACK");
            saga.setSagaStatus("FAILED");
            saga.setErrorReason("QUÁ_THỜI_GIAN_CHỜ_DUYỆT_3_NGÀY");
            saga.setUpdatedAt(Instant.now());

            // Lưu trạng thái vĩnh viễn xuống DB và xóa bọt RAM
            sagaCacheService.flushToDb(saga);
            
            // TODO: Ở bước này, trong thực tế sẽ gọi Kafka bắn lệnh "CancelLoanCommand" 
            // sang loan-service để báo cho Khách hàng biết Hồ sơ bị hủy do Timeout.
        }
        
        log.info("🏁 Đã dọn dẹp xong {} Saga Timeout chết chìm.", timedOutSagas.size());
    }
}
