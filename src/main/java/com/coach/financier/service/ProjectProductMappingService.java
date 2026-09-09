package com.coach.financier.service;

import com.coach.financier.model.ProductFamily;
import com.coach.financier.model.ProjectType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Moteur métier DÉTERMINISTE (aucun LLM) : établit, pour chaque type de projet,
 * les familles de produits bancaires autorisées. Règle métier que l'IA ne peut pas contourner.
 */
@Service
public class ProjectProductMappingService {

    private final Map<ProjectType, Set<ProductFamily>> allowedByProject = new EnumMap<>(ProjectType.class);

    public ProjectProductMappingService() {
        allowedByProject.put(ProjectType.VEHICLE,
                families(ProductFamily.AUTO_LOAN, ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT,
                        ProductFamily.YOUNG_ACTIVE_LOAN, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.HOME_WORK,
                families(ProductFamily.HOME_IMPROVEMENT_LOAN, ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT,
                        ProductFamily.YOUNG_ACTIVE_LOAN, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.ELECTRONICS,
                families(ProductFamily.INSTALLMENT_PAYMENT, ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT,
                        ProductFamily.YOUNG_ACTIVE_LOAN, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.FURNITURE,
                families(ProductFamily.INSTALLMENT_PAYMENT, ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT,
                        ProductFamily.YOUNG_ACTIVE_LOAN, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.TRAVEL,
                families(ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.EDUCATION,
                families(ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT, ProductFamily.STUDENT_LOAN,
                        ProductFamily.DRIVER_LICENSE_LOAN, ProductFamily.YOUNG_ACTIVE_LOAN, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.WEDDING,
                families(ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.HEALTH_EXPENSE,
                families(ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT, ProductFamily.REVOLVING_CREDIT));
        allowedByProject.put(ProjectType.CASH_NEED,
                families(ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT,
                        ProductFamily.REVOLVING_CREDIT, ProductFamily.YOUNG_ACTIVE_LOAN));
        allowedByProject.put(ProjectType.DEBT_RESTRUCTURING,
                families(ProductFamily.PERSONAL_LOAN, ProductFamily.CONSUMER_CREDIT, ProductFamily.DEBT_CONSOLIDATION));
        allowedByProject.put(ProjectType.REAL_ESTATE_PURCHASE,
                families(ProductFamily.MORTGAGE, ProductFamily.HOME_SAVINGS));
        allowedByProject.put(ProjectType.SAVINGS,
                families(ProductFamily.SAVINGS_PRODUCT, ProductFamily.TERM_DEPOSIT,
                        ProductFamily.HOME_SAVINGS, ProductFamily.LIFE_INSURANCE));
        allowedByProject.put(ProjectType.INVESTMENT,
                families(ProductFamily.SAVINGS_PRODUCT, ProductFamily.TERM_DEPOSIT,
                        ProductFamily.LIFE_INSURANCE, ProductFamily.RETIREMENT_SAVINGS, ProductFamily.EQUITY_INVESTMENT));
        allowedByProject.put(ProjectType.INSURANCE,
                families(ProductFamily.INSURANCE_AUTO, ProductFamily.INSURANCE_HOME, ProductFamily.INSURANCE_BORROWER));
        // BUDGET, OTHER_FINANCIAL, UNKNOWN : aucun produit proposé automatiquement.
        allowedByProject.put(ProjectType.BUDGET, Set.of());
        allowedByProject.put(ProjectType.OTHER_FINANCIAL, Set.of());
        allowedByProject.put(ProjectType.UNKNOWN, Set.of());
    }

    private static Set<ProductFamily> families(ProductFamily... values) {
        return EnumSet.of(values[0], values);
    }

    /** Familles autorisées pour un type de projet (jamais {@code null}). */
    public Set<ProductFamily> getAllowedFamilies(ProjectType projectType) {
        if (projectType == null) {
            return Set.of();
        }
        return allowedByProject.getOrDefault(projectType, Set.of());
    }

    public boolean isFamilyAllowed(ProjectType projectType, ProductFamily family) {
        return getAllowedFamilies(projectType).contains(family);
    }
}
