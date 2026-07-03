# 11. Đảm bảo Tính sẵn sàng cao (High Availability / Multi-Region)

Khi hệ thống thiết kế để chạy 100k TPS, rủi ro lớn nhất là Sập Data Center (Cháy nổ, Cắt cáp...). Hạ tầng cần có khả năng "Sống sót" thông qua kiến trúc Multi-Region / High Availability (HA).

---

## 1. KAFKA CLUSTER (Multi-Broker & HA)

Trong Local hiện tại ta dùng 1 Broker (Nguy cơ: Nếu sập, mất toàn bộ message). Để chịu lỗi:

### A. Lý thuyết Cốt lõi:
*   **Replication Factor (RF = 3):** Dữ liệu của 1 Partition không lưu ở 1 máy, mà được copy sang 2 máy khác (Tổng 3 bản sao rải đều trên 3 Brokers). Nếu 1 Broker chết, 2 cái kia vẫn giữ đủ data.
*   **`min.insync.replicas` (ISR = 2) & `acks=all`:** Ép Kafka phải ghi thành công lên ít nhất 2 Brokers thì Producer (Debezium) mới được báo là "Gửi thành công". Ngăn chặn hoàn toàn việc mất tin nhắn khi đứt mạng đột ngột.
*   **Rack Awareness:** Thiết lập tham số tĩnh `broker.rack` (VD: AZ1, AZ2, AZ3). Kafka sẽ đủ thông minh để không bao giờ đặt 3 bản sao vào chung khu vực AZ1. Cứ 1 vùng địa lý chết, vùng khác gánh.

### B. Cấu hình Docker-compose (Mẫu 3 Brokers - Yêu cầu RAM > 16GB):
```yaml
  kafka-1:
    image: bitnamilegacy/kafka:3.7.1
    environment:
      - KAFKA_CFG_NODE_ID=1
      - KAFKA_CFG_BROKER_RACK=AZ-1
      ...
  kafka-2:
    image: bitnamilegacy/kafka:3.7.1
    environment:
      - KAFKA_CFG_NODE_ID=2
      - KAFKA_CFG_BROKER_RACK=AZ-2
      ...
  kafka-3:
    image: bitnamilegacy/kafka:3.7.1
    environment:
      - KAFKA_CFG_NODE_ID=3
      - KAFKA_CFG_BROKER_RACK=AZ-3
      ...
```
*(Trong tệp code Java, việc thay đổi này là vô hình (Transparent) với ứng dụng. Chỉ cần sửa code lúc tạo Topic thành `.replicas(3)` thay vì 1)*.

---

## 2. DATABASE REPLICATION (Postgres HA)

DB Server bốc cháy, dữ liệu khách hàng sẽ biến mất? Kiến trúc Master-Slave (Primary-Standby) là bắt buộc.

### A. Lý thuyết Cốt lõi:
*   **Streaming Replication:** Postgres Primary (Máy Master) không chỉ xuất log cho Debezium, nó còn gửi luồng byte thô (WAL) liên tục sang Máy Standby (Slave) ở xa.
*   **Synchronous vs Asynchronous:**
    *   *Async (Mặc định):* Master ghi xong báo OK luôn, rảnh rỗi mới ném qua Slave. Nhanh nhưng nếu chết đột ngột -> Mất dữ liệu (Data Loss) vài mili-giây cuối.
    *   *Sync:* Master ghi xong phải đợi Slave bên DC ảo xác nhận lưu xong rồi mới báo OK cho Ứng dụng. Chậm hơn nhưng Data Loss = 0.
*   **Cơ chế Fail-over (Bàn giao quyền lực):** Hệ thống thường dùng công cụ **Patroni** hoặc **Repmgr** để giám sát. Master chết, hệ thống lập tức bầu chọn Standby lên nắm quyền Master và đổi IP để App tiếp tục chạy.
*   **Debezium Slot Failover:** Vấn đề gai góc nhất: Debezium lấy dữ liệu từ Master, nếu Master sập chuyển qua Standby, Debezium phải đọc tiếp ở đâu? Phải sử dụng plugin có sẵn của Postgres (VD: `pg_failover_slots`) để đồng bộ kênh kết nối CDC từ Primary sang Standby.

### B. Cấu hình Docker-compose (Mẫu Primary-Standby - Tham khảo tay):
Thiết lập bằng tay Postgres Replication trên Docker rất cồng kềnh (Đòi hỏi set `pg_hba.conf` và cấp quyền REPLICA). Bản mẫu đơn giản:
```yaml
  postgres-primary:
    image: bitnami/postgresql:16
    environment:
      - POSTGRESQL_REPLICATION_MODE=master
      - POSTGRESQL_REPLICATION_USER=repl_user
      - POSTGRESQL_REPLICATION_PASSWORD=repl_pass

  postgres-standby:
    image: bitnami/postgresql:16
    environment:
      - POSTGRESQL_REPLICATION_MODE=slave
      - POSTGRESQL_PRIMARY_HOST=postgres-primary
      - POSTGRESQL_PRIMARY_PORT=5432
      - POSTGRESQL_REPLICATION_USER=repl_user
      - POSTGRESQL_REPLICATION_PASSWORD=repl_pass
```
