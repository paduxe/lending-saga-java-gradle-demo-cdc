# Các bước thực hiện Test và Verify Data của 1 Flow Happy Case

Tài liệu này hướng dẫn bạn cách "soi" từng nút thắt của hệ thống khi chạy thử luồng vay vốn thành công.

## 1. Kích hoạt luồng (Gửi yêu cầu)
Dùng lệnh `curl` dưới đây để bắt đầu một quy trình mới:
```bash
curl -X POST http://localhost:8081/api/loans \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "HAPPY-CUST-LOG",
    "amount": 1000,
    "productType": "PERSONAL"
  }'
```

## 2. Xác minh dữ liệu đầu vào (Source of Truth)
Khi quy trình bắt đầu, hai bảng sau ở **Loan DB** phải có dữ liệu:
*   `loan_application`: Lưu đơn vay mới với trạng thái `PENDING`.
*   `outbox_event`: Lưu một sự kiện `LoanApplicationCreated`.

**Lệnh kiểm tra:**
```bash
docker exec -it [CONTAINER_POSTGRES_LOAN] psql -U loan -d loan_db -c "SELECT * FROM outbox_event ORDER BY created_at DESC LIMIT 1;"
```

## 3. Xác minh CDC (Debezium and Kafka)
Kiểm tra xem Debezium đã bốc tin nhắn từ DB ném lên Kafka chưa:
```bash
docker exec -it [CONTAINER_KAFKA] /opt/bitnami/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic saga-events \
  --from-beginning \
  --max-messages 10
```
**Kỳ vọng:** Bạn thấy một tin nhắn JSON bọc trong `schema` và `payload`.

## 4. Xác minh Saga Orchestrator (Monitoring)
Gọi API của Orchestrator Service (Cổng 8080) để xem trạng thái hiện tại của Saga:
```bash
curl http://localhost:8080/api/sagas/[SAGA_ID]
```
**Kỳ vọng:**
*   `currentStep`: Thay đổi từ `KYC_REQUESTED` -> `CREDIT_REQUESTED` -> `DISBURSEMENT_REQUESTED`.
*   `sagaStatus`: Phải là `RUNNING` (đang chạy) hoặc `COMPLETED` (đã xong).

## 5. Xác minh đích đến (Final Data)
Sau vài giây, kiểm tra kết quả cuối cùng tại **Disbursement DB**:
```bash
docker exec -it [CONTAINER_POSTGRES_DISBURSEMENT] psql -U disbursement -d disbursement_db -c "SELECT * FROM disbursement_result;"
```
**Kỳ vọng:** Bản ghi có trạng thái là **`SUCCESS`**.

## Tóm tắt luồng dữ liệu:
1.  **POST /api/loans** -> Ghi DB Loan -> Ghi Outbox Loan.
2.  **Postgres WAL** -> Debezium bắt sự kiện -> Gửi Topic `saga-events`.
3.  **Orchestrator** -> Nhận Event -> Cập nhật Saga DB -> Ghi Outbox (Command).
4.  **Debezium** -> Gửi Topic `saga-commands`.
5.  **Service con (KYC/Credit/Disbursement)** -> Nhận Command -> Ghi DB riêng -> Ghi Outbox (Event kết quả).
6.  **Debezium** -> Trả dữ liệu về Topic `saga-events` -> Orchestrator xử lý tiếp.
