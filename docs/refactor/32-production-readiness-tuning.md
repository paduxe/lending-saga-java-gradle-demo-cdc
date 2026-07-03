# Tổng hợp Toàn tập: Tinh chỉnh Kiến trúc Phân tán Hệ thống Cho con Mượn (Lending System) Đạt chuẩn Go-Live Production (Triệu Users)

Tài liệu này tổng hợp toàn bộ các câu hỏi, cuộc thảo luận và giải pháp kiến trúc đã được triển khai nhằm nâng cấp dự án `lending-saga-java-gradle-demo-cdc` từ mức "Proof-of-Concept" (Chạy thử nghiệm nội bộ) lên chuẩn **Production-Ready** (Sẵn sàng phục vụ hàng triệu người dùng, HA 99.99%).

## MỤC TIÊU CỐT LÕI
Đáp ứng 3 tiêu chí của một "Đấu trường thật":
1. **High Availability (HA):** Cả CSDL, Kafka và Code Application đều không có điểm nghẽn đơn lẻ (No Single Point of Failure - SPOF).
2. **Security & Zero Trust:** Tuân thủ PCI-DSS trong vấn đề lộ lọt chuỗi bí mật định danh, mã hóa đường truyền gắt gao giữa toàn bộ các service con.
3. **Resilience & Idempotency:** Hệ thống có khả năng tự phục hồi, chống nghẽn mạch cục bộ (Cascading Failures) trôi dạt vào Service lân cận và khả năng tự chống trùng lặp Message (Kafka).

---

## 21 HẠN CHẾ ĐƯỢC PHÁT HIỆN VÀ GIẢI PHÁP TRIỂN KHAI THỰC TẾ

### 1. Vấn Đề Lưu trữ / Database (Hàng Cổ / Thắt Cổ Chai DB)

*   **Hạn chế:** Chạy Postgres "cởi truồng" 1 node duy nhất, không có HA. 
*   **Giải pháp - Cụm CSDL HA CloudNativePG:** Chúng ta không sử dụng StatefulSet Postgres tự code, thay vào đó tạo các Tệp tin Manifest CloudNativePG (`02-cnpg-database-clusters.yaml`). 
    *   Setup 3-nodes Master-Replica tự động đổi Role (Failover).
    *   Tăng thông số `max_connections: 1000` chống nghẹt connection hồ hơi.
    *   Bật cấu hình Tự động sao lưu Retention 30 ngày.

*   **Hạn chế:** Bảng Outbox Event (`outbox_events`) lớn vô hạn, làm Postgres sưng phù. Sắp có 1 triệu Users = 100 Triệu Rows Event => Bể đĩa cứng.
*   **Giải pháp - Dọn rác rác tự động:**
    *   Tạo ra script giải phóng không gian ổ chứa `db-archiver.sh` và đặt lịch vào `CronJob` lúc 2H sáng (`db-cleanup-cronjob.yaml`) để xóa các lệnh đã trả `PROCESSED` cách đây 7 ngày.

### 2. Vấn Đề Message Broker (Kafka & DLQ)

*   **Hạn chế:** Kafka 1 node chạy trong Docker. Không an toàn.
*   **Giải pháp - Strimzi HA:** Định nghĩa cụm Cluster 3-nodes qua Strimzi Operator tại file `01-strimzi-kafka-cluster.yaml` cấu hình min-in-sync Replicas là 2 bằng cơ chế cực đoan (Không bao giờ mất bản tin khi đứt cáp).

*   **Hạn chế:** `DlqListener.java` bắt lỗi "Đầu độc" (Poison Pill) nhưng chỉ dùng lệnh `log.error()`. Log qua ngày sẽ bị cuộn trôi -> Mất dữ liệu quan trọng / Khách khiếu nại không có gì đền bù.
*   **Giải pháp - Cứng hóa Dead Letter Queue:** 
    *   Viết Entity `DlqMessageEntity` trên Hibernate.
    *   Bên trong `DlqListener.java`, tất cả Message vỡ sẽ được lưu thẳng vào Bảng Postgres `dead_letter_messages` với cấu hình Status: `NEW` để nhân sự Admin làm 1 màn hình UI vào Re-play bằng cơm.

### 3. Vấn Đề Bảo mật (Security & Config)

*   **Hạn chế:** Biến `ENCRYPTION_KEY = "GFTSecretKeyX"` bị nhúng Cứng vào toàn bộ Source Code của 4 Microservices. Vi phạm nghiêm trọng chuẩn thẻ tín dụng PCI-DSS.
*   **Giải pháp:**  Đã refactor hoàn toàn `CryptoConverter.java`. Lôi Value dựa vào biến môi trường: `System.getenv("ENCRYPTION_KEY")`. (Hoàn toàn tích hợp được với Hashicorp Vault).

*   **Hạn chế:** 4 Cục Service "Downstream" (KYC, Credit, Disbursement, Orchestrator) vì kế thừa `spring-security` tại `build.gradle` root nhưng GALAXY không định cấu hình -> Bị HTTP 401 Unauthorized đập hết ở cửa vào bởi màn hình Default Form Login của Boot.
*   **Giải pháp:** Thêm `SecurityConfig.java` cho cả 4 lớp dịch vụ này. Đồng bộ `issuer-uri` vào ứng dụng `application.yml` trỏ về Keycloak.

*   **Hạn chế:** Truyền nội bộ không mã hóa.
*   **Giải pháp - Istio mTLS:** Khóa chặt hệ thống với file lệnh K8s `01-peer-authentication-strict.yaml` và lưới lọc chặn ngoài `02-authorization-policy.yaml`. 

### 4. Vấn Đề Tuổi thọ Chịu tải & Idempotency (Siêu Tốc Độ)

*   **Hạn chế:** KYC/Credit/Disbursement kiểm tra xem Kafka có đánh lặp Message hay không (Double Processing) bằng câu Query DB: `processedRepository.existsById()` -> Trứng dọi đá, với 10,000 TPS, cái DB chĩa chết trước.
*   **Giải pháp - Redis SETNX:** Nâng cấp sang cấu trúc `StringRedisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", 10 min)`. Giao phó hoàn toàn công việc soi trùng lặp cho vắt RAM Redis (<1ms) của bộ nhớ tạm trước khi đụng vào CSDL.

*   **Hạn chế:** Dữ liệu Saga Cache của `SagaCacheService.java` không có cấu hình TTL tràn ngập thùng chứa Redis RAM.
*   **Giải pháp:** Chèn logic Expiration (Ví dụ TTL = 2h).

### 5. Vấn Đề "Cắt Lời / Ngắt Lỗ" (Resilience & Lifecycle)

*   **Hạn chế:** Deployment chết 1 cách đột ngột khi Kubernetes kill Pod, Transaction Data bị bỏ lửng gây ra trạng thái Dữ liệu Không Kiên Đinh (Inconsistency).
*   **Giải pháp - Graceful Shutdown:** Add thiết lập chuẩn cho tất cả Services: 
   `server.shutdown: graceful` và `spring.lifecycle.timeout-per-shutdown-phase: 30s` (Chờ làm nốt tác vụ dở rồi mới hạ nắp hòm).

*   **Hạn chế:** Microservice `loan-service` tìm kiếm Log qua ElasticSearch nhưng không có khiên. Elastcsearch ngưng thở = Bể UI do lỗi 500 chéo.
*   **Giải pháp - Circuit Breaker:** Đạp rào `@CircuitBreaker(name = "searchService")` và `@Bulkhead(type = SEMAPHORE)` cho file `LoanQueryService.java`. Từ chối request trước khi ES sập và cho phép nhả danh sách Log Null khôn ngoan thay vì HTTP Error (Graceful Degradation).

*   **Hạn chế:** Quá tải theo sự kiện.
*   **Giải pháp - K8s Manifest HPA & PDB:** Định lý hóa file `resilience-configs.yaml` thiết lập `HorizontalPodAutoscaler` nhảy cóc 3 Pod -> 50 Pod lúc CPU chạm 70%, và `PodDisruptionBudget` để luôn còn 1 Node sống sót.

---

## TỔNG KẾT
Hệ thống đã trải qua quá trình đại trùng tu từ một ứng dụng **Happy-Path (Chạy trên môi trường hoàn hảo)** biến thành một **Iron-Fortress (Pháo Đài Sắt)**, tích hợp các mẫu kiến trúc kinh điển của hệ thống Microservices quy mô toàn cầu. Sẵn sàng đáp ứng Go-Live!
