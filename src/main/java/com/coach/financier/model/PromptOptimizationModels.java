package com.coach.financier.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Modèles de l'ATELIER d'amélioration itérative des prompts (agents internes « Agent B » contrôleur
 * et « Agent A » éditeur).
 * <p>
 * Deux principes structurants :
 * <ul>
 *   <li>les sorties des agents internes sont du JSON produit par un LLM : le parsing est TOLÉRANT
 *   (champs absents, listes nulles, valeurs d'énumération inconnues) et normalisé côté backend ;</li>
 *   <li>un statut ILLISIBLE n'est jamais interprété comme « tout va bien » (cela figerait la campagne
 *   à tort) ni comme une modification à appliquer : on retombe respectivement sur
 *   {@code NEEDS_IMPROVEMENT} et {@code HUMAN_OR_BUSINESS_REVIEW_REQUIRED}.</li>
 * </ul>
 * Aucune valeur brute de code technique n'est destinée à l'affichage : les IHM utilisent les
 * méthodes {@code *Label}.
 */
public final class PromptOptimizationModels {

    private PromptOptimizationModels() {
    }

    // --- Statuts du contrôleur qualité (Agent B) -------------------------------------------------

    /** La réponse satisfait les critères : aucune modification du prompt n'est justifiée. */
    public static final String STATUS_GOOD = "GOOD";
    public static final String STATUS_NEEDS_IMPROVEMENT = "NEEDS_IMPROVEMENT";
    public static final String STATUS_BAD = "BAD";

    // --- Statuts de l'éditeur de prompts (Agent A) ----------------------------------------------

    public static final String EDITOR_UPDATED = "UPDATED";
    public static final String EDITOR_NO_CHANGE = "NO_CHANGE_REQUIRED";
    public static final String EDITOR_REVIEW_REQUIRED = "HUMAN_OR_BUSINESS_REVIEW_REQUIRED";

    // --- Sévérités (Agent B) --------------------------------------------------------------------

    public static final String SEVERITY_LOW = "LOW";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_HIGH = "HIGH";

    // --- Origine du problème (Agent B) ----------------------------------------------------------

    public static final String SOURCE_PROMPT = "PROMPT";
    public static final String SOURCE_DATA = "DATA";
    public static final String SOURCE_BACKEND_RULE = "BACKEND_RULE";
    public static final String SOURCE_MODEL_VARIABILITY = "MODEL_VARIABILITY";
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    // --- Types d'issue (Agent B) ----------------------------------------------------------------

    public static final String TYPE_OTHER = "OTHER";

    /** Types d'issue possibles (le prompt du contrôleur réutilise ces valeurs). */
    public static final List<String> ISSUE_TYPES = List.of(
            "UNANSWERED_REQUEST", "PARTIAL_ANSWER", "CONTEXT_NOT_USED", "INSUFFICIENT_PERSONALIZATION",
            "EXCESSIVE_REPETITION", "EXCESSIVE_LENGTH", "INSUFFICIENT_EXPLANATION", "UNNECESSARY_DETAIL",
            "MISUNDERSTOOD_PROJECT", "UNSUPPORTED_CLAIM", "INVENTED_DATA", "INVENTED_PRODUCT", "INVENTED_URL",
            "PRODUCT_MISMATCH", "RULE_VIOLATION", "POOR_PEDAGOGY", "UNPROFESSIONAL_TONE", "CONTEXT_LOST",
            TYPE_OTHER);

    // --- Normalisations (parsing tolérant) ------------------------------------------------------

    /** Statut du contrôleur ; une valeur illisible devient {@code NEEDS_IMPROVEMENT} (jamais GOOD). */
    public static String normalizeControllerStatus(String raw) {
        return normalize(raw, List.of(STATUS_GOOD, STATUS_NEEDS_IMPROVEMENT, STATUS_BAD), STATUS_NEEDS_IMPROVEMENT);
    }

    /** Statut de l'éditeur ; une valeur illisible devient {@code HUMAN_OR_BUSINESS_REVIEW_REQUIRED}. */
    public static String normalizeEditorStatus(String raw) {
        return normalize(raw, List.of(EDITOR_UPDATED, EDITOR_NO_CHANGE, EDITOR_REVIEW_REQUIRED),
                EDITOR_REVIEW_REQUIRED);
    }

    public static String normalizeSeverity(String raw) {
        return normalize(raw, List.of(SEVERITY_LOW, SEVERITY_MEDIUM, SEVERITY_HIGH), SEVERITY_MEDIUM);
    }

    public static String normalizeSource(String raw) {
        return normalize(raw, List.of(SOURCE_PROMPT, SOURCE_DATA, SOURCE_BACKEND_RULE,
                SOURCE_MODEL_VARIABILITY, SOURCE_UNKNOWN), SOURCE_UNKNOWN);
    }

    /** Type d'issue : valeur libre en majuscules (le contrôleur peut en proposer d'autres). */
    public static String normalizeIssueType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TYPE_OTHER;
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalize(String raw, List<String> allowed, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return allowed.contains(candidate) ? candidate : fallback;
    }

    private static List<String> cleanList(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    // --- Libellés lisibles (aucun code technique à l'écran) --------------------------------------

    /** Libellé lisible d'un statut de contrôle, ou le code humanisé si inconnu. */
    public static String controllerStatusLabel(String status) {
        if (STATUS_GOOD.equals(status)) return "Aucune amélioration nécessaire";
        if (STATUS_NEEDS_IMPROVEMENT.equals(status)) return "Amélioration nécessaire";
        if (STATUS_BAD.equals(status)) return "Réponse non conforme";
        return humanize(status);
    }

    /** Libellé lisible d'un statut d'édition, ou le code humanisé si inconnu. */
    public static String editorStatusLabel(String status) {
        if (EDITOR_UPDATED.equals(status)) return "Zone modifiée";
        if (EDITOR_NO_CHANGE.equals(status)) return "Aucune modification";
        if (EDITOR_REVIEW_REQUIRED.equals(status)) return "Revue humaine requise";
        return humanize(status);
    }

    public static String severityLabel(String severity) {
        if (SEVERITY_LOW.equals(severity)) return "Mineure";
        if (SEVERITY_MEDIUM.equals(severity)) return "Significative";
        if (SEVERITY_HIGH.equals(severity)) return "Majeure";
        return humanize(severity);
    }

    public static String sourceLabel(String source) {
        if (SOURCE_PROMPT.equals(source)) return "Prompt (zone éditable)";
        if (SOURCE_DATA.equals(source)) return "Données";
        if (SOURCE_BACKEND_RULE.equals(source)) return "Règle backend";
        if (SOURCE_MODEL_VARIABILITY.equals(source)) return "Variabilité du modèle";
        if (SOURCE_UNKNOWN.equals(source)) return "Indéterminée";
        return humanize(source);
    }

    public static String issueTypeLabel(String type) {
        if (type == null || type.isBlank()) return humanize(TYPE_OTHER);
        for (String[] entry : ISSUE_TYPE_LABELS) {
            if (entry[0].equals(type)) {
                return entry[1];
            }
        }
        return humanize(type);
    }

    /** Un code technique inconnu est humanisé (underscores → espaces, première lettre en majuscule). */
    public static String humanize(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String text = code.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Tables de libellés exposées à l'IHM ({@code GET /api/prompt-optimization/agents}) : comme pour les
     * autres modules du POC, aucun code technique n'est affiché brut à l'écran. Les libellés sont
     * calculés par les mêmes méthodes {@code *Label} que côté backend (source unique).
     */
    public static Map<String, Map<String, String>> labels() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        tables.put("campaignStatusLabels", table(PromptOptimizationModels::campaignStatusLabel,
                CAMPAIGN_CREATED, CAMPAIGN_RUNNING, CAMPAIGN_STOP_REQUESTED, CAMPAIGN_PAUSED,
                CAMPAIGN_COMPLETED, CAMPAIGN_ACCEPTED, CAMPAIGN_REJECTED, CAMPAIGN_ERROR, CAMPAIGN_CANCELLED));
        tables.put("iterationStatusLabels", table(PromptOptimizationModels::iterationStatusLabel,
                ITERATION_RUNNING, ITERATION_COMPLETED, ITERATION_ERROR));
        tables.put("controllerStatusLabels", table(PromptOptimizationModels::controllerStatusLabel,
                STATUS_GOOD, STATUS_NEEDS_IMPROVEMENT, STATUS_BAD));
        tables.put("editorStatusLabels", table(PromptOptimizationModels::editorStatusLabel,
                EDITOR_UPDATED, EDITOR_NO_CHANGE, EDITOR_REVIEW_REQUIRED));
        tables.put("severityLabels", table(PromptOptimizationModels::severityLabel,
                SEVERITY_LOW, SEVERITY_MEDIUM, SEVERITY_HIGH));
        tables.put("sourceLabels", table(PromptOptimizationModels::sourceLabel,
                SOURCE_PROMPT, SOURCE_DATA, SOURCE_BACKEND_RULE, SOURCE_MODEL_VARIABILITY, SOURCE_UNKNOWN));
        Map<String, String> issueTypes = new LinkedHashMap<>();
        for (String[] entry : ISSUE_TYPE_LABELS) {
            issueTypes.put(entry[0], entry[1]);
        }
        tables.put("issueTypeLabels", issueTypes);
        return tables;
    }

    private static Map<String, String> table(java.util.function.Function<String, String> labeller, String... codes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String code : codes) {
            map.put(code, labeller.apply(code));
        }
        return map;
    }

    private static final String[][] ISSUE_TYPE_LABELS = {
            {"UNANSWERED_REQUEST", "Question non traitée"},
            {"PARTIAL_ANSWER", "Réponse partielle"},
            {"CONTEXT_NOT_USED", "Contexte non exploité"},
            {"INSUFFICIENT_PERSONALIZATION", "Personnalisation insuffisante"},
            {"EXCESSIVE_REPETITION", "Répétitions inutiles"},
            {"EXCESSIVE_LENGTH", "Réponse trop longue"},
            {"INSUFFICIENT_EXPLANATION", "Explications insuffisantes"},
            {"UNNECESSARY_DETAIL", "Détails inutiles"},
            {"MISUNDERSTOOD_PROJECT", "Projet mal compris"},
            {"UNSUPPORTED_CLAIM", "Affirmation non étayée"},
            {"INVENTED_DATA", "Donnée inventée"},
            {"INVENTED_PRODUCT", "Produit inventé"},
            {"INVENTED_URL", "URL inventée"},
            {"PRODUCT_MISMATCH", "Produit hors périmètre du projet"},
            {"RULE_VIOLATION", "Règle non respectée"},
            {"POOR_PEDAGOGY", "Pédagogie insuffisante"},
            {"UNPROFESSIONAL_TONE", "Ton inadapté"},
            {"CONTEXT_LOST", "Contexte perdu"},
            {"OTHER", "Autre"}
    };

    // --- Sorties structurées des agents internes -------------------------------------------------

    /** Problème relevé par le contrôleur dans la réponse du Coach. */
    public record Issue(String type, String severity, String source, String observation, String expectedBehavior) {

        public Issue {
            type = normalizeIssueType(type);
            severity = normalizeSeverity(severity);
            source = normalizeSource(source);
            observation = observation == null ? "" : observation.trim();
            expectedBehavior = expectedBehavior == null ? "" : expectedBehavior.trim();
        }

        /** L'issue relève de la zone éditable du prompt (seule corrigeable par une campagne). */
        public boolean fromPrompt() {
            return SOURCE_PROMPT.equals(source);
        }

        public String severityText() {
            return severityLabel(severity);
        }

        public String typeText() {
            return issueTypeLabel(type);
        }

        public String sourceText() {
            return sourceLabel(source);
        }
    }

    /** Diagnostic du contrôleur qualité (Agent B) sur une réponse du Coach. */
    public record ControllerFeedback(String status, String summary, List<String> positivePoints, List<Issue> issues,
                                     List<String> mustPreserve, String recommendationForPromptEditor,
                                     Boolean requiresHumanOrBusinessReview) {

        public ControllerFeedback {
            status = normalizeControllerStatus(status);
            summary = summary == null ? "" : summary.trim();
            positivePoints = cleanList(positivePoints);
            issues = issues == null ? List.of() : issues.stream().filter(Objects::nonNull).toList();
            mustPreserve = cleanList(mustPreserve);
            recommendationForPromptEditor = recommendationForPromptEditor == null
                    ? "" : recommendationForPromptEditor.trim();
            requiresHumanOrBusinessReview = requiresHumanOrBusinessReview != null && requiresHumanOrBusinessReview;
        }

        /** Aucune amélioration demandée : l'éditeur doit conserver la zone à l'identique. */
        public boolean noImprovementNeeded() {
            return STATUS_GOOD.equals(status) && issues.isEmpty();
        }

        /** Issues qui relèvent réellement de la zone éditable du prompt. */
        public List<Issue> promptIssues() {
            return issues.stream().filter(Issue::fromPrompt).toList();
        }

        public String statusText() {
            return controllerStatusLabel(status);
        }
    }

    /** Proposition de l'éditeur de prompts (Agent A) : NOUVELLE zone éditable uniquement. */
    public record EditorResult(String status, String editableSection, List<String> changeSummary,
                               List<String> feedbackAddressed, List<String> preservedBehaviors,
                               List<String> unresolvedPoints, Boolean humanFeedbackApplied) {

        public EditorResult {
            status = normalizeEditorStatus(status);
            editableSection = editableSection == null ? "" : editableSection;
            changeSummary = cleanList(changeSummary);
            feedbackAddressed = cleanList(feedbackAddressed);
            preservedBehaviors = cleanList(preservedBehaviors);
            unresolvedPoints = cleanList(unresolvedPoints);
            humanFeedbackApplied = humanFeedbackApplied != null && humanFeedbackApplied;
        }

        /** L'éditeur propose une nouvelle version de la zone éditable. */
        public boolean updated() {
            return EDITOR_UPDATED.equals(status);
        }

        /** L'éditeur signale un point nécessitant une décision humaine ou métier. */
        public boolean reviewRequired() {
            return EDITOR_REVIEW_REQUIRED.equals(status);
        }

        public String statusText() {
            return editorStatusLabel(status);
        }
    }

    // --- Campagne d'optimisation : machine d'état ------------------------------------------------

    public static final String CAMPAIGN_CREATED = "CREATED";
    public static final String CAMPAIGN_RUNNING = "RUNNING";
    public static final String CAMPAIGN_STOP_REQUESTED = "STOP_REQUESTED";
    public static final String CAMPAIGN_PAUSED = "PAUSED";
    public static final String CAMPAIGN_COMPLETED = "COMPLETED";
    public static final String CAMPAIGN_ACCEPTED = "ACCEPTED";
    public static final String CAMPAIGN_REJECTED = "REJECTED";
    public static final String CAMPAIGN_ERROR = "ERROR";
    public static final String CAMPAIGN_CANCELLED = "CANCELLED";

    /** Plafond ABSOLU et CUMULÉ d'itérations par campagne (§33 : jamais de cycles sans fin). */
    public static final int MAX_ITERATIONS = 50;

    /**
     * Nombre d'itérations CONSÉCUTIVES sans aucune nouvelle version (l'éditeur ne propose plus rien)
     * au-delà duquel la campagne s'arrête d'elle-même : inutile de consommer les itérations restantes.
     */
    public static final int MAX_CONSECUTIVE_NO_CHANGE = 3;

    /** Message d'arrêt automatique sur plateau (aucun échec : la campagne est simplement mise en pause). */
    public static String plateauMessage(int consecutiveIterations, int remainingIterations) {
        return "Plateau : " + consecutiveIterations + " itérations consécutives sans aucune modification "
                + "proposée par l'agent éditeur (Agent A) — la campagne est mise en pause pour ne pas "
                + "consommer les " + Math.max(0, remainingIterations) + " itération(s) restante(s) du cycle. "
                + "Ajoutez un avis pour orienter l'éditeur, ou reprenez pour continuer.";
    }

    /** Statut de campagne ; une valeur illisible devient {@code ERROR} (jamais RUNNING par défaut). */
    public static String normalizeCampaignStatus(String raw) {
        return normalize(raw, List.of(CAMPAIGN_CREATED, CAMPAIGN_RUNNING, CAMPAIGN_STOP_REQUESTED,
                CAMPAIGN_PAUSED, CAMPAIGN_COMPLETED, CAMPAIGN_ACCEPTED, CAMPAIGN_REJECTED, CAMPAIGN_ERROR,
                CAMPAIGN_CANCELLED), CAMPAIGN_ERROR);
    }

    /** La campagne accepte encore de nouvelles itérations (y compris « continuer après la fin », §33). */
    public static boolean campaignRunnable(String status) {
        return CAMPAIGN_CREATED.equals(status) || CAMPAIGN_RUNNING.equals(status) || CAMPAIGN_COMPLETED.equals(status);
    }

    /** Un traitement est en cours : tout nouveau départ doit être REFUSÉ (concurrence, §36). */
    public static boolean campaignBusy(String status) {
        return CAMPAIGN_RUNNING.equals(status) || CAMPAIGN_STOP_REQUESTED.equals(status);
    }

    public static boolean campaignPaused(String status) {
        return CAMPAIGN_PAUSED.equals(status);
    }

    /** Décision humaine déjà prise : plus aucune itération ni promotion automatique. */
    public static boolean campaignDecided(String status) {
        return CAMPAIGN_ACCEPTED.equals(status) || CAMPAIGN_REJECTED.equals(status);
    }

    public static String campaignStatusLabel(String status) {
        if (CAMPAIGN_CREATED.equals(status)) return "Créée";
        if (CAMPAIGN_RUNNING.equals(status)) return "En cours";
        if (CAMPAIGN_STOP_REQUESTED.equals(status)) return "Arrêt demandé";
        if (CAMPAIGN_PAUSED.equals(status)) return "En pause";
        if (CAMPAIGN_COMPLETED.equals(status)) return "Terminée";
        if (CAMPAIGN_ACCEPTED.equals(status)) return "Version acceptée";
        if (CAMPAIGN_REJECTED.equals(status)) return "Campagne refusée";
        if (CAMPAIGN_ERROR.equals(status)) return "Erreur";
        if (CAMPAIGN_CANCELLED.equals(status)) return "Annulée";
        return humanize(status);
    }

    // --- Itérations ------------------------------------------------------------------------------

    public static final String ITERATION_RUNNING = "RUNNING";
    public static final String ITERATION_COMPLETED = "COMPLETED";
    public static final String ITERATION_ERROR = "ERROR";

    public static String normalizeIterationStatus(String raw) {
        return normalize(raw, List.of(ITERATION_RUNNING, ITERATION_COMPLETED, ITERATION_ERROR), ITERATION_ERROR);
    }

    public static String iterationStatusLabel(String status) {
        if (ITERATION_RUNNING.equals(status)) return "En cours";
        if (ITERATION_COMPLETED.equals(status)) return "Terminée";
        if (ITERATION_ERROR.equals(status)) return "Erreur";
        return humanize(status);
    }

    // --- Feedback : origine et priorité (§4 / §43) ------------------------------------------------

    /** Feedback produit par le contrôleur automatique (Agent B). */
    public static final String FEEDBACK_SOURCE_AGENT_B = "AGENT_B";
    /** Feedback saisi par l'humain : PRIORITAIRE sur le contrôleur. */
    public static final String FEEDBACK_SOURCE_HUMAN = "HUMAN";
    public static final String PRIORITY_NORMAL = "NORMAL";
    /** Le feedback humain s'impose au diagnostic du contrôleur en cas de contradiction. */
    public static final String PRIORITY_OVERRIDE = "OVERRIDE";

    // --- Zone optimisée ---------------------------------------------------------------------------

    /** Zone optimisée = prompt du fichier de l'agent SPÉCIALISÉ du thème (credit-conso.txt, …). */
    public static final String ZONE_AGENT = "agent";
    /** Zone optimisée = agent PRINCIPAL (principal.txt), transverse à TOUS les agents. */
    public static final String ZONE_PRINCIPAL = "principal";

    /** Numéro d'une version « Vn » (0 si le format est inattendu). */
    public static int versionNumber(String version) {
        if (version == null || version.length() < 2 || (version.charAt(0) != 'V' && version.charAt(0) != 'v')) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(version.substring(1).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Nom de version « Vn » (V0 = version de référence du snapshot). */
    public static String versionName(int number) {
        return "V" + Math.max(0, number);
    }

    // --- Records persistés ------------------------------------------------------------------------

    /** Zone éditable VERSIONNÉE : V0 = version du snapshot, Vn = version produite par l'itération n. */
    public record PromptVersion(String version, String editableSection, String promptHash, int iterationNumber) {
    }

    /** Retour HUMAIN sur une itération (§12) : prioritaire sur le contrôleur en cas de contradiction. */
    public record HumanFeedback(String feedbackId, String campaignId, int iterationNumber, String source,
                                String priority, String content, String createdAt, Boolean applied) {

        public HumanFeedback {
            feedbackId = feedbackId == null || feedbackId.isBlank() ? "fb-" + java.util.UUID.randomUUID() : feedbackId;
            iterationNumber = Math.max(0, iterationNumber);
            source = FEEDBACK_SOURCE_AGENT_B.equalsIgnoreCase(source) ? FEEDBACK_SOURCE_AGENT_B : FEEDBACK_SOURCE_HUMAN;
            priority = PRIORITY_NORMAL.equalsIgnoreCase(priority) ? PRIORITY_NORMAL : PRIORITY_OVERRIDE;
            content = content == null ? "" : content.trim();
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
            applied = applied != null && applied;
        }

        public boolean overrides() {
            return PRIORITY_OVERRIDE.equals(priority);
        }

        public HumanFeedback applied(boolean value) {
            return new HumanFeedback(feedbackId, campaignId, iterationNumber, source, priority, content, createdAt, value);
        }
    }

    /**
     * Une ITÉRATION = une réponse du Coach pour une version précise du prompt (§10). Elle conserve la
     * version utilisée, la réponse, le diagnostic, la proposition de l'éditeur et la version résultante :
     * toutes les versions restent consultables, aucune n'écrase la précédente (§15).
     */
    public record Iteration(String iterationId, String campaignId, int iterationNumber,
                            String promptVersion, String editableSection, String promptHash,
                            String coachResponse, ControllerFeedback controllerFeedback,
                            EditorResult editorResult, String resultingVersion, String resultingEditableSection,
                            List<String> changeSummary, boolean noChange, boolean humanFeedbackApplied,
                            List<String> contextAddedData, String status, String error, String provider,
                            String controllerProvider, String editorProvider, String model,
                            long promptChars, long durationMs, String startedAt, String completedAt) {

        public Iteration {
            iterationNumber = Math.max(1, iterationNumber);
            promptVersion = promptVersion == null ? "" : promptVersion;
            editableSection = editableSection == null ? "" : editableSection;
            promptHash = promptHash == null ? "" : promptHash;
            coachResponse = coachResponse == null ? "" : coachResponse;
            resultingVersion = resultingVersion == null ? "" : resultingVersion;
            resultingEditableSection = resultingEditableSection == null ? "" : resultingEditableSection;
            changeSummary = cleanList(changeSummary);
            contextAddedData = cleanList(contextAddedData);
            status = normalizeIterationStatus(status);
            error = error == null ? "" : error;
            provider = provider == null ? "" : provider;
            // Campagne antérieure au routage des fournisseurs : repli sur le fournisseur du Coach.
            controllerProvider = controllerProvider == null || controllerProvider.isBlank()
                    ? provider : controllerProvider;
            editorProvider = editorProvider == null || editorProvider.isBlank()
                    ? provider : editorProvider;
            model = model == null ? "" : model;
            startedAt = startedAt == null ? "" : startedAt;
            completedAt = completedAt == null ? "" : completedAt;
        }

        public boolean failed() {
            return ITERATION_ERROR.equals(status);
        }

        /** Raccourci : les trois étapes ont utilisé le MÊME fournisseur, sans complétion de contexte. */
        public Iteration(String iterationId, String campaignId, int iterationNumber,
                         String promptVersion, String editableSection, String promptHash,
                         String coachResponse, ControllerFeedback controllerFeedback,
                         EditorResult editorResult, String resultingVersion, String resultingEditableSection,
                         List<String> changeSummary, boolean noChange, boolean humanFeedbackApplied,
                         String status, String error, String provider, String model,
                         long promptChars, long durationMs, String startedAt, String completedAt) {
            this(iterationId, campaignId, iterationNumber, promptVersion, editableSection, promptHash,
                    coachResponse, controllerFeedback, editorResult, resultingVersion, resultingEditableSection,
                    changeSummary, noChange, humanFeedbackApplied, List.of(), status, error, provider, provider,
                    provider, model, promptChars, durationMs, startedAt, completedAt);
        }

        /** L'itération a produit une nouvelle version de la zone éditable. */
        public boolean producedNewVersion() {
            return !noChange && !resultingVersion.isBlank() && !resultingVersion.equals(promptVersion);
        }
    }

    /**
     * SNAPSHOT DE RÉFÉRENCE (§7) : tout ce qui est FIGÉ pendant la campagne. Seule la zone éditable du
     * prompt varie d'une itération à l'autre. Les valeurs sont recopiées (jamais des références vers des
     * fichiers susceptibles de changer).
     * <p>
     * Le projet courant, les engagements et les produits compatibles sont dans {@code additionalData}
     * (exactement le payload envoyé au Coach) : ils ne sont donc pas dupliqués ici.
     * <p>
     * {@code frozenTemplate} (gabarit {@code generic.txt}) et {@code frozenPrincipal}
     * ({@code principal.txt}) sont les AUTRES entrées de la composition du prompt système : les figer
     * garantit qu'une reprise de campagne ne dépend jamais de l'état du disque (§7).
     * <p>
     * {@code baseZoneSource} dit D'OÙ VIENT la zone de départ : vide quand le cycle part du prompt de production,
     * ou {@code <campaignId>:<version>} quand il enchaîne sur la version RETENUE d'un cycle précédent (les cycles
     * s'accumulent alors sans qu'aucune écriture n'ait eu lieu — cf. §20.10 quater).
     */
    public record Snapshot(String snapshotId, String campaignId,
                           String question, String agentTheme, String agentLibelle,
                           String zoneKey, String zoneFile, String promptVersion,
                           String fixedPrefix, String initialEditableSection, String fixedSuffix,
                           String baseZoneSource,
                           String frozenSystemPrompt, String frozenTemplate, String frozenPrincipal,
                           IntentClassification classification, FinancialSummary financialSummary,
                           Object catalog, List<String> allowedCatalogPaths,
                           Map<String, Object> additionalData, List<ConversationModels.Message> history,
                           String debug, String provider, String model,
                           String promptHash, String snapshotHash, String createdAt) {

        public Snapshot {
            question = question == null ? "" : question;
            agentTheme = agentTheme == null ? "" : agentTheme;
            agentLibelle = agentLibelle == null ? "" : agentLibelle;
            zoneKey = ZONE_PRINCIPAL.equals(zoneKey) ? ZONE_PRINCIPAL : ZONE_AGENT;
            zoneFile = zoneFile == null ? "" : zoneFile;
            promptVersion = promptVersion == null ? "" : promptVersion;
            fixedPrefix = fixedPrefix == null ? "" : fixedPrefix;
            initialEditableSection = initialEditableSection == null ? "" : initialEditableSection;
            fixedSuffix = fixedSuffix == null ? "" : fixedSuffix;
            // Origine de la zone de DÉPART : vide = prompt de production ; sinon « <campagne>:<version> » quand le
            // cycle a été enchaîné sur la version RETENUE d'un cycle précédent (mode automatique de l'Agent C).
            baseZoneSource = baseZoneSource == null ? "" : baseZoneSource;
            frozenSystemPrompt = frozenSystemPrompt == null ? "" : frozenSystemPrompt;
            frozenTemplate = frozenTemplate == null ? "" : frozenTemplate;
            frozenPrincipal = frozenPrincipal == null ? "" : frozenPrincipal;
            allowedCatalogPaths = cleanList(allowedCatalogPaths);
            additionalData = additionalData == null ? Map.of() : Map.copyOf(additionalData);
            history = history == null ? List.of() : List.copyOf(history);
            debug = debug == null ? "" : debug;
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            promptHash = promptHash == null ? "" : promptHash;
            snapshotHash = snapshotHash == null ? "" : snapshotHash;
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
        }

        /** Données jointes réellement envoyées au Coach (partie figée du payload). */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> providedData() {
            Object value = additionalData.get("providedData");
            return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        }
    }

    // --- Fil de conversation de l'atelier (mémoire des cycles) -------------------------------------

    /** Rôle d'un tour : la question de test posée par l'humain. */
    public static final String ROLE_USER = "user";
    /** Rôle d'un tour : la réponse du Coach pour une version PROMUE de la zone. */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * Ce que le CLIENT simulé (« Agent C ») décide de dire au Coach pour un tour de conversation : UNE seule
     * question, ou la fin du scénario. Le client ne donne jamais de conseil et n'invente aucun chiffre.
     */
    public record ClientTurn(String question, Boolean endConversation, String reason) {

        public ClientTurn {
            question = question == null ? "" : question.strip();
            reason = reason == null ? "" : reason.strip();
            endConversation = endConversation != null && endConversation;
        }

        /** Aucune question exploitable (réponse vide du modèle) : le scénario s'arrête proprement. */
        public static ClientTurn empty() {
            return new ClientTurn("", true, "");
        }

        /** Le client n'a plus rien à demander : la conversation du scénario est terminée. */
        public boolean finished() {
            return endConversation || question.isBlank();
        }
    }

    /**
     * Projet INVENTÉ par le client simulé (« Agent C ») pour alimenter le brief de l'atelier : QUI est le client
     * et POURQUOI il vient voir sa banque. {@code reason} reste interne à l'atelier (jamais montré au Coach ni
     * au client) : il explique pourquoi ce projet teste bien l'agent sélectionné.
     * <p>
     * {@code montantProjet} est le montant à financer déclaré par le modèle (en euros). Il est CONTRÔLÉ par le
     * backend contre le plafond de cohérence du dossier : c'est ce qui empêche de proposer un projet hors de
     * portée du client (« achat d'un château » avec quelques milliers d'euros d'épargne). {@code null} si le
     * modèle ne l'a pas fourni.
     */
    public record ClientBrief(String brief, BigDecimal montantProjet, String reason) {

        public ClientBrief {
            brief = brief == null ? "" : brief.strip();
            reason = reason == null ? "" : reason.strip();
        }

        /** Un brief VIDE n'est pas un scénario exploitable : l'appelant refuse la proposition. */
        public boolean usable() {
            return !brief.isBlank();
        }
    }

    /**
     * Un TOUR de la conversation de l'atelier : la question de test (rôle {@code user}) ou la réponse du
     * Coach (rôle {@code assistant}).
     * <p>
     * Un tour « assistant » n'existe QUE si une version a été PROMUE : c'est la réponse produite par
     * l'itération dont cette version est issue. C'est exactement ce que l'IHM affiche « comme dans la page
     * coach », et ce qui est rejoué au cycle suivant ({@code conversationHistory} du payload du Coach).
     */
    public record Turn(String role, String content, String campaignId, String version, String createdAt) {

        public Turn {
            role = ROLE_ASSISTANT.equalsIgnoreCase(role) ? ROLE_ASSISTANT : ROLE_USER;
            content = content == null ? "" : content.strip();
            campaignId = campaignId == null ? "" : campaignId;
            version = version == null ? "" : version;
            createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
        }

        public boolean user() {
            return ROLE_USER.equals(role);
        }

        public boolean assistant() {
            return ROLE_ASSISTANT.equals(role);
        }

        /** Corrige le contenu du tour (la réponse de l'IA reste sous contrôle humain). */
        public Turn withContent(String value) {
            return new Turn(role, value, campaignId, version, createdAt);
        }

        /** Conversion vers le format d'historique du chat : le Coach reçoit EXACTEMENT le même contrat. */
        public ConversationModels.Message asMessage() {
            return new ConversationModels.Message(role, content, instantOf(createdAt));
        }

        private static Instant instantOf(String raw) {
            try {
                return Instant.parse(raw);
            } catch (RuntimeException e) {
                return Instant.now();
            }
        }
    }

    /**
     * FIL DE CONVERSATION de l'atelier : la MÉMOIRE qui enchaîne les campagnes (une campagne = une
     * question de test). Le fil est la SOURCE UNIQUE de l'historique transmis au Coach du cycle suivant.
     * <p>
     * Seuls les échanges RÉELLEMENT validés par une promotion y entrent : une question sans version promue
     * n'a pas de réponse figée, elle n'est donc jamais rejouée (sinon l'historique contiendrait des
     * questions sans réponses).
     * <p>
     * Le fil appartient à UN agent (et à une zone) : changer d'agent, c'est changer de conversation.
     */
    public record ConversationThread(String threadId, String agentId, String agentLibelle, String zoneKey,
                                     List<Turn> turns, List<String> campaignIds,
                                     String createdAt, String updatedAt) {

        public ConversationThread {
            threadId = threadId == null ? "" : threadId;
            agentId = agentId == null ? "" : agentId;
            agentLibelle = agentLibelle == null ? "" : agentLibelle;
            zoneKey = ZONE_PRINCIPAL.equals(zoneKey) ? ZONE_PRINCIPAL : ZONE_AGENT;
            turns = turns == null ? List.of() : List.copyOf(turns);
            campaignIds = campaignIds == null ? List.of() : List.copyOf(campaignIds);
            createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
            updatedAt = updatedAt == null || updatedAt.isBlank() ? createdAt : updatedAt;
        }

        /** Historique transmis au Coach : rôle + contenu de chaque tour validé, dans l'ordre chronologique. */
        public List<ConversationModels.Message> history() {
            return turns.stream().map(Turn::asMessage).toList();
        }

        /** Nombre d'ÉCHANGES complets (une question + la réponse de la version promue). */
        public int exchanges() {
            return (int) turns.stream().filter(Turn::assistant).count();
        }

        /** Aucun échange validé : le prochain cycle démarre sans mémoire. */
        public boolean empty() {
            return turns.isEmpty();
        }

        /** Dernier tour enregistré (affiche en bas de la conversation dans l'IHM). */
        public Turn lastTurn() {
            return turns.isEmpty() ? null : turns.get(turns.size() - 1);
        }

        /** Associe une campagne au fil (idempotent) — dès le DÉMARRAGE, avant toute promotion. */
        public ConversationThread withCampaign(String campaignId) {
            if (campaignId == null || campaignId.isBlank() || campaignIds.contains(campaignId)) {
                return this;
            }
            List<String> next = new ArrayList<>(campaignIds);
            next.add(campaignId);
            return new ConversationThread(threadId, agentId, agentLibelle, zoneKey, turns, next, createdAt,
                    Instant.now().toString());
        }

        /**
         * Enregistre l'ÉCHANGE d'une campagne : la question de test, puis la réponse du Coach associée à la
         * version promue.
         * <p>
         * Une NOUVELLE promotion de la même campagne REMPLACE son échange, <b>à sa place</b> (l'ordre
         * chronologique de la conversation est conservé et aucun échange n'est jamais dupliqué) ; les
         * échanges des autres campagnes ne sont jamais modifiés. Une campagne encore inconnue du fil est
         * ajoutée en fin de conversation.
         */
        public ConversationThread withExchange(String campaignId, String question, String answer, String version) {
            String now = Instant.now().toString();
            Turn userTurn = new Turn(ROLE_USER, question, campaignId, "", now);
            Turn answerTurn = new Turn(ROLE_ASSISTANT, answer, campaignId, version, now);
            List<Turn> next = new ArrayList<>(turns);
            for (int index = next.size() - 1; index >= 0; index--) {
                Turn turn = next.get(index);
                if (turn.user() && campaignId != null && campaignId.equals(turn.campaignId())) {
                    next.set(index, userTurn);
                    if (index + 1 < next.size() && next.get(index + 1).assistant()) {
                        next.set(index + 1, answerTurn);
                    } else {
                        next.add(index + 1, answerTurn);
                    }
                    return new ConversationThread(threadId, agentId, agentLibelle, zoneKey, next,
                            campaignIdsWith(campaignId), createdAt, now);
                }
            }
            next.add(userTurn);
            next.add(answerTurn);
            return new ConversationThread(threadId, agentId, agentLibelle, zoneKey, next, campaignIdsWith(campaignId),
                    createdAt, now);
        }

        /** Corrige le contenu d'un tour : l'humain garde la main sur ce qui est rejoué ensuite. */
        public ConversationThread withTurnContent(int index, String content) {
            if (index < 0 || index >= turns.size()) {
                throw new IllegalArgumentException("Tour inexistant dans le fil de conversation : " + index);
            }
            List<Turn> next = new ArrayList<>(turns);
            next.set(index, next.get(index).withContent(content));
            return new ConversationThread(threadId, agentId, agentLibelle, zoneKey, next, campaignIds, createdAt,
                    Instant.now().toString());
        }

        private List<String> campaignIdsWith(String campaignId) {
            if (campaignId == null || campaignId.isBlank() || campaignIds.contains(campaignId)) {
                return campaignIds;
            }
            List<String> next = new ArrayList<>(campaignIds);
            next.add(campaignId);
            return next;
        }
    }

    /**
     * BILAN d'une conversation de l'atelier : le prompt AU DÉBUT de la conversation face au prompt EN VIGUEUR
     * à la fin (dernière version réellement retenue pour la conversation).
     * <p>
     * La comparaison d'une CAMPAGNE ne montre qu'un cycle (une question) ; celle-ci montre tout le chemin
     * parcouru, cycle après cycle : c'est le seul moyen de lire ce que la conversation a réellement changé au
     * prompt. Quand aucune version n'a été promue, les deux prompts sont <b>identiques</b> ({@code identical}).
     */
    public record ConversationComparison(String threadId, String agentId, String agentLibelle,
                                         String zoneKey, String zoneFile,
                                         String baseVersion, String currentVersion,
                                         String baseCampaignId, String currentCampaignId,
                                         String baseEditableSection, String currentEditableSection,
                                         String basePrompt, String currentPrompt,
                                         int cycleCount, int iterationCount, int promotionCount,
                                         boolean identical, String summary) {

        public ConversationComparison {
            threadId = threadId == null ? "" : threadId;
            agentId = agentId == null ? "" : agentId;
            agentLibelle = agentLibelle == null ? "" : agentLibelle;
            zoneKey = ZONE_PRINCIPAL.equals(zoneKey) ? ZONE_PRINCIPAL : ZONE_AGENT;
            zoneFile = zoneFile == null ? "" : zoneFile;
            baseVersion = baseVersion == null ? "" : baseVersion;
            currentVersion = currentVersion == null ? "" : currentVersion;
            baseCampaignId = baseCampaignId == null ? "" : baseCampaignId;
            currentCampaignId = currentCampaignId == null ? "" : currentCampaignId;
            baseEditableSection = baseEditableSection == null ? "" : baseEditableSection;
            currentEditableSection = currentEditableSection == null ? "" : currentEditableSection;
            basePrompt = basePrompt == null ? "" : basePrompt;
            currentPrompt = currentPrompt == null ? "" : currentPrompt;
            cycleCount = Math.max(0, cycleCount);
            iterationCount = Math.max(0, iterationCount);
            promotionCount = Math.max(0, promotionCount);
            // Phrase d'explication calculée ICI : un seul endroit décrit le bilan. Les noms de version sont
            // LOCAUX à chaque cycle : le bilan parle donc de la ZONE (tailles), jamais de « V0 → V0 » qui
            // laisserait croire que rien n'a changé.
            summary = identical
                    ? "Le prompt est identique au début et à la fin de la conversation"
                      + (promotionCount == 0
                            ? " : aucune version n'a été retenue pendant ce scénario."
                            : " : les versions retenues n'ont pas modifié la zone éditable.")
                    : "Conversation en " + cycleCount + " cycle(s) et " + iterationCount + " itération(s) — "
                      + promotionCount + " version(s) retenue(s) pour la conversation : la zone éditable a été "
                      + "modifiée pendant la conversation ("
                      + baseEditableSection.length() + " → " + currentEditableSection.length()
                      + " caractères).";
        }
    }

    /** CAMPAGNE d'optimisation (§38) : machine d'état, compteurs et fournisseurs par étape. */
    public record Campaign(String campaignId, String agentId, String agentLibelle,
                           String zoneKey, String zoneFile, String status, String question,
                           int requestedIterations, int completedIterations, int maxIterations,
                           String snapshotId, String basePromptVersion, String currentCandidateVersion,
                           String promotedVersion,
                           String provider, String controllerProvider, String editorProvider, String model,
                           int aiCalls, long totalPromptChars, long totalDurationMs,
                           String stopRequestedAt, String pausedAt,
                           String error, String errorStep, String createdAt, String updatedAt) {

        public Campaign {
            agentId = agentId == null ? "" : agentId;
            agentLibelle = agentLibelle == null ? "" : agentLibelle;
            zoneKey = ZONE_PRINCIPAL.equals(zoneKey) ? ZONE_PRINCIPAL : ZONE_AGENT;
            zoneFile = zoneFile == null ? "" : zoneFile;
            status = normalizeCampaignStatus(status);
            question = question == null ? "" : question;
            requestedIterations = Math.max(0, Math.min(requestedIterations, MAX_ITERATIONS));
            completedIterations = Math.max(0, completedIterations);
            maxIterations = maxIterations <= 0 ? MAX_ITERATIONS : Math.min(maxIterations, MAX_ITERATIONS);
            basePromptVersion = basePromptVersion == null ? "V0" : basePromptVersion;
            currentCandidateVersion = currentCandidateVersion == null ? basePromptVersion : currentCandidateVersion;
            promotedVersion = promotedVersion == null ? "" : promotedVersion;
            provider = provider == null ? "" : provider;
            // Campagne antérieure au routage des fournisseurs : repli sur le fournisseur du Coach.
            controllerProvider = controllerProvider == null || controllerProvider.isBlank()
                    ? provider : controllerProvider;
            editorProvider = editorProvider == null || editorProvider.isBlank()
                    ? provider : editorProvider;
            model = model == null ? "" : model;
            stopRequestedAt = stopRequestedAt == null ? "" : stopRequestedAt;
            pausedAt = pausedAt == null ? "" : pausedAt;
            error = error == null ? "" : error;
            errorStep = errorStep == null ? "" : errorStep;
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }

        /** Itérations RESTANTES du cycle courant (le total demandé est CUMULÉ, §33). */
        public int remainingIterations() {
            return Math.max(0, requestedIterations - completedIterations);
        }

        /** Le nombre d'itérations demandé est atteint : la campagne peut être clôturée. */
        public boolean requestedIterationsDone() {
            return completedIterations >= requestedIterations;
        }

        /** Le plafond ABSOLU cumulé est atteint : plus aucune itération possible dans cette campagne. */
        public boolean maxIterationsReached() {
            return completedIterations >= maxIterations;
        }

        /** Raccourci : les trois étapes (coach, contrôleur, éditeur) utilisent le même fournisseur. */
        public Campaign(String campaignId, String agentId, String agentLibelle,
                        String zoneKey, String zoneFile, String status, String question,
                        int requestedIterations, int completedIterations, int maxIterations,
                        String snapshotId, String basePromptVersion, String currentCandidateVersion,
                        String promotedVersion,
                        String provider, String model, int aiCalls, long totalPromptChars, long totalDurationMs,
                        String stopRequestedAt, String pausedAt,
                        String error, String errorStep, String createdAt, String updatedAt) {
            this(campaignId, agentId, agentLibelle, zoneKey, zoneFile, status, question, requestedIterations,
                    completedIterations, maxIterations, snapshotId, basePromptVersion, currentCandidateVersion,
                    promotedVersion, provider, provider, provider, model, aiCalls,
                    totalPromptChars, totalDurationMs, stopRequestedAt, pausedAt, error, errorStep, createdAt, updatedAt);
        }

        public boolean isActive() {
            return campaignBusy(status);
        }

        /** Une itération peut être lancée (état compatible + compteur restant + sous le plafond). */
        public boolean canIterate() {
            return campaignRunnable(status) && remainingIterations() > 0 && !maxIterationsReached();
        }

        /** Nouvel état après ajout d'un cycle d'itérations (jamais au-delà du plafond cumulé). */
        public Campaign withRequestedIterations(int total) {
            return new Campaign(campaignId, agentId, agentLibelle, zoneKey, zoneFile, status, question,
                    Math.min(total, maxIterations), completedIterations, maxIterations, snapshotId,
                    basePromptVersion, currentCandidateVersion, promotedVersion,
                    provider, controllerProvider, editorProvider, model,
                    aiCalls, totalPromptChars, totalDurationMs, stopRequestedAt, pausedAt, error, errorStep,
                    createdAt, java.time.Instant.now().toString());
        }
    }
}
