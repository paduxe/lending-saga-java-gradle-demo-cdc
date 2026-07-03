# BÁO CÁO PHÂN TÍCH KIẾN TRÚC: HÀNH TRÌNH TỐI ƯU HÓA HỆ THỐNG LENDING SAGA (TỪ POC LÊN HIGH-SCALE PRODUCTION)

Tài liệu này đánh giá tổng quan về toàn bộ các thay đổi, giải pháp công nghệ và hệ tư tưởng thiết kế đã được áp dụng để biến một dự án PoC cơ bản thành một hệ thống phân tán chịu tải cao (High-Scale Distributed System), sẵn sàng phục vụ Production.

---

## PHẦN 1: TỔNG QUAN VÀ LẬP LUẬN VỀ TIÊU CHÍ ĐÁNH GIÁ (NFR)

### Có nên phân loại các tiêu chí nâng cấp theo NFR (Non-Functional Requirements) không? Và vì sao?

**Trả lời:** Hoàn toàn **CHÍNH XÁC** và là **BĂT BUỘC** trong góc nhìn của Kiến trúc sư phần mềm (Software Architecture). 
*   **Lý do cốt lõi:** Các tính năng nghiệp vụ (Functional Requirements - ví dụ: tạo khoản vay, kiểm tra CIC) chỉ quyết định hệ thống *Làm được cái gì*. Trong khi đó, **NFR (Phi chức năng)** lại quyết định hệ thống *Sống được bao lâu và Phục vụ được bao nhiêu người*. Một hệ thống có thể chạy hoàn hảo với 1 user, nhưng sẽ sụp đổ hoàn toàn ở 10.000 users nếu NFR không được thiết kế### Việc gom nhóm 30 Tiêu chí Tối ưu hóa vào 6 Cột trụ NFR:

Toàn bộ **30 giải pháp/công nghệ** đã triển khai được phân bổ ngặt nghèo vào 6 cột trụ NFR chống đỡ hệ thống:

#### 1. High Availability (Tính Sẵn Sàng Cao 99.99% - Chống SPOF)
*   **(1) Microservices Architecture:** Chia tách 5 services độc lập + 1 Orchestrator, đảm bảo 1 lỗi cục bộ không kéo sập toàn hệ thống.
*   **(5) Read Replicas (Loan + KYC PgPool HA):** Phân tán traffic đọc, phòng ngừa gián đoạn Database.
*   **(23) K8s DB Operator:** Dùng CloudNativePG quản trị Cluster và S3 backup tự động.
*   **(24) Strimzi Kafka Operator:** Đảm bảo Kafka persistent với ISR=2, chống mất mát node.
*   **(27) Redis HA:** Triển khai Sentinel 3 nodes + AOF bảo vệ Cache layer.

#### 2. Scalability & High Throughput (Khả năng Co giãn & Chịu tải)
*   **(2) Event-Driven Kafka:** Thiết lập 3 brokers (KRaft), 30 partitions song song và RF=3 vút max băng thông nội bộ.
*   **(4) Database Sharding:** Cấu hình 2 Shards với Hash routing cho core Loan DB để phá vỡ giới hạn dọc.
*   **(6) Write-Through Cache:** Triển khai DB-first, Redis invalidate giảm thiểu truy vấn trùng lặp.
*   **(12) Rate Limiting:** Trụ vững 10,000 req/s thông qua Kong Gateway và Resilience4j.
*   **(20) HPA Auto-scaling:** Thiết lập K8s tự động co giãn từ 3 → 30/50 Pods cho toàn bộ 5/5 services dựa trên CPU.

#### 3. Resilience & Fault Tolerance (Khả năng chịu lỗi và tự phục hồi)
*   **(11) Circuit Breaker:** Ngắt mạch vòng lặp vô hạn bằng Resilience4j (tránh hiệu ứng Domino).
*   **(13) Dual-Layer Idempotency:** Vận hành Redis SETNX chốt cọc nhanh + DB khóa sâu, đánh bật duplicate message.
*   **(15) Saga Timeout + ShedLock:** Đặt lịch quét vé nợ quá 3 ngày với distributed lock (ShedLock), chống Deadlock.
*   **(16) DLQ Persist + Recovery:** Message độc sẽ bị đẩy xuống entity riêng ở DB (Dead Letter Queue) chờ đối soát lại.
*   **(17) Graceful Shutdown:** Buộc cả 5 services chờ 30s xả cạn request trôi nổi K8s SIGTERM mới được tắt máy.
*   **(21) K8s Probes:** Ép 5/5 services phải báo cáo Liveness (Sống sót) + Readiness (Đã nạp bối cảnh) xong mới được chầu traffic.
*   **(22) Pod Anti-Affinity:** Không cho phép 2 bản sao của cùng 1 service ở chung nhà trọ vật lý (tránh rơi nguyên chùm mây).

#### 4. Data Consistency (Tính Nhất Quán Dữ Liệu Chéo)
*   **(3) CDC + Transactional Outbox:** Sử dụng Debezium móc thẳng WAL Postgres, giải quyết triệt để vấn đề "Lưu file thành công mà Gọi Kafka thất bại".
*   **(14) Saga State Machine + Compensation:** Vận hành quy trình Rollback Tự Động (ví dụ: giải ngân xịt -> tự rollback tiền KYC/Credit).

#### 5. Security & Validation (Bảo mật Tối đa Chuẩn PCI-DSS)
*   **(7) API Gateway:** Đóng cửa tất cả các cổng hậu, mọi thao tác phải đi qua Cửa khẩu Kong.
*   **(8) OAuth2/JWT Security:** Xác thực tập trung Keycloak chốt chặn tại 100% services.
*   **(9) Field Encryption:** Mã hóa tĩnh AES-256 với cơ chế Fail-fast đọc qua Biến môi trường hệ điều hành.
*   **(10) Input Validation:** Đảm bảo hệ thống tiệt trùng Data đầu vào với chuẩn Bean Validation.
*   **(28) K8s Secrets:** Cách ly nhạy cảm bằng configMapRef + secretRef thuần K8s.
*   **(30) Anti-Fraud Rules Engine:** Phủ lớp phòng thủ lừa đảo linh hoạt tầng nghiệp vụ thông qua EasyRules.

#### 6. Observability & Testing (Khả năng Quan sát Doanh nghiệp)
*   **(18) Prometheus + AlertManager + Grafana:** Combo mắt thần nhìn xuyên thấu phần cứng + báo động Telegram.
*   **(19) Custom Business Metrics:** Monitor lượng Saga thành công/thất bại, số lượt Fraud bắt được.
*   **(25) Distributed Tracing:** Gắn mã theo dõi OpenTelemetry đổ về Jaeger Trace 5/5 services.
*   **(26) Centralized Logging:** Kubernetes DaemonSet vớt log bằng Fluent Bit nhét vào dạ dày Elasticsearch + Kibana.
*   **(29) Test Coverage:** Tiến hành đặt móng Unit Test + Mockito, châm ngòi nổ cho Continuous Integration.
*   **Xác thực tập trung:** Cắm cờ `SecurityConfig` và JWT (OAuth2 Resource Server qua Keycloak) chặn khóa tất cả downstream services.

#### 6. Observability (Khả năng Quan sát Doanh nghiệp)
*   **Distributed Tracing:** Sử dụng `micrometer-tracing-bridge-otel` gửi Dữ liệu mạch máu ngầm xuyên suốt các Microservices cho trạm `Jaeger` thu thập.
*   **Centralized Logging:** Vứt bỏ khái niệm xem log bằng lệnh console thông thường, triển khai K8s `Fluent Bit` (DaemonSet) nhặt log đẩy về cho `Elasticsearch / Kibana` lưu trữ để tra cứu Troubleshoot dễ nhất.

---

## PHẦN 2: QUÁ TRÌNH TIẾN HÓA CỦA DỰ ÁN TỪ BASIC ĐẾN HIGH-SCALE DISTRIBUTED SYSTEM

Hệ thống đã trải nghiệm 4 Giai đoạn phát triển tương ứng với 4 "công nghệ điều trị" các căn bệnh điển hình của Microservices:

### Giai đoạn 1: Sơ khai Microservices (The Synchronous HTTP Mindset)
*   **Bối cảnh:** Chia nhỏ monolith thành các service (Loan, KYC, Credit...) và kết nối chúng bằng `RestTemplate` / `FeignClient`.
*   **Vấn đề:** 
    *   *Latency:* Chờ phản hồi quá lâu, block I/O Threads liên tục khiến Tomcat Crash.
    *   *Mất tính bền vững Dữ liệu (Consistency):* Tiền chuyển đi trong Credit Thành công nhưng HTTP Timeout gửi về Loan do rớt cáp -> Core Bank bị thâm hụt số dư.
*   **Giải pháp - Tech Stack thay máu:** 
    *   Cắt đứt HTTP. Sử dụng cơ chế Giao vận thông điệp bằng `Apache Kafka`. Áp dụng `Saga Orchestration`. Bộ máy Loan Orchestrator (Nhạc trưởng) trở thành thiết bị dẫn đường ra quyết định Next hoặc Rollback qua Kafka topic thay cho gọi đồng bộ.

### Giai đoạn 2: Cuộc chiến Tính Toàn Vẹn Tín Hiệu (The Dual-Write Problem)
*   **Bối cảnh:** Khi xài Kafka, code xuất hiện 2 dòng lệnh: Dòng 1 lưu DB, Dòng 2 Push Kafka `kafkaTemplate.send()`.
*   **Vấn đề:** Network lỗi lỡ nhịp ở khoảng trống giữa Dòng 1 và Dòng 2. Hậu quả là Database Business thay đổi, nhưng Message Broker lại Trắng trơn.
*   **Giải pháp - Tech Stack thay máu:** 
    *   Sử dụng **Outbox Pattern** kết nối trực tiếp vào Transaction Database.
    *   Cấy `Kafka Connect` + `Debezium Connector`, bấu thẳng vào `Logical Replication / WAL` của PostgreSQL. Đảm bảo mọi UPDATE/INSERT ở DB gốc đều truyền tĩnh lặng 100% về Kafka mà ứng dụng App Layer không bị đè nặng. Đạt trạng thái Tối thượng **Eventual Consistency**.

### Giai đoạn 3: Phá Băng Giới Hạn Tốc Độ (Breaking the Throughput Ceiling - 100k TPS)
*   **Bối cảnh:** Khi tăng tải (Load Test), Server bị ngộp màng lưới: CPU bốc hỏa vì xử lý JSON Serialize, DB Lock dính chùm vì tra cứu xử lý chống trùng lặp Transaction (Idempotency ExistsCheck). Các Search Free-text đè sập Postgres.
*   **Giải pháp - Tech Stack thay máu:**
    *   Tối ưu hóa Data Model: Đổi từ chuẩn JSON sang chuẩn binary siêu ngách **Avro** thông qua `Confluent Schema Registry`. Payload giảm siêu tốc, giảm tải CPU.
    *   Tốc độ RAM: Triển khai chiến thần `Redis SETNX` cho bài toán Idempotency (chống loop message mốc < 1ms) và `Saga Cache` (tránh full-table scan DB không cần thiết).
    *   Rẽ nhánh Traffic Đọc: Ứng dụng mẫu kiến trúc **CQRS (Command-Query Responsibility Segregation)**, đẩy dữ liệu Search dồn qua 1 bên `Elasticsearch Engine` riêng biệt, trả tự do cho DB Postgres chỉ tập trung cho việc Ghi. 

### Giai đoạn 4: Đóng Tàu Sắt Đạt Chuẩn Production (K8s Hardening & Resilience)
*   **Bối cảnh:** Các module chạy nhanh trên máy DEV (Docker Compose), nhưng nếu ném ra 1 DC thật với nguy cơ dập dây cáp, chập điện 1 Node vật lý K8s thì... System sập toàn diện. Vắng bóng các khả năng chống sốc và khả năng soi rổ mã nguồn.
*   **Giải pháp - Tech Stack thay máu:**
    *   *High Availability Infrastructure:* Thổi bay StatefulSet tự định nghĩa; ứng dụng Operators cho cấu trúc Master-Replica như `CloudNativePG`, `Strimzi Operator` và cấu trúc `Redis Sentinel`. Kích hoạt Pod Auto-scaler `HPA`.
    *   *Mạng Lưới Thép (Service Mesh):* Kích hoạt `Istio Control Plane` tạo hàng rào `mTLS Strict` cho liên kết mạng.
    *   *Cơ giáp Ứng dụng:* Bọc `Resilience4j` chống hiệu ứng Domino. Cấu hình Catch toàn bộ rác thả vào DB `Dead Letter Queue`. Bắt 0% Test Coverage quay về khuôn qua JUnit Mockito, xóa sập hoàn toàn hard-code bảo mật (`IllegalStateException`). Thiết lập `Fluent Bit` và Tracing `OTEL` nhét thẳng phễu Elasticsearch.

**KẾT LUẬN:** 
Với sự tiến hóa theo tầng và cấu trúc vững chắc được bổ sung qua 4 chu kỳ trên, hệ thống không chỉ đơn thuần là 1 cụm Code App nữa, mà nó đã trở thành 1 **Ecosystem (Hệ sinh thái)** hoàn chỉnh khép kín mang đúng tầm vóc Distributed Architecture của ngành ngân hàng, có sức bật khổng lồ (High Scale) để đẩy lên Public Cloud đối mặt với người dùng thực.
