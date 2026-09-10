package com.coach.financier.controller;

import com.coach.financier.model.MailModels;
import com.coach.financier.service.MailService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** API d'envoi de mails : état du service + envoi d'un message. */
@RestController
@RequestMapping("/api/mail")
public class MailController {
    private final MailService mailService;

    public MailController(MailService mailService) {
        this.mailService = mailService;
    }

    /** État du service mail (pour vérifier la configuration sans envoyer). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", mailService.isEnabled());
        out.put("available", mailService.isAvailable());
        out.put("from", mailService.getFrom() == null ? "" : mailService.getFrom());
        // Diagnostic : cible SMTP + origine précise de l'indisponibilité (vide si opérationnel).
        out.put("target", mailService.describeTarget());
        out.put("reason", mailService.unavailabilityReason());
        return out;
    }

    /** Envoie un mail : {to, subject, body, html}. */
    @PostMapping
    public MailModels.MailResponse send(@Valid @RequestBody MailModels.MailRequest request) {
        boolean html = Boolean.TRUE.equals(request.html());
        try {
            mailService.send(request.to(), request.subject(), request.body(), html);
        } catch (IllegalStateException e) {
            // Configuration manquante ou échec SMTP : 503 avec un message clair.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
        return new MailModels.MailResponse("SENT", request.to(), request.subject());
    }
}
