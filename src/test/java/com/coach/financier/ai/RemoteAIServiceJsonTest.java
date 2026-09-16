package com.coach.financier.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Certains modèles encadrent leur JSON par un bloc Markdown : le contenu doit être récupéré tel quel. */
class RemoteAIServiceJsonTest {

    @Test
    void stripsAMarkdownCodeFence() {
        String fenced = "```json\n{\"status\":\"ANSWER\",\"answer\":\"Bonjour\"}\n```";

        assertEquals("{\"status\":\"ANSWER\",\"answer\":\"Bonjour\"}", RemoteAIService.stripCodeFence(fenced));
        assertEquals("{\"status\":\"ANSWER\"}", RemoteAIService.stripCodeFence("```\n{\"status\":\"ANSWER\"}\n```"));
    }

    @Test
    void keepsPlainJsonUntouched() {
        assertEquals("{\"status\":\"ANSWER\"}", RemoteAIService.stripCodeFence("{\"status\":\"ANSWER\"}"));
        assertEquals("{\"status\":\"ANSWER\"}", RemoteAIService.stripCodeFence("  {\"status\":\"ANSWER\"}  "));
        assertEquals("", RemoteAIService.stripCodeFence(null));
    }
}
