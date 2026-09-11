package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.MarketingModels;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Générateur de DONNÉES DE DÉMONSTRATION (§49) pour la page Marketing.
 * <p>
 * Les événements sont explicitement marqués {@code demo=true} (jamais mélangés silencieusement avec
 * des données réelles) et leurs identifiants sont DÉTERMINISTES : relancer la génération ne crée
 * aucun doublon (déduplication par {@code eventId} du {@link MarketingEventStore}).
 */
@Service
public class MarketingDemoDataService {

    private static final String[][] PRODUCTS = {
            {"sg_credit_auto_expresso", "Crédit Auto Expresso", "AUTO_LOAN"},
            {"sg_auto_tous_risques", "Assurance Auto SG – Tous Risques", "INSURANCE_AUTO"},
            {"sg_auto_tiers", "Assurance Auto SG – Tiers", "INSURANCE_AUTO"},
            {"sg_livret_a", "Livret A", "SAVINGS_PRODUCT"},
            {"sg_pel", "Plan Épargne Logement (PEL)", "HOME_SAVINGS"},
            {"sg_pret_immo_taux_fixe", "Prêt Immobilier à Taux Fixe", "MORTGAGE"},
            {"sg_credit_expresso", "Crédit Expresso", "PERSONAL_LOAN"},
            {"sg_habitation_confort", "Assurance Habitation SG – Confort", "INSURANCE_HOME"},
            {"sg_pea", "Plan d'Épargne en Actions (PEA)", "EQUITY_INVESTMENT"},
            {"sg_pret_etudiant_evolutif", "Prêt Étudiant Évolutif", "STUDENT_LOAN"}
    };
    private static final String[] PROJECTS = {
            "VEHICLE", "REAL_ESTATE", "SAVINGS", "HOME_WORK", "EDUCATION", "ELECTRONICS", "TRAVEL"};
    private static final String[] AMOUNT_RANGES = {
            "0_2000", "2000_5000", "5000_10000", "10000_15000", "15000_30000", "30000_PLUS"};
    private static final String[] REJECTION_REASONS = {
            "PRICE", "PREFERS_CASH", "DOES_NOT_WANT_CREDIT", "DURATION", "CONDITIONS", "COMPETITOR"};
    private static final String[] INTEREST_REASONS = {
            "DETAIL_REQUEST", "PRICE_REQUEST", "RATE_REQUEST", "COVERAGE_REQUEST", "COMPARISON", "QUOTE_REQUEST"};
    private static final String[] MISSING_INFO_REASONS = {
            "MISSING_COVERAGE_INFORMATION", "MISSING_PRICING_INFORMATION", "MISSING_CONDITIONS_INFORMATION"};

    private final MarketingEventStore eventStore;
    private final MarketingProperties properties;

    public MarketingDemoDataService(MarketingEventStore eventStore, MarketingProperties properties) {
        this.eventStore = eventStore;
        this.properties = properties;
    }

    /** Génère {@code days} journées de {@code sessionsPerDay} sessions fictives (max 60 × 200). */
    public Map<String, Object> generate(int days, int sessionsPerDay) {
        Random random = new Random(20260911L); // graine fixe : jeu de démo reproductible
        List<MarketingModels.MarketingEvent> events = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            int sessions = sessionsPerDay + random.nextInt(7) - 3;
            for (int session = 0; session < sessions; session++) {
                String sessionId = "demo-" + day + "-" + session;
                String customer = "customer_hash_demo-" + (random.nextInt(40) + 1);
                String timestamp = day + "T" + String.format("%02d:%02d:00", 8 + random.nextInt(10),
                        random.nextInt(60));
                String project = PROJECTS[random.nextInt(PROJECTS.length)];
                String range = AMOUNT_RANGES[random.nextInt(AMOUNT_RANGES.length)];
                int index = 0;
                events.add(event(sessionId, customer, "PROJECT_DETECTED", project, range, null, null, null, null,
                        null, timestamp, day + "-" + session + "-project"));
                int recommendedCount = 1 + random.nextInt(3);
                for (int r = 0; r < recommendedCount; r++) {
                    String[] product = PRODUCTS[random.nextInt(PRODUCTS.length)];
                    events.add(event(sessionId, customer, "PRODUCT_RECOMMENDED", project, range, product[0],
                            product[1], product[2], null, null, timestamp, day + "-" + session + "-rec-" + r));
                }
                int interestCount = random.nextInt(3);
                for (int i = 0; i < interestCount; i++) {
                    String[] product = PRODUCTS[random.nextInt(PRODUCTS.length)];
                    boolean high = random.nextInt(100) < 45;
                    events.add(event(sessionId, customer, "PRODUCT_INTEREST", project, range, product[0],
                            product[1], product[2], high ? "HIGH" : "MEDIUM",
                            INTEREST_REASONS[random.nextInt(INTEREST_REASONS.length)], timestamp,
                            day + "-" + session + "-int-" + i));
                    if (high && random.nextInt(100) < 30) {
                        events.add(event(sessionId, customer, "SUBSCRIPTION_INTEREST", project, range, product[0],
                                product[1], product[2], null, "SUBSCRIPTION_REQUEST", timestamp,
                                day + "-" + session + "-sub-" + i));
                    }
                    if (high && random.nextInt(100) < 20) {
                        events.add(event(sessionId, customer, "APPOINTMENT_INTEREST", project, range, product[0],
                                product[1], product[2], null, "ADVISOR_REQUEST", timestamp,
                                day + "-" + session + "-rdv-" + i));
                    }
                }
                if (random.nextInt(100) < 18) {
                    String[] product = PRODUCTS[random.nextInt(PRODUCTS.length)];
                    events.add(event(sessionId, customer, "PRODUCT_REJECTED", project, range, product[0],
                            product[1], product[2], null,
                            REJECTION_REASONS[random.nextInt(REJECTION_REASONS.length)], timestamp,
                            day + "-" + session + "-rej"));
                }
                if (random.nextInt(100) < 7) {
                    events.add(event(sessionId, customer, "UNMET_NEED", project, range, null, null, null, null,
                            "NO_SUITABLE_PRODUCT", timestamp, day + "-" + session + "-unmet"));
                }
                if (random.nextInt(100) < 10) {
                    events.add(event(sessionId, customer, "MISSING_PRODUCT_INFORMATION", project, range, null, null,
                            null, null, MISSING_INFO_REASONS[random.nextInt(MISSING_INFO_REASONS.length)], timestamp,
                            day + "-" + session + "-info"));
                }
            }
        }
        List<MarketingModels.MarketingEvent> written = eventStore.append(events);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("demo", true);
        result.put("days", days);
        result.put("sessionsPerDay", sessionsPerDay);
        result.put("eventsGenerated", events.size());
        result.put("eventsWritten", written.size());
        result.put("note", "Données de démonstration (demo=true) — ne pas interpréter comme des données réelles.");
        return result;
    }

    private MarketingModels.MarketingEvent event(String sessionId, String customer, String type, String project,
                                                 String range, String productId, String productName,
                                                 String productFamily, String interestLevel, String reasonCategory,
                                                 String timestamp, String deterministicId) {
        return new MarketingModels.MarketingEvent(
                "demo-" + deterministicId,
                type,
                timestamp,
                sessionId,
                customer,
                project,
                range,
                productId,
                productName,
                productFamily,
                interestLevel,
                reasonCategory,
                null,
                null,
                interestLevel != null ? 0.9 : 0.8,
                properties.extractorVersion(),
                "demo",
                "DEMO",
                timestamp + ":00",
                Boolean.TRUE);
    }
}
