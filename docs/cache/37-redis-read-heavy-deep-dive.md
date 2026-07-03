# Chuyên đề Kiến trúc Redis: Nhận định Kiến trúc, Phân tích 4 Case Read-Heavy & Q&A

Tài liệu này lưu trữ lại toàn bộ quá trình trao đổi và phân tích chuyên sâu về kiến trúc Caching (Redis), đặc biệt tập trung vào việc quản lý tải trọng cực lớn (High-Scale) cho các tác vụ Đọc/Ghi.

---

## 1. Bài toán Read-Heavy vs Write-Heavy: Có nên đặt Cache trước Database?

**Câu hỏi:**
*   Với bài toán Read-Heavy thì có nên dùng cache trước DB layer và phân chia thành 4 case (Single/Multiple x Hit/Miss) không?
*   Với bài toán Write-Heavy thì có nên dùng cache trước DB layer và phân chia thành 4 case (Single/Multiple x Hit/Miss) không?

**Nhận định & Lời giải:**
*   **Với Read-Heavy:** **CỰC KỲ NÊN (BẮT BUỘC).** Redis đứng TRƯỚC Database để chặn tải. Việc phân chia 4 case (Single/Multi × Hit/Miss) là cách phân tích chuẩn mực (Industry Standard) để đưa ra đòn bẩy kỹ thuật "đúng bệnh" nhằm bảo vệ DB và giảm Latency.
*   **Với Write-Heavy:** **KHÔNG NÊN.** Nếu đặt Cache đứng trước DB khi Ghi (Write-Through) sẽ làm tăng độ trễ và rủi ro lệch pha (Inconsistency). Thay vào đó, cấu trúc chuẩn là sử dụng **Kafka chặn trước**. App -> Kafka -> Database. Redis chỉ nên được cập nhật ngầm thông qua Worker / CDC (Debezium) SAU KHI Database đã ghi xong.

> **Tóm gọn:** Read-Heavy: Redis chặn TRƯỚC DB (Cache-Aside). Write-Heavy: Kafka chặn TRƯỚC DB (Event-Driven).

---

## 2. Phân tích chi tiết 4 Case cho bài toán Read-Heavy

**Câu hỏi:** Phân tích Usecase, Tradeoff và đưa ra solution tối ưu cho từng case với Read-Heavy sử dụng Redis đã cover đủ hết các trường hợp chưa?

Nhìn chung 4 trường hợp đã bao phủ 95% bối cảnh. Ta cần tách Case "Miss" làm 2 tình huống (Stampede và Penetration) để giải quyết được 100%.

### 🟢 CASE 1: READ SINGLE KEY (HIT)
*   **Usecase:** Khách hàng tra cứu 1 khoản vay/1 sản phẩm siêu Hot (Hot Key).
*   **Trade-off:** Dù là Cache Hit nhưng triệu request tập trung vào 1 Redis Node sẽ gây nghẽn Network IO.
*   **Solution:** **Sử dụng Local Cache (L1 Cache - Caffeine) tại App Pod.** Check L1 -> Miss -> Check Redis -> Lưu L1 5s. Giảm triệt để băng thông mạng tới Redis.

### 🟡 CASE 2: READ SINGLE KEY (MISS)
*   **Usecase:** Key hết hạn hoặc khách tra cứu tài liệu mới tinh. Có 2 hệ lụy:
    *   **Cache Stampede (Bão truy cập):** Hàng ngàn thread vượt Redis lao xuống DB tìm 1 dữ liệu. DB sập.
    *   **Cache Penetration (Xuyên thủng):** Hacker gọi API tìm ID ảo `id=-999`. Cả Redis và DB đều Miss liên tục làm điên đảo DB.
*   **Solution:**
    *   **Chống Stampede:** **Distributed Lock (Redisson)** hoặc Singleflight. Chỉ cho 1 Thread xuống DB, 999 thread kia chờ lấy data từ L2. Hoặc dùng "Logical Expiration" (trả data cũ, cập nhật backend).
    *   **Chống Penetration:** **Cache giá trị NULL/Rỗng** trên Redis với TTL ngắn và/hoặc ứng dụng **Bloom Filter** chặn ngay từ Gateway.

### 🔵 CASE 3: READ MULTI KEY (HIT)
*   **Usecase:** Render trang tổng quan (lấy 50 sản phẩm mà đã biết IDs).
*   **Trade-off:** Tốn độ trễ nếu gọi 50 hàm GET riêng lẻ (Network RTT).
*   **Solution:** Sử dụng lệnh **`MGET`** hoặc **Pipelining**. Đối với Redis Cluster, chú ý dùng Hash Tags `{group}:ID` để gom key nếu muốn `MGET` hiệu quả trên cùng 1 node.

### 🔴 CASE 4: READ MULTI KEY (MISS)
*   **Usecase:** Tìm kiếm chéo nhiều Filter (Ví dụ: "Top 50 khoản vay > 50 triệu tại HN").
*   **Trade-off:** Tổ hợp tìm kiếm sinh ra quá nhiều Key phức tạp, lưu Array vào Redis dễ gây lệch pha và cháy RAM. DB thì quá tải nếu join/scan liên tục.
*   **Solution:** **KHÔNG dùng Redis để Tìm kiếm**. Sử dụng nền tảng Search Engine chuyên dụng (**Elasticsearch**). Elasticsearch lọc ra danh sách mã `IDs`. Sau đó ném `IDs` này lên Redis `MGET` (Case 3) để lấy thông tin chi tiết. (Hybrid pattern).

---

## 3. Hỏi - Đáp (Q&A) Các tình huống thực tế

### Q1: Quá trình L1 (Caffeine) Miss -> Nạp Redis -> Có cần Distributed Lock?
**Trả lời: KHÔNG.** Không dùng Distributed Lock (vì nó qua mạng, chậm). Phải dùng **Local Lock (Thread Lock)** bên trong bản thân Caffeine. Thư viện Caffeine (`get(key, Function)`) đã áp dụng Lock nội bộ. 1000 threads miss L1 thì chỉ ĐÚNG 1 thread ra Redis kéo data, 999 threads khác chờ vi-giây trên RAM cục bộ.

### Q2: Xử lý Penetration (Cập nhật Cache Null), có cần Distributed Lock để lấy data từ DB không?
**Trả lời: CÓ.** Bắt buộc cần Distributed Lock (hoặc Singleflight). Vì dữ liệu là NULL, nếu thiếu Lock, 10,000 requests Miss sẽ đồng loạt chọc xuống DB tìm kiếm thứ không tồn tại. Phải cử 1 Thread xuống DB xác nhận là Rỗng -> nó lưu `NULL` vào Redis -> giải phóng Lock.

### Q3 & Q4: 1 triệu User cùng tìm danh sách "Top 50 sản phẩm" (Multi Miss & Hit). 
**Tình huống A (Danh sách giống nhau toàn cục - Global):**
Ví dụ 1 triệu người truy cập "Top mặt hàng bán chạy". Tuyệt đối không dùng 1 triệu `MGET`.
*   **Xử lý:** Một CronJob tính trước Top 50, gom thành **1 chuỗi JSON duy nhất**, đặt vào Redis. App nạp chuỗi JSON đó vào **L1 Cache**. 1 triệu User được phục vụ ngay trên RAM, số lệnh Redis = 0. Nếu Miss, dùng Distributed Lock cử 1 thread xuống DB gom lại.

**Tình huống B (Danh sách tìm kiếm riêng biệt - Personalized):**
Ví dụ AI gợi ý cho mỗi User 50 món khác nhau (Case 3 Hit) HOẶC User xài Filter khác nhau (Case 4 Miss). Cả 1 triệu danh sách không ai giống ai.
*   **Nếu Miss (Case 4 - Multi Miss cá nhân hóa):**
    Không thể dùng Distributed Lock (bởi Key khác nhau). Chuyển hoàn toàn gánh nặng tìm kiếm cho **Elasticsearch**, bắt buộc nhúng **Rate Limiting (Hạ cấp mềm)**. Nếu quá tải chặn User và trả về cấu hình Default. Đừng để chết DB.
*   **Giảm tải Hit (Case 3 - Gọi 1 triệu MGET 50 Key như thế nào?):**
    1. **Item-Level L1 Cache:** Đừng cache cả Mảng (Sẽ bị OOM). Hãy đặt Local Cache cấp độ Món hàng (Product ID). 45 món đã có sẵn ở L1, chỉ dùng `MGET` để gọi 5 món còn thiếu. Băng thông giảm 90%.
    2. **Redis Cluster:** Băm lệnh bằng Hash Slots rải đều ra 50 phân vùng Node vật lý Redis.
    3. **Pipelining:** Khung ứng dụng (như WebFlux/Lettuce) gộp chung nhiều requests thành 1 Connection mở đến DB, triệt tiêu RTT mạng.

---

## 4. Tóm tắt "Nguyên lý Vàng" đi phỏng vấn

*   Thấy **Dữ liệu Nóng & Dùng Chung** -> Nghĩ ngay đến **L1 Cache (Caffeine) & Json Gom cục**.
*   Thấy **Dữ liệu Nóng & Cá nhân hóa** -> Nghĩ ngay đến **L2 Cache (Redis Hit) & Nguyên lý MGET/Pipeline + Cache từng Item**.
*   Thấy **Miss chung 1 Điểm** -> Nghĩ ngay đến **Distributed Lock (Singleflight / Redisson)**.
*   Thấy **Miss diện rộng, bộ lọc phức tạp** -> Nghĩ ngay đến **Elasticsearch & Graceful Degradation (Rate Limit)**.
