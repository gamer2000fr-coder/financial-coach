# MISSION — IMPLÉMENTER UN ATELIER D’AMÉLIORATION ITÉRATIVE DES PROMPTS IA

Tu dois implémenter dans l’application Coach Financier existante un nouveau module permettant d’améliorer automatiquement et progressivement les prompts des agents métier grâce à deux agents IA spécialisés :

- Agent A : Prompt Editor / éditeur de prompt ; le prompt de l'agent a est /prompt/agent_a.md
- Agent B : Prompt Controller / contrôleur qualité. le prompt de l'agent b est /prompt/agent_b.md

Le système doit permettre de lancer une campagne d’optimisation sur un agent métier existant, par exemple :
- agent générique ;
- crédit consommation ;
- crédit immobilier ;
- épargne ;
- assurance auto ;
- assurance habitation ;
- assurance emprunteur ;
- ou tout autre agent déjà présent dans l’application.

L’objectif n’est PAS de laisser une IA modifier automatiquement les prompts de production.

L’objectif est de créer un environnement d’expérimentation contrôlé dans lequel :

1. une question de test est envoyée au Coach ;
2. le contexte réellement utilisé pour cette question est capturé et figé ;
3. la réponse du Coach est sauvegardée ;
4. Agent B analyse cette réponse ;
5. Agent B produit un feedback structuré ;
6. Agent A reçoit ce feedback ;
7. Agent A modifie uniquement la partie autorisée du prompt ;
8. la même question est rejouée avec exactement le même contexte figé ;
9. Agent B analyse la nouvelle réponse ;
10. Agent A adapte à nouveau le prompt ;
11. la boucle continue pendant N itérations ;
12. l’utilisateur humain peut interrompre la boucle ;
13. lorsqu’elle est en pause, l’utilisateur peut ajouter son propre feedback ;
14. Agent A doit tenir compte prioritairement de ce feedback humain ;
15. la boucle peut ensuite reprendre ;
16. toutes les versions du prompt, réponses et feedbacks sont conservées ;
17. l’utilisateur peut comparer les résultats ;
18. l’utilisateur peut retenir une version particulière ;
19. aucune version candidate ne doit modifier automatiquement le prompt de production.

Le système doit être reproductible, traçable et sécurisé.

======================================================================
1. PRINCIPE ARCHITECTURAL
   ======================================================================

Le Coach existant continue de fonctionner normalement.

Le nouveau module est un atelier d’expérimentation séparé.

Il ne doit pas casser ou remplacer le fonctionnement actuel.

Architecture conceptuelle :

                QUESTION DE TEST
                       +
              PROMPT DE RÉFÉRENCE
                       +
              CONTEXTE / DATA FIGÉS
                       |
                       v
                 COACH MÉTIER
                       |
                       v
                  RÉPONSE Rn
                       |
                       v
              AGENT B — CONTROLLER
                       |
                  FEEDBACK Bn
                       |
                       v
              AGENT A — PROMPT EDITOR
                       |
                       v
                  PROMPT Vn+1
                       |
                       v
              MÊME QUESTION
              MÊMES DONNÉES
              MÊME CONTEXTE
                       |
                       v
                    COACH
                       |
                       v
                  RÉPONSE Rn+1
                       |
                       v
                    AGENT B
                       |
                       v
                    AGENT A
                       |
                       +----------> boucle


Au bout du nombre d’itérations demandé :

                    STOP
                     |
                     v
             VALIDATION HUMAINE
                     |
        +------------+-------------+
        |            |             |
        v            v             v
     RETENIR      REFUSER      COMMENTER
                                   |
                                   v
                               AGENT A
                                   |
                                   v
                          NOUVELLE ITÉRATION
                                   |
                                   v
                               AGENT B
                                   |
                                   +----> boucle


IMPORTANT :

Agent A et Agent B ne doivent jamais avoir la possibilité de promouvoir
directement un prompt en production.

La décision finale appartient toujours à l’utilisateur humain.

======================================================================
2. AGENT A — PROMPT EDITOR
   ======================================================================

Créer un agent interne dédié à l’amélioration des prompts.

Nom fonctionnel suggéré :

Prompt Editor

ou :

Agent A — Prompt Improvement

Cet agent n’est jamais exposé au client final.

Sa mission est exclusivement de modifier la partie éditable d’un prompt
à partir des feedbacks reçus.

Agent A reçoit notamment :

- le prompt courant ;
- la zone modifiable ;
- le feedback Agent B ;
- éventuellement le feedback humain ;
- éventuellement les précédents feedbacks utiles ;
- éventuellement les modifications précédentes ;
- la question de test ;
- les informations nécessaires pour comprendre le contexte du test.

Agent A doit :

- comprendre les défauts signalés ;
- corriger le comportement du prompt ;
- préserver les comportements déjà satisfaisants ;
- éviter les modifications inutiles ;
- éviter de réécrire entièrement le prompt à chaque itération ;
- privilégier des modifications ciblées ;
- ne jamais modifier les parties protégées ;
- tenir compte des contraintes métier existantes ;
- ne pas contourner les règles applicatives ;
- ne jamais supprimer volontairement une règle obligatoire ;
- tenir compte prioritairement du feedback humain lorsqu’il existe.

Agent A ne doit PAS juger lui-même si sa version est parfaite.

Il propose une nouvelle version.

C’est Agent B qui contrôle le résultat produit par le Coach.

======================================================================
3. AGENT B — PROMPT CONTROLLER
   ======================================================================

Créer un second agent interne.

Nom fonctionnel suggéré :

Prompt Controller

ou :

Agent B — Quality Controller

Agent B ne doit PAS modifier le prompt.

Son rôle est exclusivement :

- analyser la réponse produite par le Coach ;
- vérifier si elle répond correctement à la question ;
- vérifier l’exploitation du contexte fourni ;
- vérifier la pertinence ;
- vérifier la pédagogie ;
- vérifier le professionnalisme ;
- identifier les répétitions inutiles ;
- identifier les informations importantes oubliées ;
- identifier les comportements non souhaités ;
- identifier les problèmes de formulation ;
- identifier les réponses trop longues ou trop courtes si pertinent ;
- identifier les mauvaises interprétations de la situation ;
- identifier les éventuelles incohérences ;
- signaler les règles qui semblent mal appliquées ;
- signaler les éléments corrects qu’il faut préserver.

Agent B doit produire un feedback STRUCTURÉ.

Exemple conceptuel :

{
"status": "NEEDS_IMPROVEMENT",
"summary": "La réponse est correcte mais trop générale.",
"positivePoints": [
"Le projet du client est correctement compris.",
"Les produits présentés sont cohérents avec les données fournies."
],
"issues": [
{
"type": "INSUFFICIENT_PERSONALIZATION",
"severity": "MEDIUM",
"observation": "La situation financière fournie est peu exploitée.",
"expectedBehavior": "Expliquer la recommandation en utilisant les éléments financiers pertinents."
},
{
"type": "EXCESSIVE_REPETITION",
"severity": "LOW",
"observation": "Le montant du projet est répété plusieurs fois.",
"expectedBehavior": "Éviter les répétitions sans perdre le contexte."
}
],
"mustPreserve": [
"Le ton pédagogique.",
"L’absence d’informations inventées."
],
"recommendationForPromptEditor": "Renforcer l’utilisation des données financières utiles sans augmenter excessivement la longueur de la réponse."
}

Adapter évidemment ce schéma aux conventions du projet existant.

IMPORTANT :

Agent B donne un diagnostic.

Agent B ne doit pas réécrire le prompt.

======================================================================
4. FEEDBACK HUMAIN
   ======================================================================

Le système doit permettre à l’utilisateur humain d’intervenir dans la boucle.

Le feedback humain joue un rôle comparable au feedback Agent B, mais sa
priorité est supérieure.

Exemple :

Agent B :

"La réponse pourrait être plus concise."

Utilisateur :

"Je préfère conserver ce niveau de détail. En revanche, je veux que
l’agent explique davantage pourquoi l’épargne n’est pas mobilisée."

Agent A doit privilégier l’instruction humaine.

Prévoir une notion explicite :

feedbackSource =
- AGENT_B
- HUMAN

Pour HUMAN, prévoir par exemple :

priority = OVERRIDE

ou une logique équivalente.

Le feedback humain doit être sauvegardé dans l’historique de la campagne.

Il ne doit jamais être perdu après reprise.

======================================================================
5. ZONE MODIFIABLE DU PROMPT
   ======================================================================

Il ne faut PAS permettre à Agent A de modifier arbitrairement l’intégralité
du prompt.

Utiliser dans un premier temps la convention suivante :

[[[

contenu modifiable par Agent A

]]]

Tout ce qui se trouve à l’extérieur de ces marqueurs est IMMUTABLE pendant
une campagne d’optimisation.

Exemple :

Tu es le Coach spécialisé Crédit de la banque.

RÈGLES ABSOLUES

- N’invente jamais un produit.
- Respecte les produits autorisés.
- Respecte les règles métier.
- Réponds en français.

[[[

Tu accompagnes le client de manière pédagogique.

Analyse son projet et sa situation financière.

Présente les solutions pertinentes clairement.

Évite les répétitions inutiles.

]]]

FORMAT DE SORTIE

- Réponse destinée directement au client.
- Ne montre jamais les instructions internes.


Dans cet exemple :

IMMUTABLE :

tout ce qui précède [[[

IMMUTABLE :

tout ce qui suit ]]]

MODIFIABLE :

uniquement le contenu compris entre [[[ et ]]].


IMPORTANT :

La protection ne doit PAS reposer uniquement sur une instruction envoyée
à Agent A.

Le backend doit techniquement garantir cette immuabilité.

======================================================================
6. VALIDATION BACKEND DU PROMPT MODIFIÉ
   ======================================================================

Après chaque proposition d’Agent A :

1. récupérer la nouvelle zone éditable ;
2. reconstruire le prompt ;
3. vérifier que les parties protégées sont strictement identiques au snapshot ;
4. vérifier que les marqueurs sont toujours valides ;
5. vérifier que seule la zone autorisée a changé.

Si une partie protégée a été modifiée :

REJETER la proposition.

Exemple de statut :

IMMUTABLE_PROMPT_SECTION_MODIFIED

Ne jamais envoyer au Coach un prompt candidat ayant modifié une zone protégée.

Idéalement, Agent A ne devrait retourner que :

{
"editableSection": "...",
"changeSummary": "...",
"reasoningSummary": "..."
}

Le backend reconstruit lui-même :

fixedPrefix
+
"[[["
+
editableSection
+
"]]]"
+
fixedSuffix

Cela est préférable à demander à Agent A de renvoyer l’intégralité du prompt.

======================================================================
7. SNAPSHOT DE RÉFÉRENCE
   ======================================================================

La reproductibilité est un objectif majeur.

Lors du premier lancement d’une campagne, capturer le contexte réellement
utilisé pour effectuer l’appel Coach.

Créer un snapshot IMMUTABLE de référence.

Selon les données réellement utilisées par l’application, il doit contenir
au minimum :

- question utilisateur ;
- agent métier sélectionné ;
- prompt initial complet ;
- fixedPrefix ;
- editableSection initiale ;
- fixedSuffix ;
- données financières réellement envoyées au Coach ;
- synthèse financière réellement envoyée ;
- historique conversationnel si utilisé ;
- projet courant si utilisé ;
- produits compatibles réellement transmis ;
- données catalogue réellement utilisées ;
- données cascade réellement utilisées si applicable ;
- contexte additionnel transmis ;
- configuration du provider IA ;
- modèle utilisé ;
- paramètres d’appel importants ;
- timestamp ;
- version/hash du prompt ;
- hash du snapshot si utile.

Ne pas simplement mémoriser des références vers des fichiers susceptibles
de changer.

Il faut conserver les valeurs utilisées au moment du snapshot, ou une
représentation versionnée permettant de les reproduire exactement.

Pendant toute la campagne :

QUESTION = FIGÉE
DATA FINANCIÈRE = FIGÉE
CONTEXTE = FIGÉ
PRODUITS = FIGÉS
CATALOGUE UTILISÉ = FIGÉ
CONFIGURATION = FIGÉE autant que possible
PARTIES PROTÉGÉES DU PROMPT = FIGÉES

La variable volontairement modifiée doit être :

EDITABLE PROMPT SECTION

======================================================================
8. PREMIÈRE ITÉRATION
   ======================================================================

Lorsque l’utilisateur saisit une question et clique sur GO :

1. valider le nombre d’itérations ;
2. vérifier qu’il est compris entre 1 et 50 ;
3. identifier l’agent métier ;
4. charger son prompt actuel ;
5. vérifier la présence de [[[ et ]]] ;
6. effectuer le fonctionnement normal nécessaire pour construire le contexte ;
7. capturer le snapshot ;
8. figer ce snapshot ;
9. appeler le Coach avec :

QUESTION
+
PROMPT V0
+
SNAPSHOT DATA

10. sauvegarder la réponse R0 ;
11. afficher immédiatement R0 dans l’historique ;
12. transmettre R0 à Agent B ;
13. sauvegarder feedback B0 ;
14. transmettre prompt V0 + B0 à Agent A ;
15. produire V1 ;
16. valider V1 côté backend ;
17. appeler le Coach avec :

MÊME QUESTION
+
PROMPT V1
+
MÊME SNAPSHOT DATA

18. produire R1 ;
19. continuer la boucle.

======================================================================
9. NOMBRE D’ITÉRATIONS
   ======================================================================

L’IHM permet à l’utilisateur de saisir le nombre d’itérations souhaité.

Exemples :

5
10
20
30
50

La limite absolue backend est :

MAX_ITERATIONS = 50

Cette limite doit être contrôlée côté backend.

Ne jamais faire confiance uniquement à :

<input max="50">

Si le frontend envoie :

1000

le backend doit refuser.

Prévoir une validation propre et un message compréhensible.

======================================================================
10. DÉFINITION D’UNE ITÉRATION
    ======================================================================

Définir précisément ce que représente une itération dans le code et
l’interface.

Une itération correspond à une réponse du Coach associée à une version
précise du prompt.

Exemple :

Iteration 1
Prompt V0
Coach Response R0
Controller Feedback B0

Agent A produit ensuite V1.

Iteration 2
Prompt V1
Coach Response R1
Controller Feedback B1

etc.

Éviter toute ambiguïté de numérotation.

======================================================================
11. STOP
    ======================================================================

Ajouter un bouton :

STOP

Le STOP doit être gracieux.

Ne pas essayer d’interrompre brutalement un appel LLM déjà lancé.

Si l’utilisateur clique STOP pendant qu’un appel Coach est en cours :

1. enregistrer STOP_REQUESTED ;
2. laisser l’appel courant se terminer ;
3. récupérer la réponse ;
4. sauvegarder la réponse ;
5. ne pas démarrer l’étape automatique suivante ;
6. passer la campagne à PAUSED.

Exemple :

RUNNING
|
| utilisateur clique STOP
v
STOP_REQUESTED
|
| réponse IA en cours se termine
v
SAVE RESPONSE
|
v
PAUSED

IMPORTANT :

L’utilisateur doit toujours pouvoir consulter la dernière réponse complète.

======================================================================
12. AJOUTER UN AVIS HUMAIN PENDANT LA PAUSE
    ======================================================================

Lorsque la campagne est PAUSED :

afficher un bouton :

"Ajouter mon avis"

Permettre à l’utilisateur de saisir un commentaire libre.

Exemple :

"La réponse est devenue plus professionnelle, mais trop longue.
Conserver l’explication sur l’épargne et réduire la présentation
des produits."

Sauvegarder ce feedback avec :

- campaignId ;
- iterationId ;
- source = HUMAN ;
- timestamp ;
- contenu ;
- priorité.

Lorsque l’utilisateur clique ensuite REPRENDRE :

Agent A doit recevoir :

- le dernier prompt ;
- le dernier feedback Agent B disponible ;
- le feedback humain ;
- éventuellement le contexte utile.

Le feedback humain doit avoir priorité.

Agent A génère alors le prochain prompt.

Le Coach est rejoué.

À partir de la nouvelle réponse :

Agent B reprend normalement la main.

Donc :

PAUSE
|
v
HUMAN FEEDBACK
|
v
AGENT A
|
v
NEW PROMPT
|
v
COACH
|
v
AGENT B
|
v
AGENT A
|
+---- boucle normale

======================================================================
13. REPRENDRE
    ======================================================================

Ajouter :

REPRENDRE

Disponible lorsque la campagne est PAUSED.

La reprise doit continuer la campagne existante.

Elle ne doit PAS :

- recréer un snapshot ;
- recharger les données financières actuelles ;
- recharger silencieusement un nouveau catalogue ;
- revenir au prompt de production ;
- perdre l’historique ;
- recommencer le compteur.

Elle reprend exactement à partir de l’état sauvegardé.

======================================================================
14. ÉTATS DE CAMPAGNE
    ======================================================================

Prévoir une machine d’état claire.

Minimum recommandé :

CREATED
RUNNING
STOP_REQUESTED
PAUSED
COMPLETED
ACCEPTED
REJECTED
ERROR

Éventuellement ajouter :

CANCELLED

si pertinent.

Ne pas utiliser plusieurs booléens incohérents du type :

isRunning
isStopped
isFinished
isPaused

si une enum métier claire peut éviter les états impossibles.

======================================================================
15. HISTORIQUE COMPLET
    ======================================================================

Chaque itération doit être persistée.

Pour chaque itération, conserver au minimum :

- numéro ;
- timestamp début ;
- timestamp fin ;
- promptVersion ;
- editableSection ;
- prompt complet reconstruit ou possibilité de le reconstruire ;
- hash ;
- réponse Coach ;
- feedback Agent B ;
- feedback humain éventuel ;
- résumé des modifications Agent A ;
- statut ;
- erreurs éventuelles ;
- provider/model ;
- usage tokens si disponible ;
- durée des appels si disponible.

Ne jamais écraser V1 avec V2.

Toutes les versions doivent rester consultables.

======================================================================
16. VERSION RETENUE / FAVORITE
    ======================================================================

Chaque itération doit avoir un bouton du type :

"Retenir cette version"

ou :

"Marquer comme candidate"

Éviter de nommer ce bouton simplement "Sauvegarder", car toutes les versions
doivent déjà être sauvegardées automatiquement.

Une campagne peut avoir plusieurs versions retenues.

Exemple :

V0
V1
V2
V3  ★ RETENUE
V4
V5
V6  ★ RETENUE
V7
...
V10

La dernière version n’est PAS nécessairement la meilleure.

L’utilisateur doit pouvoir revenir sur V3 même si la campagne est arrivée V10.

======================================================================
17. PROMOTION D’UN PROMPT
    ======================================================================

Aucune version candidate ne doit devenir automatiquement le prompt actif.

Prévoir une action humaine explicite :

"Utiliser cette version comme nouveau prompt"

ou :

"Promouvoir cette version"

Avant promotion :

afficher une confirmation.

Exemple :

"Vous êtes sur le point de remplacer le prompt actuel de l’agent
Crédit par la version V8 issue de cette campagne.

Le prompt actuel sera conservé dans l’historique.

Continuer ?"

ANNULER
CONFIRMER

Préserver la version précédente pour rollback.

Si l’application possède déjà un mécanisme de versioning des agents/prompts,
le réutiliser.

Ne pas créer une seconde architecture concurrente inutilement.

======================================================================
18. COMPARAISON INITIALE / FINALE
    ======================================================================

À la fin de la campagne, proposer une vue de comparaison.

Minimum :

VERSION INITIALE

Prompt V0
Réponse R0


VERSION FINALE

Prompt VN
Réponse RN

Afficher également les versions marquées comme retenues.

Permettre d’ouvrir chaque version.

======================================================================
19. DIFF DE PROMPT
    ======================================================================

Si raisonnablement simple avec les bibliothèques existantes, implémenter
un diff entre deux editableSections successives.

Exemple :

V5 -> V6

- Présente toutes les solutions disponibles.
+ Présente uniquement les solutions réellement pertinentes pour
+ la situation et le projet fournis.

- Explique chaque solution.
+ Explique les différences utiles sans répéter les informations
+ déjà données.

Il n’est PAS nécessaire de créer un moteur sophistiqué.

Un diff texte standard ligne par ligne est suffisant.

Le diff doit porter prioritairement sur la zone éditable.

Si cette fonctionnalité complique excessivement le POC, la mettre en P1,
mais préparer le modèle de données pour permettre son ajout.

======================================================================
20. IHM — PAGE PRINCIPALE
    ======================================================================

Créer une page dédiée.

Nom possible :

Atelier d’amélioration des prompts

ou :

Prompt Optimization Lab

La page doit être cohérente avec le design existant.

Ne pas transformer cette page en interface excessivement technique.

Elle est destinée à un administrateur / responsable du Coach.

======================================================================
21. IHM — CONFIGURATION
    ======================================================================

En haut :

AGENT À OPTIMISER

<select>

Exemple :

Agent générique
Crédit consommation
Crédit immobilier
Épargne
Assurance auto
Assurance habitation
...

Afficher le prompt actuellement actif.

Bouton :

"Voir le prompt actuel"

Afficher clairement :

Prompt actuel : Vxx

Zone modifiable détectée :

[[[ ... ]]]

======================================================================
22. IHM — QUESTION
    ======================================================================

Ajouter un textarea :

"Question de test"

Exemple :

"Je souhaite financer une voiture d’occasion à 15 000 €.
Quelles solutions pourraient être adaptées à ma situation ?"

La question est obligatoire.

Une fois la campagne démarrée :

elle devient figée pour cette campagne.

======================================================================
23. IHM — NOMBRE D’ITÉRATIONS
    ======================================================================

Ajouter :

Nombre d’itérations

<input type="number">

min = 1
max affiché = 50

Backend :

MAX = 50

Afficher :

Maximum : 50

======================================================================
24. IHM — GO
    ======================================================================

Bouton :

GO

ou :

Démarrer l’optimisation

Au clic :

- validation ;
- création campagne ;
- capture snapshot ;
- première réponse ;
- démarrage boucle.

Désactiver les contrôles de configuration qui ne doivent plus changer pendant
la campagne.

======================================================================
25. IHM — SNAPSHOT
    ======================================================================

Après démarrage, afficher un bloc compact :

"Snapshot de référence créé"

Exemple :

🔒 Question                         figée
🔒 Données financières              figées
🔒 Contexte                         figé
🔒 Produits compatibles             figés
🔒 Données catalogue utilisées      figées
🔒 Prompt hors [[[ ... ]]]          figé

Seule la zone [[[ ... ]]] évolue.

Ajouter :

"Voir le snapshot"

Ce bouton peut ouvrir une modal / drawer.

Ne pas surcharger l’écran principal avec toutes les données.

======================================================================
26. IHM — PROGRESSION
    ======================================================================

Afficher clairement :

État : EN COURS

Itération :

6 / 10

Réalisées :

6

Restantes :

4

Ajouter une progress bar.

Exemple :

████████████████░░░░░░ 6 / 10

IMPORTANT :

Le calcul restant doit être fait à partir des itérations effectivement
terminées, pas simplement lancées.

======================================================================
27. IHM — CONTRÔLES
    ======================================================================

RUNNING :

[ STOP ]

PAUSED :

[ AJOUTER MON AVIS ]
[ REPRENDRE ]

COMPLETED :

[ COMPARER ]
[ AJOUTER MON AVIS ET CONTINUER ]
[ RETENIR / PROMOUVOIR UNE VERSION ]

Adapter les boutons aux états.

Ne jamais afficher REPRENDRE pendant RUNNING.

Ne jamais permettre GO sur une campagne déjà en cours.

======================================================================
28. IHM — LISTE DES ITÉRATIONS
    ======================================================================

Afficher toutes les réponses Coach.

Les plus récentes peuvent être affichées en premier si cela améliore l’UX,
mais conserver le numéro chronologique.

Exemple :

--------------------------------------------------

ITÉRATION 6
Prompt V5
22:32:45

Réponse du Coach

[contenu complet rendu en Markdown]

Agent B
"1 point d’amélioration"

[ Voir le prompt ]
[ Voir l’analyse Agent B ]
[ Voir les changements V4 -> V5 ]
[ ★ Retenir cette version ]

--------------------------------------------------

ITÉRATION 5
Prompt V4

Réponse du Coach
...

--------------------------------------------------

Ne jamais afficher uniquement la dernière réponse.

L’utilisateur doit pouvoir suivre l’évolution.

======================================================================
29. IHM — VISUALISER LE PROMPT
    ======================================================================

Pour CHAQUE itération :

bouton :

"Voir le prompt"

Afficher :

- version ;
- fixedPrefix éventuellement replié ;
- editableSection clairement mise en évidence ;
- fixedSuffix éventuellement replié.

La zone :

[[[
...
]]]

doit être visuellement identifiable.

Idéalement :

PARTIE PROTÉGÉE
ZONE MODIFIABLE
PARTIE PROTÉGÉE

======================================================================
30. IHM — ANALYSE AGENT B
    ======================================================================

Pour chaque itération :

"Voir l’analyse Agent B"

Afficher au minimum :

- statut ;
- résumé ;
- points positifs ;
- problèmes ;
- sévérité ;
- comportement attendu ;
- éléments à préserver ;
- recommandation transmise à Agent A.

Exemple :

POINTS SATISFAISANTS

✓ projet correctement compris
✓ produit correctement utilisé
✓ ton professionnel

POINTS À AMÉLIORER

⚠ réponse trop longue
⚠ justification financière insuffisante

À PRÉSERVER

✓ pédagogie
✓ absence d’invention

Cela est important pour rendre l’évolution du prompt explicable.

======================================================================
31. IHM — FEEDBACK HUMAIN
    ======================================================================

En PAUSED :

ouvrir une modal / zone :

"Votre avis"

Textarea libre.

Exemple :

"Le résultat est meilleur mais le Coach insiste trop sur les risques.
Je souhaite conserver l’avertissement mais en une seule phrase."

Bouton :

"Enregistrer mon avis"

Afficher ensuite dans l’historique :

AVIS HUMAIN

avec distinction visuelle claire par rapport à :

AVIS AGENT B

======================================================================
32. IHM — CAMPAGNE TERMINÉE
    ======================================================================

À N itérations :

status = COMPLETED

Afficher :

Campagne terminée

N / N itérations réalisées.

Puis :

VERSION INITIALE
V0
Réponse R0

VS

VERSION FINALE
VN
Réponse RN

Ajouter :

"Comparer"

Afficher les versions retenues.

Exemple :

Versions retenues :

★ V3
★ V7

L’utilisateur doit pouvoir :

- accepter/promouvoir une version ;
- ne rien faire ;
- refuser la campagne ;
- ajouter un feedback humain ;
- poursuivre l’optimisation.

======================================================================
33. CONTINUER APRÈS LA FIN
    ======================================================================

L’utilisateur peut décider :

"Ajouter mon avis et continuer"

Exemple :

Campagne initiale :
10 itérations terminées.

L’utilisateur ajoute :

"La réponse est bonne mais je veux une formulation plus concise."

Il choisit éventuellement un nouveau nombre d’itérations :

10

Le système repart :

V10
+
feedback humain
|
v
Agent A
|
v
V11
|
v
Coach
|
v
R11
|
v
Agent B
|
v
...

jusqu’à V20.

IMPORTANT :

La limite 50 doit être clairement définie.

Choisir et documenter si elle représente :

A. maximum par cycle ;

ou

B. maximum cumulé d’une campagne.

RECOMMANDATION :

utiliser 50 comme maximum CUMULÉ par campagne afin d’éviter qu’un utilisateur
enchaîne indéfiniment des cycles de 50.

Si le besoin futur nécessite davantage, créer une nouvelle campagne.

======================================================================
34. COÛTS / USAGE
    ======================================================================

Une itération peut générer plusieurs appels :

- Coach ;
- Agent B ;
- Agent A.

Donc 50 itérations peuvent produire environ 150 appels ou plus selon
l’implémentation.

Si les providers exposent les informations, sauvegarder :

- nombre d’appels IA ;
- input tokens ;
- output tokens ;
- total tokens ;
- durée ;
- coût estimé si disponible.

Afficher de manière discrète :

Itérations : 7 / 20
Appels IA : 21
Tokens : 84 320
Durée : 04:17

Ne pas rendre cette partie bloquante si certains providers ne fournissent
pas les métriques.

======================================================================
35. ERREURS
    ======================================================================

Gérer proprement :

- erreur Coach ;
- erreur Agent A ;
- erreur Agent B ;
- timeout ;
- réponse invalide JSON d’un agent interne ;
- prompt sans marqueurs ;
- marqueurs multiples ou mal fermés ;
- modification d’une zone immutable ;
- snapshot impossible ;
- provider indisponible ;
- limite d’itérations dépassée.

Ne jamais perdre les itérations déjà terminées.

Si erreur :

status = ERROR

Afficher :

- étape ayant échoué ;
- message exploitable ;
- dernière itération valide.

Prévoir selon le cas :

"Réessayer"

ou :

"Reprendre depuis la dernière étape valide"

sans recommencer toute la campagne.

======================================================================
36. CONCURRENCE
    ======================================================================

Éviter que deux traitements de la même campagne tournent simultanément.

Par exemple :

double clic sur REPRENDRE
+
requête réseau répétée

ne doit pas lancer deux V8 en parallèle.

Prévoir un verrou logique / contrôle d’état / idempotence adapté à
l’architecture existante.

======================================================================
37. PERSISTANCE
    ======================================================================

Respecter l’architecture existante du POC.

Si le projet utilise actuellement JSON / JSONL et pas de base de données,
ne pas introduire une base uniquement pour ce module sans nécessité.

Créer une structure cohérente avec l’existant.

Exemple conceptuel :

data/
prompt-optimization/
campaigns/
<campaignId>/
campaign.json
snapshot.json
iterations.jsonl
feedback.jsonl

ou une structure équivalente mieux adaptée au code existant.

L’objectif est :

- simplicité ;
- auditabilité ;
- reprise après redémarrage ;
- historique complet.

Utiliser des écritures atomiques lorsque nécessaire pour les fichiers
d’état importants.

======================================================================
38. MODÈLE DE CAMPAGNE — EXEMPLE CONCEPTUEL
    ======================================================================

{
"campaignId": "prompt-opt-...",
"agentId": "credit_conso",
"status": "RUNNING",

"question": "...",

"requestedIterations": 10,
"completedIterations": 4,
"remainingIterations": 6,
"maxIterations": 50,

"snapshotId": "...",

"basePromptVersion": "V12",
"currentCandidateVersion": "V16",

"createdAt": "...",
"updatedAt": "...",

"retainedVersions": [
"V14"
]
}

Adapter aux conventions Java existantes.

======================================================================
39. MODÈLE D’ITÉRATION — EXEMPLE
    ======================================================================

{
"iterationId": "...",
"campaignId": "...",
"iterationNumber": 4,

"promptVersion": "V15",

"editableSection": "...",

"promptHash": "...",

"coachResponse": "...",

"controllerFeedback": {
"...": "..."
},

"humanFeedback": null,

"editorChangeSummary": "...",

"startedAt": "...",
"completedAt": "...",

"status": "COMPLETED"
}

======================================================================
40. PROMPT SYSTÈME — AGENT A
    ======================================================================

Créer un prompt dédié, versionné dans le système existant.

Exemple de base à adapter :

Tu es l’éditeur de prompts du Coach Financier.

Ta mission est d’améliorer une zone précise du prompt d’un agent métier
à partir des défauts constatés lors de tests.

Tu ne réponds jamais au client.

Tu ne joues pas le rôle du Coach.

Tu modifies uniquement les instructions du Coach.

Tu reçois :

- la question testée ;
- la zone éditable actuelle ;
- le feedback du contrôleur ;
- éventuellement un feedback humain ;
- les éléments à préserver.

OBJECTIF :

produire une nouvelle zone éditable permettant au Coach de mieux répondre
dans les prochaines exécutions.

RÈGLES :

1. Ne modifie que ce qui est nécessaire.
2. Préserve les comportements explicitement considérés comme corrects.
3. Ne supprime pas une instruction utile sans raison.
4. Ne contourne jamais les règles métier.
5. Ne cherche pas à résoudre uniquement la formulation exacte de la question
   testée par une règle ultra-spécifique.
6. Cherche une amélioration généralisable du comportement de l’agent.
7. Évite d’ajouter des exemples trop spécifiques au cas testé lorsqu’ils
   pourraient créer du sur-apprentissage du prompt.
8. Si un feedback humain existe, il est prioritaire sur le feedback du
   contrôleur en cas de contradiction.
9. N’invente aucune nouvelle règle bancaire.
10. Ne transforme pas une observation ponctuelle en règle absolue sans
    justification.
11. Ne modifie jamais les zones protégées.
12. Ne cherche pas à produire la réponse client.
13. Retourne uniquement la nouvelle zone éditable et les métadonnées
    demandées.

SORTIE STRUCTURÉE :

{
"editableSection": "...",
"changeSummary": [
"..."
],
"feedbackAddressed": [
"..."
],
"preservedBehaviors": [
"..."
]
}

======================================================================
41. PROMPT SYSTÈME — AGENT B
    ======================================================================

Créer un second prompt dédié.

Exemple de base :

Tu es le contrôleur qualité d’un Coach Financier IA.

Tu ne modifies jamais le prompt.

Tu analyses la qualité de la réponse produite par le Coach par rapport :

- à la question posée ;
- au contexte fourni ;
- aux données disponibles ;
- aux instructions applicables ;
- au comportement professionnel attendu.

Tu dois identifier précisément :

- ce qui est satisfaisant ;
- ce qui doit être amélioré ;
- ce qui manque ;
- ce qui est inutile ;
- les répétitions ;
- les mauvaises interprétations ;
- les problèmes de pédagogie ;
- les problèmes de pertinence ;
- les comportements à préserver.

Ne cherche pas artificiellement un défaut si la réponse est bonne.

Ne demande pas à Agent A de changer quelque chose uniquement pour produire
une nouvelle version.

Si la réponse est déjà très satisfaisante, indique-le clairement.

Ne propose pas toi-même un nouveau prompt complet.

Produis un diagnostic exploitable par Agent A.

SORTIE STRUCTURÉE :

{
"status": "GOOD | NEEDS_IMPROVEMENT | BAD",
"summary": "...",
"positivePoints": [],
"issues": [
{
"type": "...",
"severity": "LOW | MEDIUM | HIGH",
"observation": "...",
"expectedBehavior": "..."
}
],
"mustPreserve": [],
"recommendationForPromptEditor": "..."
}

======================================================================
42. IMPORTANT — ÉVITER UNE DÉRIVE A ↔ B
    ======================================================================

Agent A et Agent B ne doivent pas modifier le prompt simplement parce qu’une
itération supplémentaire existe.

Agent B peut répondre :

GOOD

avec :

issues = []

Dans ce cas, définir le comportement.

RECOMMANDATION :

Agent A peut conserver la zone éditable à l’identique si aucun changement
n’est justifié.

Une itération ne doit pas obligatoirement produire une modification.

Cela évite :

V7 bonne
→ B invente un problème
→ A change
→ V8 moins bonne
→ B change d’avis
→ A rechangе
→ dérive inutile.

======================================================================
43. RÈGLES D’AUTORITÉ
    ======================================================================

Ordre d’autorité :

1. règles backend / règles métier déterministes ;
2. zones protégées du prompt ;
3. décision humaine ;
4. feedback humain ;
5. Agent B ;
6. Agent A.

Agent A ne doit jamais pouvoir outrepasser les niveaux supérieurs.

======================================================================
44. SÉCURITÉ MÉTIER
    ======================================================================

Ce module améliore les prompts.

Il ne doit pas transférer aux prompts des responsabilités actuellement
détenues par le backend.

Conserver le principe architectural existant :

LLM = comprendre / expliquer
Java = décider / filtrer / calculer lorsque cela relève du déterministe.

Par exemple :

Agent A ne doit pas transformer le prompt en moteur de compatibilité produit
si la compatibilité est déjà déterminée par Java.

Les règles déterministes restent déterministes.

======================================================================
45. NON-RÉGRESSION — PRÉPARER L’ARCHITECTURE
    ======================================================================

La première version peut travailler sur une question active.

Cependant préparer le modèle pour permettre ultérieurement une campagne
avec plusieurs questions/scénarios.

Objectif futur :

Question Q1
Question Q2
Question Q3
...

Chaque question peut avoir son propre snapshot.

Une nouvelle version du prompt pourra alors être évaluée sur plusieurs
scénarios afin d’éviter :

"améliorer Q1 mais casser Q2".

Ne pas obligatoirement implémenter tout ce système multi-scenarios en V1
si cela augmente fortement le scope.

Mais éviter une architecture qui empêcherait son ajout.

======================================================================
46. TESTS BACKEND OBLIGATOIRES
    ======================================================================

Ajouter des tests au minimum pour :

- nombre d’itérations = 1 ;
- nombre = 50 ;
- nombre = 51 refusé ;
- nombre = 0 refusé ;
- question vide refusée ;
- prompt sans [[[ ]]] refusé ;
- zone protégée modifiée refusée ;
- Agent A modifie uniquement editableSection accepté ;
- STOP pendant appel ;
- STOP après réponse ;
- passage STOP_REQUESTED -> PAUSED ;
- REPRENDRE depuis PAUSED ;
- feedback humain transmis à Agent A ;
- compteur restant correct ;
- fin de campagne ;
- conservation de toutes les versions ;
- marquage d’une version retenue ;
- promotion uniquement sur action humaine ;
- double REPRENDRE ne lance pas deux itérations ;
- erreur Agent A ;
- erreur Agent B ;
- erreur Coach ;
- reprise après erreur si prévue ;
- snapshot inchangé entre V0 et VN.

======================================================================
47. TESTS FRONTEND
    ======================================================================

Vérifier au minimum :

- saisie agent ;
- saisie question ;
- nombre itérations ;
- limite visible 50 ;
- GO ;
- affichage progression ;
- affichage total/restant ;
- STOP ;
- état "arrêt demandé" ;
- PAUSED ;
- ajout feedback humain ;
- REPRENDRE ;
- historique des réponses ;
- visualisation prompt de chaque itération ;
- visualisation feedback B ;
- version retenue ;
- comparaison initial/final ;
- erreurs ;
- boutons correctement activés/désactivés selon état.

======================================================================
48. EXPÉRIENCE UTILISATEUR
    ======================================================================

L’utilisateur doit pouvoir comprendre en quelques secondes :

- quel agent il optimise ;
- quelle question est testée ;
- combien d’itérations sont prévues ;
- où en est la campagne ;
- quelle réponse vient d’être générée ;
- ce qu’Agent B en pense ;
- ce qu’Agent A a changé ;
- quel prompt a produit chaque réponse ;
- quelles versions il a retenues ;
- s’il peut arrêter ;
- s’il peut reprendre ;
- comment injecter son propre avis ;
- quelle version est actuellement en production ;
- quelle version est seulement candidate.

Ne pas exposer inutilement les détails techniques internes sur l’écran
principal.

Utiliser modals/drawers/accordéons pour les détails.

======================================================================
49. POINT CRITIQUE — PROMPT PROD VS PROMPT CANDIDAT
    ======================================================================

Toujours afficher clairement la distinction :

PROMPT ACTUEL / PRODUCTION
V12

CAMPAGNE
Base : V12
Candidat courant : V18

Le prompt V18 ne doit PAS être utilisé par les conversations normales tant
qu’un humain ne l’a pas explicitement promu.

======================================================================
50. OBJECTIF FINAL
    ======================================================================

Le résultat attendu n’est pas simplement une page qui appelle plusieurs fois
un LLM.

Nous voulons un véritable atelier d’amélioration contrôlée des agents IA :

OBSERVATION
|
v
CONTRÔLE AGENT B
|
v
AMÉLIORATION AGENT A
|
v
REPLAY DANS LES MÊMES CONDITIONS
|
v
NOUVEAU CONTRÔLE
|
v
ITÉRATIONS
|
+---- possibilité STOP
|          |
|          v
|     FEEDBACK HUMAIN
|          |
|          v
+------ REPRISE
|
v
COMPARAISON
|
v
DÉCISION HUMAINE
|
+--> accepter/promouvoir
+--> retenir sans promouvoir
+--> refuser
+--> commenter et continuer


Principes fondamentaux :

- reproductibilité ;
- contexte figé ;
- une zone de prompt explicitement modifiable ;
- protection backend des zones immuables ;
- séparation stricte Agent A / Agent B ;
- feedback humain prioritaire ;
- historique complet ;
- arrêt gracieux ;
- reprise ;
- limite backend de 50 itérations cumulées par campagne ;
- aucune modification automatique de production ;
- validation humaine finale ;
- conservation des anciennes versions ;
- architecture extensible aux autres agents métier.

======================================================================
51. AVANT DE CODER
    ======================================================================

Avant toute modification :

1. analyser l’architecture actuelle du projet ;
2. identifier comment les agents et prompts sont actuellement stockés ;
3. identifier comment le Coach construit actuellement son appel LLM ;
4. identifier les services existants de provider IA ;
5. identifier comment les données financières et produits sont injectés ;
6. identifier les mécanismes existants de logs/versioning ;
7. identifier les conventions frontend ;
8. réutiliser au maximum les composants/services existants ;
9. ne pas dupliquer inutilement la logique d’appel du Coach ;
10. proposer le plan des fichiers/classes/endpoints à créer ou modifier.

Ensuite implémenter par étapes.

Ne pas réécrire l’architecture existante si une extension propre suffit.

À la fin, fournir :

- liste des fichiers créés ;
- liste des fichiers modifiés ;
- architecture retenue ;
- endpoints ajoutés ;
- modèles ajoutés ;
- machine d’état ;
- format de persistance ;
- prompts Agent A et Agent B ;
- règles de snapshot ;
- règles STOP/REPRENDRE ;
- règles de promotion ;
- tests ajoutés ;
- éventuelles limites restantes ;
- procédure manuelle permettant de tester de bout en bout une campagne
  sur l’agent Crédit.