import { useEffect, useState } from 'react'
import { PhoneCall, X } from 'lucide-react'

import { ADVISOR_CALLBACK_EVENT } from './advisorCallback'

/**
 * POP-IN « être rappelé par un conseiller » : ouverte par le jeton `[RAPPEL|Être rappelé par un conseiller]`
 * que le Coach place à côté du lien de rendez-vous. Elle rassure le client : un conseiller le recontactera
 * dans les plus brefs délais.
 * <p>
 * Montée par le routeur ({@code main.tsx}) : le jeton est donc actif sur toutes les pages qui affichent une
 * réponse du Coach (chat, historique de conversation). Aucune donnée personnelle n'est demandée ni transmise,
 * et aucun créneau n'est annoncé — le POC n'enregistre pas de demande de rappel côté serveur.
 */
export default function AdvisorCallbackPopup() {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const onRequest = () => setOpen(true)
    window.addEventListener(ADVISOR_CALLBACK_EVENT, onRequest)
    return () => window.removeEventListener(ADVISOR_CALLBACK_EVENT, onRequest)
  }, [])

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  if (!open) return null

  return (
    <div
      className="qlt-popup-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="callback-popup-title"
      onClick={() => setOpen(false)}
    >
      <div className="qlt-popup" onClick={(event) => event.stopPropagation()}>
        <div className="callback-popup-head">
          <span className="callback-popup-icon"><PhoneCall size={18} /></span>
          <h2 id="callback-popup-title">Votre demande de rappel est prise en compte</h2>
          <button
            type="button"
            className="callback-popup-close"
            aria-label="Fermer"
            onClick={() => setOpen(false)}
          >
            <X size={16} />
          </button>
        </div>

        <p className="callback-popup-text">
          Un conseiller prendra contact avec vous <strong>dans les plus brefs délais</strong> pour faire le
          point sur votre projet.
        </p>
        <p className="callback-popup-text">
          Pensez à garder votre téléphone à portée de main. Aucun rendez-vous n&rsquo;est encore fixé : le
          conseiller vous proposera un créneau lors de son appel.
        </p>

        <div className="qlt-popup-actions">
          <button type="button" className="callback-popup-ok" onClick={() => setOpen(false)}>
            J&rsquo;ai compris
          </button>
        </div>
      </div>
    </div>
  )
}
