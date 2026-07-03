# Deep Dive: Các chiến lược sử dụng Redis Cache & Tại sao chọn Write-Through cho Saga

Trong các hệ thống phân tán chịu tải cao, Database truyền thống nhanh chóng trở thành "nút thắt cổ chai" (bottleneck) vì tốc độ I/O đĩa cứng (Disk) chậm hơn rất nhiều so với RAM. Redis xuất hiện như một giải pháp cứu cánh. Tuy nhiên, cách chúng ta thiết kế luồng đọc/ghi (Read/Write) giữa **Application - Cache - Database** quyết định sự sống còn của dữ liệu.

Dưới đây là 4 mẫu thiết kế Caching kinh điển (Caching Patterns) và phân tích chuyên sâu:

---

## 1. Mẫu Cache-Aside (Lazy Loading) - Kẻ lười biếng phổ biến nhất
Mẫu này để quyền chủ động hoàn toàn vào tay Application. Cache và Database không hề biết về sự tồn tại của nhau.

*   **Cách hoạt động (Luồng Read):** 
    1. App hỏi Cache. Nếu có (Cache Hit), trả về ngay.
    2. Nếu không có (Cache Miss), App cất công chọc vào Database để lấy data.
    3. Sau khi lấy được, App tự tay ghi ngược data đó vào Cache (dùng cho lần sau), rồi mới trả kết quả.
*   **Cách hoạt động (Luồng Write):**
    1. App ghi dữ liệu thẳng vào DB.
    2. App tiến hành "vô hiệu hóa" (Invalidate) hoặc xóa bản ghi cũ đó trong Cache để đảm bảo ai đọc sau sẽ chịu khó query DB lấy bản mới.
*   **Ưu điểm:** Dễ hiểu, dễ cài đặt. Cache chỉ chứa những gì thực sự được dùng (tránh lãng phí RAM). Hệ thống vẫn sống kể cả khi Redis sập (Fall-back về DB).
*   **Nhược điểm:** Tồn tại độ trễ (Latency Spike) trong lần đọc đầu tiên (Cache Miss). Code logic bị rải rác ở Application. Cần cấu hình TTL (Time To Live) khéo léo để tránh dữ liệu cũ (Stale data).
*   **Use case:** Phù hợp nhất cho hệ thống Đọc nhiều - Ghi ít (Read-heavy). Ví dụ: Thông tin Profile người dùng, Bảng tin (Feed), Cấu hình hệ thống.

---

## 2. Mẫu Read-Through Cache - Đầu vòi duy nhất
Khác với Cache-Aside, Application lúc này coi Cache như "Source of Truth" duy nhất để đọc. Cache library được chèn thêm logic tự động kết nối với Database.

*   **Cách hoạt động (Luồng Read):**
    1. App yêu cầu dữ liệu từ Cache.
    2. Nếu Cache Miss, **Cache (không phải App)** sẽ tự đứng ra query DB, tự lưu vào RAM của nó, rồi trả về cho App.
*   **Ưu điểm:** Code phía Application cực kỳ sạch (ít khối `if-else cache miss`).
*   **Nhược điểm:** Dữ liệu lần đầu tiên truy cập vẫn bị chậm. Tương đối khó thiết lập vì phải cấu hình Data Provider (với Spring Boot có thể dùng `@Cacheable`).
*   **Use case:** Giống Cache-Aside, phù hợp với Read-heavy, nhưng ưu tiên Clean Code.

---

## 3. Mẫu Write-Through Cache - Kẻ cẩn trọng (Áp dụng trong dự án Lending)
Ở mẫu này, Application sẽ coi Cache là điểm đến đầu tiên khi Ghi (Write), nhưng quá trình Ghi chỉ thành công khi cả Cache và Database đều hoàn tất lưu trữ trong cùng một bước.

*   **Cách hoạt động (Luồng Write):**
    1. App ném dữ liệu cập nhật cho Cache Service.
    2. Cache Service ghi đè lên RAM.
    3. **NGAY TRONG LÚC ĐÓ**, Cache Service gọi luôn lệnh Ghi xuống Database.
    4. Chỉ khi DB xác nhận lưu thành công, Cache Service mới báo cho App là đã hoàn thành.
*   **Ưu điểm (Giá trị cốt lõi):** **An toàn tuyệt đối (Durability).** Dữ liệu trong RAM và DB luôn đồng bộ 100%. Nếu Redis sập, dữ liệu vẫn còn nguyên ở Postgres. Không bao giờ lo mất dữ liệu. Lần đọc sau cũng cực nhanh vì dữ liệu đã hiện diện trên RAM mới tinh.
*   **Nhược điểm:** Tốc độ chốt thao tác Ghi (Write Penalty) bị chậm lại, bởi vì nó phải chờ tốc độ ghi chậm chạp của đĩa cứng DB. (Nhanh hơn DB thuần một chút lúc đọc, chậm bằng DB thuần lúc ghi).
*   **TẠI SAO CHỌN WRITE-THROUGH CHO SAGA ORCHESTRATOR?**
    *   Trạng thái Saga là cốt lõi của **Giao dịch Tài chính** (VD: Đang giải ngân tiền).
    *   Nếu chỉ ghi RAM (Write-Behind) rồi Redis sụp, bạn quên mất mình đã trừ tiền hay chưa -> Thảm họa.
    *   Dùng Write-Through, mọi Status (`KYC_PASSED`, `CREDIT_REQUESTED`) đều được cắm rễ vào DB, nhưng lần query API lấy trạng thái thì "bốc" từ Redis ra với tốc độ <1ms.

---

## 4. Mẫu Write-Behind (Write-Back) Cache - Siêu xe liều lĩnh
Hiệu năng tối đa, rủi ro cực đại. Application vứt dữ liệu cho Cache rồi "phủi tay" bỏ đi, Cache sẽ tìm cách nhồi xuống DB lúc rảnh rỗi.

*   **Cách hoạt động (Luồng Write):**
    1. App gửi lệnh cập nhật vào Cache.
    2. Cache ghi đè lên RAM và báo "Thành công" lập tức cho App. (App tốc biến đi làm việc khác).
    3. Chạy nền ở một luồng khác (Asynchronously/Eventual), Cache gom một lượng dữ liệu thay đổi trên RAM, sau đó xả (Flush) một mẻ lớn xuống Database (Batch insert).
*   **Ưu điểm:** **Tốc độ ghi siêu phàm (Extreme Throughput)**, có thể gánh hàng trăm ngàn TPS. Database thoát cảnh ăn query dồn dập, chỉ nhận cú "xả" nhẹ nhàng lâu lâu một lần.
*   **Nhược điểm:** **Nguy cơ mất trắng dữ liệu.** Nếu Server/Redis cúp điện đột ngột trước khi tác vụ "xả mẻ" chạy tới DB, mọi thay đổi trên RAM trong vài giây/vài phút qua sẽ tan biến như mây khói.
*   **Use case:** Đếm số lượt View Youtube/Tiktok, log hành vi nhấp chuột (Session Tracking), thu thập Metric thô - Những nơi mất data không đòi mạng. Tuyệt đối không dùng cho tiền bạc.

---

## 💡 Bảng Tổng Kết (Cheat Sheet)

| Chiến lược (Pattern) | Độ Bền (Durability) | Tốc Độ Ghi (Write Speed) | Tốc độ Đọc (Read Speed) | Ứng dụng khuyên dùng (Use Cases) |
| :--- | :--- | :--- | :--- | :--- |
| **Cache-Aside** | Cao | Trung bình | Nhanh sau lần 1 | Profile User, Tin tức, Product list (Best for General Read-heavy) |
| **Read-Through** | Cao | Trung bình | Nhanh sau lần 1 | Tương tự Cache-Aside, nhưng giảm rác codebase |
| **Write-Through** | **Bất diệt (Tối đa)** | Chậm (Bằng tốc độ DB) | Chớp nhoáng | **Saga State**, Banking, Giỏ hàng E-commerce, Inventory |
| **Write-Behind** | Thấp nơm nớp | **Nhanh kinh hoàng** | Chớp nhoáng | Đếm View, Lượt Like Tiktok, Tracking click (Extreme Write-heavy) |

*Dự án Lending Saga ban đầu được thiết lập ở cấu hình **Write-Behind** cho Orchestrator (để gánh TPS demo lúc phỏng vấn), nhưng sau đó đã được refactor "về mốc an toàn" **Write-Through** để trở thành Architecture Production-Ready (Có thể go-live ra thị trường).*
