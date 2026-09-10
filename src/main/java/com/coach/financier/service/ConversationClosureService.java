package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.AgentFiles;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.BankProduct;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.SuiviModels;
import com.coach.financier.repository.BankingDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Orchestration de la FIN DE CONVERSATION.
 * <p>
 * Enchaînement (cf. dossier fonctionnel) :
 * <ol>
 *   <li>récupère l'historique COMPLET, le contexte client, les projets, les produits évoqués
 *       et les URLs officielles disponibles ;</li>
 *   <li>appelle l'IA de synthèse ({@code agent/suivi.txt}) ;</li>
 *   <li>valide côté backend (produits réels, URLs non inventées, refus exclus) ;</li>
 *   <li>génère la pièce jointe à partir du brouillon d'email client ;</li>
 *   <li>envoie UN SEUL email : au CONSEILLER, avec la pièce jointe.</li>
 * </ol>
 * Le brouillon client n'est JAMAIS envoyé automatiquement : il est considéré comme un
 * BROUILLON commercial que le conseiller doit relire et décider d'envoyer.
 */
@Service
public class ConversationClosureService {
    private static final Logger log = LoggerFactory.getLogger(ConversationClosureService.class);
    private static final String ATTACHMENT_NOTICE =
            "Vous trouverez en pièce jointe un brouillon d'email préparé à destination du client. "
            + "Merci de le vérifier et de l'adapter avant tout envoi.";
    /** Libellé de l'agent affiché en badge sur la page Logs. */
    private static final String SUIVI_AGENT_LABEL = "Agent de suivi (suivi.txt)";
    /** Libellé de la « parole client » d'une trace de clôture (il n'y a pas de message client). */
    private static final String CLOSE_TRACE_MESSAGE = "Clôture de conversation — dossier de suivi";

    private final ConversationService conversationService;
    private final AIServiceFactory aiServiceFactory;
    private final ProductUrlIndex productUrlIndex;
    private final ProductCatalogueService productCatalogueService;
    private final EmailAttachmentBuilder attachmentBuilder;
    private final MailService mailService;
    private final BankingDataRepository bankingDataRepository;
    private final FinancialAnalysisService financialAnalysisService;
    private final AILogService aiLogService;
    private final ObjectMapper objectMapper;

    private final String configuredAdvisorName;
    private final String configuredAdvisorEmail;
    private final String configuredCustomerName;
    private final String defaultAttachmentFormat;
    private final String appointmentUrl;
    private final boolean advisorMailHtml;

    public ConversationClosureService(ConversationService conversationService,
                                      AIServiceFactory aiServiceFactory,
                                      ProductUrlIndex productUrlIndex,
                                      ProductCatalogueService productCatalogueService,
                                      EmailAttachmentBuilder attachmentBuilder,
                                      MailService mailService,
                                      BankingDataRepository bankingDataRepository,
                                      FinancialAnalysisService financialAnalysisService,
                                      AILogService aiLogService,
                                      ObjectMapper objectMapper,
                                      @Value("${app.advisor.name:}") String configuredAdvisorName,
                                      @Value("${app.advisor.email:}") String configuredAdvisorEmail,
                                      @Value("${app.customer.name:}") String configuredCustomerName,
                                      @Value("${app.suivi.attachment-format:txt}") String defaultAttachmentFormat,
                                      @Value("${app.suivi.advisor-appointment-url:}") String appointmentUrl,
                                      @Value("${app.suivi.advisor-mail-html:true}") boolean advisorMailHtml) {
        this.conversationService = conversationService;
        this.aiServiceFactory = aiServiceFactory;
        this.productUrlIndex = productUrlIndex;
        this.productCatalogueService = productCatalogueService;
        this.attachmentBuilder = attachmentBuilder;
        this.mailService = mailService;
        this.bankingDataRepository = bankingDataRepository;
        this.financialAnalysisService = financialAnalysisService;
        this.aiLogService = aiLogService;
        this.objectMapper = objectMapper;
        this.configuredAdvisorName = configuredAdvisorName;
        this.configuredAdvisorEmail = configuredAdvisorEmail;
        this.configuredCustomerName = configuredCustomerName;
        this.defaultAttachmentFormat = defaultAttachmentFormat;
        this.appointmentUrl = appointmentUrl;
        this.advisorMailHtml = advisorMailHtml;
    }

    public SuiviModels.CloseConversationResponse close(String sessionId, SuiviModels.CloseConversationRequest request) {
        SuiviModels.CloseConversationRequest req = request == null
                ? new SuiviModels.CloseConversationRequest(null, null, null, null, null) : request;
        ConversationModels.Conversation conversation = conversationService.find(sessionId);
        if (conversation == null) {
            // Session inconnue côté serveur : backend redémarré (mémoire) ou session restaurée par le
            // navigateur. La clôture est un effet de bord « best effort » (fire-and-forget) : on ne casse
            // pas l'appel par une 404, on renvoie une réponse explicite SANS préparation ni envoi.
            log.warn("Clôture ignorée : aucune conversation en mémoire pour la session {}", sessionId);
            return new SuiviModels.CloseConversationResponse(
                    sessionId, "NO_CONVERSATION",
                    firstNonBlank(req.advisorName(), configuredAdvisorName, "Conseiller"),
                    firstNonBlank(req.advisorEmail(), configuredAdvisorEmail, ""),
                    null, List.of(),
                    new SuiviModels.ConversationSummary("", List.of(), List.of()),
                    List.of(), List.of(),
                    new SuiviModels.EmailContent("", ""),
                    new SuiviModels.EmailContent("", ""),
                    List.of("Aucune conversation en mémoire pour cette session (backend redémarré ou session "
                            + "expirée) : aucun dossier n'a été préparé et aucun email n'a été envoyé."));
        }

        String advisorName = firstNonBlank(req.advisorName(), configuredAdvisorName, "Conseiller");
        String advisorAddress = firstNonBlank(req.advisorEmail(), configuredAdvisorEmail, "");
        String format = firstNonBlank(req.attachmentFormat(), defaultAttachmentFormat, "txt");
        List<String> warnings = new ArrayList<>();

        // 1) Produits candidats : ceux réellement présentés pendant l'échange (pas tout le catalogue).
        List<Map<String, Object>> candidateProducts = enrichProducts(conversation.discussedProducts());
        Set<String> candidateIds = new LinkedHashSet<>();
        Map<String, String> candidateNames = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidateProducts) {
            candidateIds.add(String.valueOf(candidate.get("id")));
            candidateNames.put(String.valueOf(candidate.get("id")), String.valueOf(candidate.get("name")));
        }

        // 2) URLs utiles : uniquement celles fournies par le système (whitelist anti-invention).
        Map<String, Object> usefulUrls = new LinkedHashMap<>();
        if (appointmentUrl != null && !appointmentUrl.isBlank()) {
            usefulUrls.put("advisorAppointment", appointmentUrl);
        }
        usefulUrls.put("other", List.of());
        Set<String> allowedUrls = new LinkedHashSet<>(productUrlIndex.allUrls());
        if (appointmentUrl != null && !appointmentUrl.isBlank()) {
            allowedUrls.add(appointmentUrl.trim());
        }

        // 3) Contexte transmis à l'IA de synthèse (structure du dossier de suivi).
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conversationHistory", historyAsMaps(conversation));
        context.put("customerContext", buildCustomerContext(conversation));
        Map<String, Object> advisorContext = new LinkedHashMap<>();
        advisorContext.put("advisorName", advisorName);
        advisorContext.put("advisorEmail", advisorAddress);
        context.put("advisorContext", advisorContext);
        context.put("products", candidateProducts);
        context.put("usefulUrls", usefulUrls);

        // Prompt système de l'agent de suivi + taille réellement envoyée (pour la page Logs).
        String suiviPrompt = AgentFiles.suiviSystemPrompt();
        long sentChars = suiviCharCount(suiviPrompt, context);

        // 4) Appel IA de synthèse.
        AIModels.AIProvider provider = req.provider() == null ? aiServiceFactory.defaultProvider() : req.provider();
        AIService ai = aiServiceFactory.get(provider);
        SuiviModels.SuiviResult result;
        try {
            result = ai.summarizeConversation(context, provider);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Échec de l'IA de synthèse : " + e.getMessage(), e);
        }

        // 5) Validation backend : produits réels, refus exclus, URLs non inventées.
        Validated validated = validate(result, candidateIds, candidateNames, allowedUrls, warnings);

        // 6) Pièce jointe générée à partir du brouillon client.
        SuiviModels.Attachment attachment = attachmentBuilder.build(
                format, validated.preparedCustomerEmail(), customerMail());

        // 7) Envoi du SEUL email conseiller (jamais au client), sauf dry-run.
        //    `mailError` conserve l'ORIGINE précise d'un non-envoi (configuration, SMTP...), reprise
        //    dans les avertissements de la réponse ET dans le bloc [SUIVI] de la page Logs.
        boolean send = !Boolean.FALSE.equals(req.send());
        String status;
        String mailError = "";
        List<String> sentTo = List.of();
        if (!send) {
            status = "PREPARED";
        } else if (advisorAddress.isBlank()) {
            status = "MAIL_UNAVAILABLE";
            mailError = "destinataire conseiller non configuré (ADVISOR_EMAIL / app.advisor.email)";
            warnings.add("Mail conseiller NON envoyé — " + mailError + ". Dossier préparé mais non envoyé.");
        } else {
            String reason = mailService.unavailabilityReason();
            if (reason != null && !reason.isBlank()) {
                status = "MAIL_UNAVAILABLE";
                mailError = "service mail indisponible : " + reason;
                warnings.add("Mail conseiller NON envoyé — " + mailError + " : dossier préparé mais non envoyé.");
            } else {
                try {
                    String advisorBody = advisorMailHtml
                            ? UrlLinkRenderer.toHtml(validated.advisorEmail().body())
                            : UrlLinkRenderer.toText(validated.advisorEmail().body());
                    mailService.sendWithAttachments(advisorAddress, validated.advisorEmail().subject(),
                            advisorBody, advisorMailHtml, List.of(attachment));
                    status = "SENT";
                    sentTo = List.of(advisorAddress);
                } catch (Exception e) {
                    status = "SEND_FAILED";
                    mailError = "échec de l'envoi à " + advisorAddress + " via "
                            + mailService.describeTarget() + " : " + oneLine(rootCause(e));
                    warnings.add("Mail conseiller NON envoyé — " + mailError);
                    log.warn("Échec de l'envoi du dossier de suivi pour la session {} : {}", sessionId, e.getMessage());
                }
            }
        }

        // 8) Trace dans la page Logs : un enregistrement par appel à l'agent de suivi.
        logSuiviCall(sessionId, conversation, suiviPrompt, context, sentChars, result,
                candidateProducts.size(), provider, validated, format, attachment.filename(),
                status, mailService.describeTarget(), mailError, advisorAddress, warnings);

        return new SuiviModels.CloseConversationResponse(
                sessionId, status, advisorName, advisorAddress, attachment.filename(), sentTo,
                validated.summary(), validated.products(), validated.rejectedProducts(),
                validated.advisorEmail(), validated.preparedCustomerEmail(), warnings);
    }

    /** Résultat après validation backend. */
    private record Validated(
            SuiviModels.ConversationSummary summary,
            List<SuiviModels.ProductOfInterest> products,
            List<String> rejectedProducts,
            SuiviModels.EmailContent advisorEmail,
            SuiviModels.EmailContent preparedCustomerEmail
    ) {}

    /**
     * Contrôle déterministe de la sortie IA :
     * <ul>
     *   <li>les produits d'intérêt sont restreints aux offres réellement présentées ;</li>
     *   <li>l'URL de chaque produit provient UNIQUEMENT de la fiche officielle ;</li>
     *   <li>les produits refusés sont retirés du brouillon client ;</li>
     *   <li>toute URL non fournie par le système est neutralisée.</li>
     * </ul>
     */
    private Validated validate(SuiviModels.SuiviResult result, Set<String> candidateIds,
                               Map<String, String> candidateNames, Set<String> allowedUrls,
                               List<String> warnings) {
        SuiviModels.ConversationSummary summary = result == null || result.conversationSummary() == null
                ? new SuiviModels.ConversationSummary("", List.of(), List.of())
                : result.conversationSummary();
        SuiviModels.EmailContent advisor = result == null || result.advisorEmail() == null
                ? new SuiviModels.EmailContent("", "") : result.advisorEmail();
        SuiviModels.EmailContent customer = result == null || result.preparedCustomerEmail() == null
                ? new SuiviModels.EmailContent("", "") : result.preparedCustomerEmail();

        List<SuiviModels.ProductOfInterest> products = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (result != null && result.productsOfInterest() != null) {
            for (SuiviModels.ProductOfInterest product : result.productsOfInterest()) {
                String id = product.productId() == null ? "" : product.productId().trim();
                if (id.isEmpty() || !candidateIds.contains(id)) {
                    warnings.add("Produit ignoré (non présenté pendant l'échange) : "
                            + (id.isEmpty() ? product.name() : id));
                    continue;
                }
                SuiviModels.InterestLevel level = SuiviModels.InterestLevel.parse(product.interestLevel());
                String name = firstNonBlank(product.name(), candidateNames.get(id), id);
                String category = product.category();
                if (level == SuiviModels.InterestLevel.REJECTED) {
                    rejected.add(name);
                    continue; // jamais présenté comme produit d'intérêt (§18)
                }
                products.add(new SuiviModels.ProductOfInterest(id, name, category, level.name(),
                        product.interestReason(), productUrlIndex.urlFor(id)));
            }
        }

        // Le brouillon client ne doit jamais promouvoir un produit refusé.
        SuiviModels.EmailContent customerEmail = stripRejectedMentions(customer, rejected, warnings);

        // Anti-invention d'URL : on ne conserve que les URLs fournies par le système.
        UrlLinkRenderer.SanitizeResult advisorUrlCheck = UrlLinkRenderer.sanitize(advisor.body(), allowedUrls);
        UrlLinkRenderer.SanitizeResult customerUrlCheck = UrlLinkRenderer.sanitize(customerEmail.body(), allowedUrls);
        for (String violation : advisorUrlCheck.violations()) {
            warnings.add("URL non fournie neutralisée dans l'email conseiller : " + violation);
        }
        for (String violation : customerUrlCheck.violations()) {
            warnings.add("URL non fournie neutralisée dans le brouillon client : " + violation);
        }

        String advisorSubject = firstNonBlank(advisor.subject(), "Suivi client — dossier préparé", "Suivi client");
        String customerSubject = firstNonBlank(customerEmail.subject(), summary.mainProject(), "Votre projet");
        String advisorBody = advisorUrlCheck.text();
        if (!advisorBody.toLowerCase(Locale.ROOT).contains("pièce jointe")) {
            advisorBody = advisorBody.stripTrailing() + "\n\n" + ATTACHMENT_NOTICE;
        }

        return new Validated(summary, List.copyOf(products), List.copyOf(rejected),
                new SuiviModels.EmailContent(advisorSubject, advisorBody),
                new SuiviModels.EmailContent(customerSubject, customerUrlCheck.text()));
    }

    /**
     * Retire du brouillon client toute LIGNE mentionnant un produit explicitement refusé.
     * Filet de sécurité déterministe : le prompt demande déjà de ne pas les proposer.
     */
    private SuiviModels.EmailContent stripRejectedMentions(SuiviModels.EmailContent email,
                                                           List<String> rejected, List<String> warnings) {
        if (rejected.isEmpty() || email == null || email.body() == null) {
            return email == null ? new SuiviModels.EmailContent("", "") : email;
        }
        List<String> normalizedNames = rejected.stream().map(ConversationClosureService::normalize).toList();
        StringBuilder kept = new StringBuilder();
        int removed = 0;
        for (String line : email.body().split("\n", -1)) {
            String normalizedLine = normalize(line);
            boolean mentionsRejected = normalizedNames.stream().anyMatch(name -> !name.isBlank()
                    && normalizedLine.contains(name));
            if (mentionsRejected) {
                removed++;
            } else {
                kept.append(line).append('\n');
            }
        }
        if (removed > 0) {
            warnings.add("Brouillon client corrigé : mention(s) d'un produit refusé retirée(s).");
        }
        return new SuiviModels.EmailContent(email.subject(), kept.toString().stripTrailing());
    }

    /** Nombre de caractères envoyés à l'agent de suivi : prompt système (suivi.txt) + contexte JSON. */
    private long suiviCharCount(String systemPrompt, Map<String, Object> context) {
        try {
            return systemPrompt.length() + objectMapper.writeValueAsString(context).length();
        } catch (Exception e) {
            return systemPrompt.length();
        }
    }

    /**
     * Enregistre l'appel à l'agent de suivi dans la mémoire des traces IA (page Logs) :
     * « parole » de clôture, données envoyées (descriptions), taille de l'historique, caractères,
     * statut, badge agent, prompt complet (visible), bloc de débogage [SUIVI] et réponse JSON.
     */
    private void logSuiviCall(String sessionId, ConversationModels.Conversation conversation,
                              String systemPrompt, Map<String, Object> context, long charCount,
                              SuiviModels.SuiviResult result, int candidateCount, AIModels.AIProvider provider,
                              Validated validated, String attachmentFormat, String attachmentName,
                              String sendStatus, String mailTarget, String mailError,
                              String advisorAddress, List<String> warnings) {
        List<String> dataSent = new ArrayList<>();
        dataSent.add("Historique de conversation (" + conversation.transcript().size() + " messages)");
        dataSent.add("Contexte client (synthèse financière + projets)");
        dataSent.add("Contexte conseiller");
        dataSent.add("Produits présentés (" + candidateCount + ")");
        dataSent.add("URLs utiles");

        String debug = suiviDebug(provider, conversation.transcript().size(), candidateCount, validated,
                attachmentFormat, attachmentName, sendStatus, mailTarget, mailError, advisorAddress, warnings);

        aiLogService.log(sessionId, CLOSE_TRACE_MESSAGE, dataSent, conversation.transcript().size(),
                charCount, AIModels.AIStatus.ANSWER, List.of(), SUIVI_AGENT_LABEL,
                suiviPromptSnapshot(systemPrompt, context), debug, suiviAnswer(result));
    }

    /** Prompt visible via « Voir le prompt » : prompt système (suivi.txt) + contexte de clôture. */
    private String suiviPromptSnapshot(String systemPrompt, Map<String, Object> context) {
        try {
            return "=== PROMPT SYSTÈME (agent/" + AgentFiles.SUIVI_PROMPT_FILE + ") ===\n" + systemPrompt
                    + "\n\n=== CONTEXTE DE CLÔTURE (payload JSON envoyé) ===\n"
                    + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (Exception e) {
            return "Impossible de reconstituer le prompt de suivi : " + e.getMessage();
        }
    }

    /** Réponse visible via « Voir la réponse » : sortie IA structurée (JSON). */
    private String suiviAnswer(SuiviModels.SuiviResult result) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    /** Bloc de débogage [SUIVI] affiché via « Voir le filtrage ». */
    private static String suiviDebug(AIModels.AIProvider provider, int historyCount, int candidateCount,
                                     Validated validated, String attachmentFormat, String attachmentName,
                                     String sendStatus, String mailTarget, String mailError,
                                     String advisorAddress, List<String> warnings) {
        long high = countLevel(validated.products(), SuiviModels.InterestLevel.HIGH);
        long medium = countLevel(validated.products(), SuiviModels.InterestLevel.MEDIUM);
        long low = countLevel(validated.products(), SuiviModels.InterestLevel.LOW);
        StringBuilder sb = new StringBuilder();
        sb.append("[SUIVI]\n");
        sb.append("provider=").append(provider).append('\n');
        sb.append("historyMessages=").append(historyCount).append('\n');
        sb.append("candidateProducts=").append(candidateCount).append('\n');
        sb.append("productsOfInterest=").append(validated.products().size()).append('\n');
        sb.append("  HIGH=").append(high).append('\n');
        sb.append("  MEDIUM=").append(medium).append('\n');
        sb.append("  LOW=").append(low).append('\n');
        sb.append("  REJECTED=").append(validated.rejectedProducts().size()).append('\n');
        sb.append("attachmentFormat=").append(attachmentFormat).append('\n');
        sb.append("attachmentName=").append(attachmentName).append('\n');
        sb.append("mailStatus=").append(sendStatus).append('\n');
        sb.append("mailSent=").append("SENT".equals(sendStatus)).append('\n');
        sb.append("mailTarget=").append(nullToEmpty(mailTarget)).append('\n');
        sb.append("mailError=").append(mailError == null || mailError.isBlank() ? "(aucune)" : mailError).append('\n');
        sb.append("advisor=").append(advisorAddress == null ? "" : advisorAddress).append('\n');
        sb.append("warnings=").append(warnings == null ? 0 : warnings.size()).append('\n');
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Cause racine d'une exception, sous forme « TypeException — message ». */
    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Résumé lisible d'une cause racine : type simple + message sur UNE seule ligne. */
    private static String oneLine(Throwable cause) {
        if (cause == null) {
            return "cause inconnue";
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage().replaceAll("\\s+", " ").trim();
        return cause.getClass().getSimpleName() + (message.isEmpty() ? "" : " — " + message);
    }

    private static long countLevel(List<SuiviModels.ProductOfInterest> products,
                                   SuiviModels.InterestLevel level) {
        return products.stream().filter(product -> level.name().equals(product.interestLevel())).count();
    }

    /** Historique complet sous forme de liste de maps {role, content, timestamp} (format du dossier). */
    private static List<Map<String, Object>> historyAsMaps(ConversationModels.Conversation conversation) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (ConversationModels.Message message : conversation.transcript()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role());
            entry.put("content", message.content());
            entry.put("timestamp", message.timestamp());
            history.add(entry);
        }
        return history;
    }

    private Map<String, Object> buildCustomerContext(ConversationModels.Conversation conversation) {
        Map<String, Object> customerContext = new LinkedHashMap<>();
        customerContext.put("customerName", blankToNull(configuredCustomerName));
        customerContext.put("customerReference", customerReference());
        FinancialSummary summary = conversation.financialSummary() != null
                ? conversation.financialSummary() : financialAnalysisService.analyze();
        customerContext.put("financialSummary", summary);
        List<Map<String, Object>> projects = new ArrayList<>();
        for (CurrentProject project : conversation.projects()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", project.getType() == null ? null : project.getType().name());
            entry.put("object", project.getObject());
            entry.put("amount", project.getAmount());
            entry.put("currency", project.getCurrency());
            projects.add(entry);
        }
        customerContext.put("currentProjects", projects);
        return customerContext;
    }

    /** Enrichit les produits présentés avec les infos catalogue + l'URL officielle (jamais inventée). */
    private List<Map<String, Object>> enrichProducts(List<Map<String, Object>> discussed) {
        Map<String, BankProduct> byId = new LinkedHashMap<>();
        for (BankProduct product : productCatalogueService.all()) {
            byId.put(product.getId(), product);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> compact : discussed) {
            String id = compact.get("id") == null ? null : String.valueOf(compact.get("id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            BankProduct product = byId.get(id);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("name", firstNonBlank(product == null ? null : product.getName(),
                    value(compact.get("name")), id));
            entry.put("category", product == null || product.getFamily() == null
                    ? value(compact.get("family")) : product.getFamily().name());
            putIfPresent(entry, "description", product == null ? null : product.getDescription());
            putIfPresent(entry, "taeg", product == null ? null : product.getTaeg());
            putIfPresent(entry, "minAmount", product == null ? null : product.getMinAmount());
            putIfPresent(entry, "maxAmount", product == null ? null : product.getMaxAmount());
            putIfPresent(entry, "minDurationMonths", product == null ? null : product.getMinDurationMonths());
            putIfPresent(entry, "maxDurationMonths", product == null ? null : product.getMaxDurationMonths());
            putIfPresent(entry, "productUrl", productUrlIndex.urlFor(id));
            result.add(entry);
        }
        return result;
    }

    private String customerReference() {
        JsonNode customer = bankingDataRepository.loadSnapshot().rawData().path("customer");
        return blankToNull(customer.path("customerId").asText(null));
    }

    private String customerMail() {
        JsonNode customer = bankingDataRepository.loadSnapshot().rawData().path("customer");
        return blankToNull(customer.path("mail").asText(null));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            target.put(key, value);
        }
    }

    private static String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }
}
