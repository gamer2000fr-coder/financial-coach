package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.AgentFiles;
import com.coach.financier.ai.MockAIService;
import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.AgentDefinition;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.PromptOptimizationModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ATELIER d'amélioration itérative des prompts : orchestration et machine d'état.
 * <p>
 * Boucle d'une itération — TOUS les appels sont contraints par le snapshot de référence :
 * <ol>
 *   <li><b>Coach</b> avec le prompt FIGÉ de la version courante ({@code answerWithSystemPrompt}) ;</li>
 *   <li><b>Agent B</b> (contrôleur) : diagnostic de la réponse ;</li>
 *   <li><b>Agent A</b> (éditeur) : nouvelle zone éditable, l'avis HUMAIN étant prioritaire ;</li>
 *   <li><b>Validation BACKEND</b> de la zone proposée (délimiteurs interdits, longueur) : une zone
 *   invalide est REJETÉE, la version reste inchangée ;</li>
 *   <li>persistance de l'itération puis mise à jour de la campagne (statut, compteurs, version).</li>
 * </ol>
 * Garanties : la question, le contexte, la classification et les parties protégées du prompt sont FIGÉS ;
 * la boucle {@code NEED_DATA} est DÉSACTIVÉE (elle relirait le disque et casserait la reproductibilité) ;
 * l'exécution est pilotée par l'appelant (une requête = une itération), ce qui rend le STOP gracieux
 * naturel : la réponse en cours est toujours sauvegardée avant la pause (§11) ; aucune écriture dans les
 * conversations du chat ; la promotion en production reste une ACTION HUMAINE explicite (§17).
 */
@Service
public class PromptOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(PromptOptimizationService.class);

    /** Étapes pouvant échouer, exposées à l'IHM (§35). */
    public static final String STEP_START = "START";
    public static final String STEP_COACH = "COACH";
    public static final String STEP_CONTROLLER = "CONTROLLER";
    public static final String STEP_EDITOR = "EDITOR";
    public static final String STEP_PROMOTION = "PROMOTION";

    /**
     * Message d'erreur quand le Coach réclame des données que le catalogue ne permet PAS de fournir :
     * l'atelier ne charge que les fichiers autorisés (aucune donnée inventée) et ne boucle pas
     * indéfiniment (même limite qu'en production).
     */
    public static final String NEED_DATA_MESSAGE = "Le Coach demande des données supplémentaires (NEED_DATA) que "
            + "le catalogue ne permet pas de fournir (fichiers autorisés uniquement, aucun inventé).";

    /** Nombre maximal de complétions du contexte pour UNE itération (même limite que la production). */
    public static final int MAX_CONTEXT_COMPLETIONS = 3;

    /** Trace de l'atelier dans la page Logs. */
    public static final String LOG_AGENT_PREFIX = "Atelier prompts — ";

    private final PromptOptimizationProperties properties;
    private final PromptOptimizationStore store;
    private final PromptZoneService zoneService;
    private final CoachContextBuilder contextBuilder;
    private final AIServiceFactory aiServiceFactory;
    private final AILogService aiLogService;
    private final AgentPromptStore agentPromptStore;
    private final AgentPromptHistoryStore historyStore;
    private final DataRequestService dataRequestService;
    private final ObjectMapper objectMapper;

    public PromptOptimizationService(PromptOptimizationProperties properties, PromptOptimizationStore store,
                                     PromptZoneService zoneService, CoachContextBuilder contextBuilder,
                                     AIServiceFactory aiServiceFactory, AILogService aiLogService,
                                     AgentPromptStore agentPromptStore, AgentPromptHistoryStore historyStore,
                                     DataRequestService dataRequestService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.zoneService = zoneService;
        this.contextBuilder = contextBuilder;
        this.aiServiceFactory = aiServiceFactory;
        this.aiLogService = aiLogService;
        this.agentPromptStore = agentPromptStore;
        this.historyStore = historyStore;
        this.dataRequestService = dataRequestService;
        this.objectMapper = objectMapper;
    }

    /**
     * Demande de démarrage d'une campagne. Les TROIS étapes peuvent utiliser des fournisseurs DIFFÉRENTS
     * (coach, contrôleur, éditeur) : un modèle peut être meilleur pour rédiger la réponse client, un autre
     * pour contrôler, un autre pour réécrire un prompt.
     *
     * @param provider           fournisseur du COACH (celui vu par le client)
     * @param controllerProvider fournisseur de l'Agent B (contrôleur) — {@code null} ⇒ celui du coach
     * @param editorProvider     fournisseur de l'Agent A (éditeur) — {@code null} ⇒ celui du coach
     */
    public record StartRequest(String agentId, String question, int iterations, String zoneKey,
                              AIModels.AIProvider provider, AIModels.AIProvider controllerProvider,
                              AIModels.AIProvider editorProvider) {

        /** Raccourci historique : les trois étapes utilisent le même fournisseur. */
        public StartRequest(String agentId, String question, int iterations, String zoneKey,
                            AIModels.AIProvider provider) {
            this(agentId, question, iterations, zoneKey, provider, provider, provider);
        }
    }

    /** Un fournisseur MANQUANT retombe sur le fournisseur du coach (rétro-compatibilité, IHM). */
    private static AIModels.AIProvider orCoach(AIModels.AIProvider candidate, AIModels.AIProvider coach) {
        return candidate == null ? coach : candidate;
    }

    /** Les trois fournisseurs doivent être RÉELS : le mode démo ne peut ni contrôler ni réécrire un prompt. */
    private static AIModels.AIProvider requireRealProvider(AIModels.AIProvider provider, String role) {
        if (provider == null || provider == AIModels.AIProvider.MOCK) {
            throw new IllegalArgumentException("Fournisseur IA du " + role + " : "
                    + MockAIService.NO_REAL_PROVIDER_MESSAGE);
        }
        return provider;
    }

    /** Résultat d'une promotion : campagne mise à jour + sauvegarde du prompt remplacé (§17). */
    public record PromotionResult(PromptOptimizationModels.Campaign campaign, String backupId, String backupFile,
                                  String message) {
    }

    /** Zone optimisable détectée pour un agent (affichée à l'IHM avant démarrage). */
    public record ZoneInfo(String agentId, String agentLibelle, String zoneKey, String zoneFile,
                           boolean optimizable, String editableSection, String error) {
    }

    // --- Démarrage -------------------------------------------------------------------------------

    /**
     * Crée une campagne : valide la demande, GÈLE le snapshot de référence, puis passe en {@code RUNNING}.
     * Aucune itération n'est lancée ici (l'appelant pilote la boucle).
     */
    public PromptOptimizationModels.Campaign start(StartRequest request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("L'atelier d'optimisation des prompts est désactivé.");
        }
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("La question de test est obligatoire.");
        }
        if (request.iterations() < 1 || request.iterations() > properties.maxIterations()) {
            throw new IllegalArgumentException("Nombre d'itérations invalide : " + request.iterations()
                    + " (attendu entre 1 et " + properties.maxIterations() + ").");
        }
        // Un seul fournisseur peut être choisi pour les trois étapes, ou un fournisseur PAR étape.
        AIModels.AIProvider provider = requireRealProvider(request.provider(), "coach");
        AIModels.AIProvider controllerProvider = requireRealProvider(
                orCoach(request.controllerProvider(), request.provider()), "Agent B (contrôleur)");
        AIModels.AIProvider editorProvider = requireRealProvider(
                orCoach(request.editorProvider(), request.provider()), "Agent A (éditeur)");
        AgentDefinition agent = resolveAgent(request.agentId());
        ZoneInfo zoneInfo = describeZone(agent, request.zoneKey());
        if (!zoneInfo.optimizable()) {
            throw new IllegalArgumentException(zoneInfo.error());
        }
        String campaignId = nextCampaignId();
        // La REPRISE d'une campagne n'est pas proposée dans l'IHM : une nouvelle campagne clôt donc les
        // précédentes (statut CANCELLED) pour ne jamais bloquer sur « une campagne est déjà en cours ».
        // Les fichiers restent sur disque (rien n'est supprimé), mais ils ne sont plus utilisés.
        closePreviousCampaigns(campaignId);

        AIService ai = aiServiceFactory.get(provider);
        // La classification est calculée UNE fois puis GELÉE dans le snapshot : c'est un appel IA comptabilisé.
        IntentClassification classification = ai.classifyIntent(question, "", provider);
        if (classification.isOutOfScope()) {
            throw new IllegalArgumentException("La question de test est hors périmètre du coach financier.");
        }
        CurrentProject project = new CurrentProject();
        project.apply(classification);

        CoachContext context = contextBuilder.build(question, classification, project, List.of(), agent.getTheme());
        String template = AgentFiles.readPromptOrDefault("generic.txt", "");
        String principal = AgentFiles.readPromptOrDefault(AgentFiles.PRINCIPAL_PROMPT_FILE, "");
        PromptZoneService.Zone zone = zoneService.parse(AgentFiles.readPromptOrDefault(zoneInfo.zoneFile(), ""));
        String frozenPrompt = AgentFiles.composeSystemPrompt(template, principal,
                zone.prefix() + zone.editableSection() + zone.suffix());

        String providerName = provider.name();
        String snapshotId = "snap-" + campaignId.substring(campaignId.length() - 6);
        PromptOptimizationModels.Snapshot snapshot = new PromptOptimizationModels.Snapshot(snapshotId, campaignId,
                question, agent.getTheme(), AgentFiles.libelleFor(agent.getTheme()),
                zoneInfo.zoneKey(), zoneInfo.zoneFile(), PromptOptimizationModels.versionName(0),
                zone.prefix(), zone.editableSection(), zone.suffix(),
                frozenPrompt, template, principal,
                classification, context.financialSummary(), context.catalog(),
                context.allowedCatalogPaths() == null ? List.of() : List.copyOf(context.allowedCatalogPaths()),
                context.additionalData(), context.history(), context.debug(),
                providerName, "", zoneService.hash(frozenPrompt),
                snapshotHash(question, frozenPrompt, context, providerName), Instant.now().toString());

        PromptOptimizationModels.Campaign campaign = new PromptOptimizationModels.Campaign(campaignId,
                agent.getTheme(), AgentFiles.libelleFor(agent.getTheme()), zoneInfo.zoneKey(), zoneInfo.zoneFile(),
                PromptOptimizationModels.CAMPAIGN_CREATED, question, request.iterations(), 0, properties.maxIterations(),
                snapshotId, PromptOptimizationModels.versionName(0), PromptOptimizationModels.versionName(0),
                "", providerName, controllerProvider.name(), editorProvider.name(), "", 1, 0L, 0L,
                "", "", "", "",
                Instant.now().toString(), Instant.now().toString());
        store.create(campaign, snapshot);
        PromptOptimizationModels.Campaign running = store.saveCampaignAndReturn(update(campaign,
                PromptOptimizationModels.CAMPAIGN_RUNNING, campaign.completedIterations(),
                campaign.currentCandidateVersion(), campaign.aiCalls(), 0L, 0L,
                "", "", "", "", null));
        log.info("Campagne {} démarrée : agent={}, zone={}, itérations demandées={}, fournisseurs={} (coach) / {} (Agent B) / {} (Agent A)",
                campaignId, agent.getTheme(), zoneInfo.zoneFile(), request.iterations(), provider,
                controllerProvider.name(), editorProvider.name());
        return running;
    }

    // --- Itération -------------------------------------------------------------------------------

    /** Exécute UNE itération complète (Coach → contrôleur → éditeur → validation → persistance). */
    public PromptOptimizationModels.Iteration iterate(String campaignId) {
        return store.withCampaignLock(campaignId, () -> runIteration(campaignId));
    }

    private PromptOptimizationModels.Iteration runIteration(String campaignId) {
        PromptOptimizationModels.Campaign campaign = store.require(campaignId);
        if (PromptOptimizationModels.campaignDecided(campaign.status())) {
            throw new IllegalStateException("Campagne clôturée ("
                    + PromptOptimizationModels.campaignStatusLabel(campaign.status()) + ") : créez une nouvelle campagne.");
        }
        if (PromptOptimizationModels.campaignPaused(campaign.status())) {
            throw new IllegalStateException("Campagne en pause : reprenez-la avant de lancer une itération.");
        }
        if (!campaign.canIterate()) {
            throw new IllegalStateException("Aucune itération possible : " + (campaign.maxIterationsReached()
                    ? "le plafond de " + campaign.maxIterations() + " itérations cumulées est atteint"
                    : "le cycle demandé est terminé")
                    + " (ajoutez un avis et continuez, ou créez une nouvelle campagne).");
        }
        PromptOptimizationModels.Snapshot snapshot = store.snapshot(campaignId).orElseThrow(
                () -> new IllegalStateException("Snapshot introuvable pour la campagne " + campaignId));
        AIModels.AIProvider provider = coachProviderOf(campaign);
        AIModels.AIProvider controllerProvider = providerOf(campaign.controllerProvider(), provider);
        AIModels.AIProvider editorProvider = providerOf(campaign.editorProvider(), provider);
        AIService coachAi = aiServiceFactory.get(provider);
        AIService controllerAi = aiServiceFactory.get(controllerProvider);
        AIService editorAi = aiServiceFactory.get(editorProvider);
        PromptOptimizationModels.Iteration previous = store.latestIteration(campaignId).orElse(null);

        int number = campaign.completedIterations() + 1;
        String version = campaign.currentCandidateVersion();
        String section = store.editableSectionOf(campaignId, version).orElseThrow(
                () -> new IllegalStateException("Zone éditable de la version " + version + " introuvable"));
        String composedPrompt = composePrompt(snapshot, section);
        AIModels.Classification legacy = legacyOf(snapshot.classification());
        long promptChars = coachPromptChars(snapshot, composedPrompt, legacy);
        String startedAt = Instant.now().toString();
        long began = System.currentTimeMillis();

        PromptOptimizationModels.ControllerFeedback feedback = null;
        PromptOptimizationModels.EditorResult edition = null;
        String coachResponse = "";
        String resultingVersion = version;
        String resultingSection = section;
        List<String> changeSummary = List.of();
        boolean noChange = true;
        boolean humanApplied = false;
        List<String> contextAddedData = new ArrayList<>();
        String status = PromptOptimizationModels.ITERATION_COMPLETED;
        String error = "";
        String step = STEP_COACH;
        int aiCalls = 0;

        try {
            // 1) COACH : prompt FIGÉ de la version courante (aucune lecture du prompt courant du disque).
            AIModels.AIAnswer answer = coachAi.answerWithSystemPrompt(composedPrompt, snapshot.question(), legacy,
                    snapshot.financialSummary(), snapshot.catalog(), AIModels.BankingContextMode.SYNTHESIS_AVAILABLE,
                    snapshot.additionalData(), snapshot.history(), provider);
            aiCalls++;
            // NEED_DATA n'est PAS une réponse destinée au client : on se comporte comme en PRODUCTION —
            // on complète les données figées (fichiers autorisés du catalogue) et on rejoue l'appel.
            // L'Agent B n'intervient qu'après la réponse CLIENT (jamais sur une demande de données).
            int completions = 0;
            while (answer.status() == AIModels.AIStatus.NEED_DATA) {
                if (completions >= MAX_CONTEXT_COMPLETIONS) {
                    throw new IllegalStateException(NEED_DATA_MESSAGE + requestedPaths(answer));
                }
                completions++;
                int providedBefore = snapshot.providedData().size();
                PromptOptimizationModels.Snapshot completed = completeContext(campaign, snapshot, answer);
                if (completed == null) {
                    throw new IllegalStateException(NEED_DATA_MESSAGE + requestedPaths(answer));
                }
                snapshot = completed;
                for (int i = providedBefore; i < snapshot.providedData().size(); i++) {
                    contextAddedData.add(String.valueOf(snapshot.providedData().get(i).get("description")));
                }
                legacy = legacyOf(snapshot.classification());
                composedPrompt = composePrompt(snapshot, section);
                promptChars = coachPromptChars(snapshot, composedPrompt, legacy);
                answer = coachAi.answerWithSystemPrompt(composedPrompt, snapshot.question(), legacy,
                        snapshot.financialSummary(), snapshot.catalog(), AIModels.BankingContextMode.SYNTHESIS_AVAILABLE,
                        snapshot.additionalData(), snapshot.history(), provider);
                aiCalls++;
            }
            coachResponse = answer.answer() == null ? "" : answer.answer();

            // 2) CONTRÔLEUR (Agent B) : diagnostic de la réponse.
            step = STEP_CONTROLLER;
            feedback = controllerAi.reviewCoachAnswer(controllerContext(snapshot, campaign, number, section,
                    coachResponse, composedPrompt, previous), controllerProvider);
            aiCalls++;

            // 3) ÉDITEUR (Agent A) : nouvelle zone éditable ; l'avis HUMAIN est prioritaire (§43).
            step = STEP_EDITOR;
            Optional<PromptOptimizationModels.HumanFeedback> pending = store.pendingHumanFeedback(campaignId);
            edition = editorAi.editPromptSection(editorContext(snapshot, campaign, number, section, feedback, pending,
                    previousChanges(previous)), editorProvider);
            aiCalls++;
            humanApplied = pending.isPresent();

            // 4) VALIDATION BACKEND : une zone invalide est REJETÉE, la version reste inchangée (§6).
            if (edition.updated()) {
                String rejection = validateSection(edition.editableSection());
                if (rejection != null) {
                    error = rejection;
                    log.warn("Proposition d'édition rejetée (campagne {}, itération {}) : {}", campaignId, number, rejection);
                } else {
                    resultingVersion = PromptOptimizationModels.versionName(
                            PromptOptimizationModels.versionNumber(version) + 1);
                    resultingSection = edition.editableSection();
                    changeSummary = edition.changeSummary();
                    noChange = false;
                }
            } else if (edition.reviewRequired()) {
                error = "Aucune modification appliquée : revue humaine ou métier requise. "
                        + String.join(" ", edition.unresolvedPoints());
            }
            if (humanApplied) {
                store.appendFeedback(pending.get().applied(true));
            }
        } catch (Exception e) {
            status = PromptOptimizationModels.ITERATION_ERROR;
            error = errorMessage(e);
            log.warn("Itération {} de la campagne {} en échec à l'étape {} : {}", number, campaignId, step, error);
        }

        long duration = System.currentTimeMillis() - began;
        PromptOptimizationModels.Iteration iteration = new PromptOptimizationModels.Iteration(
                "it-" + campaignId + "-" + number, campaignId, number, version, section, zoneService.hash(section),
                coachResponse, feedback, edition, resultingVersion, resultingSection, changeSummary, noChange,
                humanApplied, contextAddedData, status, error, campaign.provider(),
                campaign.controllerProvider(), campaign.editorProvider(), campaign.model(), promptChars, duration,
                startedAt, Instant.now().toString());
        store.appendIteration(iteration);

        // RELECTURE de l'état avant d'écrire : un STOP a pu être demandé PENDANT les appels IA (autre
        // thread, l'arrêt §11 doit rester gracieux et ne jamais être écrasé par l'itération en cours).
        PromptOptimizationModels.Campaign latest = store.require(campaignId);
        String campaignStatus = nextCampaignStatus(latest, status, number);
        boolean paused = PromptOptimizationModels.CAMPAIGN_PAUSED.equals(campaignStatus);
        boolean failed = PromptOptimizationModels.CAMPAIGN_ERROR.equals(campaignStatus);
        store.saveCampaignAndReturn(update(latest, campaignStatus, number, resultingVersion,
                latest.aiCalls() + aiCalls, promptChars, duration,
                failed ? error : "", failed ? step : "",
                PromptOptimizationModels.CAMPAIGN_STOP_REQUESTED.equals(campaignStatus) ? Instant.now().toString()
                        : latest.stopRequestedAt(),
                paused ? Instant.now().toString() : latest.pausedAt(), null));

        logIteration(campaign, snapshot, iteration, composedPrompt);
        return iteration;
    }

    /** Statut de campagne attendu après une itération (STOP gracieux, fin de cycle, erreur). */
    private static String nextCampaignStatus(PromptOptimizationModels.Campaign campaign, String iterationStatus,
                                             int number) {
        if (PromptOptimizationModels.ITERATION_ERROR.equals(iterationStatus)) {
            return PromptOptimizationModels.CAMPAIGN_ERROR;
        }
        if (PromptOptimizationModels.CAMPAIGN_STOP_REQUESTED.equals(campaign.status())) {
            return PromptOptimizationModels.CAMPAIGN_PAUSED;
        }
        if (number >= campaign.requestedIterations() || number >= campaign.maxIterations()) {
            return PromptOptimizationModels.CAMPAIGN_COMPLETED;
        }
        return PromptOptimizationModels.CAMPAIGN_RUNNING;
    }

    // --- Arrêt / reprise ---------------------------------------------------------------------------

    /**
     * Arrêt GRACIEUX (§11) : un appel en cours n'est jamais interrompu, il se termine puis la campagne
     * passe en pause. Si aucun traitement n'est en cours, la pause est immédiate.
     */
    public PromptOptimizationModels.Campaign stop(String campaignId) {
        PromptOptimizationModels.Campaign campaign = store.require(campaignId);
        boolean stoppable = PromptOptimizationModels.campaignBusy(campaign.status())
                || PromptOptimizationModels.CAMPAIGN_CREATED.equals(campaign.status());
        if (!stoppable) {
            throw new IllegalStateException("Aucun arrêt possible : la campagne est "
                    + PromptOptimizationModels.campaignStatusLabel(campaign.status()) + ".");
        }
        boolean inFlight = store.isBusy(campaignId);
        String status = inFlight ? PromptOptimizationModels.CAMPAIGN_STOP_REQUESTED
                : PromptOptimizationModels.CAMPAIGN_PAUSED;
        return store.saveCampaignAndReturn(update(campaign, status, campaign.completedIterations(),
                campaign.currentCandidateVersion(), campaign.aiCalls(), 0L, 0L,
                "", "",
                inFlight ? Instant.now().toString() : campaign.stopRequestedAt(),
                inFlight ? campaign.pausedAt() : Instant.now().toString(), null));
    }

    /**
     * Reprise depuis une pause, une ERREUR ou la fin d'un cycle (§12/§13) : le snapshot n'est JAMAIS
     * recréé, les données ne sont pas rechargées et l'historique est conservé (l'itération en échec
     * reste consultable, elle ne produit simplement aucune version). Un avis humain en attente est
     * appliqué par l'Agent A AVANT tout nouvel appel au Coach, pour que la réponse suivante en tienne compte.
     */
    public PromptOptimizationModels.Campaign resume(String campaignId, int additionalIterations) {
        return store.withCampaignLock(campaignId, () -> {
            PromptOptimizationModels.Campaign campaign = store.require(campaignId);
            boolean paused = PromptOptimizationModels.campaignPaused(campaign.status());
            boolean stopRequested = PromptOptimizationModels.CAMPAIGN_STOP_REQUESTED.equals(campaign.status());
            boolean completed = PromptOptimizationModels.CAMPAIGN_COMPLETED.equals(campaign.status());
            boolean failed = PromptOptimizationModels.CAMPAIGN_ERROR.equals(campaign.status());
            if (!paused && !stopRequested && !completed && !failed) {
                throw new IllegalStateException("La reprise n'est possible qu'en pause, après une erreur ou après "
                        + "la fin du cycle demandé (statut actuel : "
                        + PromptOptimizationModels.campaignStatusLabel(campaign.status()) + ").");
            }
            int additional = Math.max(0, additionalIterations);
            int total = campaign.requestedIterations() + additional;
            if (total > campaign.maxIterations()) {
                throw new IllegalArgumentException("Le plafond de " + campaign.maxIterations()
                        + " itérations CUMULÉES est atteint : créez une nouvelle campagne pour continuer (§33).");
            }
            PromptOptimizationModels.Snapshot snapshot = store.snapshot(campaignId).orElseThrow(
                    () -> new IllegalStateException("Snapshot introuvable pour la campagne " + campaignId));
            String version = campaign.currentCandidateVersion();
            Optional<PromptOptimizationModels.HumanFeedback> pending = store.pendingHumanFeedback(campaignId);
            if (pending.isPresent()) {
                version = applyHumanFeedback(campaign, snapshot, pending.get(), version);
            }
            // « Ajouter mon avis et continuer » : le cycle demandé est PROLONGÉ (compteur cumulé, §33).
            PromptOptimizationModels.Campaign base = additional > 0
                    ? campaign.withRequestedIterations(total) : campaign;
            return store.saveCampaignAndReturn(update(base, PromptOptimizationModels.CAMPAIGN_RUNNING,
                    base.completedIterations(), version,
                    base.aiCalls() + (pending.isPresent() ? 1 : 0), 0L, 0L, "", "",
                    base.stopRequestedAt(), "", null));
        });
    }

    /**
     * Applique un avis humain via l'Agent A, HORS itération (elle ne consomme aucun compteur) : la version
     * produite est enregistrée comme édition pour rester consultable et reconstructible.
     */
    private String applyHumanFeedback(PromptOptimizationModels.Campaign campaign,
                                      PromptOptimizationModels.Snapshot snapshot,
                                      PromptOptimizationModels.HumanFeedback pending, String version) {
        String campaignId = campaign.campaignId();
        String section = store.editableSectionOf(campaignId, version).orElseThrow(
                () -> new IllegalStateException("Zone éditable de la version " + version + " introuvable"));
        PromptOptimizationModels.Iteration previous = store.latestIteration(campaignId).orElse(null);
        AIModels.AIProvider coach = coachProviderOf(campaign);
        AIModels.AIProvider editor = providerOf(campaign.editorProvider(), coach);
        AIService ai = aiServiceFactory.get(editor);
        PromptOptimizationModels.EditorResult result;
        try {
            result = ai.editPromptSection(editorContext(snapshot, campaign, campaign.completedIterations(), section,
                    previous == null ? null : previous.controllerFeedback(), Optional.of(pending),
                    previousChanges(previous)), editor);
        } catch (Exception e) {
            throw new IllegalStateException("Application de l'avis humain impossible : " + errorMessage(e), e);
        }
        String newVersion = version;
        if (result.updated()) {
            String rejection = validateSection(result.editableSection());
            if (rejection == null) {
                newVersion = PromptOptimizationModels.versionName(PromptOptimizationModels.versionNumber(version) + 1);
                store.appendEdition(campaignId, new PromptOptimizationModels.PromptVersion(newVersion,
                        result.editableSection(), zoneService.hash(result.editableSection()),
                        campaign.completedIterations()));
            } else {
                log.warn("Édition issue de l'avis humain rejetée (campagne {}) : {}", campaignId, rejection);
            }
        }
        store.appendFeedback(pending.applied(true));
        return newVersion;
    }

    // --- Décisions humaines -------------------------------------------------------------------------

    /** Retour humain libre (§12) : prioritaire sur le contrôleur, jamais perdu après reprise. */
    public PromptOptimizationModels.HumanFeedback addHumanFeedback(String campaignId, String content) {
        PromptOptimizationModels.Campaign campaign = store.require(campaignId);
        if (PromptOptimizationModels.campaignDecided(campaign.status())) {
            throw new IllegalStateException("Campagne clôturée : l'avis n'est plus pris en compte.");
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("L'avis est vide.");
        }
        PromptOptimizationModels.HumanFeedback feedback = new PromptOptimizationModels.HumanFeedback(null,
                campaignId, campaign.completedIterations(), PromptOptimizationModels.FEEDBACK_SOURCE_HUMAN,
                PromptOptimizationModels.PRIORITY_OVERRIDE, text, Instant.now().toString(), false);
        store.appendFeedback(feedback);
        return feedback;
    }

    /**
     * PROMOTION (§17) : action HUMAINE explicite. Le prompt ACTUEL est sauvegardé avant remplacement
     * (retour arrière possible), puis le fichier de la zone est réécrit en ne changeant QUE la zone.
     */
    public PromotionResult promoteVersion(String campaignId, String version) {
        return store.withCampaignLock(campaignId, () -> {
            PromptOptimizationModels.Campaign campaign = store.require(campaignId);
            if (campaign.isActive()) {
                throw new IllegalStateException("Impossible de promouvoir pendant que la campagne tourne : arrêtez-la d'abord.");
            }
            PromptOptimizationModels.Snapshot snapshot = store.snapshot(campaignId).orElseThrow(
                    () -> new IllegalStateException("Snapshot introuvable pour la campagne " + campaignId));
            String section = store.editableSectionOf(campaignId, version).orElseThrow(
                    () -> new IllegalArgumentException("Version inconnue : " + version));
            String key = promotionKey(campaign, snapshot);
            if (AgentPromptStore.PRINCIPAL_KEY.equals(key)) {
                // L'agent principal est TRANSVERSE : il s'applique à tous les agents.
                for (PromptOptimizationModels.Campaign other : store.list()) {
                    if (other.campaignId().equals(campaignId)) {
                        continue;
                    }
                    if (other.isActive() || PromptOptimizationModels.campaignPaused(other.status())) {
                        throw new IllegalStateException("L'agent principal est transverse : terminez la campagne "
                                + other.campaignId() + " avant de le promouvoir.");
                    }
                }
            } else {
                ensureNoOtherActiveCampaign(campaignId, campaign.agentId());
            }
            String currentContent = AgentFiles.readPromptOrDefault(snapshot.zoneFile(), "");
            PromptZoneService.Zone zone = zoneService.parse(currentContent);
            if (!zone.valid()) {
                throw new IllegalStateException("Le prompt cible n'a plus de zone éditable valide : " + zone.error());
            }
            if (!zone.prefix().equals(snapshot.fixedPrefix()) || !zone.suffix().equals(snapshot.fixedSuffix())) {
                throw new IllegalStateException("Le prompt de l'agent a été modifié depuis le snapshot de la "
                        + "campagne : la promotion est refusée pour éviter d'écraser une modification externe.");
            }
            String rejection = validateSection(section);
            if (rejection != null) {
                throw new IllegalStateException("Version non promouvable : " + rejection);
            }
            AgentPromptHistoryStore.PromptBackup backup = historyStore.backup(key, snapshot.zoneFile(), currentContent,
                    "promotion de " + version + " (campagne " + campaignId + ")");
            agentPromptStore.write(key, preserveLineEndings(currentContent, zoneService.compose(zone, section)));
            log.info("Version {} promue pour l'agent {} (campagne {})", version, key, campaignId);
            PromptOptimizationModels.Campaign promoted = store.saveCampaignAndReturn(
                    update(campaign, PromptOptimizationModels.CAMPAIGN_ACCEPTED, campaign.completedIterations(),
                            campaign.currentCandidateVersion(), campaign.aiCalls(),
                            0L, 0L, "", "", campaign.stopRequestedAt(), campaign.pausedAt(), version));
            return new PromotionResult(promoted, backup.backupId(), backup.backupFile(),
                    "La version " + version + " remplace la zone du prompt de « " + key
                            + " ». Le prompt précédent est conservé dans l'historique (" + backup.backupFile() + ").");
        });
    }

    /** Refus explicite d'une campagne : aucune version n'est promue, rien n'est supprimé (§32). */
    public PromptOptimizationModels.Campaign reject(String campaignId) {
        PromptOptimizationModels.Campaign campaign = store.require(campaignId);
        if (campaign.isActive()) {
            throw new IllegalStateException("Arrêtez la campagne avant de la refuser.");
        }
        return store.saveCampaignAndReturn(update(campaign, PromptOptimizationModels.CAMPAIGN_REJECTED,
                campaign.completedIterations(), campaign.currentCandidateVersion(),
                campaign.aiCalls(), 0L, 0L, "", "", campaign.stopRequestedAt(), campaign.pausedAt(), null));
    }

    // --- Lecture (IHM / API) --------------------------------------------------------------------------

    public PromptOptimizationModels.Campaign campaign(String campaignId) {
        return store.require(campaignId);
    }

    public List<PromptOptimizationModels.Campaign> campaigns() {
        return store.list();
    }

    public PromptOptimizationModels.Snapshot snapshot(String campaignId) {
        return store.snapshot(campaignId).orElseThrow(
                () -> new IllegalArgumentException("Campagne inconnue : " + campaignId));
    }

    public List<PromptOptimizationModels.Iteration> iterations(String campaignId) {
        return store.iterations(campaignId).values();
    }

    public List<PromptOptimizationModels.PromptVersion> versions(String campaignId) {
        return store.versions(campaignId);
    }

    public List<PromptOptimizationModels.HumanFeedback> feedbacks(String campaignId) {
        return store.currentFeedbacks(campaignId).values();
    }

    /** Prompt système COMPLET d'une version (parties figées + zone de cette version) — IHM « Voir le prompt ». */
    public String promptFor(String campaignId, String version) {
        PromptOptimizationModels.Snapshot snapshot = snapshot(campaignId);
        String section = store.editableSectionOf(campaignId, version).orElse("");
        return composePrompt(snapshot, section);
    }

    /** Zones optimisables de tous les agents (sélecteur de l'IHM). */
    public List<ZoneInfo> zones() {
        List<ZoneInfo> zones = new ArrayList<>();
        for (AgentDefinition agent : AgentFiles.agents()) {
            zones.add(describeZone(agent));
        }
        return zones;
    }

    /** Description de la zone optimisable d'un agent, avec la zone par défaut. */
    public ZoneInfo describeZone(AgentDefinition agent) {
        return describeZone(agent, null);
    }

    /**
     * Description de la zone optimisable d'un agent. Par défaut : le prompt SPÉCIALISÉ du thème (ou
     * l'agent principal pour l'agent générique). {@code requestedZoneKey} peut imposer l'agent principal
     * (transverse) — une campagne n'optimise toutefois JAMAIS plusieurs zones à la fois.
     */
    public ZoneInfo describeZone(AgentDefinition agent, String requestedZoneKey) {
        if (agent == null || agent.getTheme() == null) {
            return new ZoneInfo("", "", PromptOptimizationModels.ZONE_AGENT, "", false, "",
                    "Agent inconnu.");
        }
        boolean generic = AgentFiles.GENERIC_THEME.equalsIgnoreCase(agent.getTheme());
        boolean principalRequested = PromptOptimizationModels.ZONE_PRINCIPAL.equalsIgnoreCase(requestedZoneKey);
        boolean agentRequested = PromptOptimizationModels.ZONE_AGENT.equalsIgnoreCase(requestedZoneKey);
        if (agentRequested && generic) {
            return new ZoneInfo(agent.getTheme(), AgentFiles.libelleFor(agent.getTheme()),
                    PromptOptimizationModels.ZONE_AGENT, "", false, "",
                    "L'agent générique n'a pas de prompt spécialisé : choisissez l'agent principal.");
        }
        boolean usePrincipal = principalRequested || (!agentRequested && generic);
        String zoneKey = usePrincipal ? PromptOptimizationModels.ZONE_PRINCIPAL
                : PromptOptimizationModels.ZONE_AGENT;
        String zoneFile = usePrincipal ? AgentFiles.PRINCIPAL_PROMPT_FILE : agent.getPrompt();
        if (zoneFile == null || zoneFile.isBlank()) {
            return new ZoneInfo(agent.getTheme(), AgentFiles.libelleFor(agent.getTheme()), zoneKey, "",
                    false, "", "Le prompt de cet agent n'est pas déclaré dans agents.json.");
        }
        PromptZoneService.Zone zone = zoneService.parse(AgentFiles.readPromptOrDefault(zoneFile, ""));
        return new ZoneInfo(agent.getTheme(), AgentFiles.libelleFor(agent.getTheme()), zoneKey, zoneFile,
                zone.valid(), zone.editableSection(), zone.valid() ? "" : zone.error());
    }

    // --- Aides internes --------------------------------------------------------------------------------

    /** Agent demandé, ou refus explicite (jamais de repli silencieux sur le générique). */
    private static AgentDefinition resolveAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("L'agent à optimiser est obligatoire.");
        }
        for (AgentDefinition agent : AgentFiles.agents()) {
            if (agentId.equalsIgnoreCase(agent.getTheme()) || agentId.equalsIgnoreCase(agent.getId())) {
                return agent;
            }
        }
        throw new IllegalArgumentException("Agent inconnu : " + agentId);
    }

    /** Refuse une campagne supplémentaire sur le MÊME agent tant qu'une autre est en cours (§36). */
    private void ensureNoOtherActiveCampaign(String campaignId, String agentTheme) {
        for (PromptOptimizationModels.Campaign other : store.list()) {
            if (other.campaignId().equals(campaignId) || !other.agentId().equalsIgnoreCase(agentTheme)) {
                continue;
            }
            if (other.isActive() || PromptOptimizationModels.campaignPaused(other.status())) {
                throw new IllegalStateException("Une campagne est déjà en cours pour l'agent " + agentTheme
                        + " (" + other.campaignId() + ") : terminez-la d'abord.");
            }
        }
    }

    /**
     * Clôt les campagnes précédentes non décidées (statut {@code CANCELLED}) : l'IHM ne propose plus de
     * « reprendre une campagne », donc une campagne laissée en cours (rechargement de page, onglet fermé)
     * ne doit jamais empêcher d'en démarrer une nouvelle. Aucun fichier n'est supprimé.
     */
    private void closePreviousCampaigns(String campaignId) {
        for (PromptOptimizationModels.Campaign other : store.list()) {
            if (other.campaignId().equals(campaignId) || PromptOptimizationModels.campaignDecided(other.status())
                    || PromptOptimizationModels.CAMPAIGN_CANCELLED.equals(other.status())) {
                continue;
            }
            store.saveCampaignAndReturn(update(other, PromptOptimizationModels.CAMPAIGN_CANCELLED,
                    other.completedIterations(), other.currentCandidateVersion(),
                    other.aiCalls(), 0L, 0L, "", "", other.stopRequestedAt(), other.pausedAt(), null));
            log.info("Campagne précédente {} clôturée (CANCELLED) : la nouvelle campagne {} la remplace",
                    other.campaignId(), campaignId);
        }
    }

    /** Zone invalide refusée par le BACKEND (§6) : délimiteurs interdits, longueur bornée, contenu non vide. */
    private String validateSection(String section) {
        String error = zoneService.validateEditableSection(section);
        if (error != null) {
            return error;
        }
        if (section.strip().length() > properties.maxEditableSectionLength()) {
            return "Zone éditable rejetée : " + section.strip().length() + " caractères (maximum "
                    + properties.maxEditableSectionLength() + ")";
        }
        return null;
    }

    /** Prompt système d'une VERSION : mêmes parties figées, seule la zone éditable change. */
    private static String composePrompt(PromptOptimizationModels.Snapshot snapshot, String editableSection) {
        return AgentFiles.composeSystemPrompt(snapshot.frozenTemplate(), snapshot.frozenPrincipal(),
                snapshot.fixedPrefix() + (editableSection == null ? "" : editableSection) + snapshot.fixedSuffix());
    }

    /**
     * La zone est reconstruite en LF (normalisation interne) : on rétablit les fins de ligne du fichier
     * d'origine pour que la promotion ne modifie QUE la zone et ne réécrive pas tout le fichier.
     */
    static String preserveLineEndings(String originalContent, String composed) {
        if (originalContent == null || !originalContent.contains("\r\n")) {
            return composed;
        }
        return composed.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private static AIModels.Classification legacyOf(IntentClassification classification) {
        IntentClassification source = classification == null ? new IntentClassification() : classification;
        return new AIModels.Classification(source.inScope(), source.toLegacyCategory(), source.getReason());
    }

    private long coachPromptChars(PromptOptimizationModels.Snapshot snapshot, String composedPrompt,
                                  AIModels.Classification legacy) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerMessage", snapshot.question());
            payload.put("classification", legacy);
            payload.put("financialSummary", snapshot.financialSummary());
            payload.put("bankingData", snapshot.catalog() == null ? Map.of() : snapshot.catalog());
            payload.put("additionalData", snapshot.additionalData());
            payload.put("conversationHistory", snapshot.history());
            return composedPrompt.length() + objectMapper.writeValueAsString(payload).length();
        } catch (Exception e) {
            return composedPrompt.length();
        }
    }

    /** Contexte transmis au CONTRÔLEUR : tout le nécessaire pour juger, sans dupliquer les données jointes. */
    private Map<String, Object> controllerContext(PromptOptimizationModels.Snapshot snapshot,
                                                 PromptOptimizationModels.Campaign campaign, int number,
                                                 String section, String coachResponse, String composedPrompt,
                                                 PromptOptimizationModels.Iteration previous) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("agentId", campaign.agentId());
        context.put("agentLibelle", campaign.agentLibelle());
        context.put("iterationNumber", number);
        context.put("question", snapshot.question());
        context.put("coachResponse", coachResponse);
        context.put("coachPrompt", composedPrompt);
        context.put("editableSection", section);
        context.put("financialSummary", snapshot.financialSummary());
        context.put("additionalData", reducedAdditionalData(snapshot));
        context.put("providedDataDescriptions", descriptions(snapshot));
        // Le CONTENU des données jointes est transmis au contrôleur : sans lui, il ne peut que SUPPOSER et
        // signale comme « inventé » ce qui figurait dans les données (ex. URL officielle d'une fiche fournie).
        context.put("providedData", snapshot.providedData());
        if (previous != null) {
            context.put("previousResponse", previous.coachResponse());
            context.put("previousControllerFeedback", previous.controllerFeedback());
        }
        return context;
    }

    /** Contexte transmis à l'ÉDITEUR : zone courante, diagnostic, avis humain prioritaire, éléments à préserver. */
    private Map<String, Object> editorContext(PromptOptimizationModels.Snapshot snapshot,
                                             PromptOptimizationModels.Campaign campaign, int number, String section,
                                             PromptOptimizationModels.ControllerFeedback feedback,
                                             Optional<PromptOptimizationModels.HumanFeedback> humanFeedback,
                                             List<String> previousChanges) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("agentId", campaign.agentId());
        context.put("agentLibelle", campaign.agentLibelle());
        context.put("iterationNumber", number);
        context.put("question", snapshot.question());
        context.put("editableSection", section);
        context.put("controllerFeedback", feedback == null ? Map.of() : objectMapper.convertValue(feedback, Map.class));
        context.put("humanFeedback", humanFeedback.map(PromptOptimizationModels.HumanFeedback::content).orElse(null));
        context.put("mustPreserve", feedback == null ? List.of() : feedback.mustPreserve());
        context.put("previousChanges", previousChanges);
        context.put("snapshotContext", reducedAdditionalData(snapshot));
        // MÊME PARITÉ QUE LE CONTRÔLEUR : l'éditeur voit le CONTENU des données fournies au Coach, donc il
        // n'écrit pas de règle sur un produit, un taux ou une URL qui n'existerait pas réellement.
        context.put("providedData", snapshot.providedData());
        context.put("providedDataDescriptions", descriptions(snapshot));
        return context;
    }

    private static Map<String, Object> reducedAdditionalData(PromptOptimizationModels.Snapshot snapshot) {
        Map<String, Object> reduced = new LinkedHashMap<>(snapshot.additionalData());
        reduced.remove("providedData");
        return reduced;
    }

    private static List<String> descriptions(PromptOptimizationModels.Snapshot snapshot) {
        return snapshot.providedData().stream()
                .map(entry -> String.valueOf(entry.get("description")))
                .toList();
    }

    private static List<String> previousChanges(PromptOptimizationModels.Iteration previous) {
        return previous == null ? List.of() : previous.changeSummary();
    }

    /** Fichiers demandés par le Coach, listés dans l'erreur (aucune donnée n'est inventée). */
    private static String requestedPaths(AIModels.AIAnswer answer) {
        List<String> paths = answer.dataRequest() == null || answer.dataRequest().paths() == null
                ? List.of() : answer.dataRequest().paths();
        return paths.isEmpty() ? "" : " Fichiers demandés : " + String.join(", ", paths) + ".";
    }

    /**
     * Complète le contexte de référence avec les fichiers demandés par le Coach (comme en PRODUCTION),
     * quel que soit le numéro d'itération : les fichiers autorisés par la whitelist du catalogue sont
     * ajoutés à {@code providedData}, le snapshot est ENREGISTRÉ (empreinte recalculée) et devient le
     * contexte commun — le complément profite donc aussi aux itérations suivantes, et il est traçable.
     *
     * @return le snapshot complété, ou {@code null} si rien n'a pu être ajouté (dans ce cas l'itération
     *         échoue avec un message explicite plutôt que d'inventer des données)
     */
    private PromptOptimizationModels.Snapshot completeContext(PromptOptimizationModels.Campaign campaign,
                                                             PromptOptimizationModels.Snapshot snapshot,
                                                             AIModels.AIAnswer answer) {
        List<String> paths = answer.dataRequest() == null || answer.dataRequest().paths() == null
                ? List.of() : answer.dataRequest().paths();
        List<Map<String, Object>> extra;
        try {
            extra = dataRequestService.fetch(paths, new LinkedHashSet<>(snapshot.allowedCatalogPaths()));
        } catch (Exception e) {
            log.warn("Complétion du contexte impossible (campagne {}) : {}", campaign.campaignId(), errorMessage(e));
            return null;
        }
        if (extra == null || extra.isEmpty()) {
            return null;
        }
        Map<String, Object> additional = new LinkedHashMap<>(snapshot.additionalData());
        List<Map<String, Object>> provided = new ArrayList<>(snapshot.providedData());
        provided.addAll(extra);
        additional.put("providedData", provided);
        PromptOptimizationModels.Snapshot completed = new PromptOptimizationModels.Snapshot(snapshot.snapshotId(),
                snapshot.campaignId(), snapshot.question(), snapshot.agentTheme(), snapshot.agentLibelle(),
                snapshot.zoneKey(), snapshot.zoneFile(), snapshot.promptVersion(), snapshot.fixedPrefix(),
                snapshot.initialEditableSection(), snapshot.fixedSuffix(), snapshot.frozenSystemPrompt(),
                snapshot.frozenTemplate(), snapshot.frozenPrincipal(), snapshot.classification(),
                snapshot.financialSummary(), snapshot.catalog(), snapshot.allowedCatalogPaths(), additional,
                snapshot.history(), snapshot.debug(), snapshot.provider(), snapshot.model(), snapshot.promptHash(),
                snapshotHash(snapshot.question(), snapshot.frozenSystemPrompt(), additional, snapshot.provider()),
                snapshot.createdAt());
        store.saveSnapshot(completed);
        log.info("Contexte de référence complété (campagne {}) : {} fichier(s) demandé(s) par le Coach",
                campaign.campaignId(), extra.size());
        return completed;
    }

    private static String promotionKey(PromptOptimizationModels.Campaign campaign,
                                       PromptOptimizationModels.Snapshot snapshot) {
        return PromptOptimizationModels.ZONE_PRINCIPAL.equals(snapshot.zoneKey())
                ? AgentPromptStore.PRINCIPAL_KEY : campaign.agentId();
    }

    /** Fournisseur du coach : une campagne persistée sans fournisseur lisible est refusée explicitement. */
    private static AIModels.AIProvider coachProviderOf(PromptOptimizationModels.Campaign campaign) {
        try {
            return AIModels.AIProvider.valueOf(campaign.provider());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Fournisseur IA inconnu pour la campagne " + campaign.campaignId()
                    + " : « " + campaign.provider() + " ».");
        }
    }

    /** Fournisseur d'une étape secondaire, avec repli sur celui du coach (campagne antérieure). */
    private static AIModels.AIProvider providerOf(String name, AIModels.AIProvider fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return AIModels.AIProvider.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String nextCampaignId() {
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
        return "po-" + stamp + "-" + java.util.UUID.randomUUID().toString().substring(0, 4);
    }

    private String snapshotHash(String question, String frozenPrompt,
                                CoachContext context, String provider) {
        return snapshotHash(question, frozenPrompt, context.additionalData(), provider);
    }

    private String snapshotHash(String question, String frozenPrompt,
                                Map<String, Object> additionalData, String provider) {
        try {
            return zoneService.hash(question + "|" + frozenPrompt + "|" + provider + "|"
                    + objectMapper.writeValueAsString(additionalData));
        } catch (Exception e) {
            return zoneService.hash(question + "|" + frozenPrompt + "|" + provider);
        }
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Trace détaillée d'une itération dans la page Logs (1 entrée par itération, pas 3). */
    private void logIteration(PromptOptimizationModels.Campaign campaign, PromptOptimizationModels.Snapshot snapshot,
                              PromptOptimizationModels.Iteration iteration, String composedPrompt) {
        List<String> dataSent = descriptions(snapshot);
        String debug = "[PROMPT_LAB]\n"
                + "campaignId=" + campaign.campaignId() + '\n'
                + "iteration=" + iteration.iterationNumber() + '\n'
                + "agent=" + campaign.agentId() + '\n'
                + "zone=" + campaign.zoneFile() + '\n'
                + "providerCoach=" + campaign.provider() + '\n'
                + "providerController=" + campaign.controllerProvider() + '\n'
                + "providerEditor=" + campaign.editorProvider() + '\n'
                + "promptVersion=" + iteration.promptVersion()
                + " -> " + iteration.resultingVersion() + '\n'
                + "noChange=" + iteration.noChange() + '\n'
                + "controllerStatus=" + (iteration.controllerFeedback() == null ? "-"
                        : iteration.controllerFeedback().status()) + '\n'
                + "issues=" + (iteration.controllerFeedback() == null ? 0
                        : iteration.controllerFeedback().issues().size()) + '\n'
                + "promptIssues=" + (iteration.controllerFeedback() == null ? 0
                        : iteration.controllerFeedback().promptIssues().size()) + '\n'
                + "editorStatus=" + (iteration.editorResult() == null ? "-" : iteration.editorResult().status()) + '\n'
                + "humanFeedbackApplied=" + iteration.humanFeedbackApplied() + '\n'
                + "aiCalls=3\n"
                + "durationMs=" + iteration.durationMs() + '\n'
                + (iteration.error().isBlank() ? "" : "error=" + iteration.error() + '\n')
                + "iterationStatus=" + iteration.status() + '\n'
                + "campaignStatus=" + campaign.status() + '\n';
        String promptLog = "=== PROMPT SYSTÈME FIGÉ (" + iteration.promptVersion() + ") ===\n" + composedPrompt
                + "\n\n=== ZONE OPTIMISÉE ===\n" + iteration.editableSection()
                + "\n\n=== DIAGNOSTIC DU CONTRÔLEUR ===\n" + json(iteration.controllerFeedback())
                + "\n\n=== PROPOSITION DE L'ÉDITEUR ===\n" + json(iteration.editorResult());
        aiLogService.log(campaign.campaignId(), LOG_AGENT_PREFIX + "itération " + iteration.iterationNumber(),
                dataSent, snapshot.history() == null ? 0 : snapshot.history().size(), iteration.promptChars(),
                iteration.failed() ? AIModels.AIStatus.ERROR : AIModels.AIStatus.ANSWER, List.of(),
                LOG_AGENT_PREFIX + campaign.agentLibelle(), promptLog, debug, iteration.coachResponse());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * Recopie d'une campagne avec un nouvel état : UN SEUL endroit construit l'état persistant, ce qui
     * évite les transitions incohérentes. Les paramètres à {@code null} conservent la valeur courante.
     */
    private static PromptOptimizationModels.Campaign update(PromptOptimizationModels.Campaign campaign,
                                                            String status, int completedIterations,
                                                            String currentVersion,
                                                            int aiCalls, long addedChars, long addedDuration,
                                                            String error, String errorStep,
                                                            String stopRequestedAt, String pausedAt,
                                                            String promotedVersion) {
        return new PromptOptimizationModels.Campaign(campaign.campaignId(), campaign.agentId(),
                campaign.agentLibelle(), campaign.zoneKey(), campaign.zoneFile(),
                status == null ? campaign.status() : status,
                campaign.question(), campaign.requestedIterations(),
                Math.max(0, completedIterations), campaign.maxIterations(), campaign.snapshotId(),
                campaign.basePromptVersion(),
                currentVersion == null ? campaign.currentCandidateVersion() : currentVersion,
                promotedVersion == null ? campaign.promotedVersion() : promotedVersion,
                campaign.provider(), campaign.controllerProvider(), campaign.editorProvider(), campaign.model(),
                Math.max(0, aiCalls),
                campaign.totalPromptChars() + Math.max(0L, addedChars),
                campaign.totalDurationMs() + Math.max(0L, addedDuration),
                stopRequestedAt == null ? campaign.stopRequestedAt() : stopRequestedAt,
                pausedAt == null ? campaign.pausedAt() : pausedAt,
                error == null ? campaign.error() : error,
                errorStep == null ? campaign.errorStep() : errorStep,
                campaign.createdAt(), Instant.now().toString());
    }
}
