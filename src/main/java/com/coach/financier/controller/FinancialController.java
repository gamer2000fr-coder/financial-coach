package com.coach.financier.controller;

import com.coach.financier.model.FinancialSummary;
import com.coach.financier.repository.BankingDataRepository;
import com.coach.financier.service.FinancialAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FinancialController {
    private final FinancialAnalysisService analysisService;
    private final BankingDataRepository repository;

    public FinancialController(FinancialAnalysisService analysisService, BankingDataRepository repository) {
        this.analysisService = analysisService;
        this.repository = repository;
    }

    @GetMapping("/financial-summary")
    public FinancialSummary financialSummary() {
        return analysisService.analyze();
    }

    @GetMapping("/banking-data")
    public Object bankingData() {
        return repository.loadSnapshot().rawData();
    }
}
