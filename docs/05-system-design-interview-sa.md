# 05. Bí kíp Phỏng vấn System Design - Vị trí Solution Architect (Domain Finance)

Dự án `lending-saga` mà bạn đang có là một **bảo bối** tuyệt vời để chứng minh năng lực thiết kế hệ thống phân tán. Dưới đây là các câu hỏi cực gắt thường gặp ở vòng System Design và cách bạn dùng dự án này để "hạ gục" nhà tuyển dụng.

---

## Câu hỏi 1: Giải quyết bài toán Dual-Write (Ghi kép)
**🗣 Câu hỏi:** *Trong một hệ thống Microservice, Service A cần lưu dữ liệu vào Database và bắn 1 event ra Kafka để Service B xử lý. Nếu lưu DB thành công nhưng lúc bắn ra Kafka thì mạng rớt, hoặc ngược lại Kafka nhận được nhưng DB rollback, bạn sẽ giải quyết sự cố mất đồng bộ này ra sao (mà không dùng 2PC)?*

**✅ Cách trả lời với dự án hiện tại:**
- **Giải pháp:** Sử dụng **Transactional Outbox Pattern kết hợp với CDC (Change Data Capture)**.
- **Minh họa trong Code:** 
  - Tại `loan-service`, thay vì gọi `kafkaTemplate.send()`, tôi tạo một bảng `outbox_event`. 
  - Khi tạo khoản vay mới, tôi `save()` thông tin khoản vay vào bảng `loan_application` và `save()` một event vào `outbox_event` **trong cùng một Database Transaction** (đảm bảo tính ACID nguyên thủy của RDBMS).
  - Sau đó, tôi dùng **Debezium** soi vào WAL (Write-Ahead Log) của Postgres để lắng nghe những event mới cắm vào bảng outbox, bóc tách payload và tự động đẩy lên Kafka một cách bất đồng bộ với cơ chế *at-least-once delivery*.
  - Cách này giúp decouple ứng dụng khỏi hạ tầng Message Broker, đảm bảo không bao giờ bị mất event.

---

## Câu hỏi 2: Bảo đảm toàn vẹn dữ liệu xuyên các Microservice
**🗣 Câu hỏi:** *Hệ thống tài chính yêu cầu dữ liệu phải chính xác tuyệt đối. Nếu quá trình vay tiền đi qua 3 bước: KYC -> Credit -> Disbursement. Lỡ bước Disbursement bị lỗi (ví dụ Core Banking từ chối), làm sao bạn undo lại việc cấp Credit đã thực hiện thành công ở bước trước?*

**✅ Cách trả lời với dự án hiện tại:**
- **Giải pháp:** Áp dụng **Saga Pattern** với cơ chế **Compensating Transactions (Giao dịch bù trừ)**.
- **Minh họa trong Code:**
  - Hệ thống áp dụng mô hình Orchestrator (`loan-orchestrator-service`) để quản lý trạng thái máy (State Machine).
  - Khi `disbursement-service` trả về event báo lỗi giải ngân, Orchestrator cập nhật biến trạng thái và thay vì dừng lại, nó sẽ sinh ra một Command là `CancelCreditCommand`.
  - Command này được đẩy vào Outbox và qua Kafka đến `credit-service` để thực hiện nghiệp vụ "Hủy hạn mức đã cấp".
  - Cuối cùng, trạng thái của `saga_status` chuyển thành `COMPENSATED` để đảm bảo hệ thống đạt được trạng thái **Eventual Consistency** (Nhất quán cuối).

---

## Câu hỏi 3: Lựa chọn Orchestration vs Choreography
**🗣 Câu hỏi:** *Tại sao bạn lại chọn kiến trúc Saga Orchestrator thay vì Choreography (Biên đạo) cho luồng duyệt vay này?*

**✅ Cách trả lời với dự án hiện tại:**
- **Sự khác biệt:** Choreography là để các service tự "bắn tín hiệu" cho nhau một cách phân tán, phù hợp với các quy trình ngắn (2-3 bước) và ít thay đổi. Orchestrator dùng một "Nhạc trưởng" trung tâm để điều phối.
- **Lý do chọn Orchestrator:** Quy trình vay tiền (Origination) là một quy trình *Long-running process* và cấu trúc phức tạp (Stateful workflows). 
  - Nếu dùng Choreography, logic nghiệp vụ sẽ nằm rải rác: KYC phải biết nó cần gọi Credit, Credit phải biết gọi Disbursement -> Sinh ra **Cyclic Dependencies** (Phụ thuộc vòng).
  - Với hệ thống hiện tại, `loan-orchestrator-service` kiểm soát hoàn toàn flow. Nếu sau này cần chèn thêm bước "Fraud Check" ở giữa, tôi chỉ cần sửa logic cấu hình ở State Machine của Orchestrator mà không cần chạm vào source code của KYC hay Credit. 

---

## Câu hỏi 4: Xử lý trùng lặp tin nhắn (Idempotent Consumer)
**🗣 Câu hỏi:** *Kafka chỉ đảm bảo "At-least-once", nghĩa là một tin nhắn có thể được Consumer đọc 2 lần do mạng lag hoặc rebalance. Bạn làm thế nào để tránh việc giải ngân 2 lần cho cùng một khách hàng?*

**✅ Cách trả lời với dự án hiện tại:**
- **Giải pháp:** Thiết kế **Idempotency (Tính lũy đẳng)** ở mọi Consumer.
- **Minh họa trong Code:** 
  - Mỗi Event hay Command (trong class `LoanEvent`, `LoanCommand`) đều đi kèm với một `eventId` hoặc `commandId` duy nhất kiểu UUID.
  - Tại Consumer (ví dụ bước Disbursement), trước khi xử lý nghiệp vụ giải ngân, hệ thống sẽ lưu cái `commandId` này vào một bảng tên là `processed_message` (hoặc kiểm tra bằng Redis/DB Unique Constraint).
  - Nếu ID đã tồn tại $\rightarrow$ Tin nhắn này là trùng lặp (Duplicate) $\rightarrow$ Hệ thống tự động bỏ qua (ACK tin nhắn) mà không thực thi lại side-effect.

---

## Câu hỏi 5: Khả năng Truy xuất & Giám sát (Observability)
**🗣 Câu hỏi:** *Có hàng ngàn khoản vay được duyệt mỗi lúc. Làm sao bạn theo dõi được vòng đời của 1 yêu cầu vay cụ thể khi nó chạy bất đồng bộ qua hàng tá Service bằng Kafka?*

**✅ Cách trả lời với dự án hiện tại:**
- **Giải pháp:** Áp dụng **Correlation ID** xuyên suốt các hệ thống.
- **Minh họa trong Code:**
  - Khi Request được tạo ở `loan-service`, tôi cố tình khai báo một mã `sagaId` (Correlation ID) và `loanApplicationId` (Aggregate/Entity ID).
  - `sagaId` này được truyền vào cấu trúc JSON của Outbox và đi qua tất cả các event: `KycChecked`, `CreditApproved`... Nó giúp gắn kết tất cả dòng Log ở mọi service lại với nhau.
  - Ngoài ra, bằng cách sử dụng config Transformer của Debezium (`event_id:header:eventId`), tôi đẩy metadata lên Header của Kafka để cho phép các công cụ tracing (như OpenTelemetry/Jaeger) vẽ biểu đồ luồng đi của tin nhắn mà không cần parse body.

---

## 🎯 Lời khuyên cho vòng phỏng vấn
- Hãy áp dụng phương pháp **STAR (Situation, Task, Action, Result)**. 
- Khi người ta hỏi một khái niệm, hãy nói: *"Ví dụ thực tế trong một hệ thống Microservices vay vốn bằng Java/Spring Boot và Kafka mà tôi từng thiết kế kiến trúc..."* và lấy ngay cái `lending-saga` này ra để làm ví dụ thực tế!
