package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.AgentFiles;
import com.coach.financier.ai.MockAIService;
import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.AgentDefinition;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProjectType;
import com.coach.financier.model.PromptOptimizationModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            + "le catalogue ne permet pas de fournir (fichiers autorisés uniquement, aucun inventé). "
            + "L'itération est CONSERVÉE avec la réponse de repli du chat (comportement de production) : "
            + "la campagne et la conversation continuent normalement.";

    /** Nombre maximal de complétions du contexte pour UNE itération (même limite que la production). */
    public static final int MAX_CONTEXT_COMPLETIONS = 3;

    /**
     * Réponse de repli quand le Coach réclame des données que le catalogue ne permet pas de fournir : c'est
     * EXACTEMENT le message du chat en production ({@code ChatController}) — l'itération de l'atelier ne bloque
     * donc jamais, et la réponse enregistrée est celle que le client recevrait réellement.
     */
    public static final String UNAVAILABLE_DATA_ANSWER =
            "Je n'ai pas pu finaliser l'analyse demandée à partir des données disponibles.";

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
    private final FinancialAnalysisService financialAnalysis;
    private final ObjectMapper objectMapper;

    public PromptOptimizationService(PromptOptimizationProperties properties, PromptOptimizationStore store,
                                     PromptZoneService zoneService, CoachContextBuilder contextBuilder,
                                     AIServiceFactory aiServiceFactory, AILogService aiLogService,
                                     AgentPromptStore agentPromptStore, AgentPromptHistoryStore historyStore,
                                     DataRequestService dataRequestService, FinancialAnalysisService financialAnalysis,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.store = store;
        this.zoneService = zoneService;
        this.contextBuilder = contextBuilder;
        this.aiServiceFactory = aiServiceFactory;
        this.aiLogService = aiLogService;
        this.agentPromptStore = agentPromptStore;
        this.historyStore = historyStore;
        this.dataRequestService = dataRequestService;
        this.financialAnalysis = financialAnalysis;
        this.objectMapper = objectMapper;
    }

    /**
     * Question posée au CLIENT simulé (« Agent C ») : l'appelant fournit le brief écrit par l'humain, le
     * numéro de la question et le fil de conversation (mémoire des échanges déjà validés). L'agent joue le
     * client — il ne conseille jamais et n'invente aucun chiffre : il ne reçoit que le brief et les
     * <b>trois chiffres du dossier</b> (solde du compte courant, épargne, mensualité de crédit en cours).
     */
    public PromptOptimizationModels.ClientTurn clientTurn(String threadId, String brief, int turnNumber, int depth,
                                                          AIModels.AIProvider provider) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("L'atelier d'optimisation des prompts est désactivé.");
        }
        String context = brief == null ? "" : brief.trim();
        if (context.isEmpty()) {
            throw new IllegalArgumentException("Le contexte du client (brief) est obligatoire pour l'agent C.");
        }
        AIModels.AIProvider agentProvider = requireRealProvider(provider, "Agent C (client simulé)");
        PromptOptimizationModels.ConversationThread thread = threadId == null || threadId.isBlank()
                ? null : thread(threadId.trim());
        FinancialSummary summary = financialAnalysis.analyze();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientBrief", context);
        payload.put("clientFigures", clientFigures(summary));
        payload.put("turnNumber", Math.max(1, turnNumber));
        payload.put("previousExchanges", thread == null ? List.of() : thread.history());
        payload.put("depth", Math.max(1, depth)); // nombre maximum de questions du client (réglé dans l'IHM)
        PromptOptimizationModels.ClientTurn turn = aiServiceFactory.get(agentProvider)
                .clientTurn(payload, agentProvider);
        log.info("Agent C (client simulé) : question {} « {} » (fil {}, {} échange(s) déjà validé(s))",
                turnNumber, abbreviate(turn.question()), thread == null ? "nouveau" : thread.threadId(),
                thread == null ? 0 : thread.exchanges());
        return turn;
    }

    /**
     * PROJET proposé par l'Agent C pour le champ « Brief du client » (bouton « Générer projet ») : l'agent
     * cherche lui-même un client et un projet correspondant à l'<b>agent de coach sélectionné</b> (crédit à la
     * consommation, épargne, assurance…). Chaque nouvel appui doit proposer un projet DIFFÉRENT : les briefs
     * déjà proposés sont transmis au modèle avec l'interdiction de les reprendre.
     * <p>
     * Le modèle ne reçoit que ce qui est nécessaire : le libellé et le prompt de l'agent (pour rester dans son
     * périmètre) et les TROIS chiffres du dossier — exactement ceux que le client simulé connaîtra ensuite.
     * Aucune écriture : la proposition n'est qu'un texte à relire, que l'humain reste libre de modifier.
     *
     * @param previousBriefs briefs déjà proposés (le plus récent en dernier), à ne pas répéter
     */
    public PromptOptimizationModels.ClientBrief clientBrief(String agentId, List<String> previousBriefs,
                                                            AIModels.AIProvider provider) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("L'atelier d'optimisation des prompts est désactivé.");
        }
        AgentDefinition agent = resolveAgent(agentId);
        AIModels.AIProvider agentProvider = requireRealProvider(provider, "Agent C (projet du client)");
        FinancialSummary summary = financialAnalysis.analyze();
        BigDecimal ceiling = projectCeiling(agent, summary);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agent", Map.of(
                "id", agent.getTheme() == null ? "" : agent.getTheme(),
                "libelle", agent.getLibelle() == null ? "" : agent.getLibelle()));
        // Le prompt de l'agent sert de CADRE : le projet doit tomber dans son périmètre (produits, cas d'usage).
        // Les marqueurs de zone sont retirés, comme pour tout prompt envoyé à un LLM.
        String agentPrompt = agentPromptStore.read(agent.getTheme());
        payload.put("agentPrompt", agentPrompt == null ? "" : AgentFiles.stripZoneMarkers(agentPrompt));
        // Les TROIS chiffres du dossier, puis le PLAFOND que le backend en déduit : un petit modèle calcule mal,
        // on lui donne donc la limite déjà calculée (et on la vérifie derrière, quoi qu'il réponde).
        payload.put("clientFigures", clientFigures(summary));
        payload.put("budgetCoherent", Map.of(
                "plafondProjet", ceiling,
                "devise", "EUR",
                "regle", "Le montant à financer doit rester SOUS ce plafond, cohérent avec l'épargne disponible, "
                        + "le solde du compte courant et la mensualité de crédit déjà remboursée."));
        payload.put("previousBriefs", cleanBriefs(previousBriefs));

        PromptOptimizationModels.ClientBrief brief = aiServiceFactory.get(agentProvider)
                .clientBrief(payload, agentProvider);
        if (!brief.usable()) {
            throw new IllegalStateException("L'agent C n'a proposé aucun projet exploitable pour l'agent « "
                    + agent.getLibelle() + " » : réessayez, ou écrivez le brief vous-même.");
        }
        requireCredibleAmount(brief, agent, summary, ceiling);
        log.info("Agent C : projet proposé pour l'agent « {} » : {} (montant {} €, plafond {} €) — {}",
                agent.getLibelle(), abbreviate(brief.brief()), amount(brief.montantProjet()),
                amount(ceiling), brief.reason());
        return brief;
    }

    /** Motifs de montants dans un texte français : « 15 000 € », « 15000 € », « 1 500,50 euros », « €EUR ». */
    private static final Pattern MONEY_PATTERN =
            Pattern.compile("(\\d[\\d\\u00A0\\u202F .,]*)\\s*(?:€|euros?|EUR)", Pattern.CASE_INSENSITIVE);

    /**
     * Plafond de montant à financer, déduit des chiffres du dossier — <b>jamais</b> d'un jugement du modèle.
     * <p>
     * L'épargne disponible est la SEULE capacité connue du client (aucun revenu ne figure au dossier) : on
     * l'autorise à financer jusqu'à deux fois son épargne, avec un plancher pour qu'un dossier sans épargne
     * puisse quand même porter un petit projet à la consommation. Le crédit immobilier et l'assurance
     * emprunteur immobilier sont EXCEPTÉS : un achat immobilier de plusieurs centaines de milliers d'euros est
     * légitime avec quelques milliers d'euros d'épargne ; seul un garde-fou de vraisemblance s'applique.
     */
    private static BigDecimal projectCeiling(AgentDefinition agent, FinancialSummary summary) {
        String theme = agent.getTheme() == null ? "" : agent.getTheme().toLowerCase(java.util.Locale.ROOT);
        if (theme.startsWith("credit_immo") || theme.startsWith("assurance_emprunteur")) {
            return new BigDecimal("1000000");
        }
        BigDecimal savings = summary == null ? BigDecimal.ZERO : BigDecimal.valueOf(summary.savingsBalance());
        return savings.multiply(new BigDecimal("2")).max(new BigDecimal("30000"));
    }

    /**
     * Refuse un projet dont le montant n'est pas crédible pour le dossier (« achat d'un château » signalé par
     * l'utilisateur). Le montant DÉCLARÉ par le modèle fait foi ; à défaut, les montants cités dans le brief
     * sont contrôlés — le prompt interdit en effet d'y mentionner un autre chiffre que celui du projet.
     */
    private static void requireCredibleAmount(PromptOptimizationModels.ClientBrief brief, AgentDefinition agent,
                                              FinancialSummary summary, BigDecimal ceiling) {
        BigDecimal declared = brief.montantProjet();
        BigDecimal found = declared != null && declared.signum() > 0 ? declared : largestAmountIn(brief.brief());
        if (found == null || found.compareTo(ceiling) <= 0) {
            return;
        }
        BigDecimal savings = summary == null ? BigDecimal.ZERO : BigDecimal.valueOf(summary.savingsBalance());
        throw new IllegalStateException("Projet refusé pour l'agent « " + agent.getLibelle() + " » : "
                + amount(found) + " € à financer alors que l'épargne du client est de " + amount(savings)
                + " € et que le plafond admis pour ce dossier est de " + amount(ceiling)
                + " €. Relancez « Générer projet » : l'agent C doit rester dans l'ordre de grandeur du dossier.");
    }

    /** Plus grand montant cité dans un texte (null si aucun) — borne le projet annoncé dans le brief. */
    private static BigDecimal largestAmountIn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        BigDecimal largest = null;
        Matcher matcher = MONEY_PATTERN.matcher(text);
        while (matcher.find()) {
            BigDecimal value = parseAmount(matcher.group(1));
            if (value != null && (largest == null || value.compareTo(largest) > 0)) {
                largest = value;
            }
        }
        return largest;
    }

    /** « 15 000,50 » / « 15.000 » / « 15000 » → nombre décimal ; null si illisible. */
    private static BigDecimal parseAmount(String raw) {
        String digits = raw.replaceAll("[\\s\\u00A0\\u202F]", "")
                .replace(".", "")
                .replace(',', '.')
                .replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String amount(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * Briefs précédents normalisés : vides retirés, longueur bornée et liste plafonnée (le contexte envoyé au
     * modèle reste volontairement court — il n'a besoin que du PROJET de chacun, pas du brief entier).
     */
    private static List<String> cleanBriefs(List<String> previousBriefs) {
        if (previousBriefs == null) {
            return List.of();
        }
        return previousBriefs.stream()
                .filter(brief -> brief != null && !brief.isBlank())
                .map(String::strip)
                .map(brief -> brief.length() <= 600 ? brief : brief.substring(0, 600) + "…")
                .limit(10)
                .toList();
    }

    /**
     * Les TROIS chiffres que le client connaît (demande explicite) : solde du compte courant, épargne totale et
     * mensualité de crédit en cours. Aucune autre donnée du dossier n'est transmise à l'agent C.
     */
    private static Map<String, Object> clientFigures(FinancialSummary summary) {
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("compteCourant", Map.of(
                "libelle", "Solde du compte courant",
                "montant", summary.currentAccountBalance(),
                "devise", "EUR"));
        figures.put("epargne", Map.of(
                "libelle", "Épargne disponible",
                "montant", summary.savingsBalance(),
                "devise", "EUR"));
        figures.put("creditEnCours", Map.of(
                "libelle", "Mensualité de crédit en cours",
                "montant", summary.monthlyLoanPayments(),
                "devise", "EUR"));
        return figures;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace('\n', ' ').strip();
        return clean.length() <= 80 ? clean : clean.substring(0, 80) + "…";
    }

    /**
     * Demande de démarrage d'une campagne. Les TROIS étapes peuvent utiliser des fournisseurs DIFFÉRENTS
     * (coach, contrôleur, éditeur) : un modèle peut être meilleur pour rédiger la réponse client, un autre
     * pour contrôler, un autre pour réécrire un prompt.
     *
     * @param provider           fournisseur du COACH (celui vu par le client)
     * @param controllerProvider fournisseur de l'Agent B (contrôleur) — {@code null} ⇒ celui du coach
     * @param editorProvider     fournisseur de l'Agent A (éditeur) — {@code null} ⇒ celui du coach
     * @param threadId           FIL DE CONVERSATION à poursuivre — {@code null} ⇒ un nouveau fil est ouvert
     *                           (aucune mémoire). Le fil fournit l'historique transmis au Coach.
     * @param fromCampaignId     CYCLE SOURCE dont on hérite la zone de départ (chaînage des cycles du mode
     *                           automatique de l'Agent C) — {@code null} ⇒ la zone part du prompt de production.
     * @param fromVersion        Version RETENUE du cycle source (sa zone devient la zone de départ).
     */
    public record StartRequest(String agentId, String question, int iterations, String zoneKey,
                              AIModels.AIProvider provider, AIModels.AIProvider controllerProvider,
                              AIModels.AIProvider editorProvider, String threadId,
                              String fromCampaignId, String fromVersion) {

        /** Raccourci historique : les trois étapes utilisent le même fournisseur. */
        public StartRequest(String agentId, String question, int iterations, String zoneKey,
                            AIModels.AIProvider provider) {
            this(agentId, question, iterations, zoneKey, provider, provider, provider, null, null, null);
        }

        /** Raccourci : un fournisseur pour les trois étapes, et un fil de conversation à poursuivre. */
        public StartRequest(String agentId, String question, int iterations, String zoneKey,
                            AIModels.AIProvider provider, String threadId) {
            this(agentId, question, iterations, zoneKey, provider, provider, provider, threadId, null, null);
        }

        /** Raccourci : fournisseurs par étape, sans fil de conversation. */
        public StartRequest(String agentId, String question, int iterations, String zoneKey,
                            AIModels.AIProvider provider, AIModels.AIProvider controllerProvider,
                            AIModels.AIProvider editorProvider) {
            this(agentId, question, iterations, zoneKey, provider, controllerProvider, editorProvider, null,
                    null, null);
        }

        /** Raccourci : les quatre fournisseurs/ fil, sans chaînage. */
        public StartRequest(String agentId, String question, int iterations, String zoneKey,
                            AIModels.AIProvider provider, AIModels.AIProvider controllerProvider,
                            AIModels.AIProvider editorProvider, String threadId) {
            this(agentId, question, iterations, zoneKey, provider, controllerProvider, editorProvider, threadId,
                    null, null);
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

    /**
     * Résultat d'une promotion : campagne mise à jour + sauvegarde du prompt remplacé (§17).
     * <p>
     * {@code backupId} / {@code backupFile} sont VIDES quand la version acceptée est identique au prompt en
     * production (aucune modification proposée) : rien n'a été écrit, il n'y a donc rien à restaurer.
     */
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
        // FIL DE CONVERSATION (§ mémoire de l'atelier) : un fil existant est REPRIS tel quel — les échanges
        // déjà validés par une promotion deviennent l'historique (`conversationHistory`) transmis au Coach,
        // exactement comme dans le chat. Sans fil, le cycle démarre sans mémoire.
        // Validation AVANT toute écriture : un fil inconnu (ou appartenant à un autre agent) ne doit ni
        // clôturer les campagnes en cours ni créer quoi que ce soit.
        PromptOptimizationModels.ConversationThread previousThread = requireThread(request.threadId(), agent);
        String campaignId = nextCampaignId();
        // La REPRISE d'une campagne n'est pas proposée dans l'IHM : une nouvelle campagne clôt donc les
        // précédentes (statut CANCELLED) pour ne jamais bloquer sur « une campagne est déjà en cours ».
        // Les fichiers restent sur disque (rien n'est supprimé), mais ils ne sont plus utilisés.
        closePreviousCampaigns(campaignId);

        // ZONE DE DÉPART du cycle — et CHAÎNAGE éventuel sur la version retenue d'un cycle précédent
        // (§20.10 quater). Validée AVANT tout appel IA : une demande de chaînage incohérente (cycle ou version
        // inconnus, zone d'un autre agent, prompt de production modifié depuis) doit être refusée sans coûter
        // ni classification, ni contexte. Les parties figées viennent du SNAPSHOT source (identiques à celles du
        // fichier tant que personne n'a promu entre-temps : la promotion re-vérifie cette égalité).
        PromptZoneService.Zone zone = zoneService.parse(AgentFiles.readPromptOrDefault(zoneInfo.zoneFile(), ""));
        PromptZoneService.Zone start = inheritZone(request, zone, zoneInfo);

        List<ConversationModels.Message> history = previousThread == null ? List.of() : previousThread.history();
        // La question renvoie souvent au projet de l'échange précédent (« et si je prends 48 mois ? ») :
        // le classifieur reçoit la description du projet de la campagne précédente, comme dans le chat.
        IntentClassification previousClassification = previousThread == null ? null
                : lastClassificationOf(previousThread);
        String currentProjectDescription = describeProject(previousClassification);

        AIService ai = aiServiceFactory.get(provider);
        // La classification est calculée UNE fois puis GELÉE dans le snapshot : c'est un appel IA comptabilisé.
        IntentClassification classification = ai.classifyIntent(question, currentProjectDescription, provider);
        if (classification.isOutOfScope()) {
            throw new IllegalArgumentException("La question de test est hors périmètre du coach financier.");
        }
        CurrentProject project = new CurrentProject();
        if (previousClassification != null && continuesProject(classification)) {
            project.apply(previousClassification);
        }
        project.apply(classification);

        CoachContext context = contextBuilder.build(question, classification, project, history, agent.getTheme());
        String template = AgentFiles.readPromptOrDefault("generic.txt", "");
        String principal = AgentFiles.readPromptOrDefault(AgentFiles.PRINCIPAL_PROMPT_FILE, "");
        String frozenPrompt = AgentFiles.composeSystemPrompt(template, principal,
                start.prefix() + start.editableSection() + start.suffix());

        String providerName = provider.name();
        String snapshotId = "snap-" + campaignId.substring(campaignId.length() - 6);
        PromptOptimizationModels.Snapshot snapshot = new PromptOptimizationModels.Snapshot(snapshotId, campaignId,
                question, agent.getTheme(), AgentFiles.libelleFor(agent.getTheme()),
                zoneInfo.zoneKey(), zoneInfo.zoneFile(), PromptOptimizationModels.versionName(0),
                start.prefix(), start.editableSection(), start.suffix(), startSource(request),
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
        // Le fil est ouvert/associé DÈS le démarrage : la campagne en cours est rattachable à sa
        // conversation (l'IHM affiche la question en attente de promotion) et la promotion y ajoutera
        // l'échange complet (question + réponse de la version promue).
        PromptOptimizationModels.ConversationThread thread = (previousThread == null
                ? new PromptOptimizationModels.ConversationThread(nextThreadId(), agent.getTheme(),
                        AgentFiles.libelleFor(agent.getTheme()), zoneInfo.zoneKey(), List.of(), List.of(),
                        Instant.now().toString(), Instant.now().toString())
                : previousThread).withCampaign(campaignId);
        store.saveThread(thread);
        PromptOptimizationModels.Campaign running = store.saveCampaignAndReturn(update(campaign,
                PromptOptimizationModels.CAMPAIGN_RUNNING, campaign.completedIterations(),
                campaign.currentCandidateVersion(), campaign.aiCalls(), 0L, 0L,
                "", "", "", "", null));
        log.info("Campagne {} démarrée : agent={}, zone={}, itérations demandées={}, fournisseurs={} (coach) / {} (Agent B) / {} (Agent A), fil={} ({} message(s) d'historique)",
                campaignId, agent.getTheme(), zoneInfo.zoneFile(), request.iterations(), provider,
                controllerProvider.name(), editorProvider.name(), thread.threadId(), history.size());
        return running;
    }

    /**
     * Zone de DÉPART du cycle : celle du prompt de production, ou celle d'une version RETENUE d'un cycle
     * précédent quand l'appelant demande un chaînage (`fromCampaignId` + `fromVersion`).
     * <p>
     * Le fichier de production n'est JAMAIS modifié : on compose une zone à partir des parties figées du
     * <b>snapshot source</b> (identiques à celles du fichier au moment de ce cycle-là) et de la section retenue.
     * Si le fichier a changé depuis (promotion, édition manuelle), le chaînage est <b>refusé</b> : composer un
     * prompt hybride ferait échouer la promotion finale — mieux vaut le dire tout de suite.
     */
    private PromptZoneService.Zone inheritZone(StartRequest request, PromptZoneService.Zone fromProduction,
                                               ZoneInfo zoneInfo) {
        String version = request.fromVersion() == null ? "" : request.fromVersion().trim();
        if (version.isEmpty()) {
            return fromProduction;
        }
        String sourceId = request.fromCampaignId() == null ? "" : request.fromCampaignId().trim();
        if (sourceId.isEmpty()) {
            throw new IllegalArgumentException("Chaînage incomplet : le cycle source est obligatoire avec une version.");
        }
        PromptOptimizationModels.Campaign source = store.require(sourceId);
        if (!source.zoneFile().equals(zoneInfo.zoneFile())) {
            throw new IllegalArgumentException("Le cycle " + sourceId + " optimise « " + source.zoneFile()
                    + " » et non « " + zoneInfo.zoneFile() + " » : impossible d'enchaîner sur sa version retenue.");
        }
        if (source.isActive()) {
            throw new IllegalStateException("Le cycle " + sourceId + " tourne encore : terminez-le avant "
                    + "d'enchaîner un nouveau cycle sur sa version retenue.");
        }
        String section = store.editableSectionOf(sourceId, version).orElseThrow(() -> new IllegalArgumentException(
                "Version inconnue dans le cycle " + sourceId + " : " + version));
        PromptOptimizationModels.Snapshot sourceSnapshot = store.snapshot(sourceId).orElseThrow(
                () -> new IllegalStateException("Snapshot introuvable pour le cycle " + sourceId));
        if (!fromProduction.prefix().equals(sourceSnapshot.fixedPrefix())
                || !fromProduction.suffix().equals(sourceSnapshot.fixedSuffix())) {
            throw new IllegalStateException("Le prompt de « " + zoneInfo.zoneFile() + " » a été modifié depuis le "
                    + "cycle " + sourceId + " : impossible d'enchaîner sur sa version retenue (rien n'a été écrit). "
                    + "Promouvez d'abord cette version, ou relancez un cycle normal.");
        }
        return new PromptZoneService.Zone(true, sourceSnapshot.fixedPrefix(), section, sourceSnapshot.fixedSuffix(),
                null);
    }

    /** Origine de la zone de départ, telle qu'elle est FIGÉE dans le snapshot ({@code <campagne>:<version>}). */
    private static String startSource(StartRequest request) {
        String version = request.fromVersion() == null ? "" : request.fromVersion().trim();
        String sourceId = request.fromCampaignId() == null ? "" : request.fromCampaignId().trim();
        return version.isEmpty() || sourceId.isEmpty() ? "" : sourceId + ":" + version;
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
        /** Motif NON bloquant : le Coach a demandé des données que le contexte ne contient pas. */
        String needDataWarning = "";
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
                    break; // borne atteinte : on dégrade comme en production (jamais de blocage)
                }
                completions++;
                int providedBefore = snapshot.providedData().size();
                PromptOptimizationModels.Snapshot completed = completeContext(campaign, snapshot, answer);
                if (completed == null) {
                    break; // rien à fournir : on dégrade comme en production (jamais de blocage)
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
            if (answer.status() == AIModels.AIStatus.NEED_DATA) {
                // EXACTEMENT comme en PRODUCTION : une demande de données que le catalogue ne permet pas de
                // fournir ne bloque JAMAIS l'itération (elle interromprait la conversation et empêcherait toute
                // décision). L'itération est CONSERVÉE avec la réponse de repli — celle que le client recevrait
                // vraiment — et le motif est tracé dans `error` : c'est une information utile à l'optimisation du
                // prompt (le prompt demande des données que le contexte ne contient pas), pas un échec.
                String requested = requestedPaths(answer);
                needDataWarning = NEED_DATA_MESSAGE + requested;
                answer = new AIModels.AIAnswer(AIModels.AIStatus.ANSWER, UNAVAILABLE_DATA_ANSWER, null, Map.of(),
                        "", null);
                log.info("Itération {} de la campagne {} : demande de données insatisfiable — itération dégradée "
                        + "comme en production{}", number, campaignId, requested);
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
                humanApplied, contextAddedData, status, error.isBlank() ? needDataWarning : error, campaign.provider(),
                campaign.controllerProvider(), campaign.editorProvider(), campaign.model(), promptChars, duration,
                startedAt, Instant.now().toString());
        store.appendIteration(iteration);

        // RELECTURE de l'état avant d'écrire : un STOP a pu être demandé PENDANT les appels IA (autre
        // thread, l'arrêt §11 doit rester gracieux et ne jamais être écrasé par l'itération en cours).
        PromptOptimizationModels.Campaign latest = store.require(campaignId);
        String campaignStatus = nextCampaignStatus(latest, status, number);
        // PLATEAU : si l'éditeur ne propose plus rien depuis plusieurs itérations, on ARRÊTE le cycle au lieu
        // de brûler les itérations restantes. Signal DÉTERMINISTE (aucun « tag » demandé au modèle) : une
        // itération sans nouvelle version est une itération où `resultingVersion == promptVersion`.
        String plateauMessage = "";
        if (PromptOptimizationModels.CAMPAIGN_RUNNING.equals(campaignStatus)) {
            int withoutChange = consecutiveIterationsWithoutChange(campaignId);
            if (withoutChange >= PromptOptimizationModels.MAX_CONSECUTIVE_NO_CHANGE) {
                campaignStatus = PromptOptimizationModels.CAMPAIGN_PAUSED;
                plateauMessage = PromptOptimizationModels.plateauMessage(withoutChange,
                        Math.max(0, latest.requestedIterations() - number));
                log.info("Campagne {} mise en pause (plateau) : {} itérations consécutives sans modification",
                        campaignId, withoutChange);
            }
        }
        boolean paused = PromptOptimizationModels.CAMPAIGN_PAUSED.equals(campaignStatus);
        boolean failed = PromptOptimizationModels.CAMPAIGN_ERROR.equals(campaignStatus);
        store.saveCampaignAndReturn(update(latest, campaignStatus, number, resultingVersion,
                latest.aiCalls() + aiCalls, promptChars, duration,
                failed ? error : plateauMessage, failed ? step : "",
                PromptOptimizationModels.CAMPAIGN_STOP_REQUESTED.equals(campaignStatus) ? Instant.now().toString()
                        : latest.stopRequestedAt(),
                paused ? Instant.now().toString() : latest.pausedAt(), null));

        logIteration(campaign, snapshot, iteration, composedPrompt);
        return iteration;
    }

    /**
     * Nombre d'itérations CONSÉCUTIVES, en fin de campagne, sans AUCUNE nouvelle version (l'éditeur n'a plus
     * rien proposé). Le comptage s'arrête dès qu'une itération a produit une version (ou s'est soldée par une
     * erreur, cas déjà traité par le statut ERROR).
     */
    private int consecutiveIterationsWithoutChange(String campaignId) {
        List<PromptOptimizationModels.Iteration> iterations = new ArrayList<>(store.iterations(campaignId).values());
        iterations.sort(Comparator.comparingInt(PromptOptimizationModels.Iteration::iterationNumber));
        int count = 0;
        for (int i = iterations.size() - 1; i >= 0; i--) {
            PromptOptimizationModels.Iteration iteration = iterations.get(i);
            if (PromptOptimizationModels.ITERATION_ERROR.equals(iteration.status())
                    || !iteration.resultingVersion().equals(iteration.promptVersion())) {
                break;
            }
            count++;
        }
        return count;
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
     * <p>
     * Cas particulier INDISPENSABLE : quand l'Agent A n'a proposé aucune modification, la version promue est
     * identique au prompt en production. La campagne est alors simplement ACCEPTÉE, <b>sans aucune écriture</b>
     * (ni sauvegarde, ni réécriture) : c'est ce qui permet d'enchaîner la conversation au lieu de rester bloqué
     * sur une campagne qui ne produira jamais de version.
     * <p>
     * MÉMOIRE : la réponse enregistrée dans le fil de conversation est celle <b>produite par la version
     * acceptée</b> (réutilisée si une itération a déjà répondu avec elle, sinon RÉGÉNÉRÉE par un appel au Coach
     * — c'est ce qui ajoute 1 appel IA à la campagne).
     */
    public PromotionResult promoteVersion(String campaignId, String version) {
        return decideVersion(campaignId, version, true);
    }

    /**
     * ACCEPTATION d'une version SANS toucher au prompt de production (« retenir pour la conversation »).
     * <p>
     * Demandée explicitement pour le <b>mode automatique de l'Agent C</b> : le scénario doit pouvoir
     * s'enchaîner (la réponse de la version acceptée entre dans la conversation, donc le client a une mémoire)
     * <b>sans écraser le prompt de production à chaque cycle</b>. La décision d'écrire reste humaine, à la fin,
     * après comparaison début ↔ fin. Rien n'est sauvegardé ni réécrit : aucune version de prompt n'est perdue.
     */
    public PromotionResult acceptVersion(String campaignId, String version) {
        return decideVersion(campaignId, version, false);
    }

    /**
     * Décision de version : ACCEPTÉE pour la conversation, avec ou sans écriture du prompt de production.
     * <p>
     * {@code writeProduction = true} ⇒ PROMOTION : le prompt actuel est sauvegardé avant remplacement
     * (retour arrière possible), puis le fichier de la zone est réécrit en ne changeant QUE la zone.
     * {@code false} ⇒ ACCEPTATION : le fil de conversation est alimenté, la campagne est close, mais le
     * fichier de production n'est ni sauvegardé ni réécrit.
     * <p>
     * Cas particulier INDISPENSABLE : quand l'Agent A n'a proposé aucune modification, la version acceptée est
     * identique au prompt en production. La campagne est alors simplement ACCEPTÉE, <b>sans aucune écriture</b>
     * (ni sauvegarde, ni réécriture) : c'est ce qui permet d'enchaîner la conversation au lieu de rester bloqué
     * sur une campagne qui ne produira jamais de version.
     * <p>
     * MÉMOIRE : la réponse enregistrée dans le fil de conversation est celle <b>produite par la version
     * acceptée</b> (réutilisée si une itération a déjà répondu avec elle, sinon RÉGÉNÉRÉE par un appel au Coach
     * — c'est ce qui ajoute 1 appel IA à la campagne).
     */
    private PromotionResult decideVersion(String campaignId, String version, boolean writeProduction) {
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
            String currentContent = AgentFiles.readPromptOrDefault(snapshot.zoneFile(), "");
            PromptZoneService.Zone zone = zoneService.parse(currentContent);
            String updatedContent = "";
            boolean unchanged = true;
            if (writeProduction) {
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
                if (!zone.valid()) {
                    throw new IllegalStateException("Le prompt cible n'a plus de zone éditable valide : "
                            + zone.error());
                }
                if (!zone.prefix().equals(snapshot.fixedPrefix())
                        || !zone.suffix().equals(snapshot.fixedSuffix())) {
                    throw new IllegalStateException("Le prompt de l'agent a été modifié depuis le snapshot de la "
                            + "campagne : la promotion est refusée pour éviter d'écraser une modification externe.");
                }
            }
            String rejection = validateSection(section);
            if (rejection != null) {
                throw new IllegalStateException("Version non promouvable : " + rejection);
            }
            // ACCEPTATION SANS CHANGEMENT : quand l'Agent A n'a rien proposé, aucune version nouvelle n'existe
            // (toutes les itérations sont « sans modification »). Pouvoir accepter la campagne TELLE QUELLE est
            // indispensable : sans cela, l'humain resterait bloqué et ne pourrait pas enchaîner la conversation.
            // Dans ce cas le contenu recomposé est IDENTIQUE au fichier en production : on n'écrit RIEN (ni
            // sauvegarde, ni réécriture) — il n'y a donc rien à écraser.
            String backupId = "";
            String backupFile = "";
            if (writeProduction) {
                updatedContent = preserveLineEndings(currentContent, zoneService.compose(zone, section));
                unchanged = updatedContent.equals(currentContent);
                if (unchanged) {
                    log.info("Campagne {} acceptée SANS modification du prompt {} (version {}) : aucune écriture",
                            campaignId, key, version);
                } else {
                    AgentPromptHistoryStore.PromptBackup backup = historyStore.backup(key, snapshot.zoneFile(),
                            currentContent, "promotion de " + version + " (campagne " + campaignId + ")");
                    agentPromptStore.write(key, updatedContent);
                    backupId = backup.backupId();
                    backupFile = backup.backupFile();
                    log.info("Version {} promue pour l'agent {} (campagne {})", version, key, campaignId);
                }
            } else {
                log.info("Version {} ACCEPTÉE pour la conversation (campagne {}), sans modification du prompt de "
                        + "production {}", version, campaignId, snapshot.zoneFile());
            }
            // MÉMOIRE DE L'ATELIER : c'est la RÉPONSE DE LA VERSION ACCEPTÉE qui entre dans la conversation —
            // celle que le client recevrait avec le prompt désormais en production.
            // 1) si une itération a RÉELLEMENT répondu avec cette version, sa réponse est réutilisée (aucun
            //    appel IA) ; 2) sinon (la version vient d'être produite par la dernière itération) la réponse
            //    est GÉNÉRÉE en rejouant le Coach avec cette version : le tour de la conversation porte donc
            //    toujours la réponse du prompt promu, jamais celle qui a motivé le changement.
            boolean threadExists = store.threadOf(campaignId).isPresent();
            String threadAnswer = "";
            boolean answerRegenerated = false;
            int extraAiCalls = 0;
            if (threadExists) {
                AcceptedAnswer accepted = acceptedAnswerOf(campaign, snapshot, version);
                threadAnswer = accepted.content();
                answerRegenerated = accepted.regenerated() && !threadAnswer.isBlank();
                extraAiCalls = answerRegenerated ? accepted.aiCalls() : 0;
                if (threadAnswer.isBlank()) {
                    // Repli (aucune réponse exploitable) : on n'écrit JAMAIS un tour vide dans l'historique.
                    threadAnswer = lastCoachResponse(campaignId);
                    answerRegenerated = false;
                    extraAiCalls = 0;
                    log.warn("Campagne {} : aucune réponse exploitable pour la version {} — repli sur la "
                            + "dernière réponse connue de la campagne", campaignId, version);
                }
            }
            PromptOptimizationModels.Campaign promoted = store.saveCampaignAndReturn(
                    update(campaign, PromptOptimizationModels.CAMPAIGN_ACCEPTED, campaign.completedIterations(),
                            campaign.currentCandidateVersion(), campaign.aiCalls() + extraAiCalls,
                            0L, 0L, "", "", campaign.stopRequestedAt(), campaign.pausedAt(), version));
            if (threadExists) {
                String answer = threadAnswer;
                store.threadOf(campaignId).ifPresent(thread -> store.saveThread(
                        thread.withExchange(campaignId, campaign.question(), answer, version)));
            }
            String conversation = threadExists
                    ? (answerRegenerated
                            ? " La réponse de cette version vient d'être générée avec ce prompt et rejoint la "
                                    + "conversation de l'atelier."
                            : " La réponse produite par cette version rejoint la conversation de l'atelier.")
                    : "";
            return new PromotionResult(promoted, backupId, backupFile, (!writeProduction
                    ? "Version " + version + " ACCEPTÉE pour la conversation : le prompt de production de « "
                            + key + " » n'a PAS été modifié. Utilisez « Promouvoir » pour l'appliquer."
                    : unchanged
                            ? "Aucune modification n'a été proposée : le prompt de « " + key + " » reste identique "
                                    + "(version " + version + " acceptée, aucune écriture)."
                            : "La version " + version + " remplace la zone du prompt de « " + key
                                    + " ». Le prompt précédent est conservé dans l'historique (" + backupFile + ")")
                    + conversation);
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

    // --- Fils de conversation (mémoire de l'atelier) -------------------------------------------------

    /** Fils connus, du plus récemment modifié au plus ancien (l'IHM reprend la conversation en cours). */
    public List<PromptOptimizationModels.ConversationThread> threads() {
        return store.threads();
    }

    /** Fil demandé : un identifiant inconnu est une erreur LISIBLE (jamais un fil vide silencieux). */
    public PromptOptimizationModels.ConversationThread thread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("Fil de conversation non précisé.");
        }
        return store.thread(threadId.trim()).orElseThrow(
                () -> new IllegalArgumentException("Fil de conversation inconnu : " + threadId));
    }

    /** Fil auquel appartient une campagne ({@code null} si elle n'en a pas : campagne antérieure). */
    public PromptOptimizationModels.ConversationThread threadOfCampaign(String campaignId) {
        store.require(campaignId);
        return store.threadOf(campaignId).orElse(null);
    }

    /**
     * Corrige le contenu d'un tour du fil : l'humain garde la main sur ce qui sera rejoué au cycle suivant
     * (une réponse mal attribuée viciérait l'optimisation du prompt).
     */
    public PromptOptimizationModels.ConversationThread updateTurn(String threadId, int index, String content) {
        PromptOptimizationModels.ConversationThread thread = thread(threadId);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Le contenu du tour est vide.");
        }
        PromptOptimizationModels.ConversationThread updated = thread.withTurnContent(index, text);
        store.saveThread(updated);
        return updated;
    }

    /**
     * BILAN début ↔ fin d'une conversation : le prompt du PREMIER cycle face au prompt en vigueur à la fin
     * (dernière version réellement PROMUE).
     * <p>
     * La comparaison d'une campagne ne montre qu'un cycle (une question) : celle-ci montre l'effet cumulé de
     * toute la conversation — c'est le bouton « comparer le prompt initial et le prompt final » de l'IHM.
     * Quand aucune version n'a été promue, les deux prompts sont identiques (rien n'a changé) : c'est une
     * information, pas une erreur.
     */
    public PromptOptimizationModels.ConversationComparison comparisonOfThread(String threadId) {
        PromptOptimizationModels.ConversationThread thread = thread(threadId);
        if (thread.campaignIds().isEmpty()) {
            throw new IllegalStateException("Aucun cycle n'a encore été mené dans cette conversation : "
                    + "il n'y a rien à comparer.");
        }
        List<PromptOptimizationModels.Campaign> cycles = new ArrayList<>();
        for (String campaignId : thread.campaignIds()) {
            cycles.add(campaign(campaignId));
        }
        PromptOptimizationModels.Campaign first = cycles.get(0);
        PromptOptimizationModels.Campaign lastAccepted = null;
        int promotions = 0;
        int iterations = 0;
        for (PromptOptimizationModels.Campaign cycle : cycles) {
            iterations += cycle.completedIterations();
            if (!cycle.promotedVersion().isBlank()) {
                promotions++;
                lastAccepted = cycle;
            }
        }
        // Début = version de référence du PREMIER cycle ; fin = dernière version PROMUE (sinon rien n'a bougé).
        String baseCampaign = first.campaignId();
        String baseVersion = first.basePromptVersion();
        String finalCampaign = lastAccepted == null ? baseCampaign : lastAccepted.campaignId();
        String finalVersion = lastAccepted == null ? baseVersion : lastAccepted.promotedVersion();
        String zoneFile = first.zoneFile().isBlank()
                ? cycles.get(cycles.size() - 1).zoneFile() : first.zoneFile();

        String baseSection = store.editableSectionOf(baseCampaign, baseVersion).orElse("");
        String finalSection = store.editableSectionOf(finalCampaign, finalVersion).orElse("");
        String basePrompt = promptFor(baseCampaign, baseVersion);
        String finalPrompt = promptFor(finalCampaign, finalVersion);
        return new PromptOptimizationModels.ConversationComparison(thread.threadId(), thread.agentId(),
                thread.agentLibelle(), thread.zoneKey(), zoneFile, baseVersion, finalVersion, baseCampaign,
                finalCampaign, baseSection, finalSection, basePrompt, finalPrompt, cycles.size(), iterations,
                promotions, basePrompt.equals(finalPrompt), "");
    }

    /** Prompt système COMPLET d'une version (parties figées + zone de cette version) — IHM « Voir le prompt ». */
    public String promptFor(String campaignId, String version) {
        PromptOptimizationModels.Snapshot snapshot = snapshot(campaignId);
        String section = store.editableSectionOf(campaignId, version).orElse("");
        return composePrompt(snapshot, section);
    }

    /**
     * Cette version est-elle DÉJÀ celle du fichier de production ?
     * <p>
     * Sert à l'IHM : ne proposer « Promouvoir » que sur une version qui n'est pas encore en production, et
     * afficher « à promouvoir » après une ACCEPTATION SANS ÉCRITURE (le mode automatique de l'Agent C
     * n'écrase plus le prompt : l'humain décide à la fin).
     * <p>
     * La comparaison porte sur la <b>zone éditable</b> du fichier — marqueurs et parties figées exclus, comme
     * dans une version — et elle est NORMALISÉE (fins de ligne et espaces de bord) : le fichier de production
     * est en CRLF alors que les versions sont stockées en LF, une comparaison brute serait toujours fausse.
     */
    public boolean appliedInProduction(String campaignId, String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        PromptOptimizationModels.Campaign campaign = campaign(campaignId);
        String production = AgentFiles.readPromptOrDefault(campaign.zoneFile(), "");
        if (production.isBlank()) {
            return false;
        }
        String section = store.editableSectionOf(campaignId, version).orElse(null);
        if (section == null) {
            return false;
        }
        PromptZoneService.Zone zone = zoneService.parse(production);
        return zone.valid()
                && normalizeNewlines(zone.editableSection()).equals(normalizeNewlines(section));
    }

    /** Comparaison de deux versions d'un même prompt, insensible aux fins de ligne et aux espaces de bord. */
    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").strip();
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

    /**
     * FIL DE CONVERSATION demandé, ou {@code null} (nouveau fil). Un identifiant inconnu et un fil
     * appartenant à un AUTRE agent sont refusés explicitement : reprendre la conversation d'un autre agent
     * produirait un historique incohérent (le Coach verrait des échanges d'un autre métier).
     */
    private PromptOptimizationModels.ConversationThread requireThread(String threadId, AgentDefinition agent) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        PromptOptimizationModels.ConversationThread thread = store.thread(threadId.trim()).orElseThrow(
                () -> new IllegalArgumentException("Fil de conversation inconnu : " + threadId));
        if (!thread.agentId().equalsIgnoreCase(agent.getTheme())) {
            throw new IllegalArgumentException("Le fil de conversation " + thread.threadId() + " appartient à "
                    + "l'agent « " + thread.agentLibelle() + " » : sélectionnez cet agent ou démarrez une "
                    + "nouvelle conversation.");
        }
        return thread;
    }

    /** Classification GELÉE de la dernière campagne du fil (projet de l'échange précédent). */
    private IntentClassification lastClassificationOf(PromptOptimizationModels.ConversationThread thread) {
        if (thread == null || thread.campaignIds().isEmpty()) {
            return null;
        }
        String previous = thread.campaignIds().get(thread.campaignIds().size() - 1);
        return store.snapshot(previous).map(PromptOptimizationModels.Snapshot::classification).orElse(null);
    }

    /** Description du projet précédent, au FORMAT attendu par le classifieur (même contrat que le chat). */
    private static String describeProject(IntentClassification classification) {
        if (classification == null || classification.getProjectType() == null
                || classification.getProjectType() == ProjectType.UNKNOWN) {
            return "";
        }
        StringBuilder text = new StringBuilder("type = ").append(classification.getProjectType());
        if (classification.getProjectObject() != null && !classification.getProjectObject().isBlank()) {
            text.append("\nobject = ").append(classification.getProjectObject());
        }
        if (classification.getAmount() != null) {
            text.append("\namount = ").append(classification.getAmount());
        }
        return text.toString();
    }

    /**
     * La nouvelle question RENVOIE-t-elle au projet de l'échange précédent ? Même logique que le chat : le
     * projet courant n'est conservé que si la question ne porte pas de projet propre et n'annonce aucun
     * changement (« et si je prends 48 mois ? » reste dans le projet précédent).
     */
    private static boolean continuesProject(IntentClassification classification) {
        ProjectType type = classification.getProjectType();
        boolean ownProject = type != null && type != ProjectType.UNKNOWN && type != ProjectType.OTHER_FINANCIAL;
        return !ownProject && !classification.isProjectChanged();
    }

    /**
     * Réponse de la version ACCEPTÉE : contenu + provenance (réutilisée d'une itération, ou régénérée).
     * {@code aiCalls} compte les appels IA réellement consommés (0 ou 1).
     */
    private record AcceptedAnswer(String content, boolean regenerated, int aiCalls) {
    }

    /**
     * Réponse du Coach produite PAR la version acceptée — c'est elle qui entre dans la conversation, puisque
     * c'est le prompt désormais en production (c'est donc la réponse que le client recevrait).
     * <p>
     * 1) Si une itération a réellement RÉPONDU avec cette version, sa réponse existe : on la réutilise (la plus
     * récente fait foi, aucun appel IA).
     * <p>
     * 2) Sinon — cas courant : la version vient d'être produite par la dernière itération, elle n'a donc jamais
     * répondu — la réponse est GÉNÉRÉE en rejouant le Coach avec cette version. La conversation ne contient
     * jamais la réponse qui a « motivé » le changement (générée avec la version PRÉCÉDENTE), sinon l'historique
     * rejoué au cycle suivant décrirait un prompt qui n'est plus celui en production.
     */
    private AcceptedAnswer acceptedAnswerOf(PromptOptimizationModels.Campaign campaign,
                                            PromptOptimizationModels.Snapshot snapshot, String version) {
        List<PromptOptimizationModels.Iteration> iterations = sortedIterations(campaign.campaignId());
        for (int index = iterations.size() - 1; index >= 0; index--) {
            PromptOptimizationModels.Iteration iteration = iterations.get(index);
            if (version != null && version.equals(iteration.promptVersion()) && !iteration.coachResponse().isBlank()) {
                return new AcceptedAnswer(iteration.coachResponse(), false, 0);
            }
        }
        String replayed = replayCoachWithVersion(campaign, snapshot, version);
        return new AcceptedAnswer(replayed, true, replayed.isBlank() ? 0 : 1);
    }

    /**
     * REJOUE le Coach avec le prompt de la version demandée et le contexte FIGÉ du snapshot : c'est exactement
     * l'appel d'une itération (même question, mêmes données, même historique), mais sans Agent B ni Agent A.
     * La boucle {@code NEED_DATA} est bornée comme en production (le contexte de référence est alors enrichi).
     */
    private String replayCoachWithVersion(PromptOptimizationModels.Campaign campaign,
                                          PromptOptimizationModels.Snapshot snapshot, String version) {
        String section = store.editableSectionOf(campaign.campaignId(), version).orElse("");
        AIModels.AIProvider provider = coachProviderOf(campaign);
        AIService ai = aiServiceFactory.get(provider);
        try {
            PromptOptimizationModels.Snapshot context = snapshot;
            String composed = composePrompt(context, section);
            AIModels.AIAnswer answer = ai.answerWithSystemPrompt(composed, context.question(),
                    legacyOf(context.classification()), context.financialSummary(), context.catalog(),
                    AIModels.BankingContextMode.SYNTHESIS_AVAILABLE, context.additionalData(), context.history(),
                    provider);
            int completions = 0;
            while (answer.status() == AIModels.AIStatus.NEED_DATA && completions < MAX_CONTEXT_COMPLETIONS) {
                completions++;
                PromptOptimizationModels.Snapshot completed = completeContext(campaign, context, answer);
                if (completed == null) {
                    break;
                }
                context = completed;
                composed = composePrompt(context, section);
                answer = ai.answerWithSystemPrompt(composed, context.question(), legacyOf(context.classification()),
                        context.financialSummary(), context.catalog(), AIModels.BankingContextMode.SYNTHESIS_AVAILABLE,
                        context.additionalData(), context.history(), provider);
            }
            log.info("Réponse de la version {} générée par rejeu du Coach (campagne {})", version,
                    campaign.campaignId());
            if (answer.status() == AIModels.AIStatus.NEED_DATA) {
                // Même dégradation qu'en itération (et qu'en production) : jamais de tour vide dans la mémoire.
                log.info("Rejeu de la version {} (campagne {}) : demande de données insatisfiable{} — réponse de "
                        + "repli utilisée", version, campaign.campaignId(), requestedPaths(answer));
                return UNAVAILABLE_DATA_ANSWER;
            }
            return answer.answer() == null ? "" : answer.answer();
        } catch (Exception e) {
            // La promotion, elle, est DÉJÀ faite : un échec de génération ne doit pas la remettre en cause.
            log.warn("Rejeu du Coach pour la version {} de la campagne {} impossible : {}", version,
                    campaign.campaignId(), errorMessage(e));
            return "";
        }
    }

    /** Dernière réponse non vide de la campagne (repli : la conversation ne contient jamais de tour vide). */
    private String lastCoachResponse(String campaignId) {
        List<PromptOptimizationModels.Iteration> iterations = sortedIterations(campaignId);
        for (int index = iterations.size() - 1; index >= 0; index--) {
            if (!iterations.get(index).coachResponse().isBlank()) {
                return iterations.get(index).coachResponse();
            }
        }
        return "";
    }

    private List<PromptOptimizationModels.Iteration> sortedIterations(String campaignId) {
        List<PromptOptimizationModels.Iteration> iterations = new ArrayList<>(store.iterations(campaignId).values());
        iterations.sort(Comparator.comparingInt(PromptOptimizationModels.Iteration::iterationNumber));
        return iterations;
    }

    private static String nextThreadId() {
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
        return "th-" + stamp + "-" + java.util.UUID.randomUUID().toString().substring(0, 4);
    }

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

    /**
     * Contexte transmis au CONTRÔLEUR : tout le nécessaire pour juger, sans dupliquer les données jointes.
     * <p>
     * PARITÉ AVEC LE COACH : il juge le RESPECT DU PROMPT, il reçoit donc <b>exactement</b> ce que le Coach a
     * reçu — le prompt système tel qu'il a été envoyé ({@code coachPrompt}, même chaîne, marqueurs de zone
     * exclus), la classification FIGÉE et la liste des données disponibles ({@code availableData}) — en plus du
     * contenu des données réellement fournies.
     */
    private Map<String, Object> controllerContext(PromptOptimizationModels.Snapshot snapshot,
                                                 PromptOptimizationModels.Campaign campaign, int number,
                                                 String section, String coachResponse, String composedPrompt,
                                                 PromptOptimizationModels.Iteration previous) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("agentId", campaign.agentId());
        context.put("agentLibelle", campaign.agentLibelle());
        context.put("iterationNumber", number);
        context.put("question", snapshot.question());
        context.put("classification", snapshot.classification());
        context.put("availableData", snapshot.catalog() == null ? List.of() : snapshot.catalog());
        // MÉMOIRE DE L'ATELIER : l'historique COMPLET est fourni pour COMPRENDRE le contexte. Le jugement,
        // lui, porte sur le SEUL échange courant (`question` + `coachResponse`) : les réponses déjà validées
        // ne sont jamais réévaluées (contrat du prompt de l'Agent B).
        context.put("conversationHistory", snapshot.history());
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
        // MÊME MÉMOIRE QUE LE CONTRÔLEUR : l'éditeur sait que la question est un SUIVI — il peut donc écrire
        // des règles de continuité (ne pas redemander ce qui a déjà été donné, rappeler l'échange précédent)
        // au lieu de règles valables seulement pour une première question.
        context.put("conversationHistory", snapshot.history());
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
                snapshot.initialEditableSection(), snapshot.fixedSuffix(), snapshot.baseZoneSource(),
                snapshot.frozenSystemPrompt(), snapshot.frozenTemplate(), snapshot.frozenPrincipal(),
                snapshot.classification(),
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
