# State Machine với Saga

Tài liệu này giải thích cách chúng ta quản lý một quy trình nghiệp vụ phức tạp kéo dài qua nhiều Microservices (Long-running process).

## 1. Saga Orchestration Pattern
Hệ thống của chúng ta đang dùng mô hình **Orchestrator** (Điều hướng trung tâm).

*   **Bộ não (Orchestrator):** Là `loan-orchestrator-service`. Nó giữ vai trò "Nhạc trưởng", biết rõ quy định: "Nếu bước A xong thì làm bước B".
*   **Các nhạc công (Service con):** KYC, Credit, Disbursement. Chúng chỉ biết làm tốt việc của mình sau khi nhận được lệnh từ bộ não.
*   **Ưu điểm:** Dễ quản lý, logic nghiệp vụ tập trung tại một chỗ, tránh việc các Service con gọi chéo nhau lung tung.

## 2. State Machine (Máy trạng thái)
Để Orchestrator không bị rối khi xử lý hàng ngàn hồ sơ vay cùng lúc, chúng ta dùng **State Machine**.

*   **States (Trạng thái):** `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `COMPENSATED`.
*   **Transitions (Sự chuyển dịch):** Khi có một sự kiện mới (ví dụ: `KycPassed`), State Machine sẽ "nhảy" từ trạng thái này sang trạng thái khác dựa trên rules đã định nghĩa sẵn.
*   **Saga Event/Command:**
    *   **Event:** "Tôi đã xong bước X rồi" (do Service con báo về).
    *   **Command:** "Anh hãy làm bước Y đi" (do Orchestrator phát ra).

## 3. Quản lý ID (Correlation/Idempotency)
ID chính là "keo dính" trong Saga State Machine:

*   `sagaId` (Correlation ID): Dùng để State Machine biết hồ sơ nào đang ở bước nào (Kết nối mọi thứ).
*   `loanApplicationId` (Aggregate ID): Dùng làm Kafka Key để đảm bảo thứ tự xử lý trên Kafka.
*   `eventId/commandId` (Idempotency Key): Dùng để chống trùng lặp dữ liệu khi mạng bị lag (Máy trạng thái chỉ xử lý 1 tin nhắn 1 lần duy nhất).

## 4. Cơ chế Compensation (Bù đắp giao dịch)
Nếu một bước bị lỗi (ví dụ: Disbursement thất bại), State Machine sẽ kích hoạt luồng ngược lại (**Compensating Transaction**): 
*   "Bước Giải ngân bị lỗi!" -> "Gửi tin nhắn: Hủy hạn mức tín dụng!" (Cancel Credit).
*   Trạng thái Saga cuối cùng sẽ là `COMPENSATED` thay vì `FAILED` để bảo đảm tính nhất quán của dữ liệu.
