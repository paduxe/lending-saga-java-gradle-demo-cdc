# 26. High-Scale Messaging: Avro & Schema Registry

Để giải quyết vấn đề **băng thông mạng** và **tương thích ngược (Backward Compatibility)** khi hệ thống đạt ngưỡng hàng trăm ngàn giao dịch, chúng ta đã chuyển đổi toàn bộ cơ chế truyền tin từ JSON sang **Avro (Binary format)**.

## 1. Tại sao phải dùng Avro?
*   **Tiết kiệm 40-60% dung lượng:** Avro không gửi tên trường (Meta-data) đi kèm mỗi Message. Nó chỉ gửi dữ liệu nhị phân thô.
*   **Schema Registry:** Là một "Thư viện quản lý hợp đồng". Mọi service khi gửi/nhận tin đều phải đối chiếu với Schema Registry. Nếu bạn thêm một trường mới, Registry sẽ kiểm tra xem các service cũ có bị "chết" (Crash) không trước khi cho phép đăng ký Schema mới.

## 2. Các thay đổi đã thực hiện

### Hạ tầng (Infrastructure)
*   **Docker Compose:** Đã thêm service `schema-registry` (Confluent) chạy tại cổng `8081`.
*   **Debezium (Outbox):** Cấu hình `VALUE_CONVERTER` từ `JsonConverter` sang `AvroConverter`. Giờ đây, dữ liệu từ bảng Outbox DB sẽ được Debezium tự động nén thành Avro trước khi đẩy lên Kafka.

### Cấu hình ứng dụng (Application Config)
Toàn bộ Microservices đã được cấu hình:
```yaml
spring:
  kafka:
    properties:
      schema.registry.url: http://schema-registry:8081
    consumer:
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
    producer:
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
```

### Định nghĩa Schema
Các file Schema chuẩn (`.avsc`) đã được lưu tại thư mục `/schemas`:
*   `LoanEvent.avsc`: Cấu trúc sự kiện đơn vay.
*   `LoanCommand.avsc`: Cấu trúc lệnh điều phối (Command).

## 3. Lợi ích Go-Live
Hệ thống hiện tại không còn sợ tình trạng "Nghẽn mạng LAN" khi traffic tăng đột biến. Việc thay đổi cấu trúc dữ liệu giữa các phòng ban (ví dụ: KYC team thêm thông tin mới) sẽ được kiểm soát chặt chẽ qua Schema Registry, tránh lỗi sập dây chuyền (Runtime exceptions).
