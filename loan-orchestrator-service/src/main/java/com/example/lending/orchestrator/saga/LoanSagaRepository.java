package com.example.lending.orchestrator.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface LoanSagaRepository extends JpaRepository<LoanSagaEntity, String> {

    @Query("select s from LoanSagaEntity s where s.sagaStatus = 'RUNNING' and s.deadlineAt is not null and s.deadlineAt < :now")
    List<LoanSagaEntity> findTimedOutRunningSagas(Instant now);

    @Query("select s from LoanSagaEntity s where s.sagaStatus IN ('COMPLETED', 'FAILED') and s.updatedAt >= :since")
    List<LoanSagaEntity> findFinishedSagasSince(Instant since);
    
    // Tìm các Saga đã khởi tạo mà quá thời gian updatedAt mà vẫn không thay đổi trạng thái
    List<LoanSagaEntity> findBySagaStatusAndUpdatedAtBefore(String sagaStatus, Instant updatedAt);
}
