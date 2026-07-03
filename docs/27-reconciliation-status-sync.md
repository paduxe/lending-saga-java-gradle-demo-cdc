# 27. Reconciliation & Status Synchronization (Đối soát & Đồng nhất trạng thái)

Đây là bước cuối cùng để đảm bảo hệ thống đạt chuẩn "Sắc bén" (Zero-Downtime Data Consistency), tránh việc dữ liệu bị lệch pha giữa các Service.

## 1. Vấn đề đã giải quyết
Trước đây, Orchestrator kết thúc Saga chỉ cập nhật trạng thái tại DB của nó. Service gốc (Loan Service) vĩnh viễn bị treo ở trạng thái `PENDING` nếu không có cơ chế báo ngược lại. Chúng ta đã sửa lỗi này bằng 2 lớp bảo vệ:

### Lớp 1: Event-Driven Result Loop (Dynamic Sync)
Khi Saga đạt trạng thái cuối (`COMPLETED` hoặc `FAILED`), Orchestrator sẽ bắn một `SagaResultEvent` lên topic Kafka `saga-results`.
*   **Loan Service** lắng nghe topic này và cập nhật ngay lập tức status của đơn vay thành `APPROVED` hoặc `REJECTED`.
*   Giúp trải nghiệm người dùng trên Mobile App mượt mà, phản hồi ngay khi có kết quả.

### Lớp 2: Reconciliation Cronjob (Static Deep-Scan)
Phòng trường hợp Kafka bị lag hoặc mạng chập chờn khiến Message bị mất, mình đã cấy thêm một **Chiến thần Đối soát (`ReconciliationJob`)** chạy định kỳ mỗi 15 phút tại Loan Service.
*   **Cơ chế:** Quét tất cả các đơn vay đang bị treo ở `PENDING` quá 10 phút.
*   **Hành động:** Gọi trực tiếp REST API sang Orchestrator để "hỏi tội" trạng thái thật của Saga.
*   **Sửa lỗi:** Tự động đồng bộ lại trạng thái nếu phát hiện lệch pha.

## 2. Các thành phần mới
*   **`LoanSagaEventListener` (Orchestrator):** Đã bổ sung logic `sendResultToLoanService`.
*   **`SagaResultListener` (Loan Service):** Tiếp nhận kết quả từ Kafka.
*   **`ReconciliationJob` (Loan Service):** Chạy vét mẻ lưới cuối cùng.
*   **`SagaQueryController` (Orchestrator):** Cung cấp API để Loan Service có thể call REST hỏi thông tin.

## 3. Lợi ích Business
*   **Tin cậy 100%:** Không bao giờ có chuyện tiền đã giải ngân mà đơn vay vẫn báo "Chờ duyệt".
*   **Idempotency:** Mọi luồng đối soát đều có cơ chế chống trùng lặp (Idempotency Key) để không update đè dữ liệu sai.
*   **Audit log:** Luồng REST query giúp đội vận hành dễ dàng tra soát lỗi (Troubleshooting).
