import { useEffect, useState } from 'react'
import { ArrowLeft, Bot, Check, Save, X } from 'lucide-react'
import { fetchAgentPrompt, fetchAgents, saveAgentPrompt, type AgentEntry } from './api'

export default function Agents() {
  const [entries, setEntries] = useState<AgentEntry[]>([])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [file, setFile] = useState('')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  // Chargement de la liste ; agent générique sélectionné par défaut.
  useEffect(() => {
    let active = true
    fetchAgents()
      .then((list) => {
        if (!active) return
        setEntries(list)
        const generic = list.find((entry) => entry.key === 'generic') ?? list[0]
        if (generic) setSelectedKey(generic.key)
      })
      .catch((err) => {
        if (!active) return
        setError(err instanceof Error ? err.message : 'Erreur de chargement.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  // Chargement du prompt quand l'agent sélectionné change.
  useEffect(() => {
    if (!selectedKey) return
    let active = true
    setLoading(true)
    setSaved(false)
    setError(null)
    fetchAgentPrompt(selectedKey)
      .then((data) => {
        if (!active) return
        setContent(data.content ?? '')
        setFile(entries.find((entry) => entry.key === selectedKey)?.file ?? '')
      })
      .catch((err) => {
        if (!active) return
        setError(err instanceof Error ? err.message : 'Erreur de chargement.')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedKey])

  async function handleSave() {
    if (!selectedKey) return
    setSaving(true)
    setSaved(false)
    setError(null)
    try {
      const data = await saveAgentPrompt(selectedKey, content)
      setContent(data.content ?? content)
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erreur lors de la sauvegarde.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="logs-shell agents-page">
      <header className="logs-header">
        <a href="#/" className="logs-back"><ArrowLeft size={18} /> Retour au chat</a>
        <h1><Bot size={22} /> Agents (prompts)</h1>
        <span className="logs-status ok"><span className="status-dot" /> Enregistré dans agent/ — actif immédiatement</span>
      </header>

      {error && <div className="logs-error">{error}</div>}

      <div className="prompt-body">
        <label className="prompt-field">
          <span className="prompt-label">Agent</span>
          <select
            className="prompt-select"
            value={selectedKey ?? ''}
            onChange={(event) => {
              setSelectedKey(event.target.value)
              setContent('')
              setSaved(false)
            }}
          >
            {entries.map((entry) => (
              <option key={entry.key} value={entry.key}>{entry.libelle}</option>
            ))}
          </select>
          {file && <span className="prompt-file">agent/{file}</span>}
        </label>

        <p className="prompt-hint">
          Chaque agent possède son propre prompt système. L&rsquo;agent <strong>générique</strong>{' '}
          inclut le contenu de l&rsquo;<strong>agent principal</strong> via la balise{' '}
          <code>[agent_principal]</code>. L&rsquo;<strong>agent de suivi</strong> est utilisé à la fin
          d&rsquo;une conversation (dossier conseiller + brouillon d&rsquo;email client) et les agents
          analystes <strong>marketing</strong>, <strong>qualité &amp; satisfaction</strong> et{' '}
          <strong>feedback conseiller</strong> rédigent les rapports des pages{' '}
          <a href="#/marketing">Marketing</a>, <a href="#/quality">Qualité</a> et{' '}
          <a href="#/advisor-feedback">Feedback Conseillers</a> à partir des statistiques calculées par le
          backend. La sauvegarde est prise en compte{' '}
          <strong>immédiatement</strong>, sans redémarrer le serveur.
        </p>

        {loading ? (
          <div className="prompt-loading">Chargement…</div>
        ) : (
          <textarea
            className="prompt-textarea"
            value={content}
            onChange={(event) => {
              setContent(event.target.value)
              setSaved(false)
            }}
            placeholder="Saisissez le prompt de l'agent…"
            spellCheck={false}
          />
        )}

        <div className="prompt-actions">
          <span className={`prompt-saved ${saved ? 'visible' : ''}`}>
            {saved && <><Check size={16} /> Prompt « {selectedKey} » enregistré et actif.</>}
          </span>
          <button className="prompt-save" type="button" onClick={handleSave} disabled={loading || saving}>
            {saving ? <X size={16} /> : <Save size={16} />}
            {saving ? 'Enregistrement…' : 'Enregistrer'}
          </button>
        </div>
      </div>
    </div>
  )
}
