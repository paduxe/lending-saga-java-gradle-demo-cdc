package com.example.lending.orchestrator.bpm;

import com.example.lending.orchestrator.messaging.MessageSender;
import com.example.lending.orchestrator.commands.VerifyKycCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.spring.annotation.ExternalTaskSubscription;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mẫu External Task Worker cho bước KYC trong Camunda.
 * 
 * Flow:
 * 1. Camunda Process Engine quản lý workflow, khi đến bước "Verify KYC", 
 *    nó sẽ tạo một task trên TOPIC "verify-kyc".
 * 2. Worker này sẽ poll task đó về.
 * 3. Gửi command sang Kafka để KYC Service xử lý (giữ nguyên hạ tầng Kafka hiện tại).
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ExternalTaskSubscription("verify-kyc") // Topic ID định nghĩa trong BPMN
public class KycExternalWorker implements ExternalTaskHandler {

    private final MessageSender messageSender;

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        // 1. Trích xuất thông tin từ Camunda Process Variables
        String loanId = externalTask.getVariable("loanId");
        String sagaId = externalTask.getProcessInstanceId(); // Dùng ProcessInstanceId của Camunda làm SagaId

        log.info("BPM Worker: Đang xử lý KYC cho LoanId: {} với SagaId: {}", loanId, sagaId);

        try {
            // 2. Tạo Command và gửi qua Kafka (Tận dụng code cũ)
            VerifyKycCommand command = VerifyKycCommand.builder()
                    .sagaId(UUID.fromString(sagaId))
                    .loanId(UUID.fromString(loanId))
                    .build();

            // Gửi message vào topic "saga-commands"
            messageSender.send("saga-commands", command);

            // 3. Đánh máy Task là đã được poll thành công. 
            // Lưu ý: Chúng ta chưa hoàn thành (complete) task ở đây.
            // Task sẽ "treo" ở Camunda cho đến khi có kết quả từ Kafka (KycPassed/Failed).
            // externalTaskService.complete(externalTask); // KHÔNG gọi ở đây vì chờ async
            
            log.info("BPM Worker: Đã gửi lệnh VerifyKyc sang Kafka. Chờ kết quả bất đồng bộ...");

        } catch (Exception e) {
            log.error("BPM Worker: Lỗi khi xử lý KYC", e);
            // Báo lỗi cho Camunda để nó tự động retry hoặc tạo Handle Incident
            externalTaskService.handleFailure(externalTask, e.getMessage(), "Kafka error", 3, 5000);
        }
    }
}
