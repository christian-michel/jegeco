# Contrat de plugin "système d'échange" (v1, déclaratif)

Ce document définit ce qu'un plugin doit déclarer pour ajouter un nouveau
système d'échange (monnaie dette, monnaie libre, troc, ou un futur système créé
par la communauté), **sans jamais exécuter de code fourni par le plugin** -
tout est décrit en JSON, interprété par un moteur fixe et commun à
l'application. C'est la conséquence directe du choix fait lors de l'audit
sécurité : on garde l'app ouverte (pas d'authentification), donc on ne peut
pas se permettre d'exécuter du code non maîtrisé venu d'un fichier uploadé.

Ce contrat n'est pas encore branché au moteur existant (`Game.java`,
`Event.java`, l'assistant dans `app.js`) - c'est la prochaine étape. Ce
document sert de référence pendant ce travail, et de point de départ pour
documenter, plus tard, comment la communauté pourra créer ses propres plugins.

## Vue d'ensemble d'un manifeste

Un plugin = un fichier `manifest.json` (+ éventuellement des fragments de
documentation HTML). À terme (upload par `.zip` depuis l'écran Paramètres) :

```
mon-plugin.zip
├── manifest.json      (obligatoire - ce document en définit le format)
├── docs/
│   ├── regles.fr.html (optionnel)
│   └── regles.en.html (optionnel)
└── icon.svg           (optionnel)
```

Aucun `.jar`, aucun script exécutable : le `.zip` ne contient que des données
(JSON, HTML statique, image). Le serveur validera strictement le manifeste
contre un schéma avant d'accepter le plugin, et rejettera tout fichier qui ne
correspond pas à ces trois types.

**Point de sécurité à trancher plus tard** : si l'upload de plugin passe par
une route HTTP du serveur web, n'importe quel appareil du réseau local pourrait
en théorie uploader un plugin (cohérent avec le choix "pas d'authentification"
déjà acté, mais à garder en tête) - même un plugin déclaratif malveillant
pourrait tenter d'afficher des libellés trompeurs. Une option plus prudente :
réserver l'upload à un dépôt de fichiers sur le disque de la machine qui
héberge la partie (pas une route réseau), lu au démarrage du serveur. À
décider quand on abordera l'upload concrètement.

## Champs du manifeste

### Métadonnées

```json
{
  "id": "troc",
  "apiVersion": 1,
  "displayName": { "fr": "Troc", "en": "Barter" },
  "shortDescription": { "fr": "Échange direct de biens et de services, sans monnaie.", "en": "..." },
  "hasBank": false,
  "hasMoneyMass": false
}
```

- `id` : identifiant unique, utilisé partout ailleurs dans la partie (en base,
  dans les clés i18n générées, etc.). Doit rester stable une fois publié.
- `hasBank` / `hasMoneyMass` : indicateurs pour piloter l'affichage de blocs
  d'interface communs (bouton "Bilan banque", carte statistique "Masse
  monétaire"...) - un plugin sans banque ni masse monétaire (le troc) fait
  simplement disparaître ces blocs, plutôt que de devoir les redéclarer.

### Champs de configuration ("Nouvelle partie")

```json
"configFields": [
  { "key": "startingGoods", "type": "integer", "default": 4, "min": 1,
    "label": { "fr": "Cartes de départ", "en": "Starting cards" } },
  { "key": "startingTimeTokens", "type": "integer", "default": 4, "min": 0,
    "label": { "fr": "Jetons de temps de départ", "en": "Starting time tokens" } }
]
```

Chaque champ devient un input numérique généré automatiquement sur l'écran
"Nouvelle partie", sans code JS/HTML à écrire côté plugin.

### Vocabulaire d'événements

Chaque plugin peut définir ses propres types d'événements, en plus du socle
commun obligatoire (`JOIN`, `TURN`, `DEATH`, `QUIT`, `END`, `WEALTH_CHECKPOINT`
- gérés par le moteur central, jamais par un plugin, pour garantir les
invariants de comparabilité : mêmes tours, mêmes morts/renaissances partout).

`WEALTH_CHECKPOINT` (ajouté à l'étape 3, voir plus bas) : un pur no-op côté
moteur (aucun effet sur la masse monétaire ni les cartes) - juste un point de
mesure de la richesse d'un joueur À UN INSTANT DONNÉ, sans qu'il s'agisse
d'une mort/sortie. Utile à tout plugin qui veut une courbe de richesse en
continu plutôt que seulement échantillonnée aux morts (voir
`StatsService.computeWealthOverTime`, câblé pour la monnaie libre en premier
mais réutilisable tel quel par n'importe quel autre système).

```json
"eventTypes": [
  {
    "code": "TRADE_GOODS",
    "label": { "fr": "Échange bien contre bien", "en": "Goods-for-goods trade" },
    "roles": ["initiator", "counterparty"],
    "fields": [
      { "key": "goodsFromInitiator", "type": "integer", "min": 0 },
      { "key": "goodsFromCounterparty", "type": "integer", "min": 0 }
    ]
  },
  {
    "code": "TRADE_SERVICE",
    "label": { "fr": "Échange bien contre service", "en": "Goods-for-service trade" },
    "roles": ["provider", "beneficiary"],
    "fields": [
      { "key": "goodsFromBeneficiary", "type": "integer", "min": 0 },
      { "key": "timeTokensSpentByProvider", "type": "integer", "min": 0 }
    ]
  }
]
```

### État par joueur

```json
"playerState": {
  "goodsCount": { "type": "integer", "initial": "$config.startingGoods" },
  "timeTokens": { "type": "integer", "initial": "$config.startingTimeTokens",
                  "resetEachTurnTo": "$config.startingTimeTokens" }
}
```

`resetEachTurnTo` matérialise directement la règle "le temps ne se stocke
jamais" : le moteur commun réinitialise ce champ à chaque nouveau tour, pour
tous les joueurs actifs, sans qu'aucun code de plugin n'ait à s'en charger.

### Cycle mort/renaissance (obligatoire, mais paramétrable)

```json
"deathRebirth": {
  "inventoryFields": ["goodsCount"],
  "onRebirth": { "goodsCount": "$config.startingGoods", "timeTokens": "$config.startingTimeTokens" }
}
```

### Formules de statistiques (structurées, jamais du texte à interpréter)

Volontairement **pas** un mini-langage d'expressions à parser : chaque
formule est un petit objet JSON décrivant une agrégation simple
(somme/comptage avec filtre d'égalité), directement interprétable par un code
fixe côté serveur. Zéro chaîne de caractères à parser = zéro risque
d'injection, au prix d'un langage volontairement limité.

```json
"wealthFormula": { "field": "goodsCount" },

"extraStats": [
  {
    "key": "timeGiven",
    "label": { "fr": "Temps de vie donné", "en": "Life time given" },
    "aggregate": "sum",
    "eventType": "TRADE_SERVICE",
    "role": "provider",
    "field": "timeTokensSpentByProvider"
  },
  {
    "key": "timeReceived",
    "label": { "fr": "Temps de vie reçu", "en": "Life time received" },
    "aggregate": "sum",
    "eventType": "TRADE_SERVICE",
    "role": "beneficiary",
    "field": "timeTokensSpentByProvider"
  },
  {
    "key": "timeNet",
    "label": { "fr": "Solde net de temps", "en": "Net time balance" },
    "derived": "timeReceived - timeGiven"
  }
]
```

`derived` reste une expression, mais volontairement minuscule : uniquement
`clé - clé` ou `clé + clé` entre stats déjà déclarées juste au-dessus - pas un
langage général, juste de quoi combiner deux nombres déjà calculés.

### Étapes de l'assistant de fin de tour

Composées à partir d'une bibliothèque fixe de "blocs" déjà existants dans le
moteur (pas de blocs custom en v1) :

```json
"wizardSteps": [
  { "block": "death-selection" },
  { "block": "death-inventory", "fields": ["goodsCount"] },
  { "block": "rebirth-grant" },
  { "block": "transaction-form", "eventType": "TRADE_GOODS" },
  { "block": "transaction-form", "eventType": "TRADE_SERVICE" },
  { "block": "turn-summary" }
]
```

`transaction-form` est le bloc le plus important à généraliser : c'est
exactement la même forme que l'étape "nouveaux crédits" de la monnaie dette
(choisir un ou deux joueurs, saisir des montants, enregistrer un événement) -
un seul bloc générique, piloté par la déclaration `eventTypes` ci-dessus, sert
donc aussi bien le troc que la monnaie dette.

### Documentation (implémenté le 23/08/2026)

```json
"documentation": { "fr": "docs/regles.fr.html", "en": "docs/regles.en.html" }
```

Les chemins sont relatifs au dossier du plugin lui-même
(`plugins/<id>/docs/regles.<langue>.html`) - de simples **fragments** HTML
(pas de page complète avec `<html>/<head>/<body>`), destinés à être insérés
tels quels dans l'écran "Documentation" de l'application via `innerHTML`.

Servis par le serveur via `GET /api/plugins/{id}/docs/{lang}` (repli sur le
français si la langue demandée n'a pas de fragment dédié). L'écran
"Documentation" en jeu appelle cette route quand une partie est ouverte,
selon le système d'échange de cette partie précise - voir
`docs/12-guide-creer-systeme-echange.md` pour le détail et un exemple
d'implémentation.

**Point de sécurité pris en compte** : le paramètre `lang` de cette route
sert à construire un chemin de fichier sur le disque - il est donc validé
strictement (motif `[a-z]{2}(-[a-z]{2})?`) pour ne jamais laisser passer une
traversée de répertoire, même si un plugin tiers malveillant tentait
d'exploiter cette route avec un code de langue fabriqué à la main.

## Étape 3 (échanges depuis les smartphones) : anticipé, puis réellement construit

Cette section décrivait, avant l'étape 3, ce qu'il faudrait anticiper pour que
ce contrat survive à l'arrivée des échanges par smartphone. L'étape 3 est
maintenant bien avancée (28/08/2026) - voici ce qui s'est **réellement** passé,
pour que ce document reste un repère fiable plutôt qu'une prévision datée :

- **Les échanges sont bien initiés par les joueurs eux-mêmes**, comme anticipé
  - mais pas via "deux téléphones qui se synchronisent" en direct : un QR code
    à courte durée de vie (~90s), scanné par l'acheteur (caméra ou saisie
    manuelle du code), voir `TradeOfferService.java` côté serveur et
    `player-view.js` côté client. Chaque échange devient une `Transaction`
    (nouvelle entité `geco-engine`, PAS un `Event` classique - voir plus bas
    pourquoi) : vendeur, acheteur, tour, horodatage, type/niveau de carte,
    jetons échangés.
- **Statistiques rendues possibles, confirmées en usage** : qui échange avec
  qui, quand, pour combien - exactement ce qui était espéré. Voir l'écran de
  stats/historique (en cours de construction, voir `docs/13-*` une fois
  écrit) qui exploite directement cette table `Transaction`.
- **Point de conception qui a divergé de ce qui était imaginé ici** : les
  échanges smartphone n'ont **pas** été modélisés comme un nouvel `eventType`
  déclaré par un plugin (`TRADE_GOODS`/`TRADE_SERVICE` façon troc), mais comme
  une entité séparée (`Transaction`), volontairement DÉCOUPLÉE du journal
  d'événements classique (`Event`) - celui-ci reste réservé à ce qu'un
  animateur constate/valide (crédits, morts, tours), jamais à un mouvement
  déclenché en direct par un joueur sur son téléphone. Voir le commentaire de
  tête de `Transaction.java` pour le raisonnement complet. Un plugin qui
  voudrait un système d'échange smartphone différent du sien (troc, futur
  MDBC...) devra donc, pour l'instant, composer avec cette même table
  `Transaction` plutôt qu'avec le mécanisme `eventTypes` décrit plus haut dans
  ce document - une incohérence assumée, à résoudre si/quand ce contrat sera
  réellement branché au moteur (voir "Prochaine étape technique" ci-dessous,
  toujours pas commencée à ce jour).
- **Fusion avec l'économie "officielle" du jeu, en monnaie libre uniquement
  pour l'instant** : les jetons échangés par smartphone ne sont PAS une
  comptabilité parallèle indéfiniment - à chaque fin de tour, l'assistant
  (voir `openEndOfTurnWizard` dans `app.js`) récupère l'historique des
  transactions de chaque joueur et pose un `WEALTH_CHECKPOINT` (voir plus
  haut) après validation de l'animateur. Décision explicite de l'utilisateur
  (28/08/2026) : cette fusion reste à construire pour la monnaie dette (une
  prise de crédit initiée depuis le téléphone, validée par l'animateur/la
  banque) et pour le troc (échange carte-contre-carte, taux de 4 cartes d'un
  niveau = 1 carte du niveau supérieur, pas encore implémenté).
- **Nature/variantes d'échange, catégorie de bien** : toujours pas
  implémenté - le catalogue de cartes (voir `CatalogService.java`,
  `docs/etape3-*` s'ils existent) porte bien un champ `secteur` par carte,
  mais il n'est pour l'instant qu'une simple étiquette sans effet sur les
  règles d'échange.

## Prochaine étape technique

1. Écrire les manifestes `plugins/dette/manifest.json` et
   `plugins/libre/manifest.json` pour les deux systèmes existants, en
   vérifiant qu'ils rentrent bien dans ce contrat sans forcer (validation de
   l'abstraction avant de toucher au code de production).
2. Écrire `plugins/troc/manifest.json` en suivant exactement les règles
   validées dans `docs/10-etape-plugins-troc.md`.
3. Une fois les trois manifestes écrits et cohérents entre eux, commencer le
   refactoring du moteur (`Event.java`, `Game.java`, `StatsService.java`,
   l'assistant dans `app.js`) pour qu'il lise ces manifestes au lieu de
   raisonner en dur sur `MONEY_DEBT`/`MONEY_LIBRE`.
