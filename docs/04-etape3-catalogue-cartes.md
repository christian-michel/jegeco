# Étape 3 — Catalogue de cartes (matières premières → produits transformés)

> ## ⚠️ MISE À JOUR — Mécanique simplifiée
> Le contenu ci-dessous décrit la **première version** du système de cartes (5
> constituants différents par produit). Elle a été **remplacée par une version plus
> simple**, alignée sur les règles officielles du jeu :
>
> - **1 seule illustration par carte**, 5 exemplaires identiques en circulation.
> - **4 cartes identiques** (pas 4 constituants différents) forment un carré.
> - Un carré rapporte 1 carte tirée au hasard dans **la couleur immédiatement
>   supérieure** (Primaire → Secondaire → Électronique → Informatique).
> - Un carré en Informatique déclenche la **révolution économique** : les cartes
>   Primaire deviennent alors plus précieuses que les cartes Informatique (mécanisme
>   `XTECHNOLOGICAL_BREAKTHROUGH` déjà présent dans le moteur).
>
> Voir **`etape3-assets/geconomicus_cartes_v2_simplifiees.xlsx`** pour la nouvelle
> liste (26 objets × 4 secteurs, thématique par ère historique : matières premières →
> révolution industrielle → électricité → informatique) et ses prompts
> d'illustration. Le reste de ce document (mécanique du carré à 5 constituants,
> `prompts-illustrations.csv`, `cards-data.js`, `card-render.html`) est conservé
> pour historique mais **ne reflète plus le système actuel**.

Ce document consigne la conception du système de cartes numériques destiné à
l'étape 3 (jeu pair-à-pair par smartphone/QR code). **Rien de ce qui suit n'est
encore implémenté** : c'est une spécification, volontairement tenue à l'écart du
code de l'étape 2 (tableau de bord animateur), pour ne pas mélanger les deux
systèmes tant qu'ils n'ont pas vocation à cohabiter.

## Principe du jeu de cartes (résumé)

- **4 secteurs (couleurs)**, formant une chaîne de valeur croissante :
  1. 🟡 **Jaune — Primaire** : matières premières → produits transformés (alimentaire notamment).
  2. 🔵 **Bleu — Secondaire** : matériaux/composants industriels → produits manufacturés.
  3. 🟢 **Vert — Électronique** : composants électroniques → objets/systèmes électroniques.
  4. 🔴 **Rouge — Informatique** : briques logicielles/données → produits numériques.
- Chaque secteur contient **26 produits transformés** (un par lettre, A à Z), et
  chaque produit possède **5 cartes "constituant"** distinctes (ex : pour "Pizza" →
  Blé, Tomate, Cochon, Vache, Olivier). Chaque carte affiche en grand le produit
  transformé (ex. l'illustration d'une pizza) et, dans les coins en diagonale, le
  constituant propre à cette carte (icône + nom).
- **Mécanique du carré** : 5 cartes sont mises en circulation par produit (pour
  fluidifier les échanges entre joueurs), mais **4 suffisent** pour constituer un
  carré. Dès qu'un joueur réunit 4 des 5 constituants d'un même produit :
  1. il obtient **une carte du secteur immédiatement supérieur**, tirée
     aléatoirement parmi les cartes de ce secteur encore disponibles (jaune → bleu
     → vert → rouge) ;
  2. les 4 cartes ayant servi au carré **retournent en circulation** (remises dans
     la pioche/le jeu, disponibles pour d'autres joueurs).
- C'est une variante du mécanisme "carré" des règles officielles du jeu
  (https://geconomicus.glibre.org/rules.html), où l'on assemble normalement 4
  cartes **identiques** pour monter d'un palier de valeur. Ici, ce sont 4 cartes
  **différentes mais complémentaires** (les constituants d'un même produit) qui
  jouent ce rôle.

## Correspondance avec le moteur existant

Le moteur (`geco-engine`) modélise déjà une notion de paliers de valeur multipliée
par 2 à chaque niveau : `weakCards` (×1), `mediumCards` (×2), `strongCards` (×4),
utilisée dans le calcul de richesse (voir `StatsService.addGain`, porté de
`StatsFrame.java`). Les 4 secteurs de ce catalogue s'alignent naturellement sur ce
principe, à condition d'ajouter **un 4ᵉ palier** :

| Secteur | Palier actuel du moteur | Multiplicateur envisagé |
|---|---|---|
| 🟡 Primaire | `weakCards` | ×1 |
| 🔵 Secondaire | `mediumCards` | ×2 |
| 🟢 Électronique | `strongCards` *(actuel)* → à renommer/étendre | ×4 |
| 🔴 Informatique | **nouveau palier à ajouter** | ×8 |

## Contenu du catalogue fourni

Fichier source : `geconomicus_26_cartes_4_secteurs.xlsx` (une feuille par secteur,
26 lignes = 26 produits, 5 constituants chacun).

Volume d'illustrations distinctes nécessaires (produits + constituants, en
comptant une seule fois les constituants qui reviennent d'un produit à l'autre au
sein d'un même secteur, ex. "Blé" apparaît dans Pizza, Pain, Bière...) :

| Secteur | Produits | Constituants uniques | Illustrations distinctes |
|---|---|---|---|
| Jaune — Primaire | 26 | 66 | 92 |
| Bleu — Secondaire | 26 | 41 | 67 |
| Vert — Électronique | 26 | 56 | 82 |
| Rouge — Informatique | 26 | 83 | 109 |
| **Total** | **104** | — | **~350** |

Le README interne du fichier précise que certaines associations sont
volontairement ludiques plutôt que des recettes industrielles strictes, et
pourront être rééquilibrées selon la rareté/valeur/cohérence pédagogique
recherchées.

## Décisions déjà actées

- **Périmètre** : ce système est réservé à l'étape 3 (inventaire numérique par
  joueur, échanges pair-à-pair par QR code) et ne modifie pas le tableau de bord
  animateur de l'étape 2.
- **Mécanique de carré** : 4 cartes parmi 5 constituants disponibles suffisent ;
  récompense = 1 carte aléatoire du secteur supérieur ; les 4 cartes utilisées
  retournent en circulation.
- **Sous-ensemble par partie** : l'animateur choisit, à la création de la partie,
  quels produits (parmi les 26 par secteur) sont inclus — pas nécessairement tous.
  Implication pour le modèle de données : une partie doit stocker sa propre
  sélection de produits actifs, distincte du catalogue complet.
- **Pioche** : limitée, comme un vrai jeu de cartes physiques — chaque carte
  n'existe qu'en un nombre fini d'exemplaires, qui s'épuise au fil de la partie.
  Implication : il faut modéliser un stock/pioche par carte (nombre d'exemplaires
  restants), décrémenté à chaque tirage lors d'un carré, potentiellement à plat
  (stock épuisé = plus aucune carte de ce type disponible pour cette partie).
- **Révolution économique (palier final rouge) — DÉCISION ROUVERTE ET
  RÉIMPLÉMENTÉE le 22/09/2026** : la décision d'origine ci-dessous (simple
  réutilisation cosmétique de `XTECHNOLOGICAL_BREAKTHROUGH`, sans vrai
  réordonnancement) a été explicitement rouverte par l'utilisateur : "quand on
  arrive à faire un carré de cartes très fortes... il peut être intéressant de
  mettre en place une rotation des valeurs, d'autant que cela est conforme à
  la règle du jeu" - suite au bug du même jour ("les carrés s'emballent et ne
  se comptent pas") où un carré au niveau maximum restait bloqué à vie faute
  de niveau supérieur. Implémenté pour de vrai cette fois (voir
  `Game.revolutionCount`/`cardPriceInDU`, `GameService.checkAndCashInSquares`) :
  - Le tirage physique des cartes reste dans l'ordre fixe
    faible→moyenne→forte→tresforte, mais boucle désormais de tresforte vers
    faible au lieu d'être un cul-de-sac - un carré au niveau maximum n'est
    plus jamais bloqué, quel que soit le nombre de tours joués.
  - Chaque carré RÉELLEMENT bouclé depuis tresforte (jamais le repli "aucun
    autre modèle disponible") déclenche une révolution : `revolutionCount`
    augmente de 1, et le PRIX de chaque niveau tourne d'un cran (jamais
    l'ordre de tirage lui-même) - après une révolution, "faible" prend le
    prix qu'avait "tresforte" (le plus cher), et ainsi de suite ; après 4
    révolutions (autant que de niveaux), le barème revient exactement à la
    normale (cycle fermé).
  - Garde-fou ajouté suite à un test réel : avec un seul modèle "tresforte"
    en circulation, les 4 cartes de remplacement retombent nécessairement sur
    ce même modèle, reformant IMMÉDIATEMENT un nouveau carré identique dans
    le MÊME appel (confirmé : 23 révolutions d'un coup dans un scénario
    synthétique) - au plus UNE révolution est désormais traitée par appel à
    `checkAndCashInSquares`, une éventuelle reformation immédiate étant
    laissée pour l'appel suivant (le prochain achat/échange), jamais bloquant
    mais jamais une rafale illisible pour les joueurs.
  - Portée volontairement limitée à la monnaie LIBRE (déjà un prix dynamique,
    en DU) - la dette garde son barème fixe en jetons (`LEVEL_JETON_PRICE`,
    inchangé, plusieurs commentaires existants disaient déjà "jamais changé"),
    le troc n'a de toute façon aucun prix. Le bouclage physique du carré
    lui-même (jamais bloqué), en revanche, bénéficie aux TROIS systèmes -
    `checkAndCashInSquares` reste agnostique du système monétaire, comme
    documenté plus haut.
  - `XTECHNOLOGICAL_BREAKTHROUGH`/`currentFactor *= 2` (StatsService, graphique
    de richesse) reste INCHANGÉ et se déclenche toujours UNE SEULE fois par
    partie (jamais à chaque révolution) - volontairement DÉCOUPLÉ de la
    rotation des valeurs, pour ne pas faire croître le graphique de richesse
    de façon exponentielle et non maîtrisée sur une partie longue.
- **Illustrations** : production via ChatGPT, par lots de 6 (une famille de
  produit à la fois : 1 produit + ses 4-5 constituants). Gabarit d'assemblage
  prêt et testé (`etape3-assets/`, voir ci-dessous) : dynamique, il suffit de
  déposer les fichiers image au bon nom pour qu'ils s'intègrent automatiquement.

## Gabarit d'assemblage des cartes

Voir `etape3-assets/card-render.html` (+ `etape3-assets/cards-data.js`) : gabarit
HTML/CSS/JS **piloté par les données**. Pour ajouter un lot d'illustrations reçu :
1. Déposer les fichiers image dans `etape3-assets/illustrations/`, avec le nom de
   fichier exact indiqué dans `prompts-illustrations.csv` (colonne `nom_fichier`).
2. Ouvrir `card-render.html` dans un navigateur : les cartes correspondantes
   s'assemblent automatiquement (fond du secteur, cercle central, coins
   diagonaux). Les illustrations pas encore fournies affichent un pictogramme de
   remplacement, sans bloquer l'affichage des autres cartes.
Aucune modification de code nécessaire à chaque nouveau lot reçu.
