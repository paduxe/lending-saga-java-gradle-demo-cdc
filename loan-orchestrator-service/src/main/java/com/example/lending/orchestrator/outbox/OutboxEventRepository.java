package com.example.lending.orchestrator.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {
    List<OutboxEventEntity> findTop20ByStatusOrderByCreatedAtAscIdAsc(String status);
}
