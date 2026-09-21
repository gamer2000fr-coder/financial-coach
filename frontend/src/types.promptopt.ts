import type { AIProvider, ConversationClosure } from './types'

/** Types de l'ATELIER d'amélioration itérative des prompts (route `#/prompt-lab`). */

export type PromptZoneKey = 'agent' | 'principal'

export type CampaignStatus =
  | 'CREATED'
  | 'RUNNING'
  | 'STOP_REQUESTED'
  | 'PAUSED'
  | 'COMPLETED'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'ERROR'
  | 'CANCELLED'

export type IterationStatus = 'RUNNING' | 'COMPLETED' | 'ERROR'

export type IssueSeverity = 'LOW' | 'MEDIUM' | 'HIGH'

export type IssueSource = 'PROMPT' | 'DATA' | 'BACKEND_RULE' | 'MODEL_VARIABILITY' | 'UNKNOWN'

/** Zone optimisable d'un agent (prompt actuellement en production + zone éditable détectée). */
export interface PromptZoneInfo {
  agentId: string
  agentLibelle: string
  zoneKey: PromptZoneKey
  zoneFile: string
  optimizable: boolean
  editableSection: string
  error: string
}

export interface PromptOptimizationAgents {
  enabled: boolean
  demoMode: boolean
  maxIterations: number
  /** Plafond DUR du backend (50) : le plafond de la configuration ne peut jamais le dépasser. */
  hardMaxIterations: number
  zones: PromptZoneInfo[]
  /** Tables de libellés fournies par le backend : aucun code technique n'est affiché brut. */
  labels?: Record<string, Record<string, string>>
}

export interface StartCampaignInput {
  agentId: string
  question: string
  iterations: number
  zoneKey?: PromptZoneKey | null
  /** Fournisseur du COACH (celui qui répond au client). */
  provider: AIProvider
  /** Fournisseur de l'Agent B (contrôleur) — omis, il reprend celui du coach. */
  controllerProvider?: AIProvider | null
  /** Fournisseur de l'Agent A (éditeur) — omis, il reprend celui du coach. */
  editorProvider?: AIProvider | null
  /**
   * FIL DE CONVERSATION à poursuivre : l'historique complet des échanges déjà validés est alors transmis au
   * Coach. Omis, un nouveau fil est ouvert (le cycle démarre sans mémoire).
   */
  threadId?: string | null
  /**
   * CYCLE SOURCE dont on hérite la ZONE DE DÉPART (chaînage des cycles du mode automatique de l'Agent C) : la
   * zone testée devient celle de la version RETENUE de ce cycle au lieu de celle du prompt de production — les
   * cycles s'accumulent alors sans qu'aucune écriture n'ait eu lieu.
   */
  fromCampaignId?: string | null
  /** Version RETENUE du cycle source (sa zone devient la zone de départ du nouveau cycle). */
  fromVersion?: string | null
}

/**
 * Un TOUR de la conversation de l'atelier : la question de test (rôle `user`) ou la réponse du Coach pour la
 * version PROMUE (rôle `assistant`). Un tour « assistant » n'existe donc qu'après une promotion.
 */
export interface PromptTurn {
  role: 'user' | 'assistant'
  content: string
  campaignId: string
  version: string
  createdAt: string
}

/**
 * FIL DE CONVERSATION de l'atelier : la mémoire qui enchaîne les cycles (une campagne = une question de test).
 * Il est la SEULE source de l'historique transmis au Coach du cycle suivant.
 */
export interface PromptThread {
  threadId: string
  agentId: string
  agentLibelle: string
  zoneKey: PromptZoneKey
  turns: PromptTurn[]
  campaignIds: string[]
  createdAt: string
  updatedAt: string
}

/**
 * CLÔTURE d'un scénario d'atelier : le fil est rejoué comme une conversation de chat et passe dans le même
 * pipeline que la page coach. `response` est le dossier de suivi renvoyé par le backend (statut
 * `SENT` / `PREPARED` / `MAIL_UNAVAILABLE` / `SEND_FAILED`, objet du mail conseiller, avertissements).
 */
export interface PromptThreadClosure {
  threadId: string
  agentId: string
  messages: number
  response: ConversationClosure
}

/** Démarrage d'une campagne : la campagne créée ET le fil de conversation (créé ou repris). */
export interface PromptCampaignStart {
  campaign: PromptCampaign
  thread: PromptThread | null
}

/**
 * Tour du CLIENT simulé (Agent C) : la question qu'il pose au Coach, ou la fin du scénario. Le client ne
 * conseille jamais et n'invente aucun chiffre (brief + trois chiffres du dossier uniquement).
 */
export interface ClientTurnView {
  question: string
  endConversation: boolean
  reason: string
}

/** Demande de question au client simulé : brief écrit par l'humain, numéro de question, profondeur, fil. */
export interface ClientQuestionInput {
  threadId?: string | null
  brief: string
  turnNumber: number
  depth: number
  provider: AIProvider
}

/**
 * Projet INVENTÉ par le client simulé (Agent C) pour le champ « Brief du client » : qui il est et pourquoi il
 * vient voir sa banque. `montantProjet` est le montant à financer proposé (contrôlé par le backend contre le
 * plafond de cohérence du dossier). `reason` reste interne à l'atelier (jamais montré au Coach ni au client).
 */
export interface GeneratedClientBrief {
  brief: string
  montantProjet?: number | null
  reason: string
}

/**
 * Demande de projet à l'Agent C : l'agent de coach visé (le projet doit relever de son périmètre) et les
 * briefs DÉJÀ proposés, pour qu'un nouvel appui en cherche un franchement différent.
 */
export interface GeneratedClientBriefInput {
  agentId: string
  provider: AIProvider
  previousBriefs: string[]
}

/** Problème relevé par le contrôleur (Agent B). */
export interface ControllerIssue {
  type: string
  severity: IssueSeverity
  source: IssueSource
  observation: string
  expectedBehavior: string
}

/** Diagnostic du contrôleur qualité (Agent B). */
export interface ControllerFeedback {
  status: string
  summary: string
  positivePoints: string[]
  issues: ControllerIssue[]
  mustPreserve: string[]
  recommendationForPromptEditor: string
  requiresHumanOrBusinessReview: boolean
}

/** Proposition de l'éditeur (Agent A) : nouvelle zone éditable uniquement. */
export interface EditorResult {
  status: string
  editableSection: string
  changeSummary: string[]
  feedbackAddressed: string[]
  preservedBehaviors: string[]
  unresolvedPoints: string[]
  humanFeedbackApplied: boolean
}

/** Une itération = une réponse du Coach pour une version précise du prompt. */
export interface PromptIteration {
  iterationId: string
  campaignId: string
  iterationNumber: number
  promptVersion: string
  editableSection: string
  promptHash: string
  coachResponse: string
  controllerFeedback: ControllerFeedback | null
  editorResult: EditorResult | null
  resultingVersion: string
  resultingEditableSection: string
  changeSummary: string[]
  /** Données ajoutées au contexte FIGÉ parce que le Coach les a demandées (NEED_DATA). */
  contextAddedData?: string[]
  noChange: boolean
  humanFeedbackApplied: boolean
  status: IterationStatus
  error: string
  provider: string
  model: string
  promptChars: number
  durationMs: number
  startedAt: string
  completedAt: string
}

/** Campagne d'optimisation : état, compteurs et fournisseurs par étape. */
export interface PromptCampaign {
  campaignId: string
  agentId: string
  agentLibelle: string
  zoneKey: PromptZoneKey
  zoneFile: string
  status: CampaignStatus
  question: string
  snapshotId: string
  basePromptVersion: string
  currentCandidateVersion: string
  promotedVersion: string
  provider: string
  /** Fournisseur de l'Agent B (contrôleur). */
  controllerProvider: string
  /** Fournisseur de l'Agent A (éditeur). */
  editorProvider: string
  model: string
  requestedIterations: number
  completedIterations: number
  maxIterations: number
  aiCalls: number
  totalPromptChars: number
  totalDurationMs: number
  stopRequestedAt: string
  pausedAt: string
  error: string
  errorStep: string
  createdAt: string
  updatedAt: string
}

export interface PromptVersionView {
  version: string
  editableSection: string
  promptHash: string
  iterationNumber: number
  /** Version ACCEPTÉE pour la conversation (elle a produit la réponse entrée dans le fil). */
  promoted: boolean
  /**
   * Version DÉJÀ appliquée au fichier de production. Distinct de `promoted` : une version peut être acceptée
   * pour la conversation (mode automatique de l'Agent C) sans que le prompt de production soit écrasé — c'est
   * ce qui laisse le bouton « Promouvoir » utile à la fin du scénario.
   */
  applied?: boolean
  production: boolean
  prompt: string
}

export interface HumanFeedbackView {
  feedbackId: string
  campaignId: string
  iterationNumber: number
  source: string
  priority: string
  content: string
  createdAt: string
  applied: boolean
}

/** Snapshot de référence : tout ce qui est FIGÉ pendant la campagne. */
export interface PromptSnapshotView {
  snapshotId: string
  createdAt: string
  question: string
  agentTheme: string
  agentLibelle: string
  zoneKey: PromptZoneKey
  zoneFile: string
  promptVersion: string
  promptHash: string
  snapshotHash: string
  provider: string
  frozenData: string[]
  catalogSize: number
  debug: string
  fixedPrefix: string
  initialEditableSection: string
  fixedSuffix: string
  /**
   * Origine de la zone de DÉPART : vide = prompt de production ; sinon « <campagne>:<version> » quand le cycle a
   * été enchaîné sur la version retenue d'un cycle précédent (la zone testée n'est alors PAS en production).
   */
  baseZoneSource?: string
}

export interface PromptCampaignDetail {
  campaign: PromptCampaign
  snapshot: PromptSnapshotView
  iterations: PromptIteration[]
  versions: PromptVersionView[]
  feedbacks: HumanFeedbackView[]
  /** Fil de conversation de l'atelier (`null` pour une campagne antérieure à cette fonctionnalité). */
  thread?: PromptThread | null
}

export interface PromptComparison {
  baseVersion: string
  currentVersion: string
  baseEditableSection: string
  currentEditableSection: string
  basePrompt: string
  currentPrompt: string
  /** Réponses comparées (absentes d'un bilan de conversation, où les questions diffèrent d'un cycle à l'autre). */
  baseResponse?: string
  currentResponse?: string
  iterationCount: number
}

/**
 * BILAN d'une conversation entière : le prompt AU DÉBUT face au prompt EN VIGUEUR à la fin de la conversation
 * (dernière version promue). La comparaison d'une campagne ne montre qu'un cycle ; celle-ci montre le chemin
 * parcouru pendant tout le scénario.
 */
export interface ConversationComparison {
  threadId: string
  agentId: string
  agentLibelle: string
  zoneKey: PromptZoneKey
  zoneFile: string
  baseVersion: string
  currentVersion: string
  /** Cycle dont vient chaque prompt : les noms de version sont LOCAUX au cycle (`V0` n'est pas global). */
  baseCampaignId: string
  currentCampaignId: string
  baseEditableSection: string
  currentEditableSection: string
  basePrompt: string
  currentPrompt: string
  /** Nombre de cycles (une question = un cycle), d'itérations cumulées et de promotions. */
  cycleCount: number
  iterationCount: number
  promotionCount: number
  /** Aucune promotion n'a modifié la zone : le prompt est inchangé. */
  identical: boolean
  /** Phrase d'explication fournie par le backend (aucun code technique à l'écran). */
  summary: string
}

export interface PromotionResult {
  campaign: PromptCampaign
  backupId: string
  backupFile: string
  message: string
}
