package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pseudonymisation des identifiants clients pour les fichiers Marketing (§5) : aucune donnée
 * personnelle n'est écrite sur disque, seul un empreinte stable est conservée.
 */
@Service
public class AnonymousIdService {

    private final String salt;

    public AnonymousIdService(MarketingProperties properties) {
        this.salt = properties.hashSalt();
    }

    /**
     * Empreinte stable (SHA-256 tronqué, salé) d'un identifiant client :
     * {@code DEMO001} → {@code customer_hash_5f3a...}. Retourne {@code null} si l'entrée est vide.
     */
    public String anonymize(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + "|" + customerId.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "customer_hash_" + hex;
        } catch (NoSuchAlgorithmException e) {
            return null; // SHA-256 toujours disponible : ne devrait pas arriver.
        }
    }
}
