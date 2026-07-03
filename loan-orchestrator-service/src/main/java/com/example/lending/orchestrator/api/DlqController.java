package com.example.lending.orchestrator.api;

import com.example.lending.orchestrator.messaging.DlqMessageEntity;
import com.example.lending.orchestrator.messaging.DlqMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dlq")
@RequiredArgsConstructor
public class DlqController {

    private final DlqMessageRepository dlqMessageRepository;

    @GetMapping
    public List<DlqMessageEntity> list(@RequestParam(defaultValue = "20") int limit) {
        return dlqMessageRepository.findAll(PageRequest.of(0, limit)).getContent();
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<DlqMessageEntity> replay(@PathVariable String id) {
        return dlqMessageRepository.findById(id)
                .map(entity -> {
                    entity.setStatus("REPLAYED");
                    return ResponseEntity.ok(dlqMessageRepository.save(entity));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
