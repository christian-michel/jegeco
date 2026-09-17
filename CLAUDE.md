# CLAUDE.md — Ğeconomicus Helper / GecoLab

Ce fichier est lu automatiquement par Claude Code à l'ouverture de ce dépôt.
Il complète, sans les remplacer, les documents suivants — à lire dans cet
ordre pour prendre en main le projet :

1. `docs/00-vue-ensemble.md` — comment est-ce que ça marche aujourd'hui, où
   regarder pour telle ou telle question.
2. `docs/13-etape3-etat-et-feuille-de-route.md` — ce qui est construit, ce
   qui reste à faire, l'état exact à la date de ce document.
3. `docs/03-architecture-technique.md` — journal chronologique de toutes les
   décisions techniques (le POURQUOI derrière un choix).
4. `CAHIER_DES_CHARGES_ETAPE3.md` — la vision et les objectifs d'origine de
   l'étape 3 (jeu sur smartphone) — toujours valable pour le PÉRIMÈTRE
   général, mais son état d'avancement est dépassé par le document 2
   ci-dessus.

## En une phrase

Refonte Java 21 + interface web (Javalin) d'un jeu pédagogique qui fait
vivre à ses joueurs l'équivalent d'une vie économique complète, une fois en
monnaie dette, une fois en monnaie libre, pour en comparer concrètement les
effets — à partir du projet original de jytou
(https://gitlab.com/jytou/geconomicus_helper).

- Règles officielles du jeu : https://geconomicus.glibre.org/rules.html et
  https://geconomicus.glibre.org/libre_money.html (monnaie libre en
  particulier — page moins visible mais essentielle).
- Théorie Relative de la Monnaie (TRM, fonde le calcul du DU en monnaie
  libre) : https://trm.creationmonetaire.info/

## Build, lancement, tests

```bash
./run.sh --rebuild     # force une recompilation complète puis lance le serveur web (port 7000)
./run.sh                # lance sans recompiler
./run.sh --classic      # ancienne interface Swing (conservée)
mvn clean package        # build seul, sans lancer
mvn test                 # suite de tests automatisés sur la logique métier (geco-engine)
```

Pas de build JS (pas de npm/webpack) : `app.js` (animateur) et
`player-view.js` (smartphones) sont du JavaScript natif, chargés tels
quels. Bibliothèques tierces vendorisées dans `js/vendor/` (pas de CDN —
l'app doit fonctionner sans connexion internet).

**Base de données** : H2 fichier unique (`~/geco.h2`), partagée entre web et
Swing.

**Différence importante avec le chat Claude.ai** : dans ce dépôt, il y a un
vrai compilateur et un vrai terminal disponibles. Les sessions de
développement précédentes (menées en chat, sans accès shell complet à
l'exécution du build) ont dû se contenter de vérifications de surface
(équilibre des accolades/parenthèses compté à la main, simulation de la
logique en Python avant de faire confiance au Java/JS réel, jamais de vrai
`mvn compile`). **Utilise le vrai build et les vrais tests ici** — c'est
plus fiable et normalement plus rapide que ces vérifications de substitution.

## Structure du dépôt

```
geco-engine/    moteur du jeu (Game/Player/Event, JPA/H2) — logique métier
                PURE, partagée par le web ET l'app Swing. C'est ici que
                vivent les RÈGLES DU JEU (voir Event.java, switch sur
                EventType).
geco-app/       ancienne interface Swing (conservée, --classic).
geco-server/    serveur web (Javalin) + front (public/).
  src/main/resources/public/
    index.html         écran animateur
    player-view.html    écran joueur (smartphone)
    js/app.js            logique animateur (voir son en-tête : carte des sections)
    js/player-view.js   logique smartphone joueur
    js/i18n.js           système de traduction .po, autonome
    js/tutorial.js       bouton d'aide "?", autonome
    js/vendor/           bibliothèques tierces vendorisées
    lang/fr.po, en.po    traductions — voir "Règle i18n" plus bas
plugins/        systèmes d'échange en plugins (dette intégrée au cœur,
                troc et libre ont leur propre dossier — voir
                docs/11-plugin-api-contrat.md pour le contrat à respecter
                si tu en ajoutes un nouveau)
docs/           documentation DE TRAVAIL (jamais servie aux utilisateurs
                finaux — distincte de public/docs/, la doc utilisateur
                intégrée à l'app)
```

## Les trois systèmes monétaires — et une distinction classique/smartphone qui s'applique désormais AUX TROIS

Depuis le 18/09/2026, les **trois** systèmes ont une variante "classique"
(animateur seul, sans smartphone, code ancien inchangé) et une variante
"smartphone" (chaque joueur a son téléphone) — **jamais l'une n'affecte
l'autre**, sauf demande explicite. Le discriminant qui fait foi n'est
JAMAIS le réglage global `AppSettings.gameMode` (qui a pu changer depuis la
création de la partie), mais toujours une donnée PROPRE à la partie/au
joueur : `Player.startingCardsJson != null` côté moteur
(`Event.isSmartphoneTrackedGame()`), `hasStartingAllocation` côté DTO.

- **Troc** : échange carte contre carte(s), **jamais de jetons ni de valeur
  monétaire, sur rien** — vrai dans les deux modes, c'est la seule règle
  intangible du troc (voir `docs/10-etape-plugins-troc.md`, règle 6).
  - *Classique* : échange bien-contre-bien négocié librement, enregistré
    par l'animateur (`GOODS_TRADE`, tableau de bord "Échange entre
    joueurs") — `Player.weakGoods/mediumGoods/strongGoods`.
  - *Smartphone* (construit le 18/09/2026) : échange DIRECT carte-contre-
    carte 1-pour-1 par QR (carte retournée → QR → bouton "Échanger" → scan
    de la carte de l'autre joueur) — voir `GameService.recordCardSwap` :
    même valeur obligatoire ET réciprocité bidirectionnelle (chacun possède
    déjà au moins un exemplaire du modèle qu'il va recevoir), sinon
    "Échange refusé". Détail complet dans `docs/10-etape-plugins-troc.md`.
- **Dette** : banque, crédits, intérêts.
  - *Classique* : cartes à prix FIXE en jetons faible/moyen/fort (voir
    `player-view.js`, `LEVEL_JETON_PRICE` — jamais changé pour ce mode).
  - *Smartphone* (construit le 17/09/2026) : "1 jeton faible = 1 unité
    monétaire", plus aucun jeton moyen/fort, prix fixe en jetons faibles
    uniquement (barème de valeur inchangé), `Player.jetonWeak` alimenté
    réellement par les crédits/remboursements/saisies (voir
    `Event.applyEvent()`, cas `NEW_CREDIT`/`REIMB_CREDIT`/`INTEREST_ONLY`/
    `BANKRUPT`/`PRISON`/`CANNOT_PAY`).
- **Libre** : Dividende Universel (DU) régulier à chaque joueur, sans dette.
  - *Classique* : l'animateur suit tout manuellement dans l'assistant de
    fin de tour — code plus ancien, largement inchangé depuis l'étape 2.
  - *Smartphone* (le premier des trois construit, cœur de l'étape 3) :
    jetons réels suivis avec précision
    (`Player.jetonWeak/jetonMedium/jetonStrong`), **seuls les jetons
    FAIBLES circulent réellement** (voir plus bas, "Le calcul du DU").

**Pioche/carré/promotion PARTAGÉE par les trois systèmes** (réponse
explicite à une question d'architecture posée par l'utilisateur le
17/09/2026, "est-il possible de mettre le système de gestion de la pioche
commun aux différents types de parties ? Ainsi, si je détecte un bug, je le
notifie et il sera pris en compte sur les trois systèmes en même temps.") :
`GameService.checkAndCashInSquares` (le mécanisme du carré) est entièrement
agnostique du système monétaire — seul `Game.smartphoneCardPileJson != null`
compte, jamais une liste de systèmes monétaires à maintenir à jour à chaque
nouveau système qui la rejoint. `captureDeckPlayerCountIfNeeded`/
`dealStartingHandsForLibreIfNeeded` (mise en place initiale de la pioche)
suivent le même principe. Un correctif sur cette mécanique bénéficie donc
**automatiquement** aux trois systèmes à la fois, exactement comme demandé
— **sauf** la dotation gratuite de jetons de départ (7 unités), qui reste
réservée à la libre (ni la dette ni le troc n'ont de jetons de départ).

## Le calcul du DU (Dividende Universel) — lire avant de toucher à la monnaie libre

Le DU **n'est pas** un nombre de jetons — c'est une **valeur monétaire**
calculée par la vraie formule de la TRM :

```
DU = c × (masse_monétaire / joueurs_vivants)
c  = ln(ev/2) / (ev/2)        (taux de croissance ANNUEL)
ev = nombre_de_tours_prévus × 8 ans   (convention du jeu : "1 tour = 8 ans")
```

Source de vérité **unique** : `Game.java` —
- `computeDuGrowthRatePerTurn()` — le taux composé sur 8 ans.
- `computeCurrentDU()` — la valeur monétaire du DU à l'instant présent,
  exposée aux DEUX applications (animateur et smartphones) via
  `GameDetailDto`/`PlayerSelfViewDto` (`currentDuValue`) — **ne jamais
  recalculer cette formule séparément côté client**, lire ce champ.
- `computeMoneyMassFromActivePlayersJetons()` — la masse monétaire globale
  n'est **jamais** incrémentée de façon indépendante ; elle est
  **recalculée** comme la somme réelle des jetons détenus par les joueurs
  actifs, à chaque `TURN`/`DEATH` en strict TRM (voir `Event.java`).
  Garantit une cohérence exacte par construction plutôt que d'espérer que
  deux calculs séparés (masse globale d'un côté, jetons distribués de
  l'autre) coïncident. **Uniquement pour une partie suivie par smartphone**
  (au moins un joueur actif avec `Player.startingCardsJson != null`, voir
  `Event.isSmartphoneTrackedGame()`) : en mode classique (sans smartphone,
  ni dans l'app Swing `geco-app`), `jetonWeak` n'est jamais renseigné et
  resterait à 0 pour tout le monde — ces parties gardent donc l'ancien
  mécanisme (DU "simple" = masse / (7 × joueurs actifs × facteur), ajouté
  à la masse, sans formule de croissance TRM). Correctif du 11/09/2026,
  suite à un audit : la version précédente cassait strict TRM en mode
  classique (masse remise à 0 à chaque mort/tour) et le test
  `FreeMoneySystemTest.testStrictTrmNeverDecreasesMoneyMassAtDeath`.
- `computeStartingJetonsPerPlayer()` — dotation de départ (7 unités
  monétaires FIXES par joueur, converties en jetons via "Valeur d'une
  pièce faible").
- `cardPriceInDU(niveau)` — prix des cartes en monnaie libre smartphone
  (faible=0,5 DU, moyenne=1, forte=2, tresforte=4) — **uniquement pour ce
  mode** ; la monnaie dette garde un prix fixe en jetons
  (`LEVEL_JETON_PRICE`), le mode classique reprend l'ancien code
  (`levelValue` historique).

**Piège d'arrondi à connaître** : en Python, `round()` fait un arrondi
bancaire (arrondit 0,5 vers le PAIR le plus proche) — **différent** de
`Math.round()` en JS/Java (arrondit toujours 0,5 vers le HAUT). Si tu
écris une simulation Python pour vérifier une formule avant de l'implémenter
en Java/JS, utilise `math.floor(x + 0.5)`, jamais `round(x)` — un piège
rencontré et corrigé plusieurs fois pendant le développement.

**Séquencement important** (`Event.applyEvent()`, cas `WEALTH_CHECKPOINT`/
`DEATH`) : pour un joueur en monnaie libre suivi par smartphone,
`player.jetonWeak` est mis à jour **en tout premier**, avant tout calcul de
masse monétaire — c'est ce qui permet à
`computeMoneyMassFromActivePlayersJetons()`, appelée juste après, de voir la
valeur à jour. Déplacé depuis `GameService.recordEvent()` vers le moteur le
11/09/2026 (audit + confirmation utilisateur) : ce code ne fonctionnait que
pour le chemin "en direct" — un rejeu historique (`Game.recomputeAll()`,
utilisé par `StatsService.computeWealthOverTime` pour le graphique "module
Galilée" et par `GameService.deleteEvent`/`editEvent`/`undoLastEvent`)
n'appelle jamais `GameService`, seulement `applyEvent()` sur chaque
événement — `jetonWeak` restait donc bloqué à sa valeur ACTUELLE au lieu de
refléter l'état réel à chaque instant rejoué. Si tu déplaces ce code, vérifie
que cet ordre (jetonWeak avant tout calcul de masse) est préservé, et qu'il
reste dans le moteur pour fonctionner identiquement en direct et en rejeu.

## Système de journalisation (utile pour diagnostiquer)

- **Serveur** : tout passe par des gestionnaires d'exception globaux dans
  `GecoServer.java` (`Exception.class`, `BadRequestResponse.class`,
  `ForbiddenResponse.class`) — toute erreur, prévue ou non, est
  systématiquement journalisée dans le terminal avec sa trace complète.
- **Client (animateur ET smartphone)** : `pushDebugLog()` capture
  automatiquement toute erreur JS non attrapée, promesse rejetée, ou
  requête réseau échouée. Sur smartphone (pas d'outils de développement
  disponibles), un badge 🐞 discret apparaît dès la première anomalie —
  tapoter dessus ouvre un panneau copiable. Avant d'ajouter un nouveau
  `try/catch`, vérifie qu'il journalise bien via `pushDebugLog` plutôt que
  d'avaler l'erreur en silence.

## Règle i18n — jamais de texte en dur dans le HTML/JS

Chaque chaîne visible passe par `data-i18n="clé"` (HTML) ou `t("clé")`
(JS). `lang/fr.po` et `lang/en.po` doivent **toujours** avoir exactement le
même jeu de clés. Avant de committer un changement touchant l'UI, vérifie :

```python
import re
html_keys = set(re.findall(r'data-i18n(?:-title)?="([^"]+)"', open('index.html').read())) \
    | set(re.findall(r'data-i18n(?:-title)?="([^"]+)"', open('player-view.html').read()))
js_keys = set(re.findall(r'\bt\("([^"]+)"', open('js/app.js').read())) \
    | set(re.findall(r'\bt\("([^"]+)"', open('js/player-view.js').read()))
fr_keys = set(re.findall(r'^msgid "(.*)"$', open('lang/fr.po', encoding='utf-8').read(), re.M))
en_keys = set(re.findall(r'^msgid "(.*)"$', open('lang/en.po', encoding='utf-8').read(), re.M))
print('manquantes fr:', sorted((html_keys | js_keys) - fr_keys))
print('manquantes en:', sorted((html_keys | js_keys) - en_keys))
```

**Seul le jeu de clés `data-i18n`/`t("...")` est vérifié par ce script** —
pas les quelques textes construits par du HTML injecté directement (ex.
`el(...).innerHTML = "texte en dur"`, sans passer par `t()`) : ceux-là
échappent au script ci-dessus. Un audit manuel du 18/09/2026 en a trouvé
quelques-uns, pré-existants, mineurs (un panneau de diagnostic HTTPS, un
message de repli QR) — vérifie visuellement les nouvelles chaînes que tu
ajoutes, pas seulement via ce script.

## Méthode de travail qui a bien fonctionné jusqu'ici

- **Sur une règle de jeu ambiguë** (calcul du DU, prix d'une carte,
  répartition d'un carré...) : demander un **exemple chiffré concret**
  plutôt que deviner — a évité plusieurs allers-retours coûteux, y compris
  très récemment sur la formule du DU.
- **Vérifier le code source original** (jytou,
  https://gitlab.com/jytou/geconomicus_helper) avant d'inventer un
  algorithme quand le comportement attendu existe déjà quelque part.
- **Tester réellement** (scénarios concrets exécutés, simulation numérique
  avant de committer une formule) plutôt qu'une simple relecture de code —
  plusieurs bugs subtils (dérive d'arrondi qui s'accumule sur plusieurs
  tours, confusion d'unités) n'ont été trouvés que comme ça.
- Le moteur (`geco-engine`) ne change **jamais** de comportement entre web
  et Swing — toute correction de règle s'y fait une seule fois.
- Documenter les décisions et leur raison **directement en commentaire
  dans le code**, avec la date et, si possible, la citation de la demande
  qui l'a motivée ("Remonté par l'utilisateur (JJ/MM/AAAA) : \"...\"") —
  convention systématique dans ce projet, à continuer.
- Messages de commit détaillés : quel bug/besoin, ce qui a été vérifié,
  les limites connues restantes le cas échéant — pas de commit laconique.
- **Seconde relecture indépendante** pour un chantier conséquent (nouveau
  mécanisme, pas juste un correctif ponctuel) : un second agent qui n'a vu
  ni le raisonnement ni les hypothèses du premier, relit le diff, revérifie
  les tests, rejoue lui-même des scénarios (jamais seulement ceux du
  premier agent) et inspecte les captures d'écran. A trouvé un vrai bug de
  sécurité le 18/09/2026 (vérification "même valeur" d'un échange troc
  contournable via des niveaux déclarés par le client, jamais revérifiés
  côté serveur) qu'une relecture par le même agent n'aurait probablement
  pas détecté — vaut le coût pour tout chantier de cette taille.

## Pièges déjà rencontrés, à ne pas reproduire

- Javalin ne sérialise PAS automatiquement les `HttpResponseException` en
  JSON sans gestionnaire explicite (texte brut par défaut, silencieusement
  perdu côté client) — déjà corrigé, mais à garder en tête pour toute
  nouvelle route.
- Un accroc réseau/wifi d'une seconde ne doit jamais, à lui seul, effacer
  l'écran d'un joueur — tolérer quelques échecs consécutifs avant
  d'afficher un état d'erreur (voir `refreshPlayer` dans `player-view.js`).
- Plusieurs appels concurrents vers le même point (verrouillage d'écran,
  reconnexion WebSocket...) peuvent se déclencher presque simultanément —
  un garde-fou "déjà en cours" simple protège contre ça, quelle que soit
  la cause exacte du déclenchement multiple.
- **Connu, non corrigé** (identifié le 18/09/2026 en seconde relecture
  indépendante du troc+smartphone) : les échanges smartphone
  (`GameService.recordTransaction`/`recordCardSwap`) valident (solde,
  réciprocité...) puis persistent SANS verrou base de données explicite —
  deux échanges distincts portant sur la même ressource rare, rédimés
  quasi simultanément, pourraient théoriquement passer tous les deux.
  Présent depuis le début pour dette/libre, pas une régression du
  18/09/2026 — à corriger si confirmé gênant en usage réel (verrou
  explicite ou transaction DB sérialisée sur la paire de joueurs
  concernée).
