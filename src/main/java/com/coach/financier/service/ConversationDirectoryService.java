package com.coach.financier.service;

import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.ConversationCategory;
import com.coach.financier.model.DirectoryModels;
import com.coach.financier.model.DossierStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ANNUAIRE DES CONVERSATIONS pour l'équipe commerciale / le centre d'appels.
 * <p>
 * Source unique : les DOSSIERS DE SUIVI persistés à chaque clôture ({@code AdvisorDossierStore}),
 * c'est-à-dire exactement le contenu envoyé au conseiller par mail, avec sa pièce jointe (le brouillon
 * d'email préparé pour le client, jamais envoyé automatiquement). La page
 * peut ainsi filtrer, trier et ouvrir un dossier sans qu'aucune donnée ne soit recalculée.
 * <p>
 * Filtres : période (N derniers jours, aujourd'hui inclus), catégorie métier, recherche libre
 * (identifiant client, titre, projet, produit). Tri : date, score de sens commercial, client,
 * catégorie, titre. Un dossier ANCIEN (écrit avant l'ajout du score ou de l'identité client) reste
 * affichable : les valeurs manquantes sont présentées vides, jamais estimées.
 */
@Service
public class ConversationDirectoryService {
    /** Nombre de jours proposés par défaut dans le filtre de période. */
    public static final int DEFAULT_DAYS = 10;
    /** Périodes proposées par l'IHM (en jours) ; {@code 0} = tout l'historique disponible. */
    public static final List<Integer> PERIODS = List.of(5, 10, 30, 0);

    private static final String SORT_DATE = "date";
    private static final String SORT_SCORE = "score";
    private static final String SORT_CLIENT = "client";
    private static final String SORT_CATEGORY = "categorie";
    private static final String SORT_TITLE = "titre";
    private static final Set<String> SORTS = Set.of(SORT_DATE, SORT_SCORE, SORT_CLIENT, SORT_CATEGORY, SORT_TITLE);

    private final AdvisorDossierStore dossierStore;
    private final AdvisorFeedbackStore feedbackStore;
    private final AdvisorDossierService advisorDossierService;
    private final CallCenterStatusStore statusStore;
    private final String contactPhone;

    public ConversationDirectoryService(AdvisorDossierStore dossierStore,
                                        AdvisorFeedbackStore feedbackStore,
                                        AdvisorDossierService advisorDossierService,
                                        CallCenterStatusStore statusStore,
                                        @Value("${app.suivi.customer-phone:}") String contactPhone) {
        this.dossierStore = dossierStore;
        this.feedbackStore = feedbackStore;
        this.advisorDossierService = advisorDossierService;
        this.statusStore = statusStore;
        this.contactPhone = contactPhone == null ? "" : contactPhone.trim();
    }

    /**
     * Liste des conversations clôturées, filtrées et triées.
     *
     * @param days     période en jours (≤ 0 = tout l'historique)
     * @param category code de catégorie ({@code null}/vide = toutes)
     * @param query    recherche libre (identifiant client, titre, projet, produit) — vide = pas de recherche
     * @param sort     clé de tri ({@code date} par défaut ; {@code score}, {@code client}, {@code categorie}, {@code titre})
     * @param order    {@code asc} ou {@code desc} (par défaut : {@code desc} sur la date, {@code asc} ailleurs)
     */
    public DirectoryModels.DirectoryList list(int days, String category, String query, String sort, String order) {
        return list(days, category, query, sort, order, null);
    }

    /**
     * Variante avec filtre de STATUT (le fil de travail du centre d'appels).
     *
     * @param status code de statut ({@code null}/vide = tous ; {@code NOUVEAU} inclut les dossiers qui n'ont
     *               encore jamais changé de statut)
     */
    public DirectoryModels.DirectoryList list(int days, String category, String query, String sort, String order,
                                              String status) {
        List<AdvisorFeedbackModels.AdvisorDossier> dossiers = periodDossiers(days);
        Set<String> evaluatedSessions = evaluatedSessions(days);
        LocalDate from = days > 0 ? LocalDate.now().minusDays(days - 1L) : null;
        CallCenterStatusStore.StatusSummary statusSummary = statusStore.summary(from, null);
        Map<String, DirectoryModels.DossierStatusEvent> statuses = statusSummary.latest();
        Map<String, Integer> noteCounts = statusSummary.noteCounts();
        ConversationCategory filter = ConversationCategory.parse(category);
        DossierStatus wanted = DossierStatus.parse(status);
        String statusFilter = wanted == null ? null : wanted.code();
        String needle = normalize(query);

        List<DirectoryModels.DirectoryRow> rows = new ArrayList<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorDossier dossier : dossiers) {
            DirectoryModels.DirectoryRow row = toRow(dossier, evaluatedSessions, statuses, noteCounts);
            categoryCounts.merge(row.category(), 1, Integer::sum);
            String priority = row.priority() == null ? "UNKNOWN" : row.priority();
            byPriority.merge(priority, 1, Integer::sum);
            byStatus.merge(row.status(), 1, Integer::sum);
            if (filter != null && !filter.code().equals(row.category())) {
                continue;
            }
            if (statusFilter != null && !statusFilter.equals(row.status())) {
                continue;
            }
            if (!needle.isEmpty() && !matches(row, needle)) {
                continue;
            }
            rows.add(row);
        }

        rows.sort(comparator(sort, order));

        List<DirectoryModels.DirectoryCategory> categories = new ArrayList<>();
        for (ConversationCategory value : ConversationCategory.values()) {
            Integer count = categoryCounts.get(value.code());
            if (count != null && count > 0) {
                categories.add(new DirectoryModels.DirectoryCategory(value.code(), value.label(), count));
            }
        }
        List<DirectoryModels.DirectoryCategory> statusesView = new ArrayList<>();
        for (DossierStatus value : DossierStatus.progression()) {
            Integer count = byStatus.get(value.code());
            if (count != null && count > 0) {
                statusesView.add(new DirectoryModels.DirectoryCategory(value.code(), value.label(), count));
            }
        }
        return new DirectoryModels.DirectoryList(List.copyOf(rows), List.copyOf(categories),
                List.copyOf(statusesView), byPriority, Math.max(0, days), sortKey(sort), orderKey(sort, order),
                rows.size());
    }

    /**
     * Enregistre le SUIVI d'un dossier : changement de statut et/ou MESSAGE laissé par le conseiller.
     * <p>
     * Trois cas : statut différent ⇒ un événement avec le nouveau statut et le message éventuel ; statut
     * identique AVEC un message ⇒ un événement « message seul » (le statut ne bouge pas, le message est
     * journalisé) ; statut identique SANS message ⇒ rien n'est écrit. Un code inconnu est refusé.
     */
    public Optional<DirectoryModels.DirectoryDetail> updateStatus(String sessionId, String status, String comment) {
        DossierStatus target = DossierStatus.parse(status);
        if (target == null) {
            throw new IllegalArgumentException("Statut inconnu : " + status);
        }
        Optional<DirectoryModels.DirectoryDetail> current = detail(sessionId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String currentCode = current.get().row().status();
        String message = comment == null ? "" : comment.trim();
        if (!target.code().equals(currentCode)) {
            statusStore.save(sessionId, target.code(), target.label(), currentCode, message);
        } else if (!message.isEmpty()) {
            statusStore.save(sessionId, currentCode, DossierStatus.labelOf(currentCode), currentCode, message);
        }
        return detail(sessionId);
    }

    /**
     * Détail d'une conversation (pop-in) : synthèse conseiller, PIÈCE JOINTE (brouillon d'email client),
     * score expliqué, suivi du dossier et transcript.
     */
    public Optional<DirectoryModels.DirectoryDetail> detail(String sessionId) {
        Optional<AdvisorFeedbackModels.AdvisorDossier> found = dossierStore.findBySession(sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdvisorFeedbackModels.AdvisorDossier dossier = found.get();
        Set<String> evaluated = feedbackStore.latestBySession(sessionId).isPresent()
                ? Set.of(sessionId) : Set.of();
        CallCenterStatusStore.StatusSummary statusSummary = statusStore.summary(null, null);
        DirectoryModels.DossierStatusEvent statusEvent = statusSummary.latest().get(sessionId);
        Map<String, DirectoryModels.DossierStatusEvent> statuses = statusEvent == null
                ? Map.of() : Map.of(sessionId, statusEvent);
        DirectoryModels.DirectoryRow row = toRow(dossier, evaluated, statuses, statusSummary.noteCounts());
        AdvisorFeedbackModels.DossierScore score = dossier.score();
        return Optional.of(new DirectoryModels.DirectoryDetail(
                row,
                dossier.advisorEmail() == null ? null : dossier.advisorEmail().subject(),
                dossier.advisorEmail() == null ? null : dossier.advisorEmail().body(),
                dossier.customerEmail() == null ? null : dossier.customerEmail().subject(),
                dossier.customerEmail() == null ? null : dossier.customerEmail().body(),
                dossier.nextActions(),
                dossier.productsOfInterest(),
                score == null ? List.of() : score.reasons(),
                score == null ? List.of() : score.details(),
                score == null ? null : score.proposedByAi(),
                dossier.transcript(),
                contactPhone,
                firstNonBlank(dossier.feedbackUrl(), advisorDossierService.feedbackUrl(sessionId)),
                advisorDossierService.conversationUrl(sessionId),
                !evaluated.isEmpty(),
                statusStore.history(sessionId)));
    }

    /** Code de statut courant d'un dossier : le dernier événement connu, sinon « Nouveau ». */
    private static DirectoryModels.DossierStatusEvent currentStatus(DirectoryModels.DossierStatusEvent event,
                                                                   String sessionId) {
        if (event != null) {
            return event;
        }
        return new DirectoryModels.DossierStatusEvent(null, sessionId, DossierStatus.NOUVEAU.code(),
                DossierStatus.NOUVEAU.label(), null, null, null);
    }

    /** Dossiers de la période, DÉDOUBLONNÉS par session (le plus récent fait foi). */
    private List<AdvisorFeedbackModels.AdvisorDossier> periodDossiers(int days) {
        LocalDate from = days > 0 ? LocalDate.now().minusDays(days - 1L) : null;
        List<AdvisorFeedbackModels.AdvisorDossier> all = dossierStore.read(from, null);
        Map<String, AdvisorFeedbackModels.AdvisorDossier> latest = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorDossier dossier : all) {
            if (dossier == null || dossier.sessionId() == null || dossier.sessionId().isBlank()) {
                continue;
            }
            AdvisorFeedbackModels.AdvisorDossier current = latest.get(dossier.sessionId());
            if (current == null || after(dossier, current)) {
                latest.put(dossier.sessionId(), dossier);
            }
        }
        return List.copyOf(latest.values());
    }

    /** Sessions ayant reçu un avis conseiller sur la période (colonne « évalué »). */
    private Set<String> evaluatedSessions(int days) {
        LocalDate from = days > 0 ? LocalDate.now().minusDays(days - 1L) : null;
        Set<String> sessions = new LinkedHashSet<>();
        try {
            for (AdvisorFeedbackModels.AdvisorFeedback feedback : feedbackStore.read(from, null).feedback()) {
                if (feedback != null && feedback.sessionId() != null) {
                    sessions.add(feedback.sessionId());
                }
            }
        } catch (Exception e) {
            // Un stockage de feedback illisible ne doit jamais empêcher l'affichage de l'annuaire.
        }
        return sessions;
    }

    private static DirectoryModels.DirectoryRow toRow(AdvisorFeedbackModels.AdvisorDossier dossier,
                                                     Set<String> evaluatedSessions,
                                                     Map<String, DirectoryModels.DossierStatusEvent> statuses,
                                                     Map<String, Integer> noteCounts) {
        AdvisorFeedbackModels.DossierClient client = dossier.client();
        AdvisorFeedbackModels.DossierScore score = dossier.score();
        ConversationCategory category = ConversationCategory.parse(client == null ? null : client.category());
        String title = firstNonBlank(client == null ? null : client.title(), dossier.mainProject(),
                "Conversation " + dossier.sessionId());
        String topProduct = dossier.productsOfInterest().isEmpty()
                ? null : dossier.productsOfInterest().get(0).name();
        int priorityScore = score == null || score.score() == null ? -1 : score.score();
        DirectoryModels.DossierStatusEvent status = currentStatus(statuses.get(dossier.sessionId()),
                dossier.sessionId());
        return new DirectoryModels.DirectoryRow(
                dossier.sessionId(),
                client == null ? null : client.customerId(),
                title,
                dossier.mainProject(),
                category == null ? ConversationCategory.AUTRE.code() : category.code(),
                category == null ? ConversationCategory.AUTRE.label()
                        : firstNonBlank(client == null ? null : client.categoryLabel(), category.label()),
                score == null ? null : score.score(),
                score == null ? null : score.priority(),
                priorityScore < 0 ? null : CommercialScoreService.labelOf(priorityScore),
                score == null ? null : score.display(),
                dossier.timestamp(),
                dossier.productsOfInterest().size(),
                topProduct,
                evaluatedSessions.contains(dossier.sessionId()),
                status.status(),
                firstNonBlank(status.statusLabel(), DossierStatus.labelOf(status.status())),
                status.timestamp(),
                noteCounts.getOrDefault(dossier.sessionId(), 0));
    }

    private static boolean matches(DirectoryModels.DirectoryRow row, String needle) {
        return normalize(join(row.customerId(), row.title(), row.mainProject(), row.categoryLabel(),
                row.topProduct(), row.sessionId())).contains(needle);
    }

    private Comparator<DirectoryModels.DirectoryRow> comparator(String sort, String order) {
        String key = sortKey(sort);
        Comparator<DirectoryModels.DirectoryRow> comparator = switch (key) {
            case SORT_SCORE -> Comparator
                    .comparingInt((DirectoryModels.DirectoryRow row) -> row.score() == null ? -1 : row.score())
                    .thenComparing(row -> value(row.closedAt()), Comparator.reverseOrder());
            case SORT_CLIENT -> Comparator.comparing(row -> value(row.customerId()));
            case SORT_CATEGORY -> Comparator.comparing(DirectoryModels.DirectoryRow::categoryLabel);
            case SORT_TITLE -> Comparator.comparing(row -> value(row.title()));
            default -> Comparator.comparing(row -> value(row.closedAt()));
        };
        return "asc".equals(orderKey(sort, order)) ? comparator : comparator.reversed();
    }

    private static String sortKey(String sort) {
        String key = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        return SORTS.contains(key) ? key : SORT_DATE;
    }

    /** Par défaut : plus récent d'abord sur la date, scores les plus hauts d'abord sur le score. */
    private static String orderKey(String sort, String order) {
        if (order != null && ("asc".equalsIgnoreCase(order.trim()) || "desc".equalsIgnoreCase(order.trim()))) {
            return order.trim().toLowerCase(Locale.ROOT);
        }
        String key = sortKey(sort);
        return SORT_DATE.equals(key) || SORT_SCORE.equals(key) ? "desc" : "asc";
    }

    private static boolean after(AdvisorFeedbackModels.AdvisorDossier candidate,
                                 AdvisorFeedbackModels.AdvisorDossier current) {
        String left = value(candidate.timestamp());
        String right = value(current.timestamp());
        return left.compareTo(right) >= 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String join(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
        return String.join(" ", parts);
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }
}
