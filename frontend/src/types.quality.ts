// ------------------------------------------------------------------ Qualité & Satisfaction (#/quality)
// Deux familles d'indicateurs STRICTEMENT séparées : satisfaction client et conformité du Coach.

export type QualityPeriod = 'today' | 'yesterday' | '7d' | '30d' | 'custom'

/** Pop-in de fin de conversation : note obligatoire pour envoyer, reste facultatif. */
export interface QualityFeedbackRequest {
  rating: number
  selectedReasons: string[]
  comment?: string
}

/** Réponse d'enregistrement (jamais bloquante : la conversation se clôture dans tous les cas). */
export interface QualityFeedbackResponse {
  status: 'SAVED' | 'SKIPPED' | 'DUPLICATE' | 'INVALID' | 'STORAGE_ERROR' | 'DISABLED' | string
  feedbackId?: string | null
  sessionId?: string | null
  rating?: number | null
  selectedReasons?: string[]
  message?: string | null
}

export interface QualitySatisfactionKpis {
  conversationsClosed: number
  feedbackCount: number
  participationRate: number | null
  averageRating: number | null
  positiveCount: number
  negativeCount: number
  neutralCount: number
  positiveRate: number | null
  negativeRate: number | null
  previousFeedbackCount: number | null
  previousAverageRating: number | null
  averageRatingEvolutionPercent: number | null
  positiveRateEvolutionPercent: number | null
  sufficientSample: boolean
}

export interface QualityConformityKpis {
  checksRun: number
  anomalies: number
  highAnomalies: number
  mediumAnomalies: number
  lowAnomalies: number
}

export interface QualityRatingBucket {
  rating: number
  count: number
  share: number
}

export interface QualityReasonMetric {
  reason: string
  label: string
  count: number
  share: number
  previousCount: number | null
  evolutionPercent: number | null
}

export interface QualityCommentTheme {
  theme: string
  label: string
  count: number
  share: number
}

export interface QualityCheckMetric {
  checkType: string
  label: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | string
  checksRun: number
  detected: number
  detectionRate: number | null
  previousDetected: number | null
  evolutionPercent: number | null
}

export interface QualitySatisfactionComplianceMatrix {
  satisfiedCompliant: number
  satisfiedAnomaly: number
  unsatisfiedCompliant: number
  unsatisfiedAnomaly: number
  unsatisfiedCompliantShare: number | null
  unsatisfiedAnomalyShare: number | null
  unratedWithAnomaly: number
}

export interface QualityDailyPoint {
  date: string
  feedbackCount: number
  averageRating: number | null
  positiveCount: number
  negativeCount: number
  anomalies: number
}

export interface QualityTrend {
  entityType: string
  entityId: string
  entityName: string
  current: number
  previous: number
  evolutionPercent: number | null
}

export interface QualityCheckEvent {
  checkId: string
  timestamp: string
  sessionId: string
  checkType: string
  severity: string
  detected: boolean
  source: string
  details: string | null
  confidence: number | null
}

export interface QualityAggregates {
  dateFrom: string
  dateTo: string
  satisfaction: QualitySatisfactionKpis
  conformity: QualityConformityKpis
  ratingDistribution: QualityRatingBucket[]
  feedbackCategories: QualityReasonMetric[]
  commentThemes: QualityCommentTheme[]
  qualityChecks: QualityCheckMetric[]
  implementedChecks: string[]
  notImplementedChecks: string[]
  satisfactionVsCompliance: QualitySatisfactionComplianceMatrix
  series: QualityDailyPoint[]
  trends: QualityTrend[]
  anonymizedComments: { commentRef: string; text: string }[]
  invalidLines: number
  demo: boolean
  generatedAt: string
}

export interface QualityReportItem {
  priority?: string
  level?: string
  type?: string
  topic?: string
  rule?: string
  title?: string
  observation?: string
  recommendation?: string
  expectedBenefit?: string
  description?: string
  coachCompliant?: boolean | null
}

export interface QualityReport {
  reportDate: string
  period?: { from: string; to: string } | null
  executiveSummary?: { status: string; summary: string } | null
  satisfactionAnalysis?: { summary: string; positivePoints: string[]; mainIrritants: string[] } | null
  qualityAndCompliance?: { summary: string; mainIssues: string[]; criticalIssues: string[] } | null
  satisfactionVsCompliance?: { summary: string; notableCases: string[] } | null
  ruleFriction: QualityReportItem[]
  trends: QualityReportItem[]
  priorityImprovements: QualityReportItem[]
  alerts: QualityReportItem[]
  finalAssessment?: string | null
  generatedAt?: string | null
  model?: string | null
  aiGenerated?: boolean | null
  error?: string | null
}

export interface QualityStatus {
  enabled: boolean
  demoMode: boolean
  feedbackDir: string
  availableDays: string[]
  reportDates: string[]
  implementedChecks: string[]
  notImplementedChecks: string[]
  commentMaxLength: number
  feedbackReasons: Record<string, string>
}
