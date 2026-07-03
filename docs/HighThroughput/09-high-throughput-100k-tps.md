# 09. Thiết kế Hệ thống High Throughput (100k TPS)

Tài liệu này đánh giá và đưa ra các đề xuất nâng cấp kiến trúc để hệ thống Lending Saga có thể chịu tải siêu cao lên đến 100,000 Transactions Per Second (TPS).

---

## Đánh giá các đề xuất quy mô (Scale-out) của bạn

Các ý tưởng bạn đưa ra hoàn toàn **chính xác và là nền tảng bắt buộc** trong thiết kế hệ thống phân tán siêu lớn. Dưới đây là phân tích chi tiết:

### 1. KAFKA: Tăng Partitions & Key-based Routing
*   **Chính xác:** Với 100k TPS, 1 broker hoặc 3 partition là quá nhỏ. Dữ liệu sẽ dội qua hàng chục Brokers. Cần tăng hàng ngàn Partitions.
*   **Vì sao `loanApplicationId` làm Key là sự lựa chọn hoàn hảo?** 
    Saga yêu cầu tính thứ tự tuyệt đối cho cùng 1 bộ hồ sơ. Khi dùng `loanApplicationId` làm Kafka Key (Key-based routing), mọi sự kiện của hồ sơ anh A (như KYC xong chưa, Credit duyệt chưa) sẽ **luôn luôn rớt vào đúng 1 Partition nhất định**. Nhờ đó, tính thứ tự (Ordering) được bảo đảm 100%, chống lại tình trạng Event bị "đá văng" thứ tự do Race Condition.

### 2. CONSUMER: Scale by Partition
*   **Chính xác:** Quy tắc Vàng: Số lượng Consumer Instance lớn nhất = Số lượng Partitions. 
*   **Nâng cấp thêm (Gợi ý):** Bên cạnh việc Scale Out ứng dụng, hãy áp dụng **Kafka Batch Processing**. Thay vì Consumer lấy từng message một và ghi DB, hãy cấu hình `max.poll.records=500`. App nhận 500 records cùng lúc, gọi hàm `Bulk Insert/Update` lưu xuống Database trong 1 lần kết nối. Nó sẽ cứu sống Database của bạn khỏi hiện tượng Connection Pool Exhaustion.

### 3. DATABASE: Sharding theo `loanApplicationId`
*   **Chính xác:** 100k TPS sẽ khiến tốc độ sinh WAL (Write-Ahead Log) của 1 instance Postgres "nổ tung", gây nghẽn cổ chai (Write Bottleneck). Chia ngang dữ liệu (Sharding) là bắt buộc.
*   **Vì sao dùng `loanApplicationId` (Shard Key)?** Để đảm bảo toàn bộ chi tiết Đơn vay, Bảng Outbox, và Trạng thái Saga... của cùng 1 đơn vay luôn nằm trên cùng 1 con Server Database (Tránh phân mảnh truy vấn chéo mạng - Cross-shard Join).
*   **Nâng cấp thêm (Gợi ý):** Nếu Postgres truyền thống phải tốn công Shard thủ công bằng code (như Sharding-JDBC), hãy cân nhắc **NewSQL** (như TiDB, CockroachDB hay YugabyteDB). Chúng có Postgres API nhưng được chia Shard ngầm định bên dưới, khả năng scale write là vô hạn.

### 4. CDC: Debezium per Shard
*   **Chính xác:** Mỗi Node Postgres vật lý sẽ có một file WAL riêng. Do đó, nếu bạn cắt làm 10 Shard, bạn bắt buộc phải cắm 10 connector Debezium để bắt sự kiện từ 10 nơi khác nhau đẩy lên Kafka cluster.
*   **Nâng cấp thêm (Lưu ý mạng):** Debezium đọc replication slot. Ở mốc 100k TPS, lượng JSON sinh ra từ Debezium là khổng lồ. Cần chú ý vặn cấu hình `max.batch.size` của Debezium lên cao để nó gộp dữ liệu đẩy một lần, và cấp đủ băng thông mạng giữa Debezium Node -> Kafka Cluster để tránh rớt gói mạng.

---

## 🚀 ĐỀ XUẤT "VŨ KHÍ" NÂNG CAO CHO 100k TPS

Để thực sự "sống qua ngày" với con số 100k TPS, bạn cần mang thêm những công nghệ này vào phòng phỏng vấn:

### 1. I/O Bất đồng bộ toàn diện (Non-blocking / Reactive)
Với 100k TPS, nếu dùng Java Spring Boot truyền thống (`Tomcat` + `Spring Data JPA` + `JDBC`), các Threads (luồng) nghỉ chờ mạng/chờ DB (Blocking) sẽ khiến CPU nhảy vọt và báo lỗi `Out Of Memory` lập tức.
*   **Giải pháp:** Đổi toàn bộ Stack sang kiến trúc **Reactive** (chẳng hạn dùng `Spring WebFlux` + `R2DBC` thay vì JDBC + Drivers Async Kafka). Kiến trúc Event-loop giống NodeJS sẽ giúp 1 instance Java xử lý hàng ngàn TPS chỉ với vài Thread.

### 2. Bypass "Transactional Outbox" bằng "Event Sourcing"
Ở quy mô cực đại, việc vừa ghi dữ liệu Business lại vừa ghi dữ liệu vào bảng `outbox_event` khiến dung lượng Storage tăng gấp đôi nhanh chóng, và Debezium phản hồi có thể bị Lag (độ trễ replication).
*   **Giải pháp nâng cao:** Bỏ hẳn thao tác ghi vào bảng Outbox. Hãy đẩy trực tiếp `KafkaTemplate.send()` từ RAM. 
*   **Lý thuyết Event Sourcing:** Kafka chính là "Source of Truth" (Nguồn chân lý). Ứng dụng chỉ có trách nhiệm validate Request và vứt Event thẳng vào Kafka. DB RDBMS lúc này chỉ có vai trò là "Read Model" (Nghe Kafka để cập nhật lấy dữ liệu hiển thị API). Điều này yêu cầu Kafka phải được cấu hình `acks=all` cực kỳ chịu lỗi.

### 3. Orchestrator State Cache (In-Memory Grid)
Bảng `loan_saga` với 100k TPS update trạng thái liên tục (`PENDING` -> `KYC_PASS` -> `CREDIT_PASS`) sẽ cày nát Database relational.
*   **Giải pháp:** Lưu State Machine vào RAM thông qua **Redis Cluster** (hoặc Hazelcast/Ignite). Redis có thể easily ôm trọn 100k-500k TPS do thuần In-Memory. 
*   Mỗi khi Saga đổi trạng thái, ta thao tác lên Redis. Cuối ngày hoặc bất đồng bộ, sử dụng cơ chế Write-Behind để lưu các Saga trạng thái `COMPLETED` xuống Postgres RDBMS làm Data Warehouse lưu trữ lạnh.
