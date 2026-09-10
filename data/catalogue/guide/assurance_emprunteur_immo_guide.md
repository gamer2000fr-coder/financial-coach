# Guide IA — Assurance Emprunteur Immobilier SG

## Structure

Le JSON utilise `produits` pour un objet produit unique. Il contient :
- objectifs ;
- obligation ;
- tarification ;
- formules ;
- dictionnaire des garanties ;
- quotité ;
- délégation d'assurance ;
- souscription ;
- règles de recommandation.

## Obligation

Le bloc `obligation` distingue explicitement :
- `obligatoire_legalement: false` ;
- `generalement_exigee_par_sg_pour_octroi_credit: true`.

Ces deux affirmations ne sont pas contradictoires. L'IA doit préserver cette distinction et ne pas dire simplement « l'assurance est légalement obligatoire ».

## Tarification

- `cotisation_constante: true` : caractéristique indiquée.
- `tarif_personnalise: true` : ne jamais inventer un tarif générique.

## Formules et cibles

Les formules distinguent :
- résidence principale ou secondaire ;
- investissement locatif ;
- senior ;
- BFM.

`cible` oriente vers la formule correspondant au projet/profil.

`eligibilite_indicative` est volontairement qualifiée d'indicative : l'âge fourni doit servir à orienter/vérifier, pas à inventer des conditions supplémentaires.

## Garanties

Le dictionnaire global `garanties` définit les sigles et certaines descriptions :
- décès ;
- PTIA ;
- ITT ;
- ITP ;
- IPT ;
- IPP ;
- aide à la famille ;
- perte d'emploi.

Une formule peut utiliser `garanties` ou `garanties_principales`. Respecter cette différence de libellé.

La formule résidence principale/secondaire liste davantage de garanties ; investissement locatif et senior listent comme garanties principales décès et PTIA dans ce catalogue.

Pour BFM, les garanties sont séparées selon `residence_principale` et `investissement_locatif_ou_senior`.

## Options

`perte_emploi` apparaît dans `options` pour la formule résidence principale/secondaire et le dictionnaire global la marque comme optionnelle. Elle ne doit pas être présentée comme incluse automatiquement.

## Quotité

`quotite.description` définit la quotité comme le pourcentage du capital assuré pour chaque emprunteur.

Les blocs `exemple_couple` et `couverture_renforcee_possible` sont des exemples/configurations possibles. Ne pas supposer qu'une répartition 50/50 ou 100/100 convient automatiquement à tous les dossiers.

## Délégation d'assurance

Le catalogue indique :
- délégation possible ;
- changement en cours de prêt possible ;
- condition d'équivalence des garanties aux exigences SG ;
- délai de réponse indiqué en jours ouvrés.

L'IA doit présenter la condition d'équivalence lorsqu'elle évoque la délégation.

## Souscription

`souscription` indique les possibilités en ligne/signature électronique et l'existence de formalités médicales si nécessaire. Ne pas affirmer qu'aucune formalité médicale ne sera demandée.

## Règles de recommandation

Les règles globales sont conditionnelles :
- crédit résidence principale → proposer la formule correspondante ;
- investissement locatif → proposer la formule correspondante ;
- âge 65+ → vérifier l'offre senior ;
- fonctionnaire → vérifier l'assurance BFM.

Le verbe `verifier` est important : il ne signifie pas que le client est automatiquement éligible.

## Recommandation

Pour choisir une formule, déterminer au minimum :
1. usage du bien financé ;
2. profil senior ou non selon les informations disponibles ;
3. appartenance éventuelle au profil BFM ;
4. garanties nécessaires/exigées ;
5. quotité souhaitée ou à étudier ;
6. besoin éventuel d'option perte d'emploi.

Ne jamais inventer un tarif ou une garantie absente de la formule.
