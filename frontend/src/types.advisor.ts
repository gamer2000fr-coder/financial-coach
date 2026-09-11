// ------------------------------------------------------------------ Feedback Conseiller (#/advisor-feedback)
// Module INDÉPENDANT de Qualité : ici, c'est le conseiller qui juge la PERTINENCE du travail du Coach.

export type AdvisorPeriod = 'today' | 'yesterday' | '7d' | '30d' | 'custom'

export type AdvisorAssessment = 'RELEVANT' | 'NEEDS_IMPROVEMENT' | 'INCORRECT'

export interface AdvisorIssueInput {
  area: string
  reason?: string
  comment?: string
}

export interface AdvisorProductFeedbackInput {
  productId: string
  productName?: string
  aiInterestLevel?: string
  advisorAssessment?: string
  advisorInterestLevel?: string
  reason?: string
  comment?: string
}

export interface AdvisorFeedbackInput {
  sessionId: string
  overallAssessment: AdvisorAssessment
  issues?: AdvisorIssueInput[]
  productFeedback?: AdvisorProductFeedbackInput[]
  missingProductIds?: string[]
  nextActionAssessment?: string
  clientEmailAssessment?: string
  editLevel?: string
  comment?: string
  advisorId?: string
}

export interface AdvisorFeedbackResponse {
  status: 'SAVED' | 'DUPLICATE' | 'INVALID' | 'STORAGE_ERROR' | 'DISABLED' | string
  feedbackId?: string | null
  sessionId?: string | null
  event?: string | null
  version?: number | null
  overallAssessment?: string | null
  message?: string | null
}

export interface AdvisorCandidateProduct {
  productId: string
  productName: string | null
  aiInterestLevel: string | null
}

export interface AdvisorCandidateSession {
  sessionId: string
  projectType: string | null
  products: AdvisorCandidateProduct[]
  evaluated: boolean
  currentVersion: number | null
}

export interface AdvisorCatalogueProduct {
  productId: string
  productName: string
  productFamily: string | null
}

export interface AdvisorCandidatesResponse {
  candidates: AdvisorCandidateSession[]
  catalogue: AdvisorCatalogueProduct[]
}

export interface AdvisorKpis {
  sessionsEvaluated: number
  feedbackCount: number
  participationRate: number | null
  relevantRate: number | null
  needsImprovementRate: number | null
  incorrectRate: number | null
  productAssessments: number
  relevantProducts: number
  notRelevantProducts: number
  productRelevanceRate: number | null
  emailsReadyOrMinor: number
  emailReadyOrMinorRate: number | null
  interestCorrections: number
  previousFeedbackCount: number | null
  previousRelevantRate: number | null
  relevantRateEvolutionPercent: number | null
  sufficientSample: boolean
}

export interface AdvisorAssessmentMetric {
  code: string
  label: string
  count: number
  share: number
  evolutionPercent: number | null
}

export interface AdvisorAreaMetric {
  area: string
  label: string
  count: number
  share: number
  evolutionPercent: number | null
}

export interface AdvisorReasonMetric {
  reason: string
  label: string
  count: number
  evolutionPercent: number | null
}

export interface AdvisorProductMetric {
  productId: string
  productName: string | null
  assessments: number
  relevant: number
  notRelevant: number
  relevanceRate: number | null
  interestCorrections: number
  addedByAdvisor: number
  evolutionPercent: number | null
}

export interface AdvisorInterestCorrection {
  productId: string
  productName: string | null
  fromLevel: string
  toLevel: string
  count: number
}

export interface AdvisorEmailMetric {
  code: string
  label: string
  count: number
  share: number
  evolutionPercent: number | null
}

export interface AdvisorDailyPoint {
  date: string
  feedbackCount: number
  relevant: number
  needsImprovement: number
  incorrect: number
  productNotRelevant: number
}

export interface AdvisorAggregates {
  dateFrom: string
  dateTo: string
  kpis: AdvisorKpis
  assessments: AdvisorAssessmentMetric[]
  areas: AdvisorAreaMetric[]
  reasons: AdvisorReasonMetric[]
  products: AdvisorProductMetric[]
  interestCorrections: AdvisorInterestCorrection[]
  emailQuality: AdvisorEmailMetric[]
  series: AdvisorDailyPoint[]
  trends: { entityType: string; entityId: string; entityName: string; current: number; previous: number; evolutionPercent: number | null }[]
  anonymizedComments: { commentRef: string; text: string }[]
  invalidLines: number
  demo: boolean
  generatedAt: string
}

export interface AdvisorReport {
  reportDate: string
  period?: { from: string; to: string } | null
  executiveSummary?: { status: string; summary: string } | null
  strengths: { title: string; observation: string }[]
  mainIssues: { area: string; observation: string; severity: string }[]
  productAnalysis: { productId: string; productName: string; observation: string; signal: string }[]
  interestLevelAnalysis?: { summary: string; overestimationSignals: string[]; underestimationSignals: string[] } | null
  nextActionAnalysis?: { summary: string; issues: string[] } | null
  clientEmailAnalysis?: { summary: string; issues: string[] } | null
  trends: { type: string; topic: string; observation: string }[]
  priorityImprovements: { priority: string; title: string; observation: string; recommendation: string; expectedBenefit: string }[]
  watchPoints: string[]
  finalAssessment?: string | null
  generatedAt?: string | null
  model?: string | null
  aiGenerated?: boolean | null
  error?: string | null
}

/** Dossier évaluable ciblé par le lien reçu par email (§43). */
export interface AdvisorDossierProduct {
  productId: string
  name: string | null
  category: string | null
  interestLevel: string | null
  interestReason: string | null
  productUrl: string | null
}

export interface AdvisorDossierEmail {
  subject: string | null
  body: string | null
}

export interface AdvisorDossier {
  dossierId: string
  timestamp: string
  sessionId: string
  mainProject: string | null
  otherProjects: string[]
  preferences: string[]
  productsOfInterest: AdvisorDossierProduct[]
  nextActions: string[]
  advisorEmail: AdvisorDossierEmail
  customerEmail: AdvisorDossierEmail
  feedbackUrl: string | null
  createdAt: string
}

/** Dossier + feedback éventuel + statut (NOT_REQUESTED | PENDING | COMPLETED). */
export interface AdvisorDossierView {
  dossier: AdvisorDossier
  feedback: AdvisorFeedbackRecord | null
  feedbackStatus: string
}

/** Feedback déjà enregistré (préremplissage de la révision). */
export interface AdvisorFeedbackRecord {
  feedbackId: string
  timestamp: string
  sessionId: string
  overallAssessment: string
  issues: { area: string; reason: string | null; comment: string | null }[]
  productFeedback: AdvisorProductFeedbackInput[]
  missingProductIds: string[]
  nextActionAssessment: string | null
  clientEmailAssessment: string | null
  editLevel: string | null
  comment: string | null
  source: string
  event: string
  version: number
}

export interface AdvisorStatus {  enabled: boolean
  demoMode: boolean
  eventsDir: string
  availableDays: string[]
  reportDates: string[]
  sufficientSampleSize: number
  assessments: Record<string, string>
  areas: Record<string, string>
  reasons: Record<string, string>
  emailAssessments: Record<string, string>
  productReasons: Record<string, string>
}
