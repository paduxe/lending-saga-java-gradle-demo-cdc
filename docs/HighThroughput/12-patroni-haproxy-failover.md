# 12. Tự động hóa Failover với Patroni, ETCD và HAProxy

Tài liệu này đi sâu vào kiến trúc khôi phục thảm họa (Disaster Recovery - DR) Tự động cho Database Postgres, đáp ứng tiêu chuẩn khắt khe nhất của vị trí Senior Solution Architect.

---

## 1. Tại sao Postgres thuần túy không thể Tự Động Failover?

Postgres có sẵn công cụ copy (Streaming Replication), nhưng khi con điện toán Master chết:
1.  **Ai có quyền bầu cử?** Ứng dụng dĩ nhiên là chết theo. Các con Standby không biết liệu Master bị "Rút điện" hay mạng Internet cắm vào Master bị hỏng (Nó vẫn sống nhăn nhưng bị cô lập).
2.  **Hội chứng Split-Brain:** Nếu Standby tự phong làm Master, trong khi Master cũ vẫn sống, bạn sẽ có 2 con Master nhận song song giao dịch! Dữ liệu sẽ nát bét (Data Corruption).

Đó là lý do **Patroni** ra đời, kết hợp bắt buộc với **ETCD** (Hệ thống đồng thuận).

---

## 2. Mô hình Kiến Trúc HA Tuyệt Đối

```mermaid
graph TD
    App[Tầng Application Java] --> |Write/Read| HA[HAProxy - Load Balancer]
    HA --> |Kiểm tra sức khỏe cổng 8008| Patroni_M
    HA -.-> |Kiểm tra sức khỏe cổng 8008| Patroni_S
    
    subgraph Data Center (Postgres Cluster)
        Patroni_M[Patroni Daemon] --- Master[(Postgres Master)]
        Patroni_S[Patroni Daemon] --- Standby[(Postgres Standby)]
        Master --> |Streaming WAL| Standby
    end
    
    Patroni_M <--> |Heartbeat / Bỏ phiếu| ETCD[(ETCD Cluster 3 Nodes)]
    Patroni_S <--> |Heartbeat / Bỏ phiếu| ETCD
```

---

## 3. Giải phẫu từng Thành phần

### A. ETCD (Distributed Consensus Store)
ETCD chạy thuật toán Raft. Nó lưu trữ file "Giấy khai sinh" báo hiệu thằng nào đang là Master thật sự. Mọi cài đặt Patroni đều phải được bầu cử và cấp giấy chứng nhận (Leader Lock) lưu tại ETCD. Khi Patroni gọi ETCD mà không gia hạn được Lock đúng hạn, nó mất chức Master.

### B. Patroni (Cảnh sát trưởng)
Là 1 tiến trình Python chạy đè lên trên Database Postgres. 
*   **Trách nhiệm:** Trực tiếp Stop/Start, Promote (Thăng chức) Postgres. Cứ mỗi 10 giây, nó nhắn tin cho ETCD: "Tao đang sống, tao đang là Master".
*   **Nếu Master chết thật sự:** ETCD phát hiện mất kết nối. Lock của Master cũ bị hủy. Con Standby Patroni tranh thủ cướp Lock, nâng cấp Postgres gốc của nó lên Master.

### C. HAProxy (Người gác cổng thông minh)
Ứng dụng Java từ nay **KHÔNG BAO GIỜ** kết nối vào IP tĩnh của Postgres nữa! 
App luôn kết nối vào cổng `:5432` của HAProxy. HAProxy làm sao biết phân phát tín hiệu Write về máy Master, và tín hiệu Read về máy Standby?

**Cách hoạt động bằng Cấu hình `haproxy.cfg` (Hỏi cực nhiều khi Phỏng vấn):**
```haproxy
listen postgres_master
    bind *:5432
    # HAProxy tự động thọc vào cổng 8008 của Patroni để soi.
    # Patroni nếu là Master sẽ trả HTTP 200 OK. Nếu là Standby trả 503.
    option httpchk GET /master
    http-check expect status 200
    
    # Máy nào vượt qua bài test HTTP thì HAProxy dẫn SQL vào máy đó!
    server psql-1 192.168.1.11:5432 maxconn 100 check port 8008
    server psql-2 192.168.1.12:5432 maxconn 100 check port 8008
```

---

## 4. Debezium vs Patroni (Vấn đề Chuyển rãnh Replication)

Lý thuyết cực mạnh cho CDC: Con Debezium đang cắm mỏ đọc file WAL (Replication Slot) của máy Master. Master sập, Standby xưng Vương. Con Debezium quay sang cắm mỏ vào Master mới, **NHƯNG MẤT DẤU TIẾN ĐỘ ĐANG ĐỌC (LSN)!**

*   **Lý do:** Replication Slot của Postgres mặc định tồn tại cục bộ trên từng máy, không copy sang Standby.
*   **Cách khắc phục:** Cấu hình Patroni sử dụng tính năng **Logical Slot Failover**. Patroni sẽ thường xuyên lấy danh sách "đề mục" (Slot status) từ ngài Master nén lại ném sang Standby. Khi Standby lên ngôi, cái Slot đó vĩnh viễn sống sót, Debezium tiếp tục móc vào và Resume quá trình đọc Event không trượt một LSN nào cả!
