# Q&A Tối Ưu Hóa Hệ Quản Trị Cơ Sở Dữ Liệu (Database Optimization)

Tài liệu này tổng hợp các phân tích chuyên sâu về tối ưu hóa truy vấn Database, so sánh JPA vs Native Query, và các chiến lược thiết kế trong NoSQL (MongoDB).

## 1. Phân tích các chiến lược tối ưu Database Query trong PostgreSQL

Để tối ưu hóa truy vấn trên cơ sở dữ liệu quan hệ như PostgreSQL, chúng ta không chỉ áp dụng một kỹ thuật duy nhất mà phải kết hợp nhiều chiến lược dựa trên đặc thù của dữ liệu, tần suất Read/Write và giới hạn tài nguyên.

### 1.1 Phân tích gốc rễ: Đừng đoán, hãy đo lường (Profiling)
Trước khi áp dụng bất kỳ chiến lược nào, bước số 0 luôn là xác định chính xác nút thắt cổ chai (bottleneck):
- **Công cụ:** Kích hoạt `pg_stat_statements` để tìm các query tốn kém nhất (chạy lâu nhất hoặc gọi nhiều nhất).
- **Phân tích:** Dùng `EXPLAIN (ANALYZE, BUFFERS)` để xem query sử dụng bộ nhớ (buffers) dính đến disk hay RAM, và nó dùng `Seq Scan` (quét toàn bộ bảng) hay `Index Scan`.

### 1.2 Chiến lược 1: Tối ưu Index (Lợi tức đầu tư cao nhất)
Thay vì để Postgres quét toàn bộ dữ liệu, ta tạo Index để tìm kiếm theo dạng cây (B-Tree, GIN, v.v.).
- **Các kỹ thuật:**
    - **B-Tree Index:** Dùng cho tìm kiếm chính xác, dấu bằng hoặc khoảng lớn hơn/nhỏ hơn.
    - **Composite Index:** Index trên nhiều cột. *Lưu ý thứ tự cột phụ thuộc Cardinality*.
    - **Partial Index:** Phân vùng giá trị. Ví dụ `CREATE INDEX idx_active_users ON users(id) WHERE status = 'ACTIVE'`. Kích thước index cực nhỏ, độ trúng đích cao.
    - **Covering Index (`INCLUDE`):** Nhúng data vào nhánh lá index giúp chạy **Index Only Scan** (cung cấp thẳng dữ liệu mà ko cần đọc Document), giảm hoàn toàn disk I/O.
- **Trade-off:**
    - Tuyệt vời cho tốc độ Đọc (Read).
    - Gây chậm trễ cho Ghi (Insert/Update/Delete) do chi phí rebalance cây cấu trúc. Tốn thêm dung lượng (Chi phí Index Bloat).

### 1.3 Chiến lược 2: Refactor cấu trúc SQL (Viết query chuẩn mực)
Rất nhiều query chậm đến từ việc lập trình viên áp dụng cấu trúc SQL đánh lừa bộ Query Planner.
- **Các kỹ thuật:**
    - **Tránh bọc hàm lên cột (Sargable rule):** Tránh `WHERE DATE(created_at) = '...'`. Hãy viết quy vế `WHERE created_at >= '...' AND created_at < '...'`.
    - **Thay `IN` bằng `EXISTS`** khi so sánh subquery mang tập kết quả khổng lồ.
    - **Hạn chế `SELECT *`**: Yêu cầu trả các biến cần thiết nhằm giảm Network I/O và chi phí Sorting RAM.
    - **Xử lý đồ thị Pagination lớn:** Cắt bỏ limit ngây ngô: `OFFSET 10000 LIMIT 10`, chuyển ngay hệ sang **Keyset Pagination** (`WHERE id > last_seen_id ORDER BY id LIMIT 10`).

### 1.4 Chiến lược 3: Denormalization (Gộp - Phi chuẩn hoá bảng dữ liệu)
- **Kỹ thuật:** Chủ động "phá vỡ" chuẩn thiết kế 3NF, lưu đè dữ liệu cần thiết (VD luận bàn: lưu thêm `total_orders` ở bảng `User` thay vì `COUNT` JOIN về từ bảng `Order`).
- **Trade-off:** Tốc độ đọc rất cao nhưng đối diện rủi ro chênh lệch Data (Data Inconsistency). Phải xử lý bằng CDC Event-driven / Triggers / Application level để bù đắp.

### 1.5 Chiến lược 4: Table Partitioning (Phân mảnh lưu trữ theo cấu trúc khối lạch)
Hiệu quả cho dữ liệu lịch sử cực lớn qua ngưỡng hàng trăm triệu record.
- **Kỹ thuật:** Tách mảng table gốc vỡ ra nhiều bảng nhỏ (`RANGE` theo ngày/tháng, hoặc `LIST` theo nhóm Category).
- **Phép màu Partition Pruning:** Query nhét chính xác `Partition Key` sẽ bỏ qua nhánh các table dư thừa không cần để mắt đến.
- **Trade-off:** Gửi Query vắng mặt key Partition buộc máy Scan quét xuyên cõi Table, hậu quả làm héo tàn hệ thống. Mất quyền bạo lực cho Unique constraints khuyết Partition Key.

### 1.6 Chiến lược 5: Materialized Views
- **Kỹ thuật:** Phục vụ dữ liệu báo cáo Aggregate cực khó: `CREATE MATERIALIZED VIEW` trích dòng table kết quả mới theo kỳ hạn Batch Update (`REFRESH MATERIALIZED VIEW CONCURRENTLY`).
- **Trade-off:** Data không thời gian thực (không Realtime). Mặc định dính Stale data.

### 1.7 Chiến lược 6: Tuning Thông Số Engine Bộ Nhớ Cấu Hình DB
- Kiểm soát `work_mem` để chặn Postgres xuất file sort đẩy gánh sang đĩa từ SSD (Tự theo dõi rủi ro OutOfMemory nếu hệ connection max ngưỡng pool). 
- Phóng khoáng `shared_buffers` lấy RAM làm mâm cache memory cho DB.

---

## 2. So sánh và phân tích Trade-off: Spring Data JPA vs Native Query

Lựa chọn giữa **Spring Data JPA (dựa trên ORM/Hibernate)** và **Native Query (SQL thuần)** là lựa chọn Tradeoff giữa "Năng Phẩm Development" và "Hiệu Suất Hardware Machine".

### 2.1 Spring Data JPA (JPQL / Derived Queries / Criteria API)
Spring Data đưa mã xử lý Java Objects thành các chuỗi lệnh DDL/DML nạp database tự động ngầm. 

**Điểm mạnh (Pros):**
- **Tiến độ dự án Tên Lửa:** Thừa kế Abstract Repo gọi các Derived Name `findByStatusAndCreatedDate...` nhàn hạ bỏ qua thời gian cấu trúc SQL Query.
- **Trừu Tượng Cấu trúc CSDL (Database Portability):** Chuyển từ Oracle/Postgres/MySQL đơn giản dựa vào sự cung cấp chuỗi driver nòng cốt.
- **An Toàn Nhờ Object Mapping:** Cơ chế JPA gom L1 Cache tuyệt hảo cho Transaction Savepoint, quy chiếu Type-Safety (Kết hợp Specification). Dễ đổi tên Field Model qua IDE.

**Hạn chế (Cons):**
- **Hiệu Năng Sinh Query Sụp Đổ Cục Bộ:** Xử lý query mập mờ, sinh `SELECT *` dư thừa, Update nạp cả Entity object chứ không gói gọn 1 Field đổi giá trị (Bất lực nếu chưa hiểu `@DynamicUpdate`).
- **N+1 Query:** Sự hủy diệt hiệu năng khi dùng Lazy fetch không kiểm soát. Danh sách load 1 lần phải kéo Query phụ n/ lần. Buộc can thiệp sâu lệnh `@EntityGraph` hoặc cắm cụm `JOIN FETCH`.
- **Rối Rắm Khổng Lồ Trong API Tổng Hợp Report:** Ép các câu Lệnh Aggregate (có HAVING Group Join 6 table) trên code Criteria sẽ thành mã hóa ngoài luồng Đọc.

### 2.2 Native Query (Truy vấn Cổng SQL Bản Địa)
Xài `@Query(nativeQuery = true)` hoặc `JdbcTemplate` cắm SQL thuần túy.

**Điểm mạnh (Pros):**
- **Sức Mạnh Trần Tuyệt Đối:** Giao trọn quyền kiểm soát Query Plan, đánh điểm Index Hinting, cấu trúc lại câu Select trơn tuột theo Performance của Database.
- **Gõ Đủ Mọi Vũ Khí Bí Mật của Posgres:** Tha hồ phô diễn với `WITH` (CTE), Window Function đỉnh cao (`PARTITION BY`, `RANK()`), múc trực tiếp Operator JSONB dữ liệu nhét dọc Array hay chạy ngầm lệnh `UPSERT (ON CONFLICT DO UPDATE)`. 
- **Bulk Insert Triệu Record:** Cắm Spring Jdbc Template `.batchUpdate()` là cõi riêng giúp ghi log nghìn rưỡi transactions vứt hết overhead JPA đi qua 1 góc.

**Hạn chế (Cons):**
- **Error Runtime Quá Muộn:** Rủi ro hở ngoặc đơn hay phẩy biến trong code String chỉ nổ tung lỗi sau khi Runtime vào luồng test đó. Phá vỡ Type-Safe.
- **Khóa Cứng Hạ Tầng:** Không thể đem sang loại DB Vendor khác sống sót.
- **Ma Trận Mapping Cực Phức Tạp:** Output dòng SQL phải đổ bằng tay về Interface / DTO (Nhờ `@SqlResultSetMapping` rập khuôn tì tì viết Code nhạt).

### 2.3 Chiến thuật (Best Practice Phân Luồng CQRS Trong Monolith)
- **Mảng WRITE (Command):** Cho JPA cáng đáng nghiệp vụ Bussiness tạo/Sửa/Delete đảm bảo đúng quan hệ chuẩn Entity State bảo vệ đồ thị logic chẽn. Xử lý các query đọc căn bản cho Table ID Profile, Config hệ thống.
- **Mảng READ/BATCH (Phần xử trí báo cáo):** Hỗ trợ đút NativeQuery hoặc dùng JdbcTemplate xử lý những nhón Join phức tạp, Bulk Insert chép file. Tại các vùng Endpoint bị Alert Slow API > 3s thì lôi Native Query ra Refactoring thẳng thừng từ bỏ JPA.

---

## 3. Các chiến lược Tối ưu Query trong Mảng NoSQL (MongoDB)

Khác với Relational DB phải chuẩn hóa chuẩn mực 3NF, với MongoDB Schema Design chạy hệ "Kiến Trúc Sinh Hình Dựa Xét Query Sẽ Gọi Ra Cỡ Nào".

### 3.1 Bắt Đầu Với Giải Bài Toán Theo Dõi Tải (Profiling & Explain)
- Chạy cờ lê Test lệnh `db.collection.find(...).explain("executionStats")` thu dấu vết quét cạn dữ liệu.
- Phân tích cắn đứt 2 còi báo động: Tập trung diệt **COLLSCAN** (Collection Scan: Không qua chỉ mục) và **inMemorySort** (Gánh RAM máy Sort không có gốc gác lá Index). 

### 3.2 Chiến lược 1: Tổ Chức Data Modeling 
- **Kỹ thuật Embedding (Nhúng Dữ liệu - Phi Chuẩn Hóa):**
    - Trả thẳng 1 Object gốc chứa trọn vẹn List Con ở Document ruột (`1 Query Call`). Phù hợp chuẩn loại hệ List nhỏ 1-n, liên hệ mảng cha con trực diện.
    - Đánh đổi: Document sập limit 16MB. Mảng vô cực kiểu list Activity Log sẽ gây đau đớn Write do MongoDB cần Expand Disk Append kéo trễ Server.
- **Kiến trúc Referencing (Dùng Bảng Tham Chiếu như khóa Phụ):**
    - Xài qua cơ chế nối Pipeline `Aggregation $lookup`. Phù hợp mảng array quá bự, N-N.
    - Đánh đổi: Bộ Engine Mongo sinh ra vướng Join qua bộ Lookup rất nặng, ăn cấu trúc RAM máy mạnh mới thó thoát.

### 3.3 Chiến lược 2: Cách Mạng Index Cốt Lõi (ESR Rule)
Vương Định của NoSQL sinh nhai hoàn toàn trên Bộ Chỉ Mục:
- **Nguyên tắt Đặt Chỉ Mục Hợp Thể Cột Khóa ESR (Equality - Sort - Range)**
    - Phải luôn kẹp trường Cố định Bằng giá trị (`==`) ném đầu mảng **Equality**. Vd: `status`.
    - Sau lưng chọc hàm dùng lệnh Order (`Sort()`): Vd `CreatedDate`.
    - Dồn cuối cùng vào góc cho hàm Range biến tấu `($in, >, <)`.
    - Sự sai lệch ESR sẽ triệt hồi năng lực Sort/Index.
- Xây chiến lũy **Covered Query** chặn data về chốn thiên đàng, tức Query gọi biến nào thì biến đó đã nhét vô lòng Index, Document Base không cần load đĩa lấy RAM.

### 3.4 Chiến lược 3: Chiều Tiễn Lọc Query Projection 
- Hủy bỏ các trường vứt lấy Full bằng kẹp cụm Lọc (Projection: `{ title: 1, author: 1, _id: 0 }`). Cứu cả Mạch Network Transport IO. 
- **Optimization Pipeline Pipeline:** Giựt toàn lực vứt mảng `$match` và `$sort` đút mũi nhọn trên đầu, đứng trước khối hàm `$project` và tuyệt đối ép đứng trước lệnh Nối bảng `$lookup`. Index Mongo chỉ chui lọt được các Stage ngắt nhịp ở Top đầu mảng Pipeline.

### 3.5 Chiến lược 4: Read Preference Khởi Sinh Dạt Tải và Phân Mảnh Khối 
- **Read Preference / Replication Route:** Tranh thủ Offload tài nguyên cấu hình `secondaryPreferred`. Toàn bộ báo cáo, Search Data thảy hết qua Máy Chủ Phụ (Secondary Replica Set) nhận query đọc. Giữ lại Node Master (Primary) chỉ chịu áp lựng hệ Write. Có Trade-off xíu độ xê dịch Trễ Realtime (Eventual Consistency). 
- **Sharding - Cưa Dữ Liệu:** Áp dụng khi bộ Volume DB Data phình húp tới các TeraByte (Table chọc vọt vài trăm Gb). Bắt buộc phân loại Mảng Server và định đoạt bộ **Shard Key** cho đúng ngữ nghĩa sử dụng dọc (Rranged Sharding) hay Chia băm ngang (Hashed Sharding) tránh bị điểm lún dồn cổ (Hotspot Node IOPS) hay rải đài (Scatter Gathering).
