# Hướng dẫn kỹ thuật chuyển đổi sang Camunda

Để tích hợp Camunda vào dự án hiện tại, hãy thực hiện các bước sau:

## 1. Cấu hình Dependency
Trong `loan-orchestrator-service/build.gradle`:
```gradle
dependencies {
    implementation 'org.camunda.bpm.springboot:camunda-bpm-spring-boot-starter-webapp:7.19.0'
    implementation 'org.camunda.bpm.springboot:camunda-bpm-spring-boot-starter-rest:7.19.0'
}
```

## 2. Bước thực hiện

### Bước 1: Loại bỏ mã nguồn cũ
Các thành phần có thể xóa bỏ sau khi migrate thành công:
- `com.example.lending.orchestrator.saga.SagaStateMachineConfig`
- `com.example.lending.orchestrator.saga.SagaTimeoutJob`
- `com.example.lending.orchestrator.saga.SagaTimeoutScheduler`
- Bảng database `loan_saga` (Camunda sẽ tự quản lý bảng riêng).

### Bước 2: Tạo sơ đồ BPMN
Dùng **Camunda Modeler** tạo file `src/main/resources/loan-process.bpmn`.
- Đặt `id` cho các Task tương ứng (ví dụ: `verify-kyc`).
- Loại task: `External`.

### Bước 3: Triển khai External Workers
Tạo các class implement `ExternalTaskHandler` cho từng bước (KYC, Credit, Disbursement).
Tham khảo mã mẫu tại: `com.example.lending.orchestrator.bpm.KycExternalWorker`.

### Bước 4: Tích hợp Kafka
- **Outgoing**: Worker gửi lệnh sang topic `saga-commands`.
- **Incoming**: Listener lắng nghe kết quả từ `saga-events` và gọi `runtimeService.createMessageCorrelation("...")` để báo cho Camunda tiếp tục.

## 3. Quản lý lỗi và Bù trừ (Compensation)
Trên sơ đồ BPMN:
1. Nhấn chuột phải vào bước `Check Credit` chọn **Boundary Event** -> **Compensation**.
2. Kéo một Task bù trừ nối với Event đó (ví dụ: `Cancel Credit`).
3. Khi bước `Disburse Loan` gặp lỗi, ném một `BpmnError` hoặc điều hướng về **End Cancel Event**. Camunda sẽ tự kích hoạt các Task bù trừ.
