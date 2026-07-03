# 13. Q&A: Thiết kế và Triển khai Multi-Region / High Availability (HA)

Tài liệu này tổng hợp toàn bộ triết lý và các câu hỏi đáp (Q&A) xung quanh việc xây dựng cụm Kafka Multi-Broker và Database Replication mà chúng ta vừa implement.

---

### Q1: Cấu trúc của Kafka Multi-Broker mang lại lợi ích gì so với 1 Broker mặc định?
*   **Tránh "Chết cả chùm" (Single Point of Failure):** Thay vì 1 máy chủ, nay hệ thống có 3 Brokers (chạy giao thức KRaft `1@kafka:9093,2@kafka-2:9093,3@kafka-3:9093`).
*   **Không mất dữ liệu (Zero Data Loss):** Bằng cách nâng cầu hình `.replicas(3)` khi tạo Topic, mọi tin nhắn bắn vào Kafka sẽ được copy ra đủ 3 máy. Giả sử 1 Data Center (Máy chủ) nổ tung bốc cháy, 2 máy còn lại vẫn ôm đủ dữ liệu khách hàng.
*   **Cơ chế Lãnh đạo (Quorum):** Kafka sử dụng thuật toán KRaft để bầu ra Controller. Miễn là >50% số máy (2/3) còn sống, cụm Kafka vẫn hoạt động rầm rập như chưa có chuyện gì xảy ra.

### Q2: Tại sao phải tốn công cài đặt Patroni / Pgpool / Repmgr thay vì dùng tính năng Streaming Replication mặc định của Postgres?
*   **Postgres mặc định cực "Ngốc":** Tính năng chuẩn của Postgres chỉ biết copy data từ Master sang Standby. Nhưng nếu Master chết, Standby KHÔNG BIẾT TỰ ĐỘNG THĂNG CHỨC (Auto-Failover). Kỹ sư phải vào bật thủ công. Trong thời gian đó App sập.
*   **Giải pháp (Failover Automation):** Sự kết hợp của `Pgpool-II` và `Repmgr` (hoặc Patroni + HAProxy) tạo ra một hệ thống giám sát. Cứ 10 giây chúng kiểm tra Master một lần. Nếu Master "ngỏm", Repmgr sẽ kích hoạt tiến trình Bầu Cử (Election) đưa Standby lên thay tên đổi họ làm Master trong vòng chưa đầy 1 giây.

### Q3: Sau khi có HA Database, Code Java (Spring Boot) phải gọi đến Database nào?
*   **Không gọi thẳng máy chủ DB!** Nếu bạn gọi thẳng IP của Master, lỡ nó chết và chức Master được dồn cho máy Standby, App của bạn sẽ văng exception liên tục vì gọi nhầm "xác chết".
*   **Kiến trúc Chuẩn:** Code Java (file `application.yml`) cấu hình kết nối tới cái "CỔNG GÁC" là Load Balancer (`pgpool-kyc:5432`). 
*   Bác bảo vệ Pgpool sẽ tự động điều tiết: "À thằng Java muốn GHI DỮ LIỆU (Insert/Update) thì tao bẻ lái luồng chui vào Master. Thằng Java muốn ĐỌC (Select) thì tao cho qua đường Standby để giảm tải". Java App hoàn toàn "Bù nhìn" trước bộ máy tinh vi đằng sau.

### Q4: Hội chứng "Split-Brain" (Não phân liệt) trong DB HA là gì và cách phòng chống?
*   **Lý thuyết:** Khi dây cáp mạng giữa Master và Standby bị đứt. Master vẫn sống (Nghĩ mình là Master). Standby không thấy Master gọi, tưởng Master chết rồi nên tự xưng làm Tân Master. Cả 2 con Database cùng nhận dữ liệu GHI TỪ APP -> Dữ liệu rách nát, chồng chéo.
*   **Phòng chống:** Pgpool/Patroni sử dụng hệ thống "Quorum / Đồng thuận" thông qua máy thứ 3 (như ETCD, ZooKeeper, Watchdog) chuyên làm giám khảo. Chỉ con nào nhận được Phiếu bầu biểu quyết của Giám khảo mới được nhận kết nối từ mạng luar, con bị đứt mạng sẽ bị tự động cách ly (Fencing).

---
*Từng khối lệnh kiến trúc siêu phức tạp trên đều đã được ánh xạ (Mapped) trực tiếp vào file `docker-compose.yml` (Kafka-1,2,3 và Pgpool/Repmgr) tại dự án local của bạn!*
