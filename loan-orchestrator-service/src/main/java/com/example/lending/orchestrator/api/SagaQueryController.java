package com.example.lending.orchestrator.api;

import com.example.lending.orchestrator.saga.LoanSagaEntity;
import com.example.lending.orchestrator.saga.LoanSagaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sagas")
@RequiredArgsConstructor
public class SagaQueryController {
    private final com.example.lending.orchestrator.saga.SagaCacheService cacheService;

    @GetMapping("/{sagaId}")
    @io.github.resilience4j.ratelimiter.annotation.RateLimiter(name = "querySagaApi")
    public LoanSagaEntity get(@PathVariable String sagaId) {
        return cacheService.getSaga(sagaId).orElseThrow();
    }
}
