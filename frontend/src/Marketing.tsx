import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, Bot, Download, Lightbulb, RefreshCw, TrendingDown, TrendingUp, Wand2 } from 'lucide-react'
import {
  generateMarketingDemoData,
  fetchMarketingOverview,
  fetchMarketingProduct,
  fetchMarketingReport,
  fetchMarketingStatus,
  marketingProductsCsvUrl,
  regenerateMarketingReport,
} from './api'
import type {
  AIProvider,
  MarketingAggregates,
  MarketingPeriod,
  MarketingProductDetail,
  MarketingProductMetric,
  MarketingReport,
  MarketingStatus,
} from './types'

type SortKey = 'interestedSessions' | 'recommendedSessions' | 'interestRate' | 'score' | 'evolutionPercent'

const PERIODS: { key: MarketingPeriod; label: string }[] = [
  { key: 'today', label: "Aujourd'hui" },
  { key: 'yesterday', label: 'Hier' },
  { key: '7d', label: '7 jours' },
  { key: '30d', label: '30 jours' },
  { key: 'custom', label: 'Personnalisé' },
]

function isoDaysAgo(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() - days)
  return date.toISOString().slice(0, 10)
}

function formatNumber(value: number | null | undefined): string {
  return value == null ? '—' : value.toLocaleString('fr-FR')
}

function formatPercent(rate: number | null | undefined, digits = 1): string {
  return rate == null ? '—' : `${(rate * 100).toLocaleString('fr-FR', { maximumFractionDigits: digits })} %`
}

function formatEvolution(value: number | null | undefined): string {
  if (value == null) return '—'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`
}

/**
 * Libellés métier : la page ne doit jamais afficher un code technique brut
 * comme « REAL_ESTATE · NO_SUITABLE_PRODUCT ».
 * La source est le backend (`GET /api/marketing/status` → `*Labels`) ; ce repli local ne sert
 * qu'avant le chargement du statut. Un code inconnu est « humanisé », jamais affiché tel quel.
 */
const FALLBACK_LABELS: Record<string, string> = {
  VEHICLE: "Achat d'un véhicule",
  REAL_ESTATE: 'Projet immobilier',
  HOME_WORK: 'Travaux / aménagement',
  ELECTRONICS: 'Achat high-tech',
  FURNITURE: 'Achat mobilier',
  TRAVEL: 'Voyage',
  EDUCATION: 'Études / formation',
  CASH_NEED: 'Besoin de trésorerie',
  SAVINGS: 'Épargne / placement',
  BUDGET: 'Gestion du budget',
  OTHER: 'Autre',
  UNKNOWN: 'Non identifié',
  MORTGAGE: 'Crédit immobilier',
  PERSONAL_LOAN: 'Crédit personnel',
  AUTO_LOAN: 'Crédit auto',
  SAVINGS_PRODUCT: "Produit d'épargne",
  INSURANCE_AUTO: 'Assurance auto',
  INSURANCE_HOME: 'Assurance habitation',
  INSURANCE_BORROWER: 'Assurance emprunteur',
  NO_SUITABLE_PRODUCT: 'Aucune offre adaptée au besoin',
  PRICE: 'Prix / coût trop élevé',
  RATE: 'Taux jugé trop élevé',
  PREFERS_CASH: 'Préfère payer comptant',
  DOES_NOT_WANT_CREDIT: 'Ne souhaite pas de crédit',
  MISSING_COVERAGE_INFORMATION: 'Garanties non documentées',
  MISSING_PRICING_INFORMATION: 'Tarif non documenté',
  MISSING_CONDITIONS_INFORMATION: 'Conditions non documentées',
}

/* Le code technique UNKNOWN ne doit jamais s'afficher tel quel dans la page. */
const UNKNOWN_CODES = new Set(['UNKNOWN', 'NONE', 'NULL', 'UNDEFINED', '-', 'N/A', 'NA'])

function isUnknown(code?: string | null): boolean {
  return !code || !code.trim() || UNKNOWN_CODES.has(code.trim().toUpperCase())
}

function humanizeCode(code: string): string {
  const text = code.replace(/_/g, ' ').toLowerCase()
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : code
}

function readableLabel(map: Record<string, string>, code?: string | null): string {
  if (!code || !code.trim()) return ''
  const key = code.trim().toUpperCase()
  return map[key] ?? FALLBACK_LABELS[key] ?? humanizeCode(key)
}

export default function Marketing() {
  const [period, setPeriod] = useState<MarketingPeriod>('7d')
  const [from, setFrom] = useState(isoDaysAgo(6))
  const [to, setTo] = useState(isoDaysAgo(0))
  const [projectType, setProjectType] = useState('')
  const [productFamily, setProductFamily] = useState('')
  const [interestLevel, setInterestLevel] = useState('')

  const [data, setData] = useState<MarketingAggregates | null>(null)
  const [status, setStatus] = useState<MarketingStatus | null>(null)
  const [report, setReport] = useState<MarketingReport | null>(null)
  const [detail, setDetail] = useState<MarketingProductDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const [search, setSearch] = useState('')
  const [sortKey, setSortKey] = useState<SortKey>('interestedSessions')

  const filters = useMemo(() => ({
    projectType: projectType || undefined,
    productFamily: productFamily || undefined,
    interestLevel: interestLevel || undefined,
  }), [projectType, productFamily, interestLevel])

  /** Tables de libellés métier fournies par le backend (source unique). */
  const labels = useMemo(() => ({
    project: status?.projectTypeLabels ?? {},
    family: status?.productFamilyLabels ?? {},
    rejection: status?.rejectionReasonLabels ?? {},
    interest: status?.interestReasonLabels ?? {},
    unmet: status?.unmetReasonLabels ?? {},
    missingInfo: status?.missingInfoReasonLabels ?? {},
  }), [status])

  /* Les valeurs des filtres restent les codes techniques : seuls les libellés affichés changent. */
  const projectLabel = useCallback((code?: string | null) => readableLabel(labels.project, code), [labels])
  const familyLabel = useCallback((code?: string | null) => readableLabel(labels.family, code), [labels])
  const rejectionLabel = useCallback((code?: string | null) => readableLabel(labels.rejection, code), [labels])
  const unmetLabel = useCallback((code?: string | null) => readableLabel(labels.unmet, code), [labels])
  const missingInfoLabel = useCallback((code?: string | null) => readableLabel(labels.missingInfo, code), [labels])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const aggregates = await fetchMarketingOverview(period, from, to, filters)
      setData(aggregates)
      setStatus(await fetchMarketingStatus())
      setReport(await fetchMarketingReport(aggregates.dateTo))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erreur de chargement.')
    } finally {
      setLoading(false)
    }
  }, [period, from, to, filters])

  useEffect(() => {
    void load()
  }, [load])

  async function runAction(action: () => Promise<unknown>, successMessage: string) {
    setWorking(true)
    setError(null)
    setNotice(null)
    try {
      await action()
      setNotice(successMessage)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Action impossible.')
    } finally {
      setWorking(false)
    }
  }

  async function openProduct(productId: string) {
    try {
      setDetail(await fetchMarketingProduct(productId, period, from, to))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Détail indisponible.')
    }
  }

  const products = useMemo(() => {
    const rows = (data?.products ?? []).filter((product) =>
      !search || product.productName.toLowerCase().includes(search.toLowerCase()))
    return [...rows].sort((a, b) => {
      const av = (a[sortKey] ?? 0) as number
      const bv = (b[sortKey] ?? 0) as number
      return bv - av
    })
  }, [data, search, sortKey])

  const topProducts = (data?.products ?? []).filter((p) => p.interestedSessions > 0).slice(0, 8)
  const maxInterested = Math.max(1, ...topProducts.map((p) => p.interestedSessions))
  const maxSeries = Math.max(1, ...(data?.series ?? []).map((point) => point.conversationCount))
  const alerts = (report?.alerts ?? []).slice(0, 6)
  const hasDemoData = Boolean(data?.demo) || Boolean(status?.demoMode)

  return (
    <div className="logs-shell marketing-page">
      <header className="logs-header">
        <a href="#/" className="logs-back"><ArrowLeft size={18} /> Retour au chat</a>
        <h1><TrendingUp size={22} /> Marketing Intelligence</h1>
        <span className={`logs-status ${data ? 'ok' : 'ko'}`}>
          <span className="status-dot" /> {data ? `${data.dateFrom} → ${data.dateTo}` : '—'}
        </span>
      </header>

      <p className="mkt-subtitle">
        Analyse des besoins, intérêts et comportements issus des conversations avec le Coach Financier IA.
        Les chiffres sont calculés par le moteur analytique&nbsp;; l'IA ne fait que les interpréter.
      </p>

      <div className="mkt-toolbar">
        <div className="mkt-periods">
          {PERIODS.map((entry) => (
            <button
              key={entry.key}
              type="button"
              className={`mkt-period${period === entry.key ? ' active' : ''}`}
              onClick={() => setPeriod(entry.key)}
            >
              {entry.label}
            </button>
          ))}
        </div>
        {period === 'custom' && (
          <div className="mkt-dates">
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            <span>→</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
        )}
        <select value={projectType} onChange={(e) => setProjectType(e.target.value)} title="Filtrer par projet">
          <option value="">Tous les projets</option>
          {(data?.projects ?? []).map((project) => (
            <option key={project.projectType} value={project.projectType}>{projectLabel(project.projectType)}</option>
          ))}
        </select>
        <select value={interestLevel} onChange={(e) => setInterestLevel(e.target.value)} title="Filtrer par niveau d'intérêt">
          <option value="">Tous les niveaux</option>
          <option value="HIGH">Intérêt élevé</option>
          <option value="MEDIUM">Intérêt moyen</option>
          <option value="LOW">Intérêt faible</option>
        </select>
        <button type="button" className="mkt-action" onClick={() => void load()} disabled={loading || working}>
          <RefreshCw size={15} /> Actualiser
        </button>
        <a className="mkt-action" href={marketingProductsCsvUrl(period, from, to)}>
          <Download size={15} /> Exporter CSV
        </a>
        <button
          type="button"
          className="mkt-action"
          disabled={loading || working}
          onClick={() => void runAction(() => regenerateMarketingReport(data?.dateTo ?? to), 'Rapport IA régénéré.')}
          title="Recalcule les agrégats et régénère le rapport IA de la date"
        >
          <Wand2 size={15} /> Régénérer le rapport IA
        </button>
        <button
          type="button"
          className="mkt-action"
          disabled={working}
          onClick={() => void runAction(() => generateMarketingDemoData(7, 12), 'Données de démonstration générées (demo=true).')}
          title="Génère des événements de démonstration clairement identifiés"
        >
          <Bot size={15} /> Données de démo
        </button>
      </div>

      {error && <div className="logs-error">{error}</div>}
      {notice && <div className="mkt-notice">{notice}</div>}
      {hasDemoData && (
        <div className="mkt-demo-banner" role="status">
          Données de démonstration détectées (événements <code>demo=true</code>) : elles ne représentent pas
          des conversations réelles.
        </div>
      )}
      {data && data.invalidEventLines > 0 && (
        <div className="mkt-notice warn">
          {data.invalidEventLines} ligne(s) d'événement invalide(s) ignorée(s) dans les fichiers JSONL.
        </div>
      )}

      {loading && !data ? (
        <div className="logs-empty">Chargement des données marketing…</div>
      ) : !data ? (
        <div className="logs-empty">Aucune donnée pour la période. Générez des données de démo ou clôturez une conversation.</div>
      ) : (
        <>
          {alerts.length > 0 && (
            <section className="mkt-alerts">
              {alerts.map((alert, index) => (
                <div key={`${alert.title}-${index}`} className={`mkt-alert ${(alert.level ?? 'INFO').toLowerCase()}`}>
                  <strong>{alert.title}</strong>
                  <span>{alert.description}</span>
                </div>
              ))}
            </section>
          )}

          <section className="mkt-kpis">
            <Kpi label="Conversations analysées" value={formatNumber(data.overview.conversationCount)} />
            <Kpi label="Sessions avec intérêt" value={formatNumber(data.overview.sessionsWithInterest)} />
            <Kpi label="Intérêts élevés (HIGH)" value={formatNumber(data.overview.highInterestCount)} />
            <Kpi label="Intentions de souscription" value={formatNumber(data.overview.subscriptionIntentCount)} />
            <Kpi label="Demandes de rendez-vous" value={formatNumber(data.overview.appointmentRequestCount)} />
            <Kpi label="Besoins non couverts" value={formatNumber(data.overview.unmetNeedCount)} />
            <Kpi label="Informations manquantes" value={formatNumber(data.overview.missingInformationCount)} />
          </section>

          <section className="mkt-chart">
            <h2>Tendances de la période</h2>
            <div className="mkt-bars">
              {data.series.map((point) => (
                <div key={point.date} className="mkt-bar-col" title={`${point.date} · ${point.conversationCount} conversation(s), ${point.interestCount} intérêt(s)`}>
                  <div className="mkt-bar" style={{ height: `${Math.max(4, (point.conversationCount / maxSeries) * 100)}%` }} />
                  <span>{point.date.slice(5)}</span>
                </div>
              ))}
            </div>
            <p className="mkt-hint">Barres = conversations par jour · survolez pour le détail (intérêts, intérêts élevés, RDV).</p>
          </section>

          <section className="mkt-grid">
            <div className="mkt-card">
              <h2>Produits les plus intéressants</h2>
              {topProducts.length === 0 && <p className="mkt-empty">Aucun intérêt produit sur la période.</p>}
              {topProducts.map((product) => (
                <button key={product.productId} type="button" className="mkt-ranking-row" onClick={() => void openProduct(product.productId)}>
                  <span className="mkt-ranking-name">{product.productName}</span>
                  <span className="mkt-ranking-bar" style={{ width: `${(product.interestedSessions / maxInterested) * 100}%` }} />
                  <span className="mkt-ranking-value">
                    {product.interestedSessions} <small>(dont {product.highCount} à intérêt élevé)</small>
                  </span>
                  <EvolutionBadge value={product.evolutionPercent} />
                </button>
              ))}
            </div>

            <div className="mkt-card">
              <h2>Recommandation vs intérêt réel</h2>
              <table className="mkt-table compact">
                <thead>
                  <tr><th>Produit</th><th>Recommandé</th><th>Intérêt</th><th>Élevé</th><th>Taux</th></tr>
                </thead>
                <tbody>
                  {data.products.filter((p) => p.recommendedSessions > 0).slice(0, 8).map((product) => (
                    <tr key={product.productId} onClick={() => void openProduct(product.productId)}>
                      <td>{product.productName}</td>
                      <td>{product.recommendedSessions}</td>
                      <td>{product.interestedSessions}</td>
                      <td>{product.highCount}</td>
                      <td className={(product.interestRate ?? 1) < 0.05 ? 'mkt-low' : ''}>{formatPercent(product.interestRate)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mkt-card">
              <h2>Projets clients</h2>
              {data.projects.map((project) => (
                <button
                  key={project.projectType}
                  type="button"
                  className="mkt-row"
                  onClick={() => setProjectType(project.projectType === projectType ? '' : project.projectType)}
                >
                  <span title={project.projectType}>{projectLabel(project.projectType)}</span>
                  <span>{formatNumber(project.volume)} <small>({formatPercent(project.share)})</small></span>
                  <EvolutionBadge value={project.evolutionPercent} />
                </button>
              ))}
            </div>

            <div className="mkt-card">
              <h2>Pourquoi les clients refusent-ils ?</h2>
              {data.rejections.length === 0 && <p className="mkt-empty">Aucun refus enregistré.</p>}
              {data.rejections.map((rejection) => (
                <div key={rejection.reasonCategory} className="mkt-row">
                  <span title={rejection.reasonCategory}>{rejectionLabel(rejection.reasonCategory)}</span>
                  <span>{formatNumber(rejection.count)} <small>({formatPercent(rejection.share)})</small></span>
                </div>
              ))}
            </div>

            <div className="mkt-card">
              <h2>Produits souvent associés</h2>
              {data.crossSell.length === 0 && <p className="mkt-empty">Aucune association significative.</p>}
              {data.crossSell.slice(0, 6).map((pair) => (
                <div key={`${pair.sourceProductId}-${pair.targetProductId}`} className="mkt-row">
                  <span>{pair.sourceProductName} → {pair.targetProductName}</span>
                  <span>{formatNumber(pair.commonSessions)} <small>({formatPercent(pair.rate, 0)})</small></span>
                </div>
              ))}
            </div>

            <div className="mkt-card">
              <h2>Besoins non couverts</h2>
              {data.unmetNeeds.length === 0 && <p className="mkt-empty">Aucun besoin non couvert détecté.</p>}
              {data.unmetNeeds.slice(0, 6).map((need, index) => (
                <div key={`${need.projectType}-${need.reasonCategory}-${index}`} className="mkt-row column">
                  <span className="mkt-strong" title={[need.projectType, need.reasonCategory].filter(Boolean).join(' · ')}>
                    {isUnknown(need.projectType) ? 'Projet non identifié' : projectLabel(need.projectType)}
                    {' — '}
                    {isUnknown(need.reasonCategory) ? 'Motif non précisé' : unmetLabel(need.reasonCategory)}
                  </span>
                  <span className="mkt-muted">
                    {need.reason?.trim() && `« ${need.reason.trim()} » · `}
                    {formatNumber(need.count)} {need.count > 1 ? 'demandes' : 'demande'}
                    {need.averageConfidence != null && ` · confiance IA ${formatPercent(need.averageConfidence, 0)}`}
                  </span>
                </div>
              ))}
            </div>

            <div className="mkt-card">
              <h2>Questions sans réponse dans le catalogue</h2>
              {data.missingInformation.length === 0 && <p className="mkt-empty">Aucun manque d'information signalé.</p>}
              {data.missingInformation.slice(0, 6).map((info) => (
                <div key={`${info.productId}-${info.reasonCategory}`} className="mkt-row">
                  <span title={info.productId}>
                    {isUnknown(info.productName) ? <span className="mkt-muted">Produit non identifié</span> : info.productName}
                    <small> · {isUnknown(info.reasonCategory) ? 'motif non précisé' : missingInfoLabel(info.reasonCategory)}</small>
                  </span>
                  <span>{formatNumber(info.count)}</span>
                </div>
              ))}
            </div>
          </section>

          <section className="mkt-card wide">
            <h2>Analyse IA du jour {report?.reportDate ? `— ${report.reportDate}` : ''}</h2>
            <p className="mkt-hint">
              Analyse générée à partir de statistiques agrégées. Les chiffres sont calculés par le moteur
              analytique et non par l'IA.
              {report?.model ? ` · modèle : ${report.model}` : ''}
            </p>
            {!report ? (
              <p className="mkt-empty">Aucun rapport IA disponible. Utilisez « Régénérer le rapport IA ».</p>
            ) : (
              <>
                {report.error && <div className="mkt-notice warn">{report.error}</div>}
                {report.executiveSummary && report.executiveSummary.length > 0 && (
                  <ul className="mkt-report-list">
                    {report.executiveSummary.map((item, index) => (
                      <li key={`${item.title}-${index}`}>
                        <span className={`mkt-importance ${(item.importance ?? 'LOW').toLowerCase()}`}>{item.importance}</span>
                        <strong>{item.title}</strong> — {item.description}
                      </li>
                    ))}
                  </ul>
                )}
                <div className="mkt-report-cols">
                  {report.mainTrends && report.mainTrends.length > 0 && (
                    <div>
                      <h3>Tendances</h3>
                      <ul>{report.mainTrends.map((trend, i) => <li key={i}><strong>{trend.entityName}</strong> — {trend.observation}</li>)}</ul>
                    </div>
                  )}
                  {report.recommendationPerformance && report.recommendationPerformance.length > 0 && (
                    <div>
                      <h3>Performance des recommandations</h3>
                      <ul>{report.recommendationPerformance.map((item, i) => <li key={i}><strong>{item.productName}</strong> — {item.observation}</li>)}</ul>
                    </div>
                  )}
                  {report.customerFriction && report.customerFriction.length > 0 && (
                    <div>
                      <h3>Freins clients</h3>
                      <ul>{report.customerFriction.map((item, i) => <li key={i}><strong>{item.category}</strong> — {item.observation}</li>)}</ul>
                    </div>
                  )}
                  {report.unmetNeeds && report.unmetNeeds.length > 0 && (
                    <div>
                      <h3>Besoins non couverts</h3>
                      <ul>{report.unmetNeeds.map((item, i) => <li key={i}><strong>{projectLabel(item.projectType) || 'Projet non identifié'}</strong> — {item.observation}</li>)}</ul>
                    </div>
                  )}
                  {report.missingProductInformation && report.missingProductInformation.length > 0 && (
                    <div>
                      <h3>Informations produit à enrichir</h3>
                      <ul>{report.missingProductInformation.map((item, i) => <li key={i}><strong>{item.productName}</strong> — {item.observation}</li>)}</ul>
                    </div>
                  )}
                  {report.crossSellInsights && report.crossSellInsights.length > 0 && (
                    <div>
                      <h3>Cross-sell</h3>
                      <ul>{report.crossSellInsights.map((item, i) => <li key={i}><strong>{item.sourceProduct} → {item.targetProduct}</strong> — {item.observation}</li>)}</ul>
                    </div>
                  )}
                  {report.aiCoachQuality && report.aiCoachQuality.length > 0 && (
                    <div>
                      <h3>Qualité du parcours IA</h3>
                      <ul>{report.aiCoachQuality.map((item, i) => <li key={i}><strong>{item.type}</strong> — {item.observation}</li>)}</ul>
                    </div>
                  )}
                </div>
                {report.opportunities && report.opportunities.length > 0 && (
                  <>
                    <h3><Lightbulb size={15} /> Opportunités à étudier</h3>
                    <ul className="mkt-report-list">
                      {report.opportunities.map((item, index) => (
                        <li key={index}><strong>{item.title}</strong> — {item.description} <em>{item.recommendation}</em></li>
                      ))}
                    </ul>
                  </>
                )}
                {report.finalSummary && <p className="mkt-summary">{report.finalSummary}</p>}
              </>
            )}
          </section>

          <section className="mkt-card wide">
            <h2>Tableau complet des produits</h2>
            <div className="mkt-table-tools">
              <input
                type="search"
                placeholder="Rechercher un produit…"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
              <select value={sortKey} onChange={(event) => setSortKey(event.target.value as SortKey)}>
                <option value="interestedSessions">Tri : sessions intéressées</option>
                <option value="recommendedSessions">Tri : recommandations</option>
                <option value="interestRate">Tri : taux d'intérêt</option>
                <option value="score">Tri : score</option>
                <option value="evolutionPercent">Tri : évolution</option>
              </select>
              <span className="mkt-muted">{products.length} produit(s)</span>
            </div>
            <div className="mkt-table-scroll">
              <table className="mkt-table">
                <thead>
                  <tr>
                    <th>Produit</th><th>Famille</th><th>Recommandations</th><th>Intérêts</th>
                    <th>Élevé</th><th>Moyen</th><th>Refus</th><th>Souscription</th><th>RDV</th>
                    <th>Taux intérêt</th><th>Score</th><th>Évolution</th>
                  </tr>
                </thead>
                <tbody>
                  {products.slice(0, 40).map((product) => (
                    <tr key={product.productId} onClick={() => void openProduct(product.productId)}>
                      <td>{product.productName}</td>
                      <td>{isUnknown(product.productFamily) ? '—' : familyLabel(product.productFamily)}</td>
                      <td>{product.recommendedSessions}</td>
                      <td>{product.interestedSessions}</td>
                      <td>{product.highCount}</td>
                      <td>{product.mediumCount}</td>
                      <td>{product.rejectedCount}</td>
                      <td>{product.subscriptionIntentCount}</td>
                      <td>{product.appointmentRequestCount}</td>
                      <td>{formatPercent(product.interestRate)}</td>
                      <td>{product.score.toLocaleString('fr-FR')}</td>
                      <td><EvolutionBadge value={product.evolutionPercent} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}

      {detail && (
        <div className="mkt-drawer" role="dialog" aria-label="Détail produit">
          <div className="mkt-drawer-head">
            <h2>{detail.metric?.productName ?? detail.productId}</h2>
            <button type="button" onClick={() => setDetail(null)}>Fermer</button>
          </div>
          {detail.metric ? (
            <div className="mkt-drawer-grid">
              <Metric label="Recommandations" value={formatNumber(detail.metric.recommendedSessions)} />
              <Metric label="Sessions intéressées" value={formatNumber(detail.metric.interestedSessions)} />
              <Metric label="Clients uniques" value={formatNumber(detail.metric.uniqueInterestedCustomers)} />
              <Metric label="Intérêt élevé / moyen" value={`${detail.metric.highCount} / ${detail.metric.mediumCount}`} />
              <Metric label="Refus" value={formatNumber(detail.metric.rejectedCount)} />
              <Metric label="Souscription / RDV" value={`${detail.metric.subscriptionIntentCount} / ${detail.metric.appointmentRequestCount}`} />
              <Metric label="Taux intérêt" value={formatPercent(detail.metric.interestRate)} />
              <Metric label="Score" value={detail.metric.score.toLocaleString('fr-FR')} />
            </div>
          ) : (
            <p className="mkt-empty">Aucune métrique sur la période.</p>
          )}
          {detail.rejections.length > 0 && (
            <>
              <h3>Motifs de refus</h3>
              <ul className="mkt-report-list">
                {detail.rejections.map((rejection) => (
                  <li key={rejection.reasonCategory}><strong>{rejectionLabel(rejection.reasonCategory)}</strong> — {rejection.count} ({formatPercent(rejection.share)})</li>
                ))}
              </ul>
            </>
          )}
          {detail.crossSell.length > 0 && (
            <>
              <h3>Produits associés</h3>
              <ul className="mkt-report-list">
                {detail.crossSell.map((pair) => (
                  <li key={pair.targetProductId}><strong>{pair.targetProductName}</strong> — {pair.commonSessions} session(s) commune(s) ({formatPercent(pair.rate, 0)})</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  )
}

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <div className="mkt-kpi">
      <span className="mkt-kpi-label">{label}</span>
      <span className="mkt-kpi-value">{value}</span>
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="mkt-metric">
      <span className="mkt-muted">{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function EvolutionBadge({ value }: { value: number | null | undefined }) {
  if (value == null) return <span className="mkt-evo flat">—</span>
  const up = value > 0
  return (
    <span className={`mkt-evo ${up ? 'up' : value < 0 ? 'down' : 'flat'}`}>
      {up ? <TrendingUp size={13} /> : value < 0 ? <TrendingDown size={13} /> : null}
      {formatEvolution(value)}
    </span>
  )
}

/** Utilisé par la page pour typer les libellés de provider (aucun effet de bord). */
export type MarketingProvider = AIProvider
