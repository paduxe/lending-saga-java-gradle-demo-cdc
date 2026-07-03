# Sửa lỗi và Cấu hình để chạy thành công Happy Case

Tài liệu này tổng hợp các lỗi quan trọng đã được khắc phục để hệ thống Saga Orchestration chạy thông suốt.

## 1. Lỗi Docker và Hạ tầng (Docker Compose)
*   **Vấn đề:** Các Image Kafka và Debezium phiên bản mới nhất (`latest`) gặp lỗi không tương thích hoặc thiếu plugin.
*   **Khắc phục:** Cố định phiên bản ổn định trong `docker-compose.yml`:
    *   Kafka: `bitnamilegacy/kafka:3.7.1`
    *   Debezium: `debezium/connect:2.7.1.Final`
*   **Lưu ý:** Đảm bảo `POSTGRES_USER` và tên Database khớp với cấu hình `application.yml` của từng Service.

## 2. Lỗi Deserialization (Kafka Message)
*   **Vấn đề:** Debezium bọc nội dung Sự kiện vào một cấu trúc JSON phức tạp (có `schema` và `payload`). Trình `JsonDeserializer` mặc định của Spring Kafka không thể tự bóc tách lớp vỏ này, dẫn đến các trường như `sagaId`, `eventId` bị `null`.
*   **Khắc phục:**
    *   Chuyển cấu hình `value-deserializer` sang `StringDeserializer` trong `KafkaConfig.java`.
    *   Sử dụng `ObjectMapper` để nhận tin nhắn dưới dạng `String` thô, sau đó thực hiện **"Unwrap"** (bóc vỏ) lấy trường `payload`.

## 3. Lỗi Mapping dữ liệu (CamelCase vs SnakeCase)
*   **Vấn đề:** Database Postgres lưu tên cột dạng `snake_case` (ví dụ: `saga_id`), trong khi Java dùng `camelCase` (`sagaId`). Debezium mặc định giữ nguyên tên cột khi đẩy lên Kafka.
*   **Khắc phục:**
    *   Sử dụng `@JsonAlias` trong các Java Record (`LoanEvent`, `LoanCommand`) để Jackson có thể nhận diện cả 2 kiểu đặt tên.
    *   Ví dụ: `@JsonAlias("saga_id") String sagaId`.

## 4. Tối ưu kiến trúc (Xóa code dư thừa)
*   **Vấn đề:** Có các file `OutboxPublisher.java` (làm nhiệm vụ polling DB thủ công) gây xung đột với Debezium CDC.
*   **Khắc phục:**
    *   Xóa toàn bộ các `OutboxPublisher.java`.
    *   Gỡ bỏ các `ProducerFactory` và `KafkaTemplate` không cần thiết trong `KafkaConfig.java` (chỉ giữ lại DLQ của Orchestrator).
    *   **Nguyên tắc:** App chỉ cần `save()` vào bảng Outbox, việc gửi tin nhắn là của Debezium.
