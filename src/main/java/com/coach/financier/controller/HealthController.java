package com.coach.financier.controller;

import com.coach.financier.ai.AIServiceFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final AIServiceFactory aiServiceFactory;

    public HealthController(AIServiceFactory aiServiceFactory) {
        this.aiServiceFactory = aiServiceFactory;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "defaultAiProvider", aiServiceFactory.defaultProvider());
    }
}
