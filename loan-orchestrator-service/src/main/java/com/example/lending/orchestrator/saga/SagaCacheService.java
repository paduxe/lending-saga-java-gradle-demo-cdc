package com.example.lending.orchestrator.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

// THE WRITE-BEHIND CACHE (Siêu vũ khí phá Nút Thắt Cổ Chai của Dữ liệu State Machine)
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaCacheService {
    private final LoanSagaRepository dbRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper mapper;

    public Optional<LoanSagaEntity> getSaga(String sagaId) {
        try {
            // 1. Chọc RAM Redis (Tốc độ ánh sáng <1ms) trốn Query Disk
            Object cached = redisTemplate.opsForValue().get("SAGA:" + sagaId);
            if (cached != null) {
                return Optional.of(mapper.convertValue(cached, LoanSagaEntity.class));
            }
        } catch (Exception e) {
            log.warn("⚠️ [REDIS CACHE MISS/DOWN] Mất kết nối RAM Redis khi truy vấn Saga {}. Sẽ đọc từ DB (Vì DB luôn được ghi trước nên dữ liệu DB là MỚI NHẤT 100%).", sagaId);
        }
        // 2. Không có trên RAM (hoặc Redis chết) thì mới lôi DB ra đọc
        return dbRepository.findById(sagaId);
    }

    public void updateStateInMemory(LoanSagaEntity saga) {
        log.info("🏦 [SAFE WRITE-THROUGH] Lưu trạng thái '{}' của Saga {} VÀO DATABASE TRƯỚC an toàn tuyệt đối.", saga.getCurrentStep(), saga.getSagaId());
        // 1. LUÔN LUÔN ghi DB trước (Gốc rễ của Sự Thật)
        dbRepository.save(saga); 
        
        // 2. Cố gắng ghi Cache để tăng tốc độ Read cho nhịp sau
        try {
            redisTemplate.opsForValue().set("SAGA:" + saga.getSagaId(), saga, Duration.ofMinutes(60));
        } catch (Exception e) {
            log.error("❌ [REDIS FAILURE] Không thể cập nhật Cache cho Saga {}. Ra lệnh XÓA KEY để tránh đọc Data cũ (Stale Data/Inconsistency).", saga.getSagaId());
            // 3. Fallback (Manual Compensation): Dẹp tụi RAM lỗi, bắt hệ thống lần sau phải đọc DB.
            redisTemplate.delete("SAGA:" + saga.getSagaId());
        }
    }

    public void flushToDb(LoanSagaEntity saga) {
        // Cập nhật trạng thái cuối cùng và dọn dẹp bộ nhớ
        log.info("💾 [END OF SAGA] Phán quyết Tòa án Saga {} đã chốt hạ ({}). Ghi nhận đòn cuối và xả rác RAM!", saga.getSagaId(), saga.getSagaStatus());
        dbRepository.save(saga);
        try {
            redisTemplate.delete("SAGA:" + saga.getSagaId());
        } catch (Exception e) {
            log.warn("🧹 Dọn rác RAM Saga {} lỗi (Có thể Redis đang sập, bỏ qua vì Saga đã kết thúc an toàn).", saga.getSagaId());
        }
    }
    
    public boolean existsInRAMOrDB(String sagaId) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey("SAGA:" + sagaId))) return true;
        } catch (Exception e) {
            log.warn("⚠️ Kiểm tra tồn tại trong RAM lỗi, fallback sang DB.");
        }
        return dbRepository.existsById(sagaId); 
    }
}
