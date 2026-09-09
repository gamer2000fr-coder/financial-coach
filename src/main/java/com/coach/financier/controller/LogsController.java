package com.coach.financier.controller;

import com.coach.financier.model.LogEntry;
import com.coach.financier.service.AILogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogsController {
    private final AILogService aiLogService;

    public LogsController(AILogService aiLogService) {
        this.aiLogService = aiLogService;
    }

    /** Traces des appels IA, pour la page Logs (polling). Le prompt n'est pas inclus (récupéré à la demande). */
    @GetMapping
    public List<LogEntry> logs() {
        return aiLogService.latest();
    }

    /** Prompt (système + payload sans données jointes) envoyé à l'IA pour une trace donnée. */
    @GetMapping("/{id}/prompt")
    public Map<String, Object> prompt(@PathVariable long id) {
        String prompt = aiLogService.promptOf(id);
        if (prompt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace inconnue : " + id);
        }
        return Map.of("id", id, "prompt", prompt);
    }

    /** Réponse de l'IA pour une trace donnée. */
    @GetMapping("/{id}/answer")
    public Map<String, Object> answer(@PathVariable long id) {
        String answer = aiLogService.answerOf(id);
        if (answer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace inconnue : " + id);
        }
        return Map.of("id", id, "answer", answer);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("total", aiLogService.latest().size());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearLogs() {
        aiLogService.clear();
    }
}
