# 06. Bí kíp Phỏng vấn System Design - Non-Functional Requirements (NFRs)

Bên cạnh các kiến trúc cốt lõi, vị trí Senior Architect hay Solution Architect sẽ luôn bị xoáy sâu vào các câu hỏi về **Non-Functional Requirements (NFR)** - các yêu cầu phi chức năng (Skalability, Reliability, Performance, Fault Tolerance...). 

Dự án `lending-saga` của bạn tiếp tục là một minh chứng hoàn hảo để trả lời các thủ thuật này.

---

## Câu hỏi 1: Khả năng mở rộng (Scalability & High Throughput)
**🗣 Câu hỏi:** *Vào các dịp Flash Sale hoặc các chiến dịch vay tiêu dùng lớn, lượng hồ sơ tăng đột biến gấp 100 lần. Bạn làm sao để hệ thống Backend chịu được tải mà không chết Database?*

**✅ Đáp án từ Project:**
- **Kafka Partitions:** Thay vì tăng cấu hình máy (Vertical Scaling), tôi mở rộng theo chiều ngang (Horizontal Scaling) bằng cách tăng số lượng Partitions của các topic `saga-events` và `saga-commands` (mặc định đang cấu hình là 3 partitions trong `KafkaConfig.java`).
- **Consumer Groups:** Tôi sẽ khởi chạy nhiều instance của `kyc-service` hoặc `orchestrator-service` (cùng chung thuộc tính `group.id`). Tải (load) sẽ tự động chia đều (Load Balancing) cho các consumer song song xử lý sự kiện. Giới hạn độ song song chính bằng kích thước lượng Partition.
- **Offload RDBMS:** Rất quan trọng! Tôi đã tách hoàn toàn quá trình "Quét DB" để gửi sự kiện (Polling) ra khỏi main thread của Java Application. Thay vào đó, **Debezium** soi trực tiếp trên Write-Ahead Log (WAL) của PostgreSQL. Việc này giúp giảm tải triệt để I/O cho Database, Java App chỉ cần làm 1 việc duy nhất là nhận Request -> Lưu Transaction.

---

## Câu hỏi 2: Khả năng phục hồi và Chịu lỗi (Fault Tolerance & Disaster Recovery)
**🗣 Câu hỏi:** *Nếu Connector Debezium (hoặc mạng giữa DB và Kafka) bị sập/chết trong 5 tiếng đồng hồ. Khi bật lại, hệ thống có bị sót dữ liệu giao dịch của khách hàng không?*

**✅ Đáp án từ Project:**
- **Tuyệt đối không sót (No Data Loss):** 
  - Trong 5 tiếng bị sập, hệ thống Java Backend (`loan-service`) vẫn nhận yêu cầu và ghi bản ghi nghiệp vụ kèm bản ghi thư (event) vào bảng `outbox_event` thành công nhờ RDBMS local transaction.
  - Debezium duy trì trạng thái "đã đọc đến đâu" (Offset/LSN) vào một topic ẩn của Kafka (hoặc file cục bộ). 
  - Khi Debezium sống dậy, nó sẽ tra cứu lại Offset này, "nối" lại dòng đọc tiếp theo ngay tại vị trí trước khi chết trên WAL của DB. Hệ thống tiếp tục bơm lượng event ùn ứ (Backpressure) lên Kafka. Kết quả là xử lý bị "Trễ" (Delayed) chứ tuyệt đối không "Mất".

---

## Câu hỏi 3: Xử lý Race Condition và Tính nhất quán (Concurrency & Consistency)
**🗣 Câu hỏi:** *Môi trường phân tán bất đồng bộ có một rủi ro: Hai sự kiện (Ví dụ từ KYC và từ Credit) phản hồi về Orchestrator cho cùng một Đơn vay (cùng `sagaId`) vào đúng 1 phần nghìn giây. Làm sao bạn tránh việc hai Thread ghi đè làm hỏng trạng thái (State) của Đơn vay?*

**✅ Đáp án từ Project:**
- **Giải pháp: Khóa lạc quan (Optimistic Locking).**
- Trong mã nguồn lớp `LoanSagaEntity.java`, tôi đã thiết kế trường `@Version private Long version;` dùng cơ chế chuẩn của Hibernate/JPA.
- Khi Thread A và B cùng đọc lên bản ghi `LoanSagaEntity` ở đang ở `version=0`. Nếu Thread A kịp cập nhật `currentStep` và lưu xuống Database, version dưới Database tự động hích lên `version=1`. 
- Gần như ngay lập tức Thread B đè cập nhật xuống, nó sẽ đối chiếu version và thấy trạng thái DB đã là `1` (khác với số `0` lúc nó đọc giữ trên tay). Transaction của Thread B sẽ văng lỗi `OptimisticLockException` và chặn đứng hành vi làm hỏng State Machine. Tin nhắn này sau đó sẽ phải trải qua quá trình Retry (Tải lại State mới nhất) để giải quyết xung đột.

---

## Câu hỏi 4: Quản lý tin nhắn Độc hại (Poison Pill)
**🗣 Câu hỏi:** *Giả sử có 1 dòng tin nhắn trong Topic Kafka bị hỏng định dạng JSON (Hoặc thiếu Key quan trọng). Cứ mỗi lần Consumer đọc tới tin nhắn này là văng Exception (Crash). Nếu không xử lý, Consumer của bạn sẽ rớt vào vòng lặp (Infinite Loop) chết chóc, "tắc đường" nghẽn mọi giao dịch phía sau. Xử lý kịch bản này thế nào?*

**✅ Đáp án từ Project:**
- **Giải pháp: Cơ chế Hàng đợi dạt (Dead Letter Queue - DLQ).**
- Trong cấu hình `KafkaConfig.java` của Orchestrator, tôi lập trình sẵn cơ chế `DeadLetterPublishingRecoverer` và `DefaultErrorHandler`.
- **Hoạt động:** Khi `LoanSagaEventListener` gặp 1 Exception không thể phục hồi (hoặc Retry quá 3 lần với `FixedBackOff`), hệ thống sẽ bỏ qua tin nhắn "độc hại" (Poison Pill) này và "cách ly" nó vào một Topic tách biệt tên là `saga-dlq`. 
- Partition chính sẽ ngay lập tức được giải phóng để phục vụ các yêu cầu vay mượn phía sau không bị nghẽn (Un-blocking). Đội ngũ vận hành (Ops) sẽ bật công cụ xem xét, sửa chữa Payload từ Topic DLQ và Re-play lại sau.

---

## Câu hỏi 5: Ngăn chặn treo giao dịch (Distributed Scheduling & SLA Timeout)
**🗣 Câu hỏi:** *Một luồng Saga nếu dịch vụ KYC bị "chết bấp bênh", đơn vay sẽ bị kẹt vĩnh viễn ở trạng thái `RUNNING`. Làm sao thiết kế để hủy các giao dịch bị "Treo" này? Nếu bạn chạy 5 instances Orchestrator, làm sao để cả 5 thằng không cùng tranh nhau quét Database hủy đơn vay?*

**✅ Đáp án từ Project:**
- Ở cấp độ nghiệp vụ, trong bảng `LoanSagaEntity` tôi lưu sẵn cột `deadlineAt` (Thời gian tối đa để duyệt xong giao dịch).
- Tôi có class `SagaTimeoutScheduler` với các hàm `@Scheduled` quét Database (Ví dụ 1 phút 1 lần) để truy tìm các hồ sơ có trạng thái `RUNNING` nhưng thời gian hiện tại đã mốc `deadlineAt`, từ đó chủ động đánh rớt về `FAILED/COMPENSATED`.
- **Để chống đụng độ giữa các instances (Distributed Lock):** Hệ thống tích hợp thư viện **ShedLock**. Nó sử dụng hệ quản trị chung (Redis hoặc 1 bảng Lock trên Database). 
- Bất kể có bao nhiêu bản sao Orchestrator đang chạy, mọi phút chỉ có **duy nhất 1 instance** lấy được "Ổ khóa" để quét DB, dọn dẹp các khoản vay hết hạn. Các instance còn lại sẽ ngoan ngoãn "ngồi đợi", ngăn chặn tình trạng thắt cổ chai và xử lý trùng lặp.
