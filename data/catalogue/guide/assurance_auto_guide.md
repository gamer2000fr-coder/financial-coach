# Guide IA — Assurance Auto SG

## Structure

Le JSON contient un objet `produit` avec plusieurs `formules`, puis des `reductions` et une `assistance_generale`.

Les caractéristiques d'une formule et les caractéristiques générales du produit doivent être combinées sans supposer qu'une option est incluse lorsqu'elle est seulement listée comme option.

## Formules

Chaque formule possède :
- `id`, `nom` ;
- `niveau_protection` lorsqu'il est fourni ;
- `tarif_indicatif_mensuel` ;
- `garanties` ;
- `assistance` ;
- `options` ;
- `regles_recommandation`.

Le niveau de protection sert à ordonner les niveaux décrits, mais ne remplace pas la comparaison des garanties.

## Tarif

`tarif_indicatif_mensuel.a_partir_de` est un prix mensuel indicatif à partir duquel la formule est présentée. Ce n'est pas un devis personnalisé ni un tarif garanti.

Les prix des `options` sont également indicatifs.

## Garanties

La liste `garanties` décrit les garanties incluses dans la formule selon le catalogue.

Ne pas confondre :
- une garantie incluse dans `garanties` ;
- une garantie/service dans `options` ;
- un élément dans `avantages`.

Une option ne doit pas être présentée comme incluse de base.

## Assistance

- `accident_km: 0` : assistance accident indiquée à partir de 0 km.
- `panne_km: 25` : seuil de panne indiqué.
- certaines formules ajoutent des services (`remise_route_rapide`, `service_voiture_a_domicile`).

`assistance_generale` contient des caractéristiques communes du produit : disponibilité, objectif de dépannage, réseau de garages, etc.

## Indemnisation

`minimum_indemnisation` et l'objet `indemnisation` décrivent les niveaux indiqués. `valeur_a_neuf_jusqua_mois` doit être interprété comme une durée maximale indiquée et non comme une garantie universelle hors conditions contractuelles.

## Leasing

La formule `Leasing` possède une `cible` explicite (`vehicule_leasing`, `loa`, `lld`) et une `priorite_tres_elevee_si`. Pour un véhicule en LOA/LLD, elle doit donc être examinée en priorité.

## Réductions

Chaque entrée de `reductions` contient un identifiant, un pourcentage et des `conditions`.

Une réduction ne doit être appliquée que si ses conditions sont satisfaites. Le fichier ne dit pas nécessairement que plusieurs réductions sont cumulables : ne pas supposer le cumul.

`reduction_pct_maximum` signifie réduction maximale et non réduction systématique.

## Recommandation

Comparer notamment :
- budget ;
- âge et valeur du véhicule ;
- véhicule neuf/récent/ancien ;
- besoin de couverture des dommages propres ;
- leasing ;
- garanties souhaitées ;
- besoin d'assistance ;
- options souhaitées.

Les règles `priorite_si` sont des signaux de pertinence, pas des critères d'éligibilité.

Ne pas sélectionner automatiquement la formule la plus protectrice : la recommandation doit tenir compte du besoin et du budget.
