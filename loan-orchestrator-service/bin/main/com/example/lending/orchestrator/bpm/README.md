# Camunda BPM Integration Example

Package này chứa mã mẫu để chuyển đổi từ **Custom Saga Orchestrator** sang **Camunda BPM Engine**.

## 1. Yêu cầu Dependency (build.gradle)

```gradle
dependencies {
    // Camunda Spring Boot Starter
    implementation 'org.camunda.bpm.springboot:camunda-bpm-spring-boot-starter-webapp:7.19.0'
    
    // Camunda External Task Client
    implementation 'org.camunda.bpm:camunda-external-task-client:7.19.0'
}
```

## 2. Luồng hoạt động (Mechanism)

Thay vì quản lý `SagaState` trong Database của mình:

1. **Khởi tạo**: Khi nhận `LoanApplicationCreated`, gọi `runtimeService.startProcessInstanceByKey("loan-process", vars)`.
2. **Điều phối**: Camunda Engine tự động chạy đến task `verify-kyc`.
3. **Thực thi**: `KycExternalWorker` sẽ "nhặt" task này lên, gửi thông tin sang Kafka.
4. **Chờ đợi**: Workflow sẽ dừng lại ở một "Catch Message Event" trong BPMN.
5. **Tiếp tục**: Khi `BpmSagaResultListener` nhận được kết quả từ Kafka, nó gọi `correlate()` để đẩy workflow đi tiếp bước tiếp theo (Check Credit).

## 3. Lợi ích
- **Visual**: Bạn có thể xem biểu đồ loan đang chạy tại `localhost:8080/camunda`.
- **Compensation**: Nếu giải ngân lỗi, chỉ cần ném lỗi, Camunda sẽ tự gọi các lệnh `Hủy Credit` mà không cần switch-case phức tạp.
- **Admin**: Có thể "Retry" một task bị lỗi từ giao diện web mà không cần sửa code/DB.
