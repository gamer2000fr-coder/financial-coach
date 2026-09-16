package com.coach.financier.service;

import com.coach.financier.config.PromptOptimizationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Historique des prompts : la copie datée prise AVANT tout remplacement est la garantie de retour
 * arrière exigée par la promotion d'une version (§17). Aucun fichier de production n'est touché ici :
 * le store écrit dans un répertoire temporaire.
 */
class AgentPromptHistoryStoreTest {

    @TempDir
    Path tempDir;

    private final PromptZoneService zoneService = new PromptZoneService();
    private AgentPromptHistoryStore store;

    @BeforeEach
    void setUp() {
        PromptOptimizationProperties properties =
                new PromptOptimizationProperties(true, false, tempDir.toString(), 50, 20000, "sel-de-test");
        store = new AgentPromptHistoryStore(properties, zoneService,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void savesTheCurrentPromptBeforeReplacement() {
        String current = "[[[\nRègles actuelles de l'agent crédit conso.\n]]]\n";

        var backup = store.backup("credit_conso", "credit-conso.txt", current,
                "promotion de V4 (campagne po-1)");

        assertTrue(Files.isRegularFile(store.historyDir().resolve(backup.backupFile())),
                "une copie datée du prompt remplacé doit exister sur disque");
        assertEquals(current, store.contentOf(backup).orElseThrow(), "le contenu remplacé est restituable");
        assertEquals(zoneService.hash(current), backup.contentHash(), "empreinte du contenu sauvegardé");
        assertEquals("credit-conso.txt", backup.file());
        assertEquals("credit_conso", backup.agentKey());
        assertTrue(backup.reason().contains("po-1"));
    }

    @Test
    void keepsAnOrderedIndexWithTheLatestBackupPerFile() {
        store.backup("credit_conso", "credit-conso.txt", "version 1", "promotion de V1");
        var latest = store.backup("credit_conso", "credit-conso.txt", "version 2", "promotion de V2");

        assertEquals(2, store.backups().size(), "l'index conserve TOUTES les sauvegardes");
        assertEquals(latest.backupId(), store.latestFor("credit-conso.txt").orElseThrow().backupId());
        assertEquals("version 2", store.contentOf(latest.backupId()).orElseThrow());
    }

    @Test
    void unknownBackupHasNoContent() {
        assertTrue(store.contentOf("inconnu").isEmpty());
        assertTrue(store.latestFor("jamais-sauvegarde.txt").isEmpty());
    }

    @Test
    void sanitizesTheFileName() {
        var backup = store.backup("credit_conso", "../evasion/agent.txt", "contenu", "test");

        assertFalse(backup.backupFile().contains("/"));
        assertFalse(backup.backupFile().contains(".."));
        assertTrue(Files.isRegularFile(store.historyDir().resolve(backup.backupFile())));
    }

    @Test
    void ignoresInvalidIndexLinesInsteadOfFailing() throws Exception {
        store.backup("credit_conso", "credit-conso.txt", "version 1", "promotion de V1");
        Files.writeString(store.historyDir().resolve("promotions.jsonl"), "{ ligne corrompue\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(1, store.backups().size(), "l'index reste exploitable malgré une ligne invalide");
    }
}
