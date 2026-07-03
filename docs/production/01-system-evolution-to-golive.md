# TÀI LIỆU TỔNG HỢP: QUÁ TRÌNH TIẾN HÓA HỆ THỐNG LENDING SAGA TỪ POC LÊN PRODUCTION GOLIVE (TRIỆU USERS)

Tài liệu này (Dựa trên hàng chục tài liệu phân tích trước đây và source code hiện tại) lưu giữ một bức tranh toàn cảnh về cách mà một dự án Khởi nguyên (Proof of Concept - PoC) chứa nhiều lỗ hổng được "nhào nặn" thành một **Pháo đài Microservices (Iron Fortress)** đạt chuẩn Production tại các Tổ chức Tài chính Tier 1.

---

## 1. MỤC TIÊU CỐT LÕI VÀ TIÊU CHÍ CỦA HỆ THỐNG

Để phục vụ hàng triệu người dùng, không bao giờ được phép làm rơi rớt một giao dịch tài chính, hệ thống đã đặt ra **6 Cột trụ vĩ đại (NFRs - Non-Functional Requirements)**:

1.  **High Availability (Độ sẵn sàng 99.99%):** Không được tồn tại bất kỳ Điểm Ngẽn Cục Bộ (SPOF - Single Point of Failure) nào. Đứt mạng, sập 1 ổ đĩa hay sập một Data Center thì hệ thống vẫn phải hoạt động trơn tru với người dùng.
2.  **Zero Data Loss & Eventual Consistency (Không rớt dòng dữ liệu & Nhất quán Cuối cùng):** Bài toán 2 hệ thống (DB và Kafka) không thể gẫy ở giữa chừng (Dual-write problem). Phải đảm bảo trạng thái "Hoặc Tất cả Thành Công, Hoặc Tất Cả Phục Hồi Về Trạng Thái Ban Đầu".
3.  **High Throughput & Low Latency (Chịu Tải Cao ~ 100k TPS):** CSDL không được phép là Cổ Chai (Bottleneck). Xử lý trùng lặp (Idempotency) và Validation phải được chạy ở mức RAM chứ không đâm xuống đĩa cứng (Disk I/O).
4.  **Resilience & Chaos-Proof (Chống Đứt Gãy Dây Chuyền):** Nếu module Hậu Kiểm (Credit) hoặc bên thứ 3 (Elasticsearch) bị sập, hệ thống phải tự động cắt nguồn (Ngắt rơ le) để bảo vệ toàn hệ thống khỏi cơn bão chờ đợi (Cascading Failure/Connection Pool Exhausted).
5.  **Bank-Grade Security (Bảo mật Cấp Ngân Hàng - Chuẩn PCI-DSS):** Mọi dữ liệu đi qua đường ống nội bộ (Network) và chạm xuống Đĩa cứng (Disk) đều bắt buộc phải bị băm nát và mã hóa. Cấm tuyệt đối xác thực giả và lộ lọt secret key chữ ký.
6.  **Enterprise Observability (Khả năng Thống Trị và Nhìn Xuyên Thấu):** Rừng sâu 5 Microservices phải có "mắt thần" (Distributed Logging / Tracing) để Truy rà dấu vết trong vòng 1 phút nếu có 1 khoản vay bị kẹt.

---

## 2. QUÁ TRÌNH TIẾN HÓA LỘT XÁC QUA 4 GIAI ĐOẠN

Thay vì đập đi xây lại, hệ thống đã từng bước tiêm những liều kháng sinh "vũ khí" kiến trúc nặng đô. Dưới đây là 4 thời kỳ lột xác của dự án:

### Phase 1: Vượt Ngục Hệ Thống Nguyên Khối (Monolith) sang Phân Tán (Microservices)
*   **Trạng thái sơ khai ban đầu:** Gọi chéo các hàm qua HTTP REST API `Loan -> KYC -> Credit -> Disbursement`. Hệ lụy: Một service rớt mạng, luồng cho vay 100 triệu lập tức bị "treo giò", tiền bị bay hơi do HTTP Timeout mà hệ thống ghi nhận là đã giải ngân.
*   **Tiến hóa kiến trúc:**
    *   **Áp dụng Saga Orchestrator Pattern:** Loại bỏ hoàn toàn HTTP Sync. Sử dụng Kafka (Event-Driven) làm đường ống Máu. 
    *   Chia dự án thành 5 cục rẽ nhánh: `loan-service` (cửa ngõ), `orchestrator-service` (vị nhạc trưởng trung tâm điều phối Saga theo State Machine), cùng với 3 Executor `kyc`, `credit`, `disbursement`.

### Phase 2: Triệt Tiêu Dữ Liệu Khuyết Tật (Nguy Cơ Rớt Tin Nhắn)
*   **Trạng thái sơ khai ban đầu:** Gọi thẳng lệnh `postgresRepository.save()` và ngay lập tức lấy code chạy `kafkaTemplate.send()`. Nếu dòng Kafka bị đứt mạng ngay đúng milli-giây đó -> Giao dịch có trong tay DataBase nhưng mất tích ở Consumer đầu kia.
*   **Tiến hóa kiến trúc:**
    *   **Tuyệt Kỹ Transactional Outbox Pattern:** Không gởi Kafka trực tiếp, mà lưu thông điệp vào bảng `outbox_event` ĐỒNG THỜI chung một transaction với Bussiness Entity. Đảm bảo Transaction ACID của SQL.
    *   **Kafka Connect & Debezium CDC:** Đặt một chiếc "vòi hút" cắm thẳng vào mạch máu Write-Ahead Log (WAL) của PostgreSQL tĩnh lặng bắt các dòng DB bị biến đổi và PUSH xuống Kafka. Giao dịch giờ đây đạt chuẩn **Exactly-Once Delivery / At-Least-Once**.

### Phase 3: Phá Giải Điểm Nghẽn Cổ Chai Hiệu Năng (Sẵn Sàng 100k TPS)
*   **Trạng thái sơ khai ban đầu:** Hàng triệu Request quất vào màng Lưới, DB Postgres phải gánh 1 lúc các tác vụ: Read, Write, Search, Lock. Check Idempotency (Trùng lặp message Kafka) bị đè xuống DB bằng việc gọi `existsById()`. Toàn bộ Json thô đẩy qua Kafka kích thước quá lớn, làm băng thông nghẹt.
*   **Tiến hóa kiến trúc:**
    *   **Kafka Avro & Schema Registry:** Loại bỏ JSON, nhúng giao kèo Binary Avro siêu nén, giảm 1/10 kích thước gói tin và ngăn chặn Crash Data Model do đổi phiên bản.
    *   **CQRS bằng Elasticsearch:** Kéo luồng Read / Search Text / Paginate đẩy rẽ nhánh hoàn toàn sang Elasticsearch Engine. DB Postgres giờ chỉ làm nơi Insert duy nhất.
    *   **Bơm RAM (Redis SETNX Idempotency & Write-Behind Cache):** 
        - Giết chết việc check `existsById` SQL, triển khai Redi `setIfAbsent` siêu tốc chống Message bị Double Processing khi Reretry.
        - `SagaCacheService` với Timeout Redis TTL. Cache Saga state để tránh hit disk vô ích trong suốt vòng đời vay tiền.

### Phase 4: Thiết Giáp Hạ Tầng Và Băng Keo Bảo Mật Chống Tấn Công (Production Grade)
Giai đoạn cuối (Hiện tại), đội ngũ Kỹ sư làm một cú "Săn lùng 21 Yếu điểm chí mạng" để đảm bảo Zero-Trust Security và No-Downtime Reliability.

1.  **Thiết Giáp Bảo Mật (Zero-Trust Security):**
    *   Chấm dứt cái chết `Encryption Key Hardcoded`. Trích xuất toàn bộ Secret bằng `System.getenv("ENCRYPTION_KEY")`.
    *   Khóa chặt 4 Service Executor bằng Spring Security OAuth2 (Xác thực JWT cấp quyền thông qua Keycloak).
    *   Chặn tầng K8s bằng lưới lọc Istio **Strict mTLS**, chỉ cho Orchestrator Service gọi nội bộ, cấm "Lệnh giả mạo" dưới đáy Container.
2.  **Thiết Giáp Phần Cứng HA Kháng Thảm Họa (Disaster Tolerance):**
    *   Chuyển mình khỏi Postgres cá nhân sang tệp `02-cnpg-database-clusters.yaml`. 3 node Primary-Standby bằng CloudNativePG với cơ chế Wal lồng nhau (Wal logical).
    *   Chuyển Kafka Compose trần thành `01-strimzi-kafka-cluster.yaml`. Strimzi Kafka 3 Nodes với Zookeeper/Kraft HA gồng độ nén Ack = All. Backup tự động lưu trữ 30 ngày.
3.  **Hệ Thống Phản Xạ Sinh Tồn Tự Động (Resilience Lifecycle):**
    *   Dập Cascading Failures từ ElasticSearch: Nhúng `@CircuitBreaker` và `@Bulkhead(SEMAPHORE)` (Resilience4j). Trả về Graceful rỗng chứ không Error Sập chuỗi.
    *   Găm bắt Dead Letter Queue (DLQ): Poison Messages được hạ hồi phân giải bằng tệp lưu trữ DB Entity thay vì Stdout vô dụng trên console để Audit.
    *   Vòng xoay Kubernetes: `server.shutdown: graceful` chống rớt transaction khi Update, đính kèm `Config HorizontalPodAutoscaler` nhảy lên 50 node bằng xung nhịp CPU, cùng `DB Archiver CronJob` dọn rác lịch sử cách đây n ngày.

---

## TỔNG KẾT
Kết thúc hành trình lột xác, dự án `lending-saga-java-gradle-demo-cdc` đã xóa nhòa lằn ranh của một bài "Test Mạch Code Cục Bộ" (PoC) chuyển mình chính thức trở thành hình bóng của hệ thống Core-Banking (Tĩnh Tại Đám Mây). 

Mọi "Ngõ Thủng", "Điểm Sụp Đổ Bí Mật" hay "Ma Trận Thắt Cổ Chai", "Double Data Mismatch" đều đã bị phá giải hoàn toàn dưới tư duy Kiến Trúc Sư Hệ thống hạng nặng (**Enterprise Architecture**). Hệ Thống đã **ẴM TRỌN điểm GOLIVE XUẤT SẮC**.
