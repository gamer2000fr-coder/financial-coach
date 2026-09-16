package com.coach.financier.controller;

import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.service.PromptOptimizationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API de l'ATELIER d'amélioration itérative des prompts (IHM {@code #/prompt-lab}).
 * <p>
 * La boucle est pilotée par l'IHM : une requête = UNE itération complète (Coach → contrôleur → éditeur),
 * ce qui rend l'arrêt gracieux naturel (la réponse en cours est toujours sauvegardée) et évite tout
 * traitement concurrent côté serveur.
 * <p>
 * Aucune de ces opérations ne modifie le prompt de production, sauf {@code /promote} qui exige une
 * action humaine explicite et sauvegarde le prompt remplacé.
 */
@RestController
@RequestMapping("/api/prompt-optimization")
public class PromptOptimizationController {

    private final PromptOptimizationService service;
    private final PromptOptimizationProperties properties;

    public PromptOptimizationController(PromptOptimizationService service,
                                        PromptOptimizationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * Corps de démarrage d'une campagne.
     * <p>
     * {@code provider} = fournisseur du COACH ; {@code controllerProvider} (Agent B) et
     * {@code editorProvider} (Agent A) sont facultatifs : omis, ils reprennent le fournisseur du coach.
     */
    public record StartCampaignRequest(String agentId, String question, Integer iterations, String zoneKey,
                                       AIModels.AIProvider provider, AIModels.AIProvider controllerProvider,
                                       AIModels.AIProvider editorProvider) {
    }

    /** Corps portant une version de prompt. */
    public record VersionRequest(String version) {
    }

    /** Corps d'un avis humain. */
    public record HumanFeedbackRequest(String content) {
    }

    /** Corps d'une reprise (nombre d'itérations supplémentaires, facultatif). */
    public record ResumeRequest(Integer additionalIterations) {
    }

    // --- Configuration -----------------------------------------------------------------------------

    /** Agents et zones optimisables (sélecteur) + plafond d'itérations + état du module. */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.isEnabled());
        body.put("demoMode", properties.isDemoMode());
        body.put("maxIterations", properties.maxIterations());
        body.put("hardMaxIterations", PromptOptimizationModels.MAX_ITERATIONS);
        body.put("zones", service.zones());
        body.put("labels", PromptOptimizationModels.labels());
        return body;
    }

    // --- Campagnes ---------------------------------------------------------------------------------

    /** Campagnes connues, la plus récemment modifiée d'abord (reprise après rechargement de l'IHM). */
    @GetMapping("/campaigns")
    public List<PromptOptimizationModels.Campaign> campaigns() {
        return service.campaigns();
    }

    /** Démarre une campagne : validation, snapshot de référence figé, statut RUNNING (aucune itération). */
    @PostMapping("/campaigns")
    public PromptOptimizationModels.Campaign start(@RequestBody StartCampaignRequest request) {
        int iterations = request.iterations() == null ? 1 : request.iterations();
        return service.start(new PromptOptimizationService.StartRequest(request.agentId(), request.question(),
                iterations, request.zoneKey(), request.provider(), request.controllerProvider(),
                request.editorProvider()));
    }

    /**
     * Vue COMPLÈTE d'une campagne en un seul appel : état, snapshot de référence, itérations, versions et
     * avis humains. L'IHM n'a donc pas à orchestrer plusieurs requêtes.
     */
    @GetMapping("/campaigns/{campaignId}")
    public Map<String, Object> detail(@PathVariable String campaignId) {
        PromptOptimizationModels.Campaign campaign = service.campaign(campaignId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("campaign", campaign);
        body.put("snapshot", snapshotView(campaignId));
        body.put("iterations", service.iterations(campaignId));
        body.put("versions", versionViews(campaignId));
        body.put("feedbacks", service.feedbacks(campaignId));
        return body;
    }

    /** Une itération complète : Coach (prompt figé) → contrôleur → éditeur → validation → persistance. */
    @PostMapping("/campaigns/{campaignId}/iterate")
    public PromptOptimizationModels.Iteration iterate(@PathVariable String campaignId) {
        return service.iterate(campaignId);
    }

    /** Arrêt gracieux : l'appel en cours se termine, la réponse est sauvegardée, puis la campagne passe en pause. */
    @PostMapping("/campaigns/{campaignId}/stop")
    public PromptOptimizationModels.Campaign stop(@PathVariable String campaignId) {
        return service.stop(campaignId);
    }

    /** Reprise : prolonge éventuellement le cycle et applique l'avis humain en attente AVANT le prochain Coach. */
    @PostMapping("/campaigns/{campaignId}/resume")
    public PromptOptimizationModels.Campaign resume(@PathVariable String campaignId,
                                                   @RequestBody(required = false) ResumeRequest request) {
        int additional = request == null || request.additionalIterations() == null
                ? 0 : request.additionalIterations();
        return service.resume(campaignId, additional);
    }

    /** Avis humain : toujours prioritaire sur le diagnostic du contrôleur, jamais perdu après reprise. */
    @PostMapping("/campaigns/{campaignId}/feedback")
    public PromptOptimizationModels.HumanFeedback feedback(@PathVariable String campaignId,
                                                          @RequestBody HumanFeedbackRequest request) {
        return service.addHumanFeedback(campaignId, request == null ? null : request.content());
    }

    /** Retient une version (sans la promouvoir) : plusieurs versions peuvent être retenues. */
    @PostMapping("/campaigns/{campaignId}/retain")
    public PromptOptimizationModels.Campaign retain(@PathVariable String campaignId,
                                                    @RequestBody VersionRequest request) {
        return service.retain(campaignId, request == null ? null : request.version());
    }

    /**
     * Retire une version de la sélection (§16) : « retenir » n'affecte JAMAIS la production et reste
     * réversible (la version demeure consultable).
     */
    @PostMapping("/campaigns/{campaignId}/unretain")
    public PromptOptimizationModels.Campaign unretain(@PathVariable String campaignId,
                                                      @RequestBody VersionRequest request) {
        return service.unretain(campaignId, request == null ? null : request.version());
    }

    /**
     * PROMEUT une version en production (§17) : action humaine explicite. Le prompt actuel est sauvegardé
     * avant remplacement (retour arrière possible) et seule la zone éditable est réécrite.
     */
    @PostMapping("/campaigns/{campaignId}/promote")
    public PromptOptimizationService.PromotionResult promote(@PathVariable String campaignId,
                                                             @RequestBody VersionRequest request) {
        return service.promoteVersion(campaignId, request == null ? null : request.version());
    }

    /** Refuse la campagne : rien n'est supprimé, aucune version n'est promue. */
    @PostMapping("/campaigns/{campaignId}/reject")
    public PromptOptimizationModels.Campaign reject(@PathVariable String campaignId) {
        return service.reject(campaignId);
    }

    // --- Lectures (IHM) ------------------------------------------------------------------------------

    /** Prompt système COMPLET de chaque version (parties protégées repliées côté IHM). */
    @GetMapping("/campaigns/{campaignId}/versions")
    public List<Map<String, Object>> versionViews(@PathVariable String campaignId) {
        return versionViewsInternal(campaignId);
    }

    /** Comparaison version INITIALE / version FINALE + versions retenues (§18). */
    @GetMapping("/campaigns/{campaignId}/compare")
    public Map<String, Object> compare(@PathVariable String campaignId) {
        PromptOptimizationModels.Campaign campaign = service.campaign(campaignId);
        List<PromptOptimizationModels.Iteration> iterations = service.iterations(campaignId);
        String base = campaign.basePromptVersion();
        String current = campaign.currentCandidateVersion();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseVersion", base);
        body.put("currentVersion", current);
        body.put("retainedVersions", campaign.retainedVersions());
        body.put("baseEditableSection", sectionOf(campaignId, base));
        body.put("currentEditableSection", sectionOf(campaignId, current));
        body.put("basePrompt", service.promptFor(campaignId, base));
        body.put("currentPrompt", service.promptFor(campaignId, current));
        body.put("baseResponse", iterations.isEmpty() ? "" : iterations.get(0).coachResponse());
        body.put("currentResponse", iterations.isEmpty() ? "" : iterations.get(iterations.size() - 1).coachResponse());
        body.put("iterationCount", iterations.size());
        return body;
    }

    /** Nombre de caractères / appels IA par itération, utile pour l'affichage discret des coûts (§34). */
    @GetMapping("/campaigns/{campaignId}/usage")
    public Map<String, Object> usage(@PathVariable String campaignId) {
        PromptOptimizationModels.Campaign campaign = service.campaign(campaignId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completedIterations", campaign.completedIterations());
        body.put("aiCalls", campaign.aiCalls());
        body.put("promptChars", campaign.totalPromptChars());
        body.put("durationMs", campaign.totalDurationMs());
        return body;
    }

    // --- Aides ----------------------------------------------------------------------------------------

    private List<Map<String, Object>> versionViewsInternal(String campaignId) {
        PromptOptimizationModels.Campaign campaign = service.campaign(campaignId);
        List<String> retained = campaign.retainedVersions();
        List<Map<String, Object>> views = new ArrayList<>();
        for (PromptOptimizationModels.PromptVersion version : service.versions(campaignId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("version", version.version());
            view.put("editableSection", version.editableSection());
            view.put("promptHash", version.promptHash());
            view.put("iterationNumber", version.iterationNumber());
            view.put("retained", retained.contains(version.version()));
            view.put("promoted", version.version().equals(campaign.promotedVersion()));
            view.put("production", version.version().equals(campaign.basePromptVersion())
                    && campaign.promotedVersion().isEmpty());
            view.put("prompt", service.promptFor(campaignId, version.version()));
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> snapshotView(String campaignId) {
        PromptOptimizationModels.Snapshot snapshot = service.snapshot(campaignId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("snapshotId", snapshot.snapshotId());
        view.put("createdAt", snapshot.createdAt());
        view.put("question", snapshot.question());
        view.put("agentTheme", snapshot.agentTheme());
        view.put("agentLibelle", snapshot.agentLibelle());
        view.put("zoneKey", snapshot.zoneKey());
        view.put("zoneFile", snapshot.zoneFile());
        view.put("promptVersion", snapshot.promptVersion());
        view.put("promptHash", snapshot.promptHash());
        view.put("snapshotHash", snapshot.snapshotHash());
        view.put("provider", snapshot.provider());
        view.put("frozenData", snapshot.providedData().stream()
                .map(entry -> String.valueOf(entry.get("description"))).toList());
        view.put("catalogSize", snapshot.catalog() instanceof List<?> list ? list.size() : 0);
        view.put("debug", snapshot.debug());
        view.put("fixedPrefix", snapshot.fixedPrefix());
        view.put("initialEditableSection", snapshot.initialEditableSection());
        view.put("fixedSuffix", snapshot.fixedSuffix());
        return view;
    }

    private String sectionOf(String campaignId, String version) {
        for (PromptOptimizationModels.PromptVersion candidate : service.versions(campaignId)) {
            if (candidate.version().equals(version)) {
                return candidate.editableSection();
            }
        }
        return "";
    }

    // --- Erreurs : messages EXPLOITABLES par l'IHM ------------------------------------------------------

    /** Demande invalide (itération hors bornes, question vide, version inconnue, mode démo…). */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        return Map.of("error", "BAD_REQUEST", "message", e.getMessage());
    }

    /** Conflit d'état (campagne en cours, pause, clôturée, fournisseur indisponible…). */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> conflict(IllegalStateException e) {
        return Map.of("error", "CONFLICT", "message", e.getMessage());
    }
}
