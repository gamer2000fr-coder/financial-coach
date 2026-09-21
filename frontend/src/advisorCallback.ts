/**
 * Demande de rappel par un conseiller.
 * <p>
 * Le Coach écrit un jeton SANS URL — `[RAPPEL|Être rappelé par un conseiller]` — parce qu'aucune adresse de
 * rappel n'existe dans les données : le lien ne doit donc PAS naviguer, il ouvre une pop-in qui informe le
 * client qu'un conseiller le recontactera. Le clic émet un événement, écouté par la pop-in globale
 * ({@code AdvisorCallbackPopup}) montée par le routeur : ainsi le jeton fonctionne sur toutes les pages
 * (chat, historique de conversation) sans dépendre de l'état d'une page.
 */
export const ADVISOR_CALLBACK_EVENT = 'advisor-callback-request'

/** Émet la demande de rappel (appelée par le rendu du jeton `[RAPPEL|…]`). */
export function requestAdvisorCallback(): void {
  window.dispatchEvent(new CustomEvent(ADVISOR_CALLBACK_EVENT))
}
