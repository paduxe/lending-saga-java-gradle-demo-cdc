package com.example.lending.orchestrator.security;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    @Around("within(com.example.lending.orchestrator.api..*)")
    public Object logAudit(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String userId = "GFT-SYS-ADMIN"; 
        String method = joinPoint.getSignature().toShortString();
        String args = Arrays.toString(joinPoint.getArgs());
        
        log.info("[AUDIT_LOG_START] Identity '{}' yêu cầu thực thi API '{}' với Payload: {}", userId, method, args);
        
        try {
            Object result = joinPoint.proceed();
            long timeTaken = System.currentTimeMillis() - start;
            log.info("[AUDIT_LOG_SUCCESS] API '{}' hoàn tất phục vụ trong {} ms.", method, timeTaken);
            return result;
        } catch (Exception e) {
            log.error("[AUDIT_LOG_FAILED] API '{}' xịt do lỗi: {}", method, e.getMessage());
            throw e;
        }
    }
}
