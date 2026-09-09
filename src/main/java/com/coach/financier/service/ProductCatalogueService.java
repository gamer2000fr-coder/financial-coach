package com.coach.financier.service;

import com.coach.financier.model.BankProduct;
import com.coach.financier.model.ProductFamily;
import com.coach.financier.model.ProjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

/**
 * Charge le catalogue machine-readable des produits bancaires (products.json)
 * et applique le filtrage déterministe : famille autorisée pour le projet
 * + types de projets autorisés + bornes de montant. Aucun LLM ici.
 */
@Service
public class ProductCatalogueService {

    private final List<BankProduct> products;
    private final ProjectProductMappingService mappingService;

    public ProductCatalogueService(ObjectMapper objectMapper,
                                   ProjectProductMappingService mappingService,
                                   @Value("${app.data.dir:./data}") String dataDir) throws IOException {
        this.products = List.copyOf(loadProducts(objectMapper, dataDir));
        this.mappingService = mappingService;
    }

    private static List<BankProduct> loadProducts(ObjectMapper objectMapper, String dataDir) throws IOException {
        JsonNode root = readJson(objectMapper, dataDir);
        List<BankProduct> result = new ArrayList<>();
        if (root != null && root.path("products").isArray()) {
            for (JsonNode node : root.path("products")) {
                result.add(objectMapper.treeToValue(node, BankProduct.class));
            }
        }
        return result;
    }

    private static JsonNode readJson(ObjectMapper objectMapper, String dataDir) throws IOException {
        Path path = Path.of(dataDir, "catalogue", "products.json").toAbsolutePath().normalize();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                return objectMapper.readTree(in);
            }
        }
        try (InputStream in = new ClassPathResource("data/catalogue/products.json").getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    public List<BankProduct> all() {
        return products;
    }

    /**
     * Produits compatibles avec un type de projet (famille autorisée + types autorisés
     * + plage de montant si le montant du projet est connu).
     */
    public List<BankProduct> findCompatible(ProjectType projectType, BigDecimal projectAmount) {
        return findCompatible(projectType, projectAmount, null);
    }

    public List<BankProduct> findCompatible(ProjectType projectType, BigDecimal projectAmount,
                                            Set<ProductFamily> allowedFamilies) {
        if (projectType == null) {
            return List.of();
        }
        Set<ProductFamily> families = allowedFamilies != null ? allowedFamilies
                : mappingService.getAllowedFamilies(projectType);
        List<BankProduct> result = new ArrayList<>();
        for (BankProduct product : products) {
            if (product.getFamily() == null || !families.contains(product.getFamily())) {
                continue;
            }
            if (!product.getAllowedProjectTypes().isEmpty()
                    && !product.getAllowedProjectTypes().contains(projectType)) {
                continue;
            }
            if (projectAmount != null) {
                if (product.getMinAmount() != null && projectAmount.compareTo(product.getMinAmount()) < 0) {
                    continue;
                }
                if (product.getMaxAmount() != null && projectAmount.compareTo(product.getMaxAmount()) > 0) {
                    continue;
                }
            }
            result.add(product);
        }
        return List.copyOf(result);
    }
}
