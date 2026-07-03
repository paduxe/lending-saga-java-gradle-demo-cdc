# Hành trình Tiến hóa Hệ thống: Từ Basic Microservices đến Production-Ready Distributed System

**Mục đích tài liệu:** Cung cấp cái nhìn toàn cảnh (overview) về quá trình xây dựng, thiết kế và mở rộng (scale-up) dự án Lending Saga từ mức độ sơ khai nhất cho đến khi đạt chuẩn go-live phục vụ hàng triệu người dùng. Tài liệu giải thích rõ **TẠI SAO** cần scale ở mỗi bước và **THỨ TỰ** scale tối ưu nhất (Best Practice). 

---

## Giai đoạn 1: Sơ khai Microservices (Basic & Synchronous)
*   **Kiến trúc ban đầu:** Hệ thống được chia nhỏ thành các service độc lập (`loan`, `kyc`, `credit`, `disbursement`). Gọi nhau qua REST API (Synchronous bằng FeignClient/RestTemplate).
*   **Vấn đề (Tại sao cần tiến hóa?):**
    *   **Tight coupling:** Các service phụ thuộc nhau quá chặt về thời gian (Temporal coupling).
    *   **Cascading failures (Hiệu ứng domino):** Nếu service KYC chậm hoặc chết, Loan service phải chờ (blocking) khiến cạn kiệt thread pool và sụp đổ toàn bộ hệ thống.
    *   **Distributed Transaction:** Giải ngân bị lỗi nhưng khoản vay đã ghi nhận thành công ở Loan service, gây sai lệch tài chính (Không có tính ACID nhất quán).

## Giai đoạn 2: Quản trị Phân tán & Bất đồng bộ (Saga Orchestration)
*   **Giải pháp Scale-up:** Chuyển giao tiếp sang Asynchronous qua Message Broker (Kafka). Áp dụng pattern Saga Orchestrator.
*   **Lý do chọn Orchestrator thay vì Choreography:** Quy trình vay vốn tài chính có độ phức tạp cao, nếu để các service tự gọi nhau chéo (Choreography) sẽ tạo ra mớ bòng bong (Spaghetti). Cần một "Nhạc trưởng" (`loan-orchestrator-service`) đứng ra điều phối tập trung, theo dõi trạng thái và ra lệnh Compensation (hoàn tác) nếu có lỗi.
*   **Hiệu quả:** Hệ thống đạt khả năng Non-blocking, chịu tải cao (High Throughput). Các service nghiệp vụ hoàn toàn rảnh tay, chỉ nhận lệnh và trả kết quả.

## Giai đoạn 3: Rủi ro Dữ liệu kép (Outbox Pattern + CDC)
*   **Vấn đề (Tại sao cần tiến hóa?):** Khi `loan-service` lưu DB và bắn message sang Kafka, có rủi ro là DB lưu thành công nhưng Kafka sập (hoặc ngược lại). Đây là bài toán Data Inconsistency (Dual-Write problem).
*   **Giải pháp Scale-up:** Áp dụng **Transactional Outbox Pattern** kết hợp Change Data Capture (**Debezium**).
*   **Cơ chế:** Dữ liệu nghiệp vụ và sự kiện (Outbox event) được lưu trong **cùng một transaction DB**. Sau đó, Debezium (Kafka Connect) sẽ đọc âm thầm ở tầng binlog/WAL của PostgreSQL và đẩy chắc chắn lên Kafka.
*   **Sự kiên cố:** Cam kết Exactly-Once (hoặc At-Least-Once), loại bỏ hoàn toàn khả năng mất mát dữ liệu do rớt mạng.

## Giai đoạn 4: Hiệu suất & Quản lý Trạng Thái (Redis Cache & State Machine)
*   **Vấn đề (Tại sao cần tiến hóa?):** Khi TPS lên mức 10.000+, Orchestrator phải liên tục Read/Write trạng thái của hàng ngàn Saga vào Database PostgreSQL, tạo ra "Nút thắt cổ chai" (I/O Bottleneck).
*   **Giải pháp Scale-up:** 
    *   Áp dụng **Spring State Machine** để quản lý luồng rành mạch.
    *   Thêm **Redis để caching**.
*   **Điều chỉnh thiết kế (Sự tiến hóa về Durability):**
    *   *Lần 1 (Write-Behind Cache):* Chỉ ghi trạng thái RAM, khi kết thúc mới xả DB. -> *Nguy hiểm:* Nếu Redis chết, toàn bộ trạng thái đang chạy bốc hơi.
    *   *Lần 2 (Write-Through Cache):* Cập nhật đồng thời vào RAM và lưu Database lập tức. Trả giá một phần nhỏ hiệu năng để quy đổi lấy sự an toàn tuyệt đối cho Dữ liệu Tài chính.

## Giai đoạn 5: Tiêu chuẩn hóa Hợp đồng Giao tiếp & Data Governance
*   **Vấn đề (Tại sao cần tiến hóa?):** Khi code nhiều, các team tự ý gửi Kafka Message bằng chuỗi JSON thuần tuý. Database DDL thì dùng `spring.jpa.hibernate.ddl-auto: update`. Triển khai lên K8s sẽ gây giẫm đạp schema và lỗi Deserialize. 
*   **Giải pháp Scale-up:**
    *   **Data Governance:** Tích hợp **Flyway Migration**. Quản lý DB versioning chặt chẽ, chấm dứt việc Hibernate sửa DB vô tổ chức.
    *   **Message Contract:** Nâng cấp Kafka từ String JSON sang **Avro** kết hợp schema quản lý tập trung bằng **Confluent Schema Registry**. Đảm bảo tương thích ngược-xuôi (Forward/Backward compatibility). Ép mọi service giao tiếp bằng 1 loại "ngôn ngữ" chuẩn.

## Giai đoạn 6: Tính bền bỉ & Tự phục hồi (Resilience & Reconciliation)
*   **Vấn đề (Tại sao cần tiến hóa?):** Hệ thống có thể bị DDoS, API bên thứ ba (CIC, Provider) có thể bị Timeout treo vĩnh viễn, hoặc kafka rớt gói tin dẫn đến Saga "nằm chờ" mãi mãi.
*   **Giải pháp Scale-up:**
    *   **Phòng vệ:** Áp dụng API Gateway (Kong) và Resilience4j (Rate Limiter + Circuit Breaker) chống quá tải.
    *   **Xử lý treo:** Xây dựng `SagaTimeoutScheduler` (có dùng ShedLock Distributed Lock) quét định kỳ các khoản vay bị treo (> 60s) để đánh dấu FAILED và gửi kết quả về nhánh chính.
    *   **Đồng bộ cuối cùng (Reconciliation):** Xây dựng Job đối soát (Hòa giải dữ liệu) chạy vét mỗi 30 phút, đảm bảo tính Certain/Eventual Consistency cho cả hệ thống.

## Giai đoạn 7: Bảo vệ Doanh nghiệp (Thành trì Anti-Fraud)
*   **Vấn đề (Tại sao cần tiến hóa?):** Logic kinh doanh phức tạp lên, hệ thống phải đối mặt với các đợt vay lừa đảo bằng Bot. Việc hardcode IF/ELSE cho Fraud Logic sẽ phá nát Clean Code.
*   **Giải pháp Scale-up:** Chèn **EasyRules (Anti-Fraud Engine)** ngay tại cửa ngõ của `loan-service`. Hệ thống luật phân ly hoàn toàn khỏi code, đánh giá điểm tín dụng (Fraud Score) minh bạch và DROP ngay đơn hàng trước khi kịp đẩy vào mớ Kafka/Saga (Save Infrastructure Cost).

## Giai đoạn 8: Giám sát Toàn diện "Mắt Thần" (Production-grade Observability)
*   **Vấn đề (Tại sao cần tiến hóa?):** Chạy trên Production mà không thể biết lỗi ở đâu, hệ thống giống như "Hộp đen".
*   **Giải pháp Scale-up:**
    *   Nhúng **Micrometer** và **Zipkin** trace ID. Ráp các log chằng chịt lại thành một luồng duy nhất.
    *   Bơm Custom Metrics vào **Prometheus** (vd: `saga_failed_total`, `loans_fraud_blocked_total`).
    *   Định nghĩa còi báo động AlertManager (cảnh báo Slack/"Réo Dev lúc 2h sáng") -> Biến hệ thống từ Passive sang Proactive Resolution.

## Giai đoạn 9: Scaling Cấp độ Hạ tầng (Infrastructure & HA)
*   **Vấn đề (Tại sao cần tiến hóa?):** Cơ sở dữ liệu Postgres đạt tới hạn I/O, một instance service xử lý không xuể Traffic Spike đỉnh điểm.
*   **Giải pháp Scale-up thực thụ:**
    *   **Load Balancing & DR:** Kafka chạy 3 nodes với 3 Replicas / 30 Partitions.
    *   **Database HA:** Áp dụng mô hình Master-Slave với Pgpool-II/Repmgr.
    *   **Sharding:** Cài đặt Application-layer Sharding tại Loan-Service với `ShardRoutingDataSource` để chia rẽ chướng khí tập trung (Split Read/Write hoặc Customer Group).
    *   **Điện toán co giãn tự động:** Áp dụng Kubernetes HPA (Horizontal Pod Autoscaler) tự đo CPU và Scale up số lượng Pod (ví dụ từ 2 Pod lên 50 Pods) rồi tự Scale down về khi rảnh.

---

## 🚀 TỔNG KẾT: THỨ TỰ SCALE UP THEO BEST PRACTICE TẠI SAO PHẢI NHƯ VẬY?

Quy trình Scale không phải ngẫu nhiên thích gì làm nấy, mà tuân thủ quy trình xương sống: **Sạch Code -> An toàn Dữ liệu -> Hiệu suất -> Chịu lỗi -> Rộng Hạ tầng**.

1.  **Thiết lập Domain / Microservices Core (Basic):** Tách rãnh đúng chức năng. Nếu cắt file domain sai từ đầu, hệ thống phân tán sẽ càng nát.
2.  **Đảm bảo Tính Toàn vẹn (Saga + Outbox + CDC):** Đẩy dữ liệu ra ngoài luôn có rủi ro network. Phải đảm bảo DB và Event Message nhất quán 100% trước tiên (Durability > Speed).
3.  **Governance & Chuẩn hóa (Avro Registry + Flyway):** Trước khi Team Scale Up (từ 5 Dev lên 50 Devs), phải chốt chặt Hợp đồng Data. Nếu không Schema Management sẽ thành cơn ác mộng.
4.  **Tăng Hiệu suất (Redis + Caching):** Dữ liệu đã an toàn rồi mới tìm cách làm cho nó chạy nhanh hơn.
5.  **Áo Giáp và Tự Chữa Lành (Resilience + Anti-Fraud + Job Đối soát):** Xây dựng khả năng tự bật lại cho ứng dụng (Rate Limiter, Timeout Cronjob, Sharding Lock).
6.  **"Cắm mắt" Toàn bản đồ (Observability):** Chuẩn bị sẵn Metric Dashboard, Logging và Báo động.
7.  **Scale Hạ Tầng (K8s HPA + DB Sharding):** Scale Application là bước cuối cùng. Đẩy mạnh phần cứng (Hardware Scale/Ops Scale) chỉ thực sự có ích khi Core Codebase đã được gọt giũa và hoàn chỉnh.
