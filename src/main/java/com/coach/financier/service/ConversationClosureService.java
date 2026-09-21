package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.AgentFiles;
import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.BankProduct;
import com.coach.financier.model.ConversationCategory;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.ProductFamily;
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
    /**
     * Préfixe de l'objet du mail de NOTIFICATION envoyé au conseiller : il permet d'identifier et de
     * trier automatiquement les dossiers de suivi dans la boîte du conseiller.
     */
    private static final String ADVISOR_SUBJECT_PREFIX = "[coach_financier] ";
    /** Objet de repli du mail conseiller lorsque l'IA n'en fournit pas. */
    private static final String DEFAULT_ADVISOR_SUBJECT = "Suivi client — dossier préparé";

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
    private final MarketingProperties marketingProperties;
    private final MarketingEventStore marketingEventStore;
    private final MarketingExtractionService marketingExtractionService;
    private final CoachQualityCheckService coachQualityCheckService;
    private final AdvisorDossierService advisorDossierService;
    private final CommercialScoreService commercialScoreService;

    private final String configuredAdvisorName;
    private final String configuredAdvisorEmail;
    private final String configuredCustomerName;
    private final String defaultAttachmentFormat;
    private final String appointmentUrl;
    private final String dossierUrl;
    private final boolean advisorMailHtml;
    private final String customerPhone;

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
                                      MarketingProperties marketingProperties,
                                      MarketingEventStore marketingEventStore,
                                      MarketingExtractionService marketingExtractionService,
                                      CoachQualityCheckService coachQualityCheckService,
                                      AdvisorDossierService advisorDossierService,
                                      CommercialScoreService commercialScoreService,
                                      @Value("${app.advisor.name:}") String configuredAdvisorName,
                                      @Value("${app.advisor.email:}") String configuredAdvisorEmail,
                                      @Value("${app.customer.name:}") String configuredCustomerName,
                                      @Value("${app.suivi.attachment-format:txt}") String defaultAttachmentFormat,
                                      @Value("${app.suivi.advisor-appointment-url:}") String appointmentUrl,
                                      @Value("${app.suivi.dossier-url:}") String dossierUrl,
                                      @Value("${app.suivi.advisor-mail-html:true}") boolean advisorMailHtml,
                                      @Value("${app.suivi.customer-phone:}") String customerPhone) {
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
        this.marketingProperties = marketingProperties;
        this.marketingEventStore = marketingEventStore;
        this.marketingExtractionService = marketingExtractionService;
        this.coachQualityCheckService = coachQualityCheckService;
        this.advisorDossierService = advisorDossierService;
        this.commercialScoreService = commercialScoreService;
        this.configuredAdvisorName = configuredAdvisorName;
        this.configuredAdvisorEmail = configuredAdvisorEmail;
        this.configuredCustomerName = configuredCustomerName;
        this.defaultAttachmentFormat = defaultAttachmentFormat;
        this.appointmentUrl = appointmentUrl;
        this.dossierUrl = dossierUrl == null ? "" : dossierUrl.trim();
        this.advisorMailHtml = advisorMailHtml;
        this.customerPhone = customerPhone == null ? "" : customerPhone.trim();
    }

    public SuiviModels.CloseConversationResponse close(String sessionId, SuiviModels.CloseConversationRequest request) {
        return close(sessionId, request, true);
    }

    /**
     * Clôture d'une conversation, avec choix d'ARCHIVER le dossier.
     *
     * @param archive {@code false} = ne PAS écrire de dossier de suivi : utilisé par l'ATELIER lorsque
     *                l'utilisateur veut seulement envoyer le mail conseiller sans encombrer l'annuaire du
     *                centre d'appels. Dans ce cas le bloc « évaluer le suivi » est lui aussi retiré du mail
     *                (il cible un dossier qui n'existera pas) ; le lien vers l'historique de la conversation
     *                reste, lui, valable.
     */
    public SuiviModels.CloseConversationResponse close(String sessionId, SuiviModels.CloseConversationRequest request,
                                                      boolean archive) {
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
            // IMPORTANT : on trace l'échec AVANT de sortir, sinon aucun enregistrement [SUIVI]
            // n'apparaît dans la page Logs (l'écriture avait lieu après la synthèse).
            logSuiviFailure(sessionId, conversation, suiviPrompt, context, sentChars, provider,
                    candidateProducts.size(), advisorAddress, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Échec de l'IA de synthèse : " + e.getMessage(), e);
        }

        // 5) Validation backend : produits réels, refus exclus, URLs non inventées.
        Validated validated = validate(result, candidateIds, candidateNames, allowedUrls, warnings);

        // 5bis) Signaux Marketing issus du MÊME appel IA, persistés en JSONL (anonymisés).
        int marketingEventCount = persistMarketingEvents(conversation, sessionId, provider, result, warnings);

        // 5ter) Contrôles qualité automatiques (CONFORMITÉ du Coach), indépendants de la satisfaction
        //       client : ils sont exécutés à chaque clôture et ne bloquent jamais le dossier de suivi.
        int qualityCheckCount = runQualityChecks(conversation, sessionId, warnings);

        // 5quater) SCORE DE SENS COMMERCIAL : proposé par l'IA de synthèse (borné par le backend) sinon
        //          calculé de façon déterministe. Il sert à PRIORISER les relances du conseiller et figure
        //          en tête des compléments du mail conseiller, avec son explication courte.
        SuiviModels.CommercialScore score = commercialScoreService.scoreFor(conversation, result,
                candidateProducts);

        // 5quinquies) Dossier évaluable : persistance (best effort, enrichi pour l'ANNUAIRE DES
        //          CONVERSATIONS du centre d'appels) + LIEN D'ÉVALUATION ajouté au mail conseiller APRÈS la
        //          validation des URLs (le lien est fabriqué par le backend, il ne peut donc pas être
        //          neutralisé par le contrôle anti-invention). L'URL ne contient que le sessionId :
        //          aucune donnée personnelle (§45). `archive=false` (atelier) : aucun dossier n'est écrit et
        //          le bloc d'évaluation — qui cible ce dossier — n'est pas ajouté au mail.
        if (archive) {
            advisorDossierService.persist(sessionId, result, dossierExtras(conversation, result, score));
        }
        validated = withAdvisorLinks(validated, sessionId, score, archive);

        // 6) Pièce jointe générée à partir du brouillon client : destinataire = mail du client
        //    (fiche customer.mail), expéditeur = mail du conseiller (évite « unknown sender »).
        SuiviModels.Attachment attachment = attachmentBuilder.build(format, validated.preparedCustomerEmail(),
                new SuiviModels.EmailAddresses(customerMail(), advisorAddress));

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
                status, mailService.describeTarget(), mailError, advisorAddress, warnings,
                marketingEventCount, qualityCheckCount);

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
     * Ajoute au mail conseiller les compléments fournis par le SYSTÈME (jamais fabriqués par l'IA, donc
     * insensibles au contrôle d'invention d'URL, appliqué plus haut) :
     * <ul>
     *   <li><b>score de sens commercial</b> + son explication + le lien d'appel du client — en TÊTE, car
     *       c'est l'information qui permet de prioriser la relance ;</li>
     *   <li>« Ouvrir le dossier du client » — lien vers la page « Centre d'appels », qui ouvre DIRECTEMENT la
     *       pop-in du dossier (score, synthèse, suivi, conversation). Le lien ne contient que le sessionId ;
     *       si la base IHM n'est pas configurée, on retombe sur l'URL de l'outil conseiller
     *       ({@code app.suivi.dossier-url}) ;</li>
     *   <li>« Évaluer le suivi du Coach » — lien direct vers le dossier évaluable, qui ne contient que
     *       le sessionId (aucune donnée personnelle), uniquement lorsque le dossier est archivé.</li>
     * </ul>
     * Le lien « Consulter l'historique de la conversation » n'est plus ajouté au mail : le conseiller ouvre
     * la conversation depuis la pop-in du centre d'appels (la page de relecture reste disponible).
     */
    private Validated withAdvisorLinks(Validated validated, String sessionId,
                                       SuiviModels.CommercialScore score, boolean archive) {
        SuiviModels.EmailContent advisor = validated.advisorEmail();
        if (advisor == null) {
            return validated;
        }
        StringBuilder body = new StringBuilder(advisor.body() == null ? "" : advisor.body().stripTrailing());
        String scoreBlockText = scoreBlock(score);
        if (!scoreBlockText.isBlank()) {
            body.append("\n\n").append(scoreBlockText);
        }
        String directoryLink = advisorDossierService.directoryUrl(sessionId);
        if (!advisorDossierService.hasFrontendUrl() && !dossierUrl.isBlank()) {
            // Repli : aucune IHM configurée (pas de base frontend) → on garde l'outil conseiller externe.
            directoryLink = dossierUrl;
        }
        if (!directoryLink.isBlank()) {
            body.append("\n\nDossier client : [URL|Ouvrir le dossier du client|")
                    .append(directoryLink).append(']');
        }
        if (archive) {
            body.append("\n\n").append(advisorDossierService.feedbackBlock(sessionId));
        }
        return new Validated(validated.summary(), validated.products(), validated.rejectedProducts(),
                new SuiviModels.EmailContent(advisorSubject(advisor.subject()), body.toString()),
                validated.preparedCustomerEmail());
    }

    /**
     * Bloc « score de sens commercial » : score, explication courte (raisons) et lien d'appel direct du
     * client. Le numéro provient de la CONFIGURATION ({@code app.suivi.customer-phone}) — jamais de l'IA —
     * et le lien n'est ajouté que s'il est renseigné : aucun numéro n'est jamais inventé.
     * <p>
     * La première ligne est mise en **gras** au format Markdown du projet : elle devient un
     * {@code <strong>} dans le mail HTML et redevient du texte simple dans le mail en texte brut.
     */
    private String scoreBlock(SuiviModels.CommercialScore score) {
        if (score == null) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("--------------------------------\n");
        block.append("**Score de sens commercial : ").append(score.display()).append("**\n");
        if (!score.reasons().isEmpty()) {
            block.append("Pourquoi ce score : ").append(String.join(" ", score.reasons())).append('\n');
        }
        if (!customerPhone.isBlank()) {
            block.append("Contacter le client : [URL|Appeler le client|tel:")
                    .append(customerPhone).append(']');
        }
        return block.toString().stripTrailing();
    }

    /**
     * Éléments du dossier utiles à l'ANNUAIRE DES CONVERSATIONS (centre d'appels) : identité « métier »
     * (client, titre, catégorie), score commercial et transcript des échanges.
     */
    private AdvisorDossierService.DossierExtras dossierExtras(ConversationModels.Conversation conversation,
                                                              SuiviModels.SuiviResult result,
                                                              SuiviModels.CommercialScore score) {
        ConversationCategory category = categoryOf(conversation);
        return new AdvisorDossierService.DossierExtras(customerReference(),
                conversationTitle(conversation, result), category.code(), category.label(), score,
                transcriptMessages(conversation));
    }

    /**
     * Catégorie MÉTIER de la conversation : familles des offres présentées pendant l'échange (source la
     * plus fiable), sinon type du projet courant, sinon « Autre ». Aucune catégorie n'est inventée.
     */
    private static ConversationCategory categoryOf(ConversationModels.Conversation conversation) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> product : conversation.discussedProducts()) {
            String family = value(product.get("family"));
            if (family != null && !family.isBlank()) {
                counts.merge(family.trim().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        String dominant = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        ConversationCategory fromProducts = categoryOfFamily(dominant);
        if (fromProducts != ConversationCategory.AUTRE) {
            return fromProducts;
        }
        CurrentProject project = conversation.currentProject();
        return ConversationCategory.fromProjectType(project == null ? null : project.getType());
    }

    /** Famille du catalogue → catégorie métier (famille inconnue ⇒ « Autre », jamais une erreur). */
    private static ConversationCategory categoryOfFamily(String familyName) {
        if (familyName == null || familyName.isBlank()) {
            return ConversationCategory.AUTRE;
        }
        try {
            return ConversationCategory.fromFamily(ProductFamily.valueOf(familyName));
        } catch (IllegalArgumentException e) {
            return ConversationCategory.AUTRE;
        }
    }

    /** Titre lisible de la conversation : projet principal, sinon premier message du client. */
    private static String conversationTitle(ConversationModels.Conversation conversation,
                                            SuiviModels.SuiviResult result) {
        SuiviModels.ConversationSummary summary = result == null ? null : result.conversationSummary();
        String mainProject = summary == null ? null : summary.mainProject();
        if (mainProject != null && !mainProject.isBlank()) {
            return abbreviate(mainProject.trim().replaceAll("\\s+", " "));
        }
        for (ConversationModels.Message message : conversation.transcript()) {
            if ("user".equalsIgnoreCase(value(message.role())) && message.content() != null
                    && !message.content().isBlank()) {
                return abbreviate(message.content().strip().replaceAll("\\s+", " "));
            }
        }
        return "Conversation " + conversation.sessionId();
    }

    /** Transcript des échanges, conservé avec le dossier (bloc dépliable de la fiche conversation). */
    private static List<AdvisorFeedbackModels.DossierMessage> transcriptMessages(
            ConversationModels.Conversation conversation) {
        List<AdvisorFeedbackModels.DossierMessage> messages = new ArrayList<>();
        for (ConversationModels.Message message : conversation.transcript()) {
            messages.add(new AdvisorFeedbackModels.DossierMessage(message.role(), message.content(),
                    message.timestamp() == null ? null : message.timestamp().toString()));
        }
        return messages;
    }

    /** Tronque un libellé (titres de l'annuaire) sans casser le mot : 90 caractères au plus. */
    private static String abbreviate(String value) {
        if (value == null || value.length() <= 90) {
            return value;
        }
        return value.substring(0, 87).stripTrailing() + "...";
    }

    /**
     * Objet du mail de notification au conseiller, préfixé par {@code [coach_financier]} afin de
     * permettre son identification et son tri automatique dans la boîte du conseiller. Le préfixe
     * n'est ajouté qu'une seule fois, même si l'appel est rejoué.
     */
    private static String advisorSubject(String subject) {
        String base = firstNonBlank(subject, DEFAULT_ADVISOR_SUBJECT, "Suivi client");
        return base.startsWith(ADVISOR_SUBJECT_PREFIX.strip())
                ? base
                : ADVISOR_SUBJECT_PREFIX + base;
    }

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

        String advisorSubject = firstNonBlank(advisor.subject(), DEFAULT_ADVISOR_SUBJECT, "Suivi client");
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
    /**
     * Persiste les signaux Marketing extraits par l'IA de suivi (§3/§4 du module Marketing).
     * Best effort : un échec d'écriture ne doit jamais empêcher l'envoi du dossier conseiller.
     */
    private int persistMarketingEvents(ConversationModels.Conversation conversation, String sessionId,
                                       AIModels.AIProvider provider, SuiviModels.SuiviResult result,
                                       List<String> warnings) {        if (!marketingProperties.isEnabled() || result == null || result.marketingEvents().isEmpty()) {
            return 0;
        }
        try {
            List<MarketingModels.MarketingEvent> events = marketingExtractionService.toEvents(
                    result.marketingEvents(), sessionId, conversation, customerReference(), provider);
            return marketingEventStore.append(events).size();
        } catch (Exception e) {
            warnings.add("Signaux Marketing non persistés : " + e.getMessage());
            log.warn("Persistance des signaux Marketing impossible (session {}) : {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * Exécute les CONTRÔLES QUALITÉ automatiques de la conversation (module Qualité, dimension
     * « conformité du Coach »). Best effort : un échec n'empêche jamais l'envoi du dossier conseiller
     * (les contrôles sont indépendants de la satisfaction client).
     */
    private int runQualityChecks(ConversationModels.Conversation conversation, String sessionId,
                                 List<String> warnings) {
        try {
            return coachQualityCheckService.run(sessionId, conversation).size();
        } catch (Exception e) {
            warnings.add("Contrôles qualité non exécutés : " + e.getMessage());
            log.warn("Contrôles qualité impossibles (session {}) : {}", sessionId, e.getMessage());
            return 0;
        }
    }

    private void logSuiviCall(String sessionId, ConversationModels.Conversation conversation,
                              String systemPrompt, Map<String, Object> context, long charCount,
                              SuiviModels.SuiviResult result, int candidateCount, AIModels.AIProvider provider,
                              Validated validated, String attachmentFormat, String attachmentName,
                              String sendStatus, String mailTarget, String mailError,
                              String advisorAddress, List<String> warnings, int marketingEventCount,
                              int qualityCheckCount) {
        String debug = suiviDebug(provider, conversation.transcript().size(), candidateCount, validated,
                attachmentFormat, attachmentName, sendStatus, mailTarget, mailError, advisorAddress, warnings,
                marketingEventCount, qualityCheckCount);

        aiLogService.log(sessionId, CLOSE_TRACE_MESSAGE, suiviDataSent(conversation, candidateCount),
                conversation.transcript().size(), charCount, AIModels.AIStatus.ANSWER, List.of(),
                SUIVI_AGENT_LABEL, suiviPromptSnapshot(systemPrompt, context), debug, suiviAnswer(result),
                sendStatus);
    }

    /**
     * Trace un ÉCHEC de la synthèse (clé IA absente, fournisseur injoignable, réponse illisible...) :
     * une entrée [SUIVI] est TOUJOURS créée, même quand aucun dossier n'a pu être produit. C'est ce qui
     * permet de distinguer « rien n'a été déclenché » de « déclenché mais en échec » dans la page Logs.
     */
    private void logSuiviFailure(String sessionId, ConversationModels.Conversation conversation,
                                 String systemPrompt, Map<String, Object> context, long charCount,
                                 AIModels.AIProvider provider, int candidateCount,
                                 String advisorAddress, Exception error) {
        String cause = oneLine(rootCause(error));
        StringBuilder sb = new StringBuilder();
        sb.append("[SUIVI]\n");
        sb.append("provider=").append(provider).append('\n');
        sb.append("historyMessages=").append(conversation.transcript().size()).append('\n');
        sb.append("candidateProducts=").append(candidateCount).append('\n');
        sb.append("productsOfInterest=0\n");
        sb.append("  HIGH=0\n  MEDIUM=0\n  LOW=0\n  REJECTED=0\n");
        sb.append("attachmentFormat=(aucune)\n");
        sb.append("attachmentName=(aucune)\n");
        sb.append("mailStatus=AI_FAILED\n");
        sb.append("mailSent=false\n");
        sb.append("mailTarget=").append(nullToEmpty(mailService.describeTarget())).append('\n');
        sb.append("mailError=échec de la synthèse IA : ").append(cause).append('\n');
        sb.append("advisor=").append(advisorAddress == null ? "" : advisorAddress).append('\n');
        sb.append("warnings=1\n");

        aiLogService.log(sessionId, CLOSE_TRACE_MESSAGE, suiviDataSent(conversation, candidateCount),
                conversation.transcript().size(), charCount, AIModels.AIStatus.ERROR, List.of(),
                SUIVI_AGENT_LABEL, suiviPromptSnapshot(systemPrompt, context), sb.toString(),
                "Échec de la synthèse IA : " + cause, "AI_FAILED");
    }

    /** Descriptions des données transmises à l'agent de suivi (affichées sur la page Logs). */
    private static List<String> suiviDataSent(ConversationModels.Conversation conversation, int candidateCount) {
        List<String> dataSent = new ArrayList<>();
        dataSent.add("Historique de conversation (" + conversation.transcript().size() + " messages)");
        dataSent.add("Contexte client (synthèse financière + projets)");
        dataSent.add("Contexte conseiller");
        dataSent.add("Produits présentés (" + candidateCount + ")");
        dataSent.add("URLs utiles");
        return dataSent;
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
                                     String advisorAddress, List<String> warnings, int marketingEventCount,
                                     int qualityCheckCount) {
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
        sb.append("marketingEvents=").append(marketingEventCount).append('\n');
        sb.append("qualityChecks=").append(qualityCheckCount).append('\n');
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

    /** Adresse email du client, lue dans la fiche bancaire ({@code customer.mail}). */
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
