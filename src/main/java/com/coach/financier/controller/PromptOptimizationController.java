package com.coach.financier.controller;

import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.service.PromptOptimizationService;
import com.coach.financier.service.PromptThreadClosureService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final PromptThreadClosureService threadClosureService;

    public PromptOptimizationController(PromptOptimizationService service,
                                        PromptOptimizationProperties properties,
                                        PromptThreadClosureService threadClosureService) {
        this.service = service;
        this.properties = properties;
        this.threadClosureService = threadClosureService;
    }

    /**
     * Corps de démarrage d'une campagne.
     * <p>
     * {@code provider} = fournisseur du COACH ; {@code controllerProvider} (Agent B) et
     * {@code editorProvider} (Agent A) sont facultatifs : omis, ils reprennent le fournisseur du coach.
     * <p>
     * {@code threadId} = FIL DE CONVERSATION à poursuivre. Omis, un nouveau fil est ouvert : le cycle
     * démarre sans mémoire (comportement historique). Fourni, l'historique complet du fil est transmis au
     * Coach : c'est ce qui permet d'enchaîner les cycles comme une VRAIE conversation.
     * <p>
     * {@code fromCampaignId} + {@code fromVersion} = CHAÎNAGE : la zone de départ du cycle est celle de la
     * version RETENUE de ce cycle-là, et non celle du prompt de production (mode automatique de l'Agent C :
     * les cycles s'accumulent sans qu'aucune écriture n'ait lieu).
     */
    public record StartCampaignRequest(String agentId, String question, Integer iterations, String zoneKey,
                                       AIModels.AIProvider provider, AIModels.AIProvider controllerProvider,
                                       AIModels.AIProvider editorProvider, String threadId,
                                       String fromCampaignId, String fromVersion) {
    }

    /** Corps d'une demande de question au CLIENT simulé (Agent C). */
    public record ClientQuestionRequest(String threadId, String brief, Integer turnNumber, Integer depth,
                                        AIModels.AIProvider provider) {
    }

    /**
     * Corps d'une demande de PROJET au CLIENT simulé (Agent C) : l'agent de coach visé, le fournisseur, et
     * les briefs déjà proposés (pour qu'il en cherche un FRANCHEMENT différent au clic suivant).
     */
    public record ClientBriefRequest(String agentId, AIModels.AIProvider provider,
                                     List<String> previousBriefs) {
    }

    /** Corps de correction du contenu d'un tour de la conversation. */
    public record TurnRequest(String content) {
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

    /**
     * Démarre une campagne : validation, snapshot de référence figé, statut RUNNING (aucune itération).
     * <p>
     * La réponse porte aussi le FIL DE CONVERSATION (créé ou repris) : l'IHM affiche la conversation de
     * l'atelier et sait quelle mémoire sera transmise au cycle suivant.
     */
    @PostMapping("/campaigns")
    public Map<String, Object> start(@RequestBody StartCampaignRequest request) {
        int iterations = request.iterations() == null ? 1 : request.iterations();
        PromptOptimizationModels.Campaign campaign = service.start(new PromptOptimizationService.StartRequest(
                request.agentId(), request.question(), iterations, request.zoneKey(), request.provider(),
                request.controllerProvider(), request.editorProvider(), request.threadId(),
                request.fromCampaignId(), request.fromVersion()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("campaign", campaign);
        body.put("thread", service.threadOfCampaign(campaign.campaignId()));
        return body;
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
        body.put("thread", service.threadOfCampaign(campaignId));
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

    /**
     * PROMEUT une version en production (§17) : action humaine explicite. Le prompt actuel est sauvegardé
     * avant remplacement (retour arrière possible) et seule la zone éditable est réécrite.
     */
    @PostMapping("/campaigns/{campaignId}/promote")
    public PromptOptimizationService.PromotionResult promote(@PathVariable String campaignId,
                                                             @RequestBody VersionRequest request) {
        return service.promoteVersion(campaignId, request == null ? null : request.version());
    }

    /**
     * ACCEPTATION d'une version POUR LA CONVERSATION, sans écrire le prompt de production : c'est le mode
     * automatique de l'Agent C. La réponse de la version acceptée entre dans le fil (le client garde sa
     * mémoire), la campagne est close — mais le fichier de production reste intact. La décision d'écrire
     * reste HUMAINE, à la fin du scénario, après comparaison début ↔ fin.
     */
    @PostMapping("/campaigns/{campaignId}/accept")
    public PromptOptimizationService.PromotionResult accept(@PathVariable String campaignId,
                                                            @RequestBody VersionRequest request) {
        return service.acceptVersion(campaignId, request == null ? null : request.version());
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

    /** Comparaison version INITIALE / version FINALE (§18). */
    @GetMapping("/campaigns/{campaignId}/compare")
    public Map<String, Object> compare(@PathVariable String campaignId) {
        PromptOptimizationModels.Campaign campaign = service.campaign(campaignId);
        List<PromptOptimizationModels.Iteration> iterations = service.iterations(campaignId);
        String base = campaign.basePromptVersion();
        String current = campaign.currentCandidateVersion();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseVersion", base);
        body.put("currentVersion", current);
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

    // --- Fils de conversation (mémoire de l'atelier) --------------------------------------------------

    /** Fils connus, du plus récemment modifié au plus ancien (l'IHM reprend la conversation en cours). */
    @GetMapping("/threads")
    public List<PromptOptimizationModels.ConversationThread> threads() {
        return service.threads();
    }

    /** Conversation complète d'un fil : les tours validés par promotion, dans l'ordre chronologique. */
    @GetMapping("/threads/{threadId}")
    public PromptOptimizationModels.ConversationThread thread(@PathVariable String threadId) {
        return service.thread(threadId);
    }

    /** Corrige le contenu d'un tour : l'humain garde la main sur ce qui est rejoué au cycle suivant. */
    @PutMapping("/threads/{threadId}/turns/{turnIndex}")
    public PromptOptimizationModels.ConversationThread updateTurn(@PathVariable String threadId,
                                                                 @PathVariable int turnIndex,
                                                                 @RequestBody TurnRequest request) {
        return service.updateTurn(threadId, turnIndex, request == null ? null : request.content());
    }

    /**
     * Comparaison DÉBUT ↔ FIN de la conversation : le prompt du premier cycle face au prompt en vigueur à la
     * fin (dernière version promue). Le bilan de tout le scénario, cycle après cycle — là où la comparaison
     * d'une campagne ne montre qu'une question.
     */
    @GetMapping("/threads/{threadId}/comparison")
    public PromptOptimizationModels.ConversationComparison threadComparison(@PathVariable String threadId) {
        return service.comparisonOfThread(threadId);
    }

    /**
     * Corps de la CLÔTURE d'un scénario d'atelier.
     *
     * @param sendMail {@code true} = envoie le mail au conseiller, exactement comme « Terminer la
     *                 conversation » dans la page coach (dossier préparé par l'agent de suivi, score de sens
     *                 commercial, liens, brouillon client en pièce jointe). Défaut : {@code false}
     *                 (rien n'est envoyé).
     * @param archive  {@code true} = écrit le dossier de suivi, donc la conversation devient consultable dans
     *                 la page « Centre d'appels ». Défaut : {@code false} (un test d'atelier n'encombre pas
     *                 l'annuaire).
     */
    public record ThreadCloseRequest(Boolean sendMail, Boolean archive, AIModels.AIProvider provider) {
    }

    /**
     * CLÔTURE du scénario : le fil de l'atelier est rejoué comme une conversation de chat et passe dans le
     * MÊME pipeline que la page coach (agent de suivi → dossier → mail conseiller → annuaire du centre
     * d'appels). Aucune écriture de prompt n'a lieu, et rien n'est envoyé au client.
     */
    @PostMapping("/threads/{threadId}/close")
    public PromptThreadClosureService.ThreadClosure closeThread(@PathVariable String threadId,
                                                                @RequestBody(required = false) ThreadCloseRequest request) {
        ThreadCloseRequest body = request == null ? new ThreadCloseRequest(null, null, null) : request;
        return threadClosureService.close(threadId, Boolean.TRUE.equals(body.sendMail()),
                Boolean.TRUE.equals(body.archive()), body.provider());
    }

    // --- Agent C : le CLIENT simulé (il mène la conversation) ----------------------------------------

    /**
     * Demande au CLIENT simulé (« Agent C ») la question qu'il pose au Coach : il joue le client à partir du
     * brief écrit par l'humain, des trois chiffres du dossier (compte courant, épargne, mensualité de crédit)
     * et de la conversation déjà échangée (mémoire du fil). Il peut aussi clore le scénario.
     */
    @PostMapping("/client/question")
    public PromptOptimizationModels.ClientTurn clientQuestion(@RequestBody ClientQuestionRequest request) {
        return service.clientTurn(request.threadId(), request.brief(),
                request.turnNumber() == null ? 1 : request.turnNumber(),
                request.depth() == null ? 1 : request.depth(), request.provider());
    }

    /**
     * PROJET inventé par l'Agent C pour le champ « Brief du client » (bouton « Générer projet ») : le client
     * et la raison de sa visite sont proposés dans le périmètre de l'agent sélectionné. Chaque appui doit
     * donner un projet différent — l'IHM transmet donc ceux déjà proposés. Rien n'est écrit : le brief proposé
     * reste modifiable par l'humain avant de lancer le scénario.
     */
    @PostMapping("/client/brief")
    public PromptOptimizationModels.ClientBrief clientBrief(@RequestBody ClientBriefRequest request) {
        return service.clientBrief(request.agentId(), request.previousBriefs(), request.provider());
    }

    // --- Aides ----------------------------------------------------------------------------------------

    private List<Map<String, Object>> versionViewsInternal(String campaignId) {
        PromptOptimizationModels.Campaign campaign = service.campaign(campaignId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (PromptOptimizationModels.PromptVersion version : service.versions(campaignId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("version", version.version());
            view.put("editableSection", version.editableSection());
            view.put("promptHash", version.promptHash());
            view.put("iterationNumber", version.iterationNumber());
            view.put("promoted", version.version().equals(campaign.promotedVersion()));
            // Une version ACCEPTÉE mais non écrite (mode automatique de l'Agent C) reste PROMOUVABLE : c'est
            // `appliedInProduction` qui décide si le bouton « Promouvoir » a encore un sens.
            boolean applied = service.appliedInProduction(campaignId, version.version());
            view.put("applied", applied);
            // « production » = version de RÉFÉRENCE du cycle ET rien de promu ET c'est bien la zone du fichier.
            // Le chaînage des cycles (mode automatique) hérite la zone d'un cycle précédent : sa V0 n'est donc
            // PAS le prompt de production — elle est seulement la référence de CE cycle (« référence » à l'écran).
            view.put("production", version.version().equals(campaign.basePromptVersion())
                    && campaign.promotedVersion().isEmpty() && applied);
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
        // Origine de la zone de départ : vide = prompt de production, sinon « <campagne>:<version> » (chaînage
        // des cycles du mode automatique — la zone testée n'est alors PAS celle de la production).
        view.put("baseZoneSource", snapshot.baseZoneSource());
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
