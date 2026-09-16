package com.coach.financier.controller;

import com.coach.financier.service.AgentPromptStore;
import com.coach.financier.service.PromptZoneService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Page « Agents » du frontend : liste des agents éditables (générique, agent principal,
 * agents spécialisés) + lecture/écriture du prompt de chaque agent. Le contenu est relu à
 * chaque appel IA : une sauvegarde est prise en compte immédiatement, sans redémarrage.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentPromptController {
    private final AgentPromptStore store;
    private final PromptZoneService zoneService;

    public AgentPromptController(AgentPromptStore store, PromptZoneService zoneService) {
        this.store = store;
        this.zoneService = zoneService;
    }

    /** Liste déroulante : [{key, libelle, file}, ...]. */
    @GetMapping
    public List<Map<String, String>> list() {
        return store.entries();
    }

    /** Prompt d'un agent : {key, content}. 404 si l'agent n'existe pas. */
    @GetMapping("/{key}/prompt")
    public Map<String, Object> get(@PathVariable String key) {
        String content = store.read(key);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent inconnu : " + key);
        }
        return Map.of("key", key, "content", content);
    }

    /**
     * Sauvegarde le prompt d'un agent : {key, content}.
     * <p>
     * Refus 400 si les délimiteurs de zone sont incohérents : un prompt à demi marqué (un seul
     * {@code [[[} ou {@code ]]]}) rendrait l'agent inutilisable et n'est jamais intentionnel.
     */
    @PutMapping("/{key}/prompt")
    public Map<String, Object> save(@PathVariable String key, @RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "");
        String markerError = zoneService.validateMarkerPair(content);
        if (markerError != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, markerError);
        }
        store.write(key, content);
        return Map.of("key", key, "content", store.read(key));
    }
}
