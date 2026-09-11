package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.MarketingModels;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dossiers proposés à l'évaluation du conseiller : liste des conversations récentes (issues des
 * signaux Marketing et des conversations clôturées) avec les produits d'intérêt détectés.
 * <p>
 * Sert la saisie du feedback (IHM) : produits réellement détectés pour la session, produits du
 * catalogue pour l'ajout d'un produit manquant (§8 : uniquement des produits existants).
 * Aucune donnée personnelle n'est exposée : identifiants de session et de produit uniquement.
 */
@Service
public class AdvisorFeedbackCandidatesService {

    private final MarketingEventStore marketingEventStore;
    private final MarketingProperties marketingProperties;
    private final QualityCheckStore qualityCheckStore;
    private final AdvisorFeedbackStore advisorFeedbackStore;
    private final ProductCatalogueService productCatalogueService;
    private final AdvisorFeedbackProperties properties;

    public AdvisorFeedbackCandidatesService(MarketingEventStore marketingEventStore,
                                           MarketingProperties marketingProperties,
                                           QualityCheckStore qualityCheckStore,
                                           AdvisorFeedbackStore advisorFeedbackStore,
                                           ProductCatalogueService productCatalogueService,
                                           AdvisorFeedbackProperties properties) {
        this.marketingEventStore = marketingEventStore;
        this.marketingProperties = marketingProperties;
        this.qualityCheckStore = qualityCheckStore;
        this.advisorFeedbackStore = advisorFeedbackStore;
        this.productCatalogueService = productCatalogueService;
        this.properties = properties;
    }

    /** Produit détecté pour un dossier (valeur IA conservée telle quelle). */
    public record CandidateProduct(String productId, String productName, String aiInterestLevel) {
    }

    /** Dossier candidat à l'évaluation. */
    public record CandidateSession(String sessionId, String projectType, List<CandidateProduct> products,
                                   boolean evaluated, Integer currentVersion) {
    }

    /** Produit du catalogue (sélection du « produit manquant », §8). */
    public record CatalogueProduct(String productId, String productName, String productFamily) {
    }

    /** Dossiers récents ; {@code includeEvaluated=false} masque ceux déjà évalués. */
    public List<CandidateSession> candidates(int days, boolean includeEvaluated) {
        int window = Math.min(Math.max(days, 1), 90);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(window - 1L);

        Map<String, List<MarketingModels.MarketingEvent>> bySession = new LinkedHashMap<>();
        if (marketingProperties.isEnabled()) {
            for (MarketingModels.MarketingEvent event : marketingEventStore.read(from, to).events()) {
                if (event != null && event.sessionId() != null) {
                    bySession.computeIfAbsent(event.sessionId(), key -> new ArrayList<>()).add(event);
                }
            }
        }
        Set<String> sessions = new LinkedHashSet<>(bySession.keySet());
        for (com.coach.financier.model.QualityModels.QualityCheck check
                : qualityCheckStore.read(from, to).values()) {
            if (check != null && check.sessionId() != null) {
                sessions.add(check.sessionId());
            }
        }

        List<CandidateSession> result = new ArrayList<>();
        for (String sessionId : sessions) {
            CandidateSession candidate = build(sessionId, bySession.getOrDefault(sessionId, List.of()));
            if (includeEvaluated || !candidate.evaluated()) {
                result.add(candidate);
            }
        }
        result.sort(Comparator.comparing(CandidateSession::evaluated)
                .thenComparing(CandidateSession::sessionId, Comparator.reverseOrder()));
        return result;
    }

    /** Dossier précis (utilisé quand l'IHM connaît déjà la session). */
    public Optional<CandidateSession> candidate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        List<MarketingModels.MarketingEvent> events = marketingProperties.isEnabled()
                ? marketingEventStore.read(null, null).events().stream()
                .filter(event -> sessionId.equals(event.sessionId())).toList()
                : List.of();
        return Optional.of(build(sessionId, events));
    }

    /** Catalogue produits (identifiants réels uniquement : aucun produit ne peut être inventé). */
    public List<CatalogueProduct> catalogue() {
        return productCatalogueService.all().stream()
                .map(product -> new CatalogueProduct(product.getId(), product.getName(),
                        product.getFamily() == null ? null : product.getFamily().name()))
                .sorted(Comparator.comparing(CatalogueProduct::productName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private CandidateSession build(String sessionId, List<MarketingModels.MarketingEvent> events) {
        Map<String, CandidateProduct> products = new LinkedHashMap<>();
        String projectType = null;
        for (MarketingModels.MarketingEvent event : events) {
            if (event == null) {
                continue;
            }
            if (projectType == null && event.projectType() != null) {
                projectType = event.projectType();
            }
            if (event.productId() == null) {
                continue;
            }
            CandidateProduct existing = products.get(event.productId());
            String level = event.interestLevel() == null || event.interestLevel().isBlank()
                    ? existing == null ? null : existing.aiInterestLevel() : event.interestLevel();
            products.put(event.productId(), new CandidateProduct(event.productId(),
                    event.productName() == null ? (existing == null ? null : existing.productName())
                            : event.productName(),
                    level));
        }
        AdvisorFeedbackModels.AdvisorFeedback current = advisorFeedbackStore.latestBySession(sessionId).orElse(null);
        return new CandidateSession(sessionId, projectType, List.copyOf(products.values()),
                current != null, current == null ? null : current.version());
    }
}
