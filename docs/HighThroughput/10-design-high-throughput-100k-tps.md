# Design a High-Throughput Event-Driven System (100k TPS)

## 1. Core Architecture (Saga + EDA)
*   **Mô hình:** Saga Orchestration kết hợp Event-Driven Architecture (EDA).
*   **Giải quyết:** Tách biệt Transaction Database và Data Transport (Kafka) để khắc phục Bottleneck của 2PC (Two-Phase Commit). Đảm bảo Eventual Consistency.

## 2. Chiến lược Scale-Out cho Kafka (Message Broker)
*   **Multiple Partitions:** Tăng số lượng Partition (ví dụ 1000 partitions). Đây là chìa khóa mở rộng "đường cao tốc" xử lý song song.
*   **Key-based Routing:** Duyên dùng `Aggregate ID` (VD: `loanApplicationId`) làm Kafka Key.
    *   *Mục đích:* Đảm bảo tính toán toàn vẹn và Thứ tự (Ordering). Mọi Events của cùng 1 Đơn vay sẽ rớt vào cùng 1 Partition, chặn đứng Race Condition.

## 3. Quản lý Consumer (Workers)
*   **Scale by Partition:** Nâng số lượng Consumer Instances bằng (hoặc nhỏ hơn) tỷ lệ 1-1 với Partitions.
*   **Kafka Batch Listener:** Bật tính năng nhận diện theo lô (`max.poll.records = 500`).
    *   *Mục đích:* Giảm tải I/O Database. Thực thi Bulk Insert (JDBC Batch Update) để không vắt kiệt Connection Pool.

## 4. Tối ưu Database & Bám sát CDC
*   **Database Sharding:** Băm ngang (Shard) CSDL Transaction theo `loanApplicationId` (Hash-Modulo). Tránh quá tải Disk IO và giới hạn WAL của 1 Node RDBMS duy nhất.
*   **Debezium per Shard:**
    *   *Concept:* Mỗi Shard là một RDBMS vật lý độc lập (có WAL riêng).
    *   *Action:* Thêm 1 Shard DB bắt buộc phải deploy thêm 1 Debezium Connector chĩa vào Host tương ứng.
    *   *Dynamic Routing (SMT):* Cấu hình Debezium SMT để hội tụ dữ liệu từ nhiều Shard về chung một Kafka Topic (`saga-events`) dựa vào cột `topic_name` từ bảng Outbox.

## 5. Nâng cấp cấp đọ Framework & Infra (Optional cho Senior/Principal)
*   **Reactive Stack (I/O Asynchronous):** Chuyển đổi từ Spring Web MVC (Blocking IO - Tomcat) sang Spring WebFlux + R2DBC (Non-blocking IO - Netty) để gánh 100k kết nối với lượng chỉ vài Thread CPU.
*   **Orchestrator State Machine Caching:** Ở 100k TPS, bỏ qua việc chọt thẳng RDBMS. Đẩy State (`PENDING`, `RUNNING`) lên In-Memory Grid (Redis Cluster). Sử dụng cơ chế *Write-behind* đồng bộ dọn rác xuống DB kho lạnh ngày kết thúc phiên làm việc.
*   **Idempotency & Poison Pill:** Thiết lập `Dead Letter Queue` (DLQ) ở tầng KafkaConfig để tống khứ các sự kiện lỗi vĩnh viễn (Poison Pill), tránh Consumer bị Infinite Loop/Crash.

---

## 6. Góc giải thích sâu (Deep Dive cho Phỏng vấn)

**A. Tương quan giữa Scale Partitions và Consumers:**
*   **Nguyên tắc 1 Partition - 1 Consumer:** Một Partition chỉ phục vụ một Thread/Consumer tại một thời điểm (trong cùng Group) để bảo đảm *Ordering*. Nhưng 1 luồng Consumer gánh được nhiều Partitions.
*   **Kịch bản Maximum Parallelism (Ví dụ hệ thống cấp 30 Partitions):**
    *   **Trường hợp 1 (Ít tiền - 1 Node x 3 luồng = 3 Consumers):** Mỗi luồng nhai *10 Partitions*. Vẫn chạy, chỉ là CPU gánh cực nặng.
    *   **Trường hợp 2 (Scale đúng hạn mức - 10 Nodes x 3 luồng = 30 Consumers):** Khả năng song song kịch trần. Mỗi luồng phụ trách chuẩn *1 Partition*.
    *   **Trường hợp 3 (Tiêu xài hoang phí - 15 Nodes x 3 luồng = 45 Consumers):** Số Consumers > Số Partitions. Sẽ có *15 Threads ngồi chơi (Idle)*. Hệ thống không nhanh lên tý nào, lượng dư thừa đóng vai trò dự bị (Hot Standby) phòng khi máy khác hỏng.
*   **Kết luận:** Nâng Partition (VD: 30, 100) từ đầu là dỡ bỏ **Giới hạn trần mở rộng**. Việc nâng số lượng Container/App không làm hệ thống nhanh hơn nếu đã đụng "trần" Partition.

**B. Cơ chế Scale và Batching phía Debezium (Producer):**
*   Hệ thống không code Java Publisher, thay vào đó Debezium là Producer "Chính".  
*   **Debezium mặc định xử lý Batch:** Không hề nhặt từng dòng WAL để bắn đi một cách cục súc.
*   **Tinh chỉnh "Độ to" của phễu:** Bổ sung thẳng tay các thông số (Bypass Kafka Properties) vào JSON Connector:
    *   `"max.batch.size": "10000"` (Nuốt 10k sự kiện mới gom lại).
    *   `"producer.override.linger.ms": "10"` (Đợi chẵn 10ms để gom cho đủ một chuyến đò).
*   **Tương quan Sharding DB:** Có 10 Cụm CSDL RDBMS bị chẻ (Shard) ngang, đồng nghĩa có 10 file WAL vật lý nằm tách rải rác trên Disk. Hệ quả: Bắt buộc cấu hình deploy **10 Debezium Connectors** trỏ độc quyền vào 10 DB đó. Nhờ cơ chế `Event Router SMT` ánh xạ cột, chúng đều phun Event hội tụ gọn gàng về các Topic trung tâm như `saga-events`.
