package com.coach.financier.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fournisseur LOCAL : un serveur compatible OpenAI tournant sur la machine (LM Studio, Ollama, llama.cpp…).
 * <p>
 * Rien à installer côté application : on parle le MÊME protocole que GPT/DeepSeek
 * ({@code POST /chat/completions}), seules trois différences comptent et sont configurées ici :
 * <ul>
 *   <li><b>aucune clé API</b> ({@code app.ai.local.api-key} vide par défaut) : l'en-tête {@code Authorization}
 *       n'est même pas envoyé, et une clé absente n'est pas une erreur ;</li>
 *   <li><b>pas de mode JSON natif</b> ({@code app.ai.local.json-mode: false}) : LM Studio refuse
 *       {@code response_format: json_object} (400 « must be 'json_schema' or 'text' »). Les prompts exigent
 *       déjà du JSON, l'encadrement Markdown éventuel est retiré et un JSON tronqué est réparé ;</li>
 *   <li><b>contexte limité par le serveur local</b> : LM Studio charge le modèle avec une taille de contexte
 *       ({@code n_ctx}) réglée dans l'interface (4 096 par défaut), or une conversation du coach envoie
 *       facilement 6 000 à 15 000 jetons (prompts d'agent + catalogue + synthèse + historique). Il faut donc
 *       charger le modèle avec un contexte plus large ; sinon LM Studio répond
 *       « The number of tokens to keep from the initial prompt is greater than the context length ».</li>
 * </ul>
 * Le modèle servi est celui chargé côté serveur : {@code app.ai.local.model} doit correspondre à l'identifiant
 * exposé par {@code GET /v1/models} (réglable par variables d'environnement, sans redéployer).
 */
@Service("localAIService")
public class LocalAIService extends RemoteAIService {

    public LocalAIService(ObjectMapper objectMapper,
                          @Value("${app.ai.local.base-url:http://localhost:1234/v1}") String baseUrl,
                          @Value("${app.ai.local.api-key:}") String apiKey,
                          @Value("${app.ai.local.model}") String model,
                          @Value("${app.ai.local.max-tokens:8192}") int maxTokens,
                          @Value("${app.ai.local.json-mode:none}") String jsonMode,
                          @Value("${app.ai.local.read-timeout-seconds:1800}") int readTimeoutSeconds) {
        super(objectMapper, baseUrl, apiKey, model, "Local (LM Studio)", maxTokens,
                false,   // clé API non exigée : un serveur local n'en demande pas
                false,   // pas de response_format=json_object : LM Studio le REFUSE
                "schema".equalsIgnoreCase(jsonMode) || "json_schema".equalsIgnoreCase(jsonMode),
                // 0 (ou négatif) = AUCUN délai de lecture : une génération locale longue n'est jamais
                // coupée. Utile en démonstration, où l'on préfère attendre plutôt que d'échouer.
                readTimeoutSeconds <= 0 ? 0 : readTimeoutSeconds * 1000);
    }

    /**
     * Un modèle local n'est pas « en panne » quand il dépasse le délai : il est simplement plus lent
     * (raisonnement + génération, et la génération d'une réponse longue peut être interrompue en plein
     * milieu). Le message doit donc désigner le RÉGLAGE à modifier, pas seulement constater l'échec.
     */
    @Override
    protected String readTimeoutHint() {
        return "Un modèle local génère lentement : augmentez app.ai.local.read-timeout-seconds "
                + "(variable d'environnement LOCAL_READ_TIMEOUT_SECONDS, en secondes) "
                + "puis relancez le backend ; si le modèle ne répond toujours pas, vérifiez qu'il est "
                + "toujours chargé et que le contexte demandé tient dans sa fenêtre (lms ps / lms load -c).";
    }
}
