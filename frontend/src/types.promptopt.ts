import type { AIProvider } from './types'

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

/** Démarrage d'une campagne : la campagne créée ET le fil de conversation (créé ou repris). */
export interface PromptCampaignStart {
  campaign: PromptCampaign
  thread: PromptThread | null
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
  promoted: boolean
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
  baseResponse: string
  currentResponse: string
  iterationCount: number
}

export interface PromotionResult {
  campaign: PromptCampaign
  backupId: string
  backupFile: string
  message: string
}
