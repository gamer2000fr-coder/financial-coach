# AJOUT — LIEN D'ÉVALUATION DANS LE MAIL DE SUIVI CONSEILLER

Le mail de suivi actuellement envoyé au conseiller doit contenir un lien direct permettant d'évaluer le travail réalisé par le Coach IA pour la conversation concernée.

Objectif :

**le conseiller doit pouvoir passer du mail de suivi à l'écran de feedback en un clic.**

---

# 41. LIEN DIRECT DEPUIS LE MAIL CONSEILLER

Dans l'email de suivi envoyé au conseiller après clôture de la conversation, ajouter un bloc visible :

```text
Évaluer le suivi du Coach IA
```

avec un lien direct vers la page de feedback correspondant à cette conversation.

Exemple fonctionnel :

```text
Donnez votre avis sur la synthèse et les recommandations préparées par le Coach :

[Évaluer le suivi du Coach]
```

---

# 42. URL DE FEEDBACK

L'URL doit permettre d'identifier la conversation à évaluer sans demander au conseiller de la rechercher manuellement.

Exemple :

```text
http://<frontend>/#/advisor-feedback/session/{sessionId}
```

ou, selon le routing existant :

```text
http://<frontend>/#/advisor-feedback?sessionId={sessionId}
```

Choisir la convention la plus cohérente avec l'application actuelle.

Le conseiller clique sur le lien :

```text
Email
  ↓
Lien "Évaluer le suivi du Coach"
  ↓
Page Advisor Feedback
  ↓
Dossier déjà chargé
  ↓
Feedback en quelques secondes
```

---

# 43. PAGE CIBLÉE PAR SESSION

Lorsque la page est ouverte depuis le lien du mail :

ne pas afficher directement le dashboard statistique global.

Afficher d'abord le dossier correspondant à la session.

La page doit récupérer et présenter les informations nécessaires à l'évaluation :

- projet client détecté ;
- résumé produit par le Coach ;
- produits d'intérêt ;
- niveaux d'intérêt ;
- suivi conseillé ;
- email client préparé ;
- éventuellement informations de contexte strictement nécessaires.

Puis afficher le formulaire de feedback.

Exemple :

```text
Feedback conseiller

Conversation : 11/09/2026
Projet : Achat véhicule

--------------------------------

Résumé du Coach
[...]

Votre avis :
[ Pertinent ] [ À améliorer ] [ Incorrect ]

--------------------------------

Produits identifiés

Crédit Auto Expresso
Intérêt IA : HIGH

[ ✓ Pertinent ] [ ✕ Non pertinent ]

--------------------------------

Suivi conseillé
[...]

[ Pertinent ] [ À améliorer ] [ Incorrect ]

--------------------------------

Email client préparé

[ Prêt à utiliser ]
[ Modifications mineures ]
[ Modifications importantes ]
[ Non utilisable ]

--------------------------------

Commentaire facultatif
[...]

[ Envoyer mon feedback ]
```

---

# 44. APRÈS ENVOI DU FEEDBACK

Après soumission :

afficher un message clair :

```text
Merci, votre retour a bien été enregistré.
```

Puis proposer éventuellement :

```text
Retour au tableau de suivi
```

Ne pas demander une nouvelle authentification ou recherche de dossier si la session conseiller est déjà active.

---

# 45. LIEN SÉCURISÉ

Ne jamais mettre dans l'URL :

- nom du client ;
- email client ;
- numéro de compte ;
- montant bancaire ;
- données personnelles ;
- commentaire ;
- détail de la conversation.

Utiliser uniquement un identifiant technique comme :

```text
sessionId
```

ou, si nécessaire :

```text
feedbackToken
```

Pour un POC local, `sessionId` peut suffire si l'application est déjà protégée.

Pour une approche plus propre, prévoir un token opaque associé à la session.

Exemple :

```text
/#/advisor-feedback?t=opaqueFeedbackToken
```

Le backend résout ensuite le token vers la session correspondante.

---

# 46. NE PAS FAIRE CONFIANCE AU SESSION ID FRONTEND

Le backend doit toujours vérifier que :

- la session existe ;
- le dossier est accessible ;
- le feedback correspond bien à cette session.

Le frontend ne décide jamais seul quel dossier charger.

---

# 47. EMAIL DE SUIVI — EXEMPLE

Le mail conseiller pourrait contenir :

```text
Bonjour,

Une conversation nécessitant un suivi a été clôturée.

Projet détecté :
Achat d'un véhicule

Produits d'intérêt :
- Crédit Auto Expresso — intérêt HIGH
- Assurance Auto — intérêt MEDIUM

Suivi conseillé :
- vérifier l'éligibilité au Crédit Auto Expresso ;
- réaliser une simulation via l'outil officiel ;
- accompagner le client vers une souscription s'il le souhaite.

Un email client a également été préparé et joint pour validation.

--------------------------------

Votre retour nous aide à améliorer le Coach IA.

[Évaluer le suivi du Coach]

--------------------------------
```

Le bouton/lien doit ouvrir directement le dossier correspondant.

---

# 48. RAPPEL FACULTATIF

Pour le POC, ne pas mettre en place automatiquement des relances multiples.

Prévoir simplement dans la structure un statut :

```text
NOT_REQUESTED
PENDING
COMPLETED
```

ou :

```text
feedbackStatus
```

Exemple :

```json
{
  "feedbackStatus": "PENDING"
}
```

Après retour conseiller :

```json
{
  "feedbackStatus": "COMPLETED"
}
```

Cela permettra plus tard d'identifier les dossiers sans feedback.

---

# 49. DASHBOARD GLOBAL ET PAGE DOSSIER

La route `/advisor-feedback` doit gérer deux usages distincts.

## Vue 1 — Dashboard

```text
/#/advisor-feedback
```

Affiche :

- KPI ;
- tendances ;
- problèmes fréquents ;
- analyse IA ;
- feedback par produit.

## Vue 2 — Évaluation d'un dossier

```text
/#/advisor-feedback/session/{sessionId}
```

Affiche :

- résultats produits par le Coach pour cette conversation ;
- formulaire d'évaluation.

Ne pas mélanger les deux expériences.

---

# 50. TESTS DU LIEN EMAIL

Tester au minimum :

### Lien valide

Le clic ouvre directement le bon dossier.

### Session inexistante

Afficher :

```text
Ce dossier n'est plus disponible.
```

sans erreur technique.

### Feedback déjà donné

Afficher le feedback existant.

Permettre éventuellement sa modification si cette fonctionnalité est prévue.

### URL manipulée

Le backend contrôle l'accès et ne retourne pas de dossier non autorisé.

### Données personnelles

Aucune donnée client sensible n'est présente dans l'URL.

### Email

Le bouton fonctionne dans les principaux clients mail avec une URL HTML classique.