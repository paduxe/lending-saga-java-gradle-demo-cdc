# Q&A: Câu hỏi thường gặp về GraphQL & gRPC

Tài liệu này tổng hợp các câu hỏi và giải đáp về hiệu năng cũng như cơ chế hoạt động của GraphQL và gRPC trong hệ thống.

---

## 1. Tại sao gRPC có performance tốt hơn RESTful dù cả hai đều đóng gói binary?

Có một nhầm lẫn phổ biến là "cứ binary là nhanh như nhau", nhưng thực tế gRPC nhanh hơn RESTful (ngay cả khi REST dùng format binary như MessagePack hay BSON) vì các lý do sau:

### a. Cơ chế Đóng gói (Protobuf vs JSON/Binary-REST)
*   **Protobuf:** Không gửi tên trường dữ liệu (ví dụ `"customerId"`). Nó sử dụng các **Tag numbers** (vị trí số 1, 2, 3...). 
*   **Hiệu quả:** Payload cực kỳ nhỏ gọn và việc giải mã (decode) diễn ra tức thì vì máy tính không cần parse chuỗi văn bản.

### b. Giao thức truyền tải (HTTP/2)
*   **Multiplexing:** Gửi hàng trăm request cùng lúc trên duy nhất một kết nối TCP. Loại bỏ hiện tượng "Head-of-Line Blocking" (phải chờ request cũ xong mới chạy request mới).
*   **Header Compression (HPACK):** Nén Header và chỉ gửi những gì thay đổi, tiết kiệm băng thông tối đa.

---

## 2. Khi nào nên dùng GraphQL thay vì gRPC?

*   **GraphQL:** Phù hợp nhất khi làm **External API** hoặc **BFF**. Nó giải quyết bài toán "Over-fetching" (lấy thừa dữ liệu) bằng cách cho phép Client tự chọn field.
*   **gRPC:** Phù hợp nhất khi làm **Internal Service-to-Service**. Nó giải quyết bài toán "Low-latency" (độ trễ thấp) và "Strong Contract" giữa các đội phát triển khác nhau.

---

## 3. Việc thêm gRPC check đồng bộ vào flow Saga có làm chậm hệ thống không?

*   **Dưới góc độ kỹ thuật:** Tăng thêm một chút latency cho request đầu tiên.
*   **Dưới góc độ Business:** **Nhanh hơn**. Vì nó ngăn chặn hàng ngàn Saga chắc chắn thất bại không được đẩy vào Kafka/Database, từ đó giúp giảm tải cho hệ thống và tránh lãng phí tài nguyên xử lý các bước sau như Disbursement hay Credit Allocation.

---

## 4. GraphQL aggregation làm việc như thế nào?

Trong `loan-service`, GraphQL đóng vai trò là "nhạc trưởng":
1. Nhận truy vấn từ Client.
2. Phân tách (Parse) các field yêu cầu.
3. Song song gọi gRPC sang các service tương ứng (KYC, Credit).
4. Gom tất cả response thành 1 cấu trúc duy nhất trả về.
5. **Đặc biệt:** Nếu Client không hỏi về `credit`, server sẽ tự động bỏ qua cuộc gọi gRPC sang Credit Service, giúp tối ưu hóa tuyệt đối.
