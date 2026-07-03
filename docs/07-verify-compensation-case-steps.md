# 07. Hướng dẫn Test và Verify luồng Giao dịch bù trừ (Compensation Flow)

Đây là tài liệu hướng dẫn cực kỳ quan trọng để kiểm tra kịch bản **Giải ngân thất bại (Disbursement Failed)**. 
Bài test này sẽ chứng minh hệ thống của bạn có khả năng "quay xe" (Rollback/Compensation) hoàn hảo khi có lỗi ở cuối quy trình, giúp bảo toàn tính nhất quán (Eventual Consistency).

## 1. Kích hoạt luồng (Gửi yêu cầu cố tình gây lỗi)
Dùng lệnh `curl` dưới đây. Lưu ý cờ `"simulateDisbursementFail": true`, điều này sẽ ra lệnh cho `disbursement-service` giả vờ bị lỗi kết nối Core Banking.
```bash
curl -X POST http://localhost:8081/api/loans \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "COMPENSATION-TEST",
    "amount": 5000,
    "productType": "PERSONAL",
    "simulateDisbursementFail": true
  }'
```
*(Hãy copy lại mã `sagaId` trả về nhé).*

---

## 2. Xác minh tại Disbursement DB (Gây án)
Kiểm tra xem Microservice này có thực sự đã đánh dấu giao dịch là FAILED hay không:
```bash
docker exec -it lending-saga-java-gradle-demo-cdc-postgres-disbursement-1 psql -U disbursement -d disbursement_db \
  -c "SELECT * FROM disbursement_result WHERE customer_id = 'COMPENSATION-TEST';"
```
**Kỳ vọng:** Bạn sẽ thấy 1 bản ghi với cột `status` là **`FAILED`**.

---

## 3. Xác minh tại Credit DB (Nạn nhân được hoàn tiền)
Đây là phần tinh túy nhất của Saga. Do Disbursement bị lỗi, Orchestrator sẽ bắn lệnh `CancelCreditCommand` quay ngược lại Credit Service để thu hồi hạn mức đã cấp ở bước 2.

```bash
docker exec -it lending-saga-java-gradle-demo-cdc-postgres-credit-1 psql -U credit -d credit_db \
  -c "SELECT status, processed_at FROM credit_result WHERE customer_id = 'COMPENSATION-TEST' ORDER BY processed_at ASC;"
```
**Kỳ vọng:** Bạn sẽ thấy **2 bản ghi** cho cùng một Đơn vay này:
1. Bản ghi 1: Trạng thái **`SUCCESS`** (Sinh ra lúc bước 2 kiểm tra Credit vượt qua thành công).
2. Bản ghi 2: Trạng thái **`CANCELLED`** (Sinh ra từ nghiệp vụ bù trừ, hoàn trả lại hạn mức).

---

## 4. Xác minh tại Orchestrator DB (Sổ số mạng)
Kiểm tra xem "Nhạc trưởng" đã ghi nhận và đóng hồ sơ này với lý do gì:
```bash
docker exec -it lending-saga-java-gradle-demo-cdc-postgres-orchestrator-1 psql -U orchestrator -d orchestrator_db \
  -c "SELECT current_step, saga_status, error_reason FROM loan_saga ORDER BY updated_at DESC LIMIT 1;"
```
**Kỳ vọng:**
*   `current_step`: Trạng thái cuối cùng dừng lại ở **`CREDIT_CANCELLED`**.
*   `saga_status`: Đã bị đánh là **`FAILED`** (hoặc `COMPENSATED`).
*   `error_reason`: Hiện rõ lý do **`DISBURSEMENT_FAILED_CREDIT_COMPENSATED`**.

---

## Tóm tắt Luồng sự kiện (Flow) dưới vỏ bọc Kafka:
Để hiểu rõ hệ thống đã "chạy đua" như thế nào trong 5 giây vừa rồi:

1. **[Start]** Đơn vay khởi tạo $\rightarrow$ KYC Thành công $\rightarrow$ Credit Cấp hạn mức Thành công.
2. **[Lỗi phát sinh]** Disbursement Service cố gắng giải ngân $\rightarrow$ Lỗi (do cờ simulate) $\rightarrow$ Ghi Outbox `DisbursementFailedEvent`.
3. **[Điều hướng]** Orchestrator nhận event lỗi $\rightarrow$ Chuyển state sang `CREDIT_CANCELLATION_REQUESTED` $\rightarrow$ Ghi Outbox `CancelCreditCommand`.
4. **[Bù trừ]** Credit Service nhận command $\rightarrow$ Tiến hành Hủy tín dụng $\rightarrow$ Ghi Outbox `CreditCancelledEvent`.
5. **[Kết thúc]** Orchestrator nhận event hủy $\rightarrow$ Đóng Saga lại vĩnh viễn với trạng thái `FAILED`.

Đây chính là câu trả lời thực tế bằng hệ thống cho bài toán: *"Làm sao đảm bảo tính toàn vẹn dữ liệu xuyên suốt nhiều Database mà không dùng Distributed Transaction (2PC)?"*
