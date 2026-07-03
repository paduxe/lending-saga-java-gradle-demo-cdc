# Cấu hình Debezium và Nguyên lý hoạt động

Tài liệu này giải thích lý do tại sao Debezium là trái tim của kiến trúc Microservices hiện đại, hoạt động dựa trên CDC (Change Data Capture).

## 1. Nguyên lý Transactional Outbox Pattern
*   **Vấn đề:** Nếu một Service vừa ghi dữ liệu vào bảng chính (Business Table), vừa gửi tin nhắn trực tiếp qua Kafka (`kafkaTemplate.send`), hệ thống có thể mất tin nhắn khi App bị crash giữa 2 bước này (Lỗi phân tán).
*   **Giải pháp:**
    1.  Service ghi dữ liệu nghiệp vụ **VÀ** ghi một bản ghi Sự kiện vào bảng `outbox_event` TRONG CÙNG MỘT TRANSACTION của Database.
    2.  Debezium "soi" (monitor) dòng dữ liệu mới trong bảng `outbox_event` từ file nhật ký hệ thống (Write Ahead Log - WAL) của Database.
    3.  Debezium tự động biến dòng dữ liệu đó thành tin nhắn (Message) và đẩy lên Kafka.
    4.  **Kết quả:** Đảm bảo "Chắc chắn ghi thành công DB mới đẩy Kafka".

## 2. Cấu hình Debezium Connector (JSON Magic)
Các cấu hình "bí thuật" giúp Debezium xử lý thông minh:

*   `table.include.list`: Chỉ soi bảng `outbox_event`. Lờ đi các bảng nghiệp vụ khác để tránh rò rỉ thông tin không cần thiết.
*   `transforms.outbox.route.by.field`: "topic_name". Đây là bộ định tuyến. Giá trị trong cột `topic_name` của bảng DB là gì, thì tin nhắn sẽ được gửi vào Topic đó trong Kafka.
*   `transforms.outbox.table.field.event.payload`: "payload". Chỉ lấy nội dung cột `payload` để làm thân bài của tin nhắn Kafka. Điều này giúp gỡ bỏ các lớp vỏ dư thừa của Debezium.
*   `transforms.outbox.table.fields.additional.placement`: "event_id:header:eventId". Bốc dữ liệu từ cột và dán làm Header của Kafka Message để dễ Theo dõi (Tracing).

## 3. Bí ẩn Định tuyến: Làm sao Debezium biết đẩy tin nhắn vào Topic nào khi không còn Publisher?

Điều tuyệt vời nhất của Transactional Outbox + Debezium là Java Backend không còn gọi `kafkaTemplate.send(...)`, nhưng tin nhắn vẫn "rẽ" đúng đường. Điều này có được nhờ cơ chế **Event Router SMT của Debezium**.

**Giai đoạn 1: Tại tầng Application (Java)**
Trong code Java, Lập trình viên đóng gói tên Topic (Target Topic) thành một cột dữ liệu trong Database. (Xem `LoanApplicationService.java`):

```java
outboxRepository.save(OutboxEventEntity.builder()
        .eventId(event.eventId())
        .topicName("saga-events") // 👈 Ấn định tên Topic vào một cột của Database
        .payload(objectMapper.writeValueAsString(event))
        .build());
```

**Giai đoạn 2: Tại tầng Middleware (Debezium Connector)**
Trong cấu hình `loan-outbox-connector.json`, chúng ta lồng đoạn mã SMT (Single Message Transform) sau:

```json
"transforms": "route",
"transforms.route.type": "io.debezium.transforms.outbox.EventRouter",     
"transforms.route.route.by.field": "topic_name",                            
"transforms.route.route.topic.replacement": "${routedByValue}"
```
Sự kết hợp này ra lệnh cho Debezium: *"Chặn dòng cập nhật lại, soi vào cột `topic_name` (có giá trị là saga-events). Lấy chính giá trị này làm tên của Kafka Topic đích"*.

**✨ Sức mạnh của kiến trúc này:** Logic định tuyến (Routing Logic) đã chuyển dịch hoàn toàn từ mức Vận chuyển (Network level) xuống mức Dữ liệu (Data level). Developer muốn xây tính năng mới đẩy vào Topic mới? Chỉ cần đổi tham số `.topicName("topic-moi")` trong code hàm Save của Java, hoàn toàn KHÔNG CẦN đụng vào bất kỳ cấu hình rườm rà nào của tệp Connector!

## 4. Tại sao CDC (Log-based) tốt hơn Polling (Query-based)?
*   **CDC (Debezium):** Đọc file nhật ký của DB. Nhìn thấy **TẤT CẢ** các thay đổi, kể cả lệnh Xóa (DELETE) và các cập nhật diễn ra siêu nhanh (miliseconds).
*   **Polling (Quét bảng):** Truy vấn DB định kỳ. Dễ gây tải cho DB, có thể bỏ sót những bản ghi bị cập nhật nhanh giữa 2 lần quét và không thấy được các bản ghi đã bị Xóa.

## 5. Trách nhiệm của Developer (Java Code)
Khi dùng Debezium, bạn không cần quan tâm đến Kafka khi viết logic nghiệp vụ. Bạn chỉ cần:
1.  Thiết kế bảng `outbox_event`.
2.  Thực hiện `outboxRepository.save(event)` ngay trong Transaction của nghiệp vụ.

Hết! Mọi việc còn lại Debezium sẽ lo.
