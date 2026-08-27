# Guide : créer son propre système d'échange (plugin)

Ce guide explique concrètement comment ajouter un nouveau système d'échange à
Ğeconomicus Helper, à partir de l'exemple réel du troc (le seul système ajouté
depuis l'architecture à plugins, en dehors des deux fournis par défaut). Il
répond à trois questions : quels points relier, comment personnaliser ses
propres règles, et ce qu'il en est des échanges depuis les smartphones des
joueurs.

**Pas de captures d'écran dans ce guide** : je n'ai pas de navigateur pour
faire tourner l'application et en capturer des écrans réels - les extraits de
code ci-dessous, tous tirés du troc (donc testés), servent d'illustration à
la place.

## ⚠️ À savoir avant de commencer : un manifeste seul ne suffit pas

C'est le point le plus important de tout ce guide. Le contrat déclaratif
(`docs/11-plugin-api-contrat.md`) décrit un format JSON pur, sans code - c'est
un choix assumé pour la sécurité (voir l'audit sécurité et la décision sur
l'upload). Mais **dans l'état actuel du moteur, ce fichier JSON n'est pas
encore interprété automatiquement** : il ne fait que déclarer des métadonnées
(nom, description, configuration proposée) et être validé au chargement.

Concrètement, déposer un `manifest.json` dans `plugins/mon-systeme/` suffit à
faire apparaître le système dans l'écran Paramètres et sur "Nouvelle partie",
avec le badge "pas encore jouable" (`engineReady: false`) - mais **pour qu'une
partie soit réellement jouable, il faut aussi écrire du code Java et
JavaScript**, exactement comme pour le troc. Ce guide couvre donc deux
niveaux, à ne pas confondre :

| Niveau | Ce qu'il faut | Résultat |
|---|---|---|
| 1. Déclaratif | Un `manifest.json` | Visible dans Paramètres/Nouvelle partie, sélectionnable pour test, **pas jouable** |
| 2. Fonctionnel | Niveau 1 + code Java/JS | Réellement jouable, `engineReady: true` |

Un futur chantier (évoqué mais pas commencé) consisterait à faire lire le
manifeste par un interpréteur générique côté moteur, pour qu'un système
"simple" (sans mécanique radicalement nouvelle) n'ait plus besoin de code du
tout - voir la section "Limites actuelles et pistes" en fin de document.

## Niveau 1 : le manifeste (toujours nécessaire)

Le format complet est documenté dans `docs/11-plugin-api-contrat.md` - ce
guide n'en refait pas la liste exhaustive, seulement un résumé pratique.

### Où le placer

```
plugins/
└── mon-systeme/
    └── manifest.json
```

Chargé automatiquement au démarrage du serveur depuis le dossier `plugins/`
à la racine du projet (voir `PluginRegistry.loadAll()`, appelé dans
`GecoServer.main()`). Aucun redémarrage à chaud pour l'instant : il faut
relancer `run.sh` après avoir ajouté ou modifié un manifeste.

### Champs obligatoires (sinon le plugin est ignoré, voir plus bas)

- `id` : minuscules/chiffres/tirets, doit commencer par une lettre (ex.
  `"troc"`, `"marche-local"`).
- `apiVersion` : doit valoir `1` (seule version supportée).
- `displayName.fr` : le nom affiché, au moins en français.
- `wizardSteps` : un tableau non vide (même minimal).
- `wealthFormula` : chaque système doit pouvoir produire un nombre de
  "richesse" par joueur, sinon les statistiques générales et "Comparer des
  parties" ne peuvent pas fonctionner pour lui.

### Ce qui se passe si le manifeste est invalide

Rien de grave : le plugin est simplement ignoré, avec le motif écrit dans les
logs du serveur au démarrage (voir `PluginRegistry.validate()`) :

```
Plugins charges : 3
  - dette (plugins/dette)
  - libre (plugins/libre)
  - troc (plugins/troc)
  ! Plugin "mon-systeme" ignoré - "wealthFormula" manquant : chaque système doit pouvoir produire une richesse comparable (voir le contrat).
```

Un plugin mal écrit n'empêche jamais le reste de l'application de démarrer -
vérifiez toujours cette sortie après avoir ajouté ou modifié un manifeste.

### Exemple minimal (un système fictif "don" - à titre d'illustration uniquement)

```json
{
  "id": "don",
  "apiVersion": 1,
  "displayName": { "fr": "Don", "en": "Gift economy" },
  "shortDescription": { "fr": "Chacun donne sans attendre de retour direct.", "en": "..." },
  "hasBank": false,
  "hasMoneyMass": false,
  "configFields": [
    { "key": "startingGoods", "type": "integer", "default": 4, "min": 1,
      "label": { "fr": "Cartes de départ", "en": "Starting cards" } }
  ],
  "eventTypes": [],
  "wealthFormula": { "field": "goodsCount" },
  "wizardSteps": [
    { "block": "death-selection" },
    { "block": "death-inventory", "fields": ["goodsCount"] },
    { "block": "turn-summary" }
  ]
}
```

Ce fichier seul suffit à faire apparaître "Don" dans Paramètres et sur
"Nouvelle partie", grisé, avec le badge "pas encore jouable" - exactement
comme le troc à ses débuts.

### Bonus (toujours niveau 1, aucun code) : le fragment de documentation

Si votre manifeste déclare un champ `documentation` (voir
`docs/11-plugin-api-contrat.md`), déposez simplement les fichiers annoncés à
côté du manifeste :

```
plugins/
└── mon-systeme/
    ├── manifest.json
    └── docs/
        ├── regles.fr.html
        └── regles.en.html
```

Ce sont de simples **fragments** HTML (pas de page complète avec
`<html>/<head>/<body>` - juste des titres, paragraphes, listes...), servis
automatiquement par `GET /api/plugins/{id}/docs/{lang}` (voir les fragments
déjà écrits pour dette/libre/troc dans `plugins/*/docs/` comme modèle) - et
affichés automatiquement dans l'écran "Documentation" en jeu dès qu'une
partie utilisant votre système est ouverte. **Aucun code à écrire pour ça** -
contrairement à tout ce qui suit dans ce guide.

## Niveau 2 : rendre le système réellement jouable

C'est la partie qui demande du code. Voici, dans l'ordre où ça a été fait pour
le troc, chaque point à relier - avec, pour chacun, l'extrait réel du troc en
exemple.

### 1. `geco-engine/Game.java` : la constante du système + sa configuration

```java
// Dans les constantes de système monétaire
public final static int MONEY_TROC = 2;

// Champs de configuration propres au système (avec leur valeur par défaut)
private int startingGoods = 4;
// + le getter/setter habituel
```

**Point à bien relier** : la constante doit être un entier qui n'existe pas
déjà (`MONEY_LIBRE=0`, `MONEY_DEBT=1` sont pris). Le champ `moneySystem` de
`Game` reste un simple `int` plutôt que l'id du plugin (`String`) pour
l'instant - voir "Limites actuelles" plus bas.

### 2. `geco-engine/Player.java` : l'état propre à chaque joueur

```java
private int goodsCount;

// Dans le constructeur, initialisé depuis la config de la partie :
goodsCount = pGame.getStartingGoods();

// + getter/setter habituels
```

**Point à bien relier** : si votre système a besoin de plusieurs compteurs
par joueur (comme la première version du troc avait `goodsCount` +
`timeTokens`), ajoutez-les tous ici, initialisés dans le même constructeur.

### 3. `geco-engine/Event.java` : le(s) type(s) d'événement

```java
GOODS_TRADE(Messages.getString("BaseMessage.Event.GoodsTrade"));
```

**Point à bien relier, et piège classique** : `EventTypeConverter` déduit
automatiquement le code stocké en base à partir de la **première lettre** du
nom de l'enum - deux types ne peuvent donc jamais commencer par la même
lettre, tous systèmes confondus. Les lettres suivantes sont déjà prises :
`J T N I R C B P Q M E D X S A G`. Choisissez un nom d'enum dont la première
lettre est libre (c'est exactement pourquoi le troc s'appelle `GOODS_TRADE`
et pas `TRADE_GOODS`, qui aurait collisionné avec `TURN`). Si vous avez
besoin de plusieurs types, vérifiez qu'aucun d'eux ne collisionne ni entre
eux, ni avec l'existant.

Ajoutez aussi la traduction du libellé dans les deux fichiers
`geco-engine/src/main/resources/jyt/geconomicus/helper/messages*.properties`
(ce libellé sert de repli si jamais le front n'a pas sa propre traduction).

Si votre système implique un second joueur par événement (un échange, comme
en troc), réutilisez le champ `counterpartyPlayer` déjà présent sur `Event`
plutôt que d'en ajouter un autre - il est prévu pour ça.

### 4. `geco-engine/Event.java` : la logique d'application (`applyEvent()`)

C'est le cœur du moteur : chaque type d'événement doit avoir son `case` dans
le grand `switch` de `applyEvent()`.

```java
case GOODS_TRADE:
    player.setGoodsCount(player.getGoodsCount() - goodsFromPlayer + goodsFromCounterparty);
    counterpartyPlayer.setGoodsCount(counterpartyPlayer.getGoodsCount() - goodsFromCounterparty + goodsFromPlayer);
    break;
```

**Points à bien relier** :
- Le `case DEATH` (partagé par tous les systèmes) doit avoir une branche pour
  le vôtre, qui réinitialise l'état du joueur à sa dotation de renaissance :
  ```java
  if ((game.getMoneySystem() == Game.MONEY_TROC) && EventType.DEATH.equals(evt))
      player.setGoodsCount(game.getStartingGoods());
  ```
- Le `case TURN` (aussi partagé) est l'endroit où renouveler un état qui ne
  se reporte jamais d'un tour à l'autre (c'était le cas des jetons de temps
  du troc, retirés depuis, mais le mécanisme reste disponible si un futur
  système en a besoin).
- **N'oubliez jamais la mort/renaissance et la sortie de fin de partie** :
  c'est un invariant de comparabilité imposé par l'utilisateur (voir
  `docs/10-etape-plugins-troc.md`) - tous les systèmes doivent avoir le même
  nombre de tours et les mêmes règles de mort/renaissance pour que les
  comparer entre eux ait un sens.

### 5. `geco-server/Dtos.java` : exposer le nouvel état à l'interface

Trois DTOs à étendre selon ce que vous avez ajouté :
- `PlayerDto` : ajoutez vos nouveaux champs (ex. `goodsCount`).
- `EventDto`/`RecordEventRequest` : ajoutez les champs nécessaires pour
  transmettre les détails de vos nouveaux événements (ex.
  `goodsFromPlayer`/`goodsFromCounterparty`).
- `CreateGameRequest`/`GameDetailDto` : si votre système a des champs de
  configuration (ex. `startingGoods`), ajoutez-les ici aussi, dans les deux
  sens (requête de création + détail renvoyé).

### 6. `geco-server/GameService.java` : `createGame()` et `recordEvent()`

```java
// Dans createGame() : accepter et appliquer le champ de config
if (pStartingGoods > 0)
    game.setStartingGoods(pStartingGoods);
```

```java
// Dans recordEvent() : résoudre le second joueur, AVEC LA MÊME VÉRIFICATION
// DE PROPRIÉTÉ que le premier (voir l'audit sécurité - IDOR) :
Player counterpartyPlayer = null;
if (pCounterpartyPlayerId != null)
{
    counterpartyPlayer = em.find(Player.class, pCounterpartyPlayerId);
    if ((counterpartyPlayer == null) || !counterpartyPlayer.getGame().equals(game))
        throw new PlayerNotFoundException(String.valueOf(pCounterpartyPlayerId));
}
```

**Point à bien relier, non négociable** : tout identifiant de joueur reçu de
l'extérieur (`playerId`, `counterpartyPlayerId`, ou tout autre que vous
ajouteriez) **doit** être vérifié comme appartenant à la partie de l'URL,
avec `!player.getGame().equals(game)`. Sans ça, vous réintroduisez la faille
IDOR corrigée lors de l'audit sécurité (voir plus haut dans cette
conversation) - n'importe qui pourrait manipuler un joueur d'une autre
partie en devinant un identifiant.

Si votre système a des contraintes métier (comme "un fournisseur ne peut pas
dépenser plus de jetons qu'il n'en a", pour le troc avec services), validez-
les ici, **avant** d'enregistrer l'événement - `recordEvent()` doit rejeter
clairement la requête (`IllegalArgumentException`), pas laisser
`applyEvent()` produire un état incohérent.

### 7. `geco-server/StatsService.java` : la formule de richesse

```java
private int computeGain(final Game pGame, final Event pEvent, final int pCurrentFactor)
{
    if (pGame.getMoneySystem() == Game.MONEY_TROC)
        return pEvent.getGoodsFromPlayer();
    // ... les autres systèmes
}
```

C'est la fonction appelée à chaque `DEATH`/`QUIT` pour capturer la richesse
d'un joueur au moment où il sort de la partie. Si votre système a des
statistiques propres (comme le nombre d'échanges du troc), ajoutez une
méthode dédiée (voir `computeTrocStats()`) et un champ dans `FinalReport`
pour l'exposer - vide pour tous les autres systèmes.

### 8. `geco-server/GecoServer.java` : câbler les nouveaux paramètres

Mettez à jour les deux appels (`createGame(...)`, `recordEvent(...)`) pour
transmettre les nouveaux champs des DTOs vers `GameService`. C'est
mécanique, mais facile à oublier un paramètre - le compilateur vous le
signalera si la signature ne correspond plus.

### 9. Front (`index.html` + `app.js`) : tableau de bord, échanges, assistant

C'est la partie la plus longue, mais qui suit toujours le même schéma pour
chaque écran concerné : ajouter une condition `isMonSysteme = game.moneySystem === N`
à côté des `isDebt`/`isTroc` déjà là, et brancher un cas de plus.

- **Tableau de bord** (`renderGameDetail`) : cartes statistiques à afficher/
  masquer selon votre système (voir les blocs `el("statXxxCard").classList.toggle(...)`),
  méta affichée par ligne de joueur, boutons d'action pertinents (masquer
  ceux qui n'ont pas de sens, comme "Investissement banque" pour un système
  sans banque).
- **Formulaire d'échange** (si votre système en a un) : sur le modèle
  d'`openTrocTradeDialog()` - ouvre un formulaire, appelle
  `Api.recordEvent()` avec le type et les champs voulus, valide côté client
  ce qui peut l'être (mais **ne remplace jamais** la validation côté
  serveur, qui reste la seule qui compte vraiment).
- **Assistant de fin de tour** : au minimum, une étape de sélection des
  morts (`death-selection`, déjà partagée, rien à coder) suivie d'une étape
  d'inventaire adaptée à votre système (sur le modèle de
  `renderStepDeathTroc()`), avant `renderStep4()` (récap final, partagé).
  Si votre système a des actions qui n'ont de sens qu'en plein tour (comme
  les échanges du troc), elles n'ont pas besoin d'être dans l'assistant du
  tout - elles peuvent vivre comme un bouton du tableau de bord.
- **"Nouvelle partie"** : si votre manifeste déclare des `configFields`, ils
  sont déjà lus pour pré-remplir le facteur carte/monnaie par défaut (voir
  `findConfigFieldDefault()`) - mais s'ils ont besoin d'un champ dédié dans
  le formulaire (comme les options du troc avant leur retrait), il faut
  l'ajouter à la main dans `index.html` et le montrer/masquer dans
  `selectMoneyChoice()`.

### 10. Traductions (i18n)

Chaque nouveau texte visible (libellés, messages, titres) a besoin d'une clé
dans `lang/fr.po` **et** `lang/en.po`, synchronisées. Après chaque lot de
modifications, exécutez ce script (déjà utilisé tout au long du projet) pour
vérifier qu'aucune clé n'est référencée sans être définie :

```bash
python3 - <<'EOF'
import re
html = open("index.html", encoding="utf-8").read()
js = open("js/app.js", encoding="utf-8").read()
html_keys = set(re.findall(r'data-i18n(?:-html|-placeholder|-title)?="([^"]+)"', html))
js_keys = set(re.findall(r'\bt\(\s*"([^"]+)"', js))
js_keys |= set(re.findall(r'GecoI18n\.t\(\s*"([^"]+)"', js))
all_keys = html_keys | js_keys
existing = set(re.findall(r'msgid "((?:[^"\\]|\\.)*)"', open("lang/fr.po", encoding="utf-8").read()))
print("Missing:", sorted(all_keys - existing))
EOF
```

### 11. Activer le système une fois tout vérifié

Dernière étape, seulement quand tout ce qui précède fonctionne :

```java
// PluginRegistry.java
private static final List<String> ENGINE_READY_IDS = List.of("dette", "libre", "troc", "mon-systeme");
```

C'est ce qui fait passer le badge "pas encore jouable" à un vrai bouton
"Créer la partie" actif sur l'écran "Nouvelle partie".

## Checklist condensée

- [ ] `manifest.json` valide (voir les logs au démarrage)
- [ ] `Game.MONEY_XXX` (constante inutilisée) + champs de config
- [ ] `Player` : champs d'état, initialisés dans le constructeur
- [ ] `Event` : type(s) d'événement, première lettre libre, traductions FR/EN
- [ ] `Event.applyEvent()` : nouveaux cas + branches DEATH/TURN génériques
- [ ] `Dtos.java` : PlayerDto/EventDto/RecordEventRequest/CreateGameRequest/GameDetailDto étendus
- [ ] `GameService.createGame()`/`recordEvent()` : nouveaux champs + **vérification de propriété sur tout ID de joueur reçu**
- [ ] `StatsService` : formule de richesse (`computeGain`) + stats propres si besoin
- [ ] `GecoServer.java` : les deux appels mis à jour
- [ ] Front : tableau de bord, formulaire(s) d'échange, assistant (mort/renaissance + inventaire + récap)
- [ ] i18n : clés ajoutées dans fr.po ET en.po, script de vérification exécuté
- [ ] `ENGINE_READY_IDS` mis à jour en dernier, une fois tout testé

## Interactions avec les smartphones des joueurs (étape 3)

Honnêteté d'abord : **l'étape 3 n'est pas construite**. Ce qui existe
aujourd'hui (`join.html`, `js/player.js`, l'écran "Connexion joueurs" avec
QR code) ne couvre que **l'auto-inscription** d'un joueur depuis son
téléphone (nom, âge, couleur, avatar) - rien côté échanges. Un joueur ne peut
pas, à ce jour, initier ou confirmer un échange depuis son téléphone.

Ce qui est déjà anticipé dans le contrat de plugin (`docs/11`, section
"Anticiper l'étape 3"), pour que votre système survive à ce chantier sans
devoir être repensé :

- Le champ `roles` de chaque `eventType` (ex. `["initiator", "counterparty"]`
  pour le troc) identifie déjà clairement qui est qui dans un échange - la
  structure est prête pour un flux "les deux téléphones confirment", même si
  ce flux n'existe pas encore.
- Si votre système a des variantes de nature d'échange (comme les
  "catégories" de troc évoquées au tout début - vêtements, livres...),
  pensez-les comme un simple champ `category` sur l'`eventType` plutôt que
  des types distincts, pour rester compatible avec cette anticipation.

**Ce que ça veut dire concrètement pour vous aujourd'hui** : concevez vos
règles indépendamment de qui les saisit (l'animateur depuis le tableau de
bord, ou plus tard un joueur depuis son téléphone) - toute la logique de
validation doit vivre côté serveur (`GameService`), jamais seulement côté
client, précisément pour rester valable le jour où un smartphone appellera
directement `recordEvent()` sans passer par l'interface de l'animateur.

## Limites actuelles et pistes pour aller plus loin

À garder en tête si vous envisagez de créer plusieurs systèmes, ou d'ouvrir
ça à la communauté :

- **Le moteur ne lit pas encore le manifeste** : toute la logique (niveau 2)
  est dupliquée en code Java/JS pour chaque système, plutôt que d'être
  interprétée génériquement depuis `eventTypes`/`wizardSteps`. Un système
  "simple" (qui ne fait que recomposer les briques déjà existantes -
  transactions, inventaire, mort/renaissance) pourrait en théorie être
  entièrement piloté par son manifeste, sans code - mais cette
  généralisation n'a pas encore été construite.
- **Les codes d'événements à une lettre** (`EventTypeConverter`) sont une
  vraie limite de passage à l'échelle : avec suffisamment de systèmes, les
  lettres disponibles finiront par manquer. À revoir si des plugins
  communautaires se multiplient.
- **`Game.moneySystem` reste un `int`**, pas l'id du plugin (`String`) - un
  choix délibéré pour limiter le risque sur dette/libre en production, mais
  qui empêchera un plugin tiers de fonctionner sans modifier ce champ un
  jour.
