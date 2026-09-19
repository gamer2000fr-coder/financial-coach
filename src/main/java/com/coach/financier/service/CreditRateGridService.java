package com.coach.financier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Grille de taux du crédit à la consommation : {@code data/catalogue/taux/grilles_taux_credit_conso.json}
 * (déclarée dans {@code data/data.json} et donc fournie au Coach, qui s'appuie sur la même grille).
 * <p>
 * SOURCE UNIQUE des TAEG : rien n'est inventé — une règle désigne un couple (montant, durée) → TAEG.
 * Grille absente ou produit inconnu ⇒ aucun taux, donc AUCUNE simulation (cf. {@link CreditSimulationService}).
 * <p>
 * La grille est lue du système de fichiers ({@code app.data.dir}, comme les autres catalogues) puis du
 * classpath en repli. Le fichier de démonstration porte le statut
 * {@code DEMO_SYNTHETIC_NOT_OFFICIAL_SG_RATE_SCHEDULE} : ces TAEG sont des taux de démonstration.
 */
@Service
public class CreditRateGridService {

    private static final Logger log = LoggerFactory.getLogger(CreditRateGridService.class);
    private static final String GRID_RELATIVE_PATH = "catalogue/taux/grilles_taux_credit_conso.json";
    private static final String GRID_CLASSPATH = "data/" + GRID_RELATIVE_PATH;

    /** Une règle de la grille : un couple (montant, durée) désigne un TAEG. */
    public record RateRule(String productId,
                           String productName,
                           String ruleId,
                           String loanType,
                           BigDecimal amountMinEur,
                           BigDecimal amountMaxEur,
                           int durationMinMonths,
                           int durationMaxMonths,
                           BigDecimal taegPercent) {

        /** La règle couvre-t-elle ce montant et cette durée ? (bornes incluses, bornes nulles = non borné) */
        public boolean matches(BigDecimal amount, int durationMonths) {
            if (amount == null) {
                return false;
            }
            if (amountMinEur != null && amount.compareTo(amountMinEur) < 0) {
                return false;
            }
            if (amountMaxEur != null && amount.compareTo(amountMaxEur) > 0) {
                return false;
            }
            if (durationMinMonths > 0 && durationMonths < durationMinMonths) {
                return false;
            }
            return durationMaxMonths <= 0 || durationMonths <= durationMaxMonths;
        }

        /** Le produit est-il un crédit renouvelable ? (pas d'amortissement à échéances constantes) */
        public boolean revolving() {
            return loanType != null && "revolving".equalsIgnoreCase(loanType.trim());
        }
    }

    private final List<RateRule> rules;
    private final String asOf;
    private final String status;
    private final JsonNode usuryNode;

    public CreditRateGridService(ObjectMapper objectMapper,
                                 @Value("${app.data.dir:./data}") String dataDir) {
        JsonNode root = readGrid(objectMapper, dataDir);
        this.rules = List.copyOf(parseRules(root));
        this.asOf = text(root, "as_of");
        this.status = root == null ? "ABSENT" : root.path("status").asText("");
        this.usuryNode = root == null ? null : root.path("usury_q3_2026");
        log.info("Grille de taux crédit conso : {} règle(s), statut {} (as_of {})", rules.size(), status, asOf);
    }

    /** Toutes les règles disponibles (vide si la grille est illisible : on ne devine aucun taux). */
    public List<RateRule> rules() {
        return rules;
    }

    /** Règles d'un produit (vide si le produit est inconnu de la grille). */
    public List<RateRule> rulesFor(String productId) {
        List<RateRule> result = new ArrayList<>();
        if (productId == null || productId.isBlank()) {
            return result;
        }
        for (RateRule rule : rules) {
            if (productId.trim().equals(rule.productId())) {
                result.add(rule);
            }
        }
        return result;
    }

    /** Première règle couvrant le couple (montant, durée) pour ce produit. */
    public Optional<RateRule> ruleFor(String productId, BigDecimal amount, Integer durationMonths) {
        if (durationMonths == null || durationMonths <= 0) {
            return Optional.empty();
        }
        for (RateRule rule : rulesFor(productId)) {
            if (rule.matches(amount, durationMonths)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** Identifiants des produits présents dans la grille (utile pour expliquer un refus). */
    public List<String> productIds() {
        List<String> ids = new ArrayList<>();
        for (RateRule rule : rules) {
            if (!ids.contains(rule.productId())) {
                ids.add(rule.productId());
            }
        }
        return ids;
    }

    /** Le produit est-il un crédit renouvelable ? */
    public boolean isRevolving(String productId) {
        for (RateRule rule : rulesFor(productId)) {
            if (rule.revolving()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Taux d'usure (TAEG) de la tranche de montant, lu dans la grille, ou vide si la grille ne le fournit pas.
     * Aucun seuil n'est codé en dur ici : une grille sans taux d'usure ne bloque rien.
     */
    public Optional<BigDecimal> usuryCeilingPercent(BigDecimal amount) {
        if (usuryNode == null || usuryNode.isMissingNode() || amount == null) {
            return Optional.empty();
        }
        String key = amount.compareTo(BigDecimal.valueOf(3000)) <= 0 ? "up_to_3000_eur"
                : amount.compareTo(BigDecimal.valueOf(6000)) <= 0 ? "over_3000_up_to_6000_eur"
                : "over_6000_eur";
        JsonNode value = usuryNode.path(key);
        return value.isNumber() ? Optional.of(value.decimalValue()) : Optional.empty();
    }

    /** Date de référence de la grille (« as_of »), ou {@code null} si absente. */
    public String asOf() {
        return asOf;
    }

    /** Statut déclaré par la grille (ex. {@code DEMO_SYNTHETIC_NOT_OFFICIAL_SG_RATE_SCHEDULE}). */
    public String status() {
        return status;
    }

    // ------------------------------------------------------------------ lecture

    private static List<RateRule> parseRules(JsonNode root) {
        List<RateRule> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        for (JsonNode product : root.path("products")) {
            String productId = product.path("id").asText(null);
            String productName = product.path("nom").asText(null);
            String loanType = product.path("loan_type").asText(null);
            for (JsonNode rule : product.path("rules")) {
                String ruleProductId = rule.path("product_id").asText(productId);
                if (ruleProductId == null || ruleProductId.isBlank()) {
                    continue;
                }
                result.add(new RateRule(
                        ruleProductId,
                        productName,
                        rule.path("rule_id").asText(null),
                        loanType,
                        decimal(rule, "amount_min_eur"),
                        decimal(rule, "amount_max_eur"),
                        rule.path("duration_min_months").asInt(0),
                        rule.path("duration_max_months").asInt(0),
                        decimal(rule, "taeg_percent")));
            }
        }
        return result;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static JsonNode readGrid(ObjectMapper objectMapper, String dataDir) {
        Path path = Path.of(dataDir, GRID_RELATIVE_PATH).toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                return objectMapper.readTree(in);
            } catch (IOException e) {
                log.warn("Grille de taux illisible ({}): {}", path, e.getMessage());
            }
        }
        try (InputStream in = new ClassPathResource(GRID_CLASSPATH).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            log.warn("Grille de taux introuvable ({}) : aucune simulation ne sera possible.", GRID_CLASSPATH);
            return null;
        }
    }
}
