package com.coach.financier.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        // Lecture TOLÉRANTE des réponses LLM : un modèle en mode JSON renvoie régulièrement des
        // caractères de contrôle BRUTS dans les chaînes (retours à la ligne d'un texte de plusieurs
        // paragraphes, tabulations) — sans cette tolérance, Jackson échoue avec
        // « Illegal unquoted character ((CTRL-CHAR, code 10)): has to be escaped using backslash »
        // et l'itération (ou la clôture de conversation) est perdue. Une virgule finale est également
        // fréquente dans les sorties de modèles.
        JsonFactory factory = JsonFactory.builder()
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        // Enregistre le module pour gérer java.time.LocalDate, LocalDateTime, etc.
        mapper.registerModule(new JavaTimeModule());
        // Tolère les champs inconnus (réponses LLM / catalogues évolutifs).
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Tolère aussi une valeur d'ÉNUMÉRATION inconnue : un modèle confond régulièrement deux listes
        // proches (« intent » et « projectType » contiennent tous deux DEBT_RESTRUCTURING par exemple) et
        // une seule valeur hors liste faisait échouer TOUT l'appel (HTTP 500 « Réponse classification
        // invalide »). La valeur devient null et les setters défensifs des beans la ramènent à une valeur
        // par défaut (OTHER / UNKNOWN / LOW).
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        // Évite d'écrire les dates sous forme de tableau [2026, 9, 5]
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
