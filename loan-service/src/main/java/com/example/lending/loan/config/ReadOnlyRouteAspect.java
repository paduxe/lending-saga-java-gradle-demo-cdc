package com.example.lending.loan.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Random;

@Aspect
@Component
@Slf4j
@Order(1) // Đảm bảo Aspect này rẽ nhánh trước khi TransactionManager mở kết nối
public class ReadOnlyRouteAspect {

    private final Random random = new Random();

    @Around("@annotation(ReadOnlyRoute)")
    public Object routeToReplica(ProceedingJoinPoint joinPoint) throws Throwable {
        // Thuật toán Round-Robin hoặc Random chia luồng chọt vào Read-Only ĐB (Replicas)
        String replicaShard = random.nextBoolean() ? "SHARD_REPLICA_1" : "SHARD_REPLICA_2";
        
        log.info("🔍 [CQRS Read-Route] Đổi luồng kết nối sang Database Chỉ-Đọc (Read-Replica): {}", replicaShard);
        
        // Cắm cờ vào ThreadLocal để AbstractRoutingDataSource hiểu đường đi
        ShardContext.setCurrentShard(replicaShard);
        
        try {
            return joinPoint.proceed(); // Thực thi hàm Query ban đầu
        } finally {
            ShardContext.clear(); // Dọn dẹp ThreadLocal tránh rò rỉ bộ nhớ Connection
        }
    }
}
