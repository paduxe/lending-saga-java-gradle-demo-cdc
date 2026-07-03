# 08. Tổng hợp Kịch bản Test Case: Failures & Compensations

Tài liệu này tổng hợp 3 kịch bản lỗi (Failure Scenarios) tiêu biểu nhất trong quá trình phê duyệt vay vốn (Saga Flow) và cách kiểm tra dữ liệu tương ứng.

---

## Kịch bản 1: Lỗi ngay từ vòng gửi xe (KYC Failed)
Khách hàng không vượt qua được khâu xác minh danh tính.

**1. Kích hoạt lệnh:**
```bash
curl -X POST http://localhost:8081/api/loans \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "TEST-KYC-FAIL",
    "amount": 1000,
    "productType": "PERSONAL",
    "simulateKycFail": true
  }'
```

**2. Verify hệ thống:**
*   **KYC DB:** Ghi nhận trạng thái `FAILED`.
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-kyc-1 psql -U kyc -d kyc_db -c "SELECT status FROM kyc_result WHERE customer_id = 'TEST-KYC-FAIL';"
    ```
*   **Orchestrator DB:** Ghi nhận lỗi và đóng Saga vĩnh viễn (Không gọi Credit).
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-orchestrator-1 psql -U orchestrator -d orchestrator_db -c "SELECT current_step, saga_status, error_reason FROM loan_saga ORDER BY updated_at DESC LIMIT 1;"
    ```
    *Kỳ vọng:* `current_step = KYC_FAILED` | `saga_status = FAILED` | `error_reason = KYC_REJECTED`

---

## Kịch bản 2: Bị từ chối Tín dụng (Credit Rejected)
Khách hàng qua được KYC nhưng điểm tín dụng (CIC) quá thấp, hệ thống từ chối cấp hạn mức.

**1. Kích hoạt lệnh:**
```bash
curl -X POST http://localhost:8081/api/loans \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "TEST-CREDIT-REJECT",
    "amount": 50000000,
    "productType": "PERSONAL",
    "simulateCreditReject": true
  }'
```

**2. Verify hệ thống:**
*   **Credit DB:** Ghi nhận trạng thái `FAILED`.
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-credit-1 psql -U credit -d credit_db -c "SELECT status FROM credit_result WHERE customer_id = 'TEST-CREDIT-REJECT';"
    ```
*   **Orchestrator DB:** Dừng Saga, không tiếp tục gọi Disbursement.
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-orchestrator-1 psql -U orchestrator -d orchestrator_db -c "SELECT current_step, saga_status, error_reason FROM loan_saga ORDER BY updated_at DESC LIMIT 1;"
    ```
    *Kỳ vọng:* `current_step = CREDIT_REJECTED` | `saga_status = FAILED` | `error_reason = CREDIT_CHECK_FAILED`

---

## Kịch bản 3: Giải ngân thất bại - Đòi hỏi Giao dịch bù trừ (Disbursement Failed & Compensation)
Khách hàng qua KYC, được cấp Credit thành công, nhưng hệ thống Core Banking lỗi không bắn được tiền. Buộc hệ thống phải quay lại hủy hạn mức Credit.

**1. Kích hoạt lệnh:**
```bash
curl -X POST http://localhost:8081/api/loans \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "TEST-COMPENSATION",
    "amount": 2000,
    "productType": "PERSONAL",
    "simulateDisbursementFail": true
  }'
```

**2. Verify hệ thống (Trái tim của Saga):**
*   **Disbursement DB:** Báo `FAILED`.
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-disbursement-1 psql -U disbursement -d disbursement_db -c "SELECT status FROM disbursement_result WHERE customer_id = 'TEST-COMPENSATION';"
    ```
*   **Credit DB:** Phải có **2 bản ghi**. Một cái `SUCCESS` (Lúc cấp) và một cái `CANCELLED` (Lúc bị đòi lại).
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-credit-1 psql -U credit -d credit_db -c "SELECT status, processed_at FROM credit_result WHERE customer_id = 'TEST-COMPENSATION' ORDER BY processed_at ASC;"
    ```
*   **Orchestrator DB:** State Machine hoàn tất sơ đồ chữ V.
    ```bash
    docker exec -it lending-saga-java-gradle-demo-cdc-postgres-orchestrator-1 psql -U orchestrator -d orchestrator_db -c "SELECT current_step, saga_status, error_reason FROM loan_saga ORDER BY updated_at DESC LIMIT 1;"
    ```
    *Kỳ vọng:* `current_step = CREDIT_CANCELLED` | `saga_status = FAILED` | `error_reason = DISBURSEMENT_FAILED_CREDIT_COMPENSATED`
