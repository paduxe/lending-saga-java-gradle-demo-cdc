package com.example.lending.loan.security;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

// 2. AUDIT LOG (Sổ Nhật ký Kiểm toán)
// Ứng dụng mô hình AOP (Góc khuất Aspect): Đứng trực ở cổng lớp Controller.
// Mọi Request đi qua đều bị tóm lại ghi hình. Developer không cần tự viết lệnh Log.
@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    // Chặn mọi function nằm trong thư mục API của Loan Service
    @Around("within(com.example.lending.loan.api..*)")
    public Object logAudit(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        
        // Giả lập bóc tách Identity từ lớp bảo mật Token (OAuth2)
        String userId = "GFT-SYS-ADMIN"; 
        String method = joinPoint.getSignature().toShortString();
        String args = Arrays.toString(joinPoint.getArgs());
        
        log.info("[AUDIT_LOG_START] Identity '{}' yêu cầu thực thi API '{}' với Payload: {}", userId, method, args);
        
        try {
            Object result = joinPoint.proceed(); // Cho phép request đi tiếp vào Controller
            long timeTaken = System.currentTimeMillis() - start;
            log.info("[AUDIT_LOG_SUCCESS] API '{}' hoàn tất phục vụ trong {} ms.", method, timeTaken);
            return result;
        } catch (Exception e) {
            log.error("[AUDIT_LOG_FAILED] API '{}' xịt do lỗi: {}", method, e.getMessage());
            throw e;
        }
    }
}
