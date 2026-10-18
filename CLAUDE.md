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
- **Corrigé le 27/09/2026** (identifié le 18/09/2026 en seconde relecture
  indépendante du troc+smartphone, confirmé en usage réel le 27/09/2026
  suite à un retour utilisateur — voir `docs/03-architecture-technique.md`,
  entrée du 27/09/2026, pour le détail complet) : les échanges smartphone
  validaient (solde, réciprocité...) puis persistaient SANS verrou base de
  données explicite — deux échanges distincts portant sur la même
  ressource rare, rédimés quasi simultanément, pouvaient effectivement
  tous les deux passer (jusqu'à +31073 unités monétaires créées de rien,
  mesuré en HTTP réel). Corrigé par `GameService.withGameLock` (verrou en
  mémoire par partie, voir son commentaire pour le détail).
- **Corrigé le 17/10/2026** ("Phase 0" de la feuille de route post-
  diffusion, partie du bug identifié le 27/09/2026 ci-dessous - voir
  `docs/13-etape3-etat-et-feuille-de-route.md` pour le plan complet) :
  en dette+smartphone, "Annuler"/supprimer/éditer un événement SANS AUCUN
  RAPPORT avec un crédit déjà accordé faisait grandir le solde physique du
  joueur (`Player.jetonWeak`) à CHAQUE annulation — mesuré à l'époque :
  "3 'Annuler' après un crédit de 10 unités font passer les jetons de 10 à
  40". Cause : `Game.recomputeAll()` (le rejeu complet de l'historique,
  déclenché par `GameService.deleteEvent`/`editEvent`/`undoLastEvent` à
  CHAQUE modification, même d'un événement totalement différent) remettait
  déjà à zéro `curDebt`/`curInterest`/`active`/`quit`/`visitedBank`/
  `prisonUntilTurn` avant de rejouer, mais jamais `jetonWeak`/
  `jetonMedium`/`jetonStrong` — or `Event.applyEvent()`, cas NEW_CREDIT
  (dette+smartphone), fait `jetonWeak += principal` (un AJOUT relatif,
  jamais une affectation absolue) : sans remise à zéro préalable, chaque
  rejeu rempilait un nouvel ajout sur la valeur déjà là depuis le rejeu
  PRÉCÉDENT. Corrigé en remettant ces trois champs à zéro au même endroit,
  **restreint explicitement à `Game.MONEY_DEBT`** — une première version
  non restreinte cassait la monnaie LIBRE (régression détectée par la
  suite de tests existante avant toute mise en production, jamais en
  usage réel) : la dotation de départ libre (7 unités) est posée en DEHORS
  de tout événement, et `WEALTH_CHECKPOINT`/`DEATH` (libre) affectent
  TOUJOURS ces champs par une affectation absolue déjà replay-safe — la
  dette, elle, n'a par construction AUCUN de ces points de contrôle
  absolus en cours de partie (jamais de dotation gratuite, QUE des ajouts/
  retraits relatifs), d'où le besoin réel de cette remise à zéro, mais
  pour elle seule. Vérifié par un nouveau test de régression
  (`GameServicePileConservationTest.testUndoOfUnrelatedEventNeverDuplicatesDebtSmartphoneCredit`,
  reproduit le bug AVANT correctif puis confirme sa disparition APRÈS) +
  une reproduction HTTP réelle via le vrai bouton "Annuler"
  (`POST /api/games/{id}/undo`) + la suite complète (244 tests, aucune
  régression, y compris les tests "module Galilée"/masse monétaire en
  libre).
  **Seconde moitié du bug du 27/09/2026 : DÉCISION UTILISATEUR FINALE,
  JAMAIS un correctif à prévoir (actée le 17/10/2026)** — un achat/vente
  smartphone n'est PAS un événement (c'est une `Transaction`), donc n'est
  jamais rejoué par `recomputeAll()` : annuler un événement sans rapport
  peut remettre les jetons d'un joueur à leur valeur d'avant un achat déjà
  effectué, sans annuler le transfert de carte associé (l'acheteur obtient
  la carte gratuitement). Réponse de l'utilisateur à la question posée
  dans cette même entrée : "Les transactions validées restent validées.
  Autrement ça impacterait tout un tas d'opérations en cascades. Ça
  serait compliqué à coder et ça risquerait de vraiment altérer la
  qualité du jeu." Ce comportement est donc désormais un choix de design
  DÉLIBÉRÉ, pas une limite subie — ne pas le "redécouvrir" plus tard
  comme un bug à corriger ; la piste de redesign un temps envisagée
  (ajuster les jetons ACTUELS par la différence entre rejouer avec/sans
  l'événement retiré) est abandonnée, sans suite prévue.
- **Corrigé le 17/10/2026** (même demande utilisateur que ci-dessus,
  "corrige en t'assurant bien ensuite que tout est correct à présent" —
  bug mineur lié mais de cause DIFFÉRENTE du correctif dette ci-dessus,
  confirmé en l'examinant : la masse monétaire globale `Game.moneyMass`
  a toujours été correctement remise à zéro avant rejeu, y compris pour
  la dette) : le graphique "Évolution de la masse monétaire"
  (`StatsService.computeMoneyMassHistory`) affichait pour "Tour 1" la
  masse FINALE de la partie au lieu de la masse réelle à ce tour — et la
  courbe "module Galilée" (`computeWealthOverTime`) avait très
  probablement le même défaut sur son ratio "richesse relative à la
  moyenne" du tour 1 (même mécanisme, même ligne de code lue). Cause
  réelle (en monnaie LIBRE suivie par smartphone exclusivement — jamais
  en dette, qui ne recalcule jamais la masse depuis les jetons) :
  `Game.recomputeAll()` ne remet jamais `Player.jetonWeak` à zéro pour la
  LIBRE (il n'en a besoin que pour la dette, voir le correctif ci-dessus)
  — le tout premier `TURN` rejoué voit donc des joueurs déjà suivis par
  smartphone et recalcule la masse (mode strict TRM,
  `Game.computeMoneyMassFromActivePlayersJetons`) à partir de leurs
  jetons ACTUELS (ceux de la fin de la partie réelle), jamais de ceux
  qu'ils avaient réellement au tout début — EXACTEMENT le même mécanisme
  que le bug déjà trouvé et corrigé le 27/09/2026 dans le rapport "Masse
  monétaire détaillée" (`StatsService.resetJetonsToStartOfGameForReplay`),
  resté volontairement NON étendu à ces deux autres graphiques à
  l'époque, en attente de cette décision utilisateur. Corrigé en
  réutilisant ce même correctif local (lecture seule, objet détaché
  jamais persisté, sans aucun effet sur le rejeu réel utilisé par
  Annuler/éditer) dans `computeMoneyMassHistory` ET `computeWealthOverTime`.
  Vérifié par un nouveau test de régression
  (`StatsServiceExchangeStatsTest.testExchangeStatsMatchHandTrackedGroundTruth`,
  étendu) : reproduit le bug AVANT correctif (tour 1 affichait 1610612733
  au lieu de 21 sur un scénario à 3 joueurs avec soldes gonflés) puis
  confirme sa disparition APRÈS ; suite complète verte sans régression
  (dont les deux tests déjà existants sur `computeWealthOverTime`,
  inchangés).
- **Corrigé le 10/10/2026** (campagne de validation troc+smartphone, 3
  parties 4/8/16 joueurs/12 tours, preuve concrète via API+Playwright) :
  `Player.goodsCount` (le "nombre d'objets" affiché au TABLEAU DE BORD
  ANIMATEUR — `app.js`, `statGoods`/`game.player_meta_troc`/classement) ne
  reflétait plus la réalité pour un carré troc+smartphone, resté figé sur
  l'ancienne mécanique "carte-contre-jetons" (`recordTransactionUnlocked`,
  remplacée le 18/09/2026 par `recordCardSwap`/`/redeem-swap`) et la
  renaissance seule (`Event.java`, cas `DEATH`) : `checkAndCashInSquares`
  ne le mettait jamais à jour. Mesuré : après un carré forcé (API réelle),
  le téléphone du joueur affichait 5 cartes (4 "replenished" + 1
  "promoted" − 4 défaussées), le tableau de bord affichait toujours 4
  (figé sur la dotation de départ) — écart qui persiste tant qu'aucun
  autre carré ne se reproduit pour ce joueur. Corrigé dans
  `GameService.checkAndCashInSquaresUnlocked` : `goodsCount` ajusté du
  même delta exact que les cartes réellement échangées
  (`-4 + replenished.size() + 1`), uniquement pour `Game.MONEY_TROC`
  (seul système où ce champ a un sens, voir
  `docs/10-etape-plugins-troc.md` règle 9) — jamais pour dette/libre.
  76 tests toujours verts après correctif (aucune régression).
- **Motif récurrent à surveiller** (confirmé une troisième fois le
  11/10/2026, panneau "Demandes de crédit" dette+smartphone — voir
  `docs/03-architecture-technique.md`, entrée du 10-11/10/2026, pour le
  détail complet) : `GameService.withGameLock` protège l'état DE LA
  PARTIE pendant `recordEvent`, mais tout état séparé maintenu ailleurs
  (ex. `CreditRequestService`, une simple `ConcurrentHashMap` en mémoire)
  n'est PAS couvert par ce verrou. Un contrôle-puis-écriture (TOCTOU) sur
  cet état séparé — vérifier un statut "pending" puis appeler
  `recordEvent` séparément, sans rien entre les deux — reproduit
  exactement la même classe de bug que le 27/09/2026 ci-dessus (ici :
  double octroi de crédit via deux approbations concurrentes). Avant
  d'ajouter un nouveau panneau/une nouvelle route qui lit-puis-écrit un
  état en mémoire propre à elle (pas le moteur de jeu lui-même), prévoir
  une transition atomique dès le départ (voir
  `CreditRequestService.tryClaim()`, `computeIfPresent`, comme modèle).
- **Corrigé le 11/10/2026** (seconde relecture indépendante, campagne
  dette+smartphone — voir `docs/03-architecture-technique.md` pour le
  détail complet) : un dialogue enchaînant plusieurs étapes dans LA MÊME
  `<dialog>` (en rappelant `openDialog()` depuis l'intérieur du
  `onSubmit()` de l'étape précédente, voir `openPlayerQuitDialog`) se
  faisait refermer INSTANTANÉMENT par le `dlg.close()` générique
  d'`openDialog()`, qui s'exécutait TOUJOURS après `await onSubmit()` —
  y compris quand `onSubmit()` venait tout juste de rouvrir la boîte avec
  l'étape suivante. Aucune erreur visible ; le bouton "Un joueur quitte"
  n'enregistrait tout simplement jamais l'événement QUIT pour le cas le
  plus fréquent (joueur sans dette), silencieusement, depuis l'origine de
  cette fonctionnalité. Corrigé par un compteur de génération
  (`dlgOpenSeq`) dans `openDialog()` lui-même — avant d'enchaîner un
  second `openDialog()` depuis un `onSubmit()`, vérifier que ce motif
  n'est pas déjà couvert par ce garde-fou, ou le réutiliser tel quel.
- **Corrigé le 18/10/2026** (remonté par l'utilisateur sur une vraie
  campagne de test : "je lance une partie dette+smartphone en reprenant
  les joueurs d'une partie libre+smartphone... le QR code affiché
  indique la même url... sur les smartphones des joueurs, l'affichage
  reste celui de la partie en monnaie libre qu'on vient de terminer,
  impossible de lancer la partie en monnaie dette pour les joueurs") :
  `renderGameDetail()` (`app.js`) ne réinitialisait les panneaux
  "Inviter" (QR/lien d'inscription) et "Historique des échanges" que si
  le réglage "mode smartphone" passait à désactivé (voir le `.then()`
  d'`Api.getSettings()`) — jamais simplement parce que la PARTIE
  CONSULTÉE avait changé. Si le panneau "Inviter" était resté ouvert sur
  une ancienne partie (toujours en mode smartphone, comme ici), son
  contenu (le lien/QR de CETTE ancienne partie) restait affiché tel quel
  après avoir navigué vers une nouvelle partie — et pire,
  `renderInviteQr()`/`toggleTransactionsPanel()` considèrent un panneau
  déjà VISIBLE comme "ouvert, donc à FERMER" (jamais "à rafraîchir") : un
  seul clic sur "Inviter" fermait silencieusement le panneau périmé au
  lieu de le régénérer pour la partie courante, il aurait fallu cliquer
  deux fois. Les joueurs scannant ce QR périmé atterrissaient donc
  toujours sur l'ANCIENNE partie (ou sur rien du tout si le panneau
  venait d'être fermé), jamais sur la nouvelle — bloquant purement et
  simplement la fonctionnalité "reprendre les joueurs d'une partie
  existante" (option très utilisée pour comparer dette/libre/troc avec
  les mêmes joueurs, l'intérêt même du jeu) dès qu'une partie précédente
  avait son panneau "Inviter" laissé ouvert. Corrigé en réinitialisant
  ces deux panneaux (masqués, contenu vidé) inconditionnellement dès que
  `gameId` consulté change, au même endroit que la remise à zéro déjà
  existante de `state.turnEnded`. Vérifié par reproduction Playwright
  AVANT/APRÈS (scénario exact du retour utilisateur : partie libre créée,
  panneau "Inviter" laissé ouvert, partie dette créée en reprenant le
  même joueur, navigation automatique vers cette nouvelle partie comme le
  fait réellement `btnNewGame.onclick`) : AVANT correctif, le panneau
  restait visible après changement de partie et un clic "Inviter" le
  fermait sans jamais afficher la bonne URL (`undefined`) ; APRÈS
  correctif, le panneau est bien masqué après changement de partie et un
  seul clic affiche l'URL correcte et distincte de la nouvelle partie.
- **Corrigés le 18/10/2026** (campagne de test complète demandée par
  l'utilisateur : 9 parties réelles, 2/6/16 joueurs × 12 tours, libre+
  smartphone PUIS reprise des joueurs en dette+smartphone ET en
  troc+smartphone, pilotées en HTTP réel contre une vérité terrain
  indépendante — méthode à deux agents, contrôle puis audit indépendant,
  comme pour le PDF du 19/09/2026 ; voir `docs/03-architecture-
  technique.md`, entrée du 18/10/2026, pour le détail complet) — 6 bugs
  réels trouvés et corrigés :
  - Un WebSocket fermé côté client (téléphone verrouillé/onglet fermé/
    wifi perdu) mais pas encore retiré de `mSessionGameIds` (`onClose`
    traité de façon asynchrone par Jetty) faisait lever
    `ClosedChannelException` dans `GecoServer.broadcast()`, remontant en
    500 l'action HTTP qui venait de diffuser — ALORS QUE l'écriture
    était déjà persistée (mesuré : "Fin de tour" en 500, assistant
    interrompu en plein milieu, encaissement automatique des carrés
    jamais exécuté après un achat). Corrigé : chaque envoi isolé (session
    fermée retirée sans tenter l'envoi, erreur journalisée puis session
    retirée, jamais propagée aux autres destinataires ni à l'appelant
    HTTP) + `ws.onError` retire la session en erreur. Reproduit
    déterministe : 10/40 réponses 500 avant, 0/40 après.
  - "Fin de tour" cliqué MANUELLEMENT n'arrêtait que le minuteur affiché
    chez l'animateur — `GameService.isTradingAllowed` ne bloquait les
    échanges qu'à l'expiration NATURELLE du chrono (jusqu'à 5 minutes
    plus tard), le temps que l'assistant de fin de tour (qui pose des
    `WEALTH_CHECKPOINT`/saisies ABSOLUS, lus à son ouverture) tourne. Une
    vente conclue entre-temps était donc écrasée par l'assistant,
    mesuré : vendeur à 26 au lieu de 31, carte transférée gratuitement.
    Corrigé : `POST /turn/announce-end` (appelé par le clic manuel ET
    l'expiration naturelle) enregistre la fin du tour en mémoire
    (`GameService.markTurnEndAnnounced`), `isTradingAllowed` bloque
    jusqu'au `TURN` suivant. Limite assumée : contrôle en mémoire
    (perdu à un redémarrage serveur), fait avant la prise du verrou de
    partie (comme le contrôle de pause existant).
  - "Reprendre les joueurs" (fonctionnalité du 29/08/2026) restait
    inutilisable par QR en mode smartphone malgré le correctif du
    panneau "Inviter" ci-dessus : un joueur repris tapant son prénom
    habituel sur le lien d'invitation recevait "Ce prénom est déjà
    pris" (l'animateur le PRÉ-INSCRIT par son prénom via `addPlayer` à
    la création de la partie, `joinAsPlayer` refusait ensuite tout
    prénom déjà présent). Corrigé en deux temps : (1) un joueur
    pré-inscrit jamais encore réclamé (ni avatar ni âge posés —
    `addPlayer` n'en pose aucun, alors que `join.html` en envoie toujours
    un) est rattaché au téléphone qui tape ce prénom ; (2) **seconde
    relecture indépendante** : cette première version ne distinguait pas
    "jamais réclamé" de "jamais utilisé" — un joueur pré-inscrit jouant
    déjà via son lien personnel 🔗 (qui, lui, ne pose jamais d'avatar)
    restait réclamable EN PLEINE PARTIE par n'importe qui connaissant le
    lien d'invitation, un vrai risque (mesuré : prise de la place de
    "Yasmine" en pleine partie dette, HTTP 201, puis dépense de 9 de ses
    10 jetons). Resserré au rattachement UNIQUEMENT si
    `game.getTurnNumber() == 0` (inscription, avant "Démarrer la
    partie" — aucun jeton ni carte encore en jeu à ce moment).
    Limite restante, acceptée : avant le démarrage, n'importe quel
    porteur du lien d'invitation peut encore réclamer une place
    pré-inscrite non réclamée (sans enjeu à ce stade, visible par
    l'animateur).
  - Quatre défauts d'affichage/statistiques : (1) `quitEarly` valait vrai
    pour TOUS les joueurs d'une partie libre+smartphone terminée
    NORMALEMENT (l'inventaire de fin de partie pose un `QUIT` pour
    chacun, strict TRM met alors `Player.quit` à vrai) — "(sorti)"
    partout, courbes en pointillés ; corrigé au même critère que
    `endOfGameQuitEvents` dans `computeWealthOverTime` (sorti EN COURS
    de partie seulement si un `TURN` suit son `QUIT`). (2) Module
    Galilée, `relativeToAverage` : numérateur en "jetons ÷ 3" (unité
    héritée de Swing) divisé par un dénominateur en unités monétaires —
    un joueur exactement dans la moyenne valait 0,67 au lieu de 1,0 (avec
    `weakCoinValue` par défaut 0,5), 0 au tour 1 pour tout le monde ;
    corrigé pour la libre+smartphone (ratio en unités monétaires réelles
    des deux côtés). (3) Une partie TROC étiquetée "Libre" (même couleur
    verte) dans l'écran de comparaison, indiscernable d'une vraie partie
    libre — libellé "Troc" + nom de partie ajouté, couleur ambre
    dédiée. (4) Carte "Reste à changer" du tableau de bord (écart à une
    cible de convergence propre au mode NON strict TRM) affichant des
    valeurs négatives croissantes dénuées de sens en strict TRM (mesuré :
    -278) — masquée dans ce mode.
  - Un échange troc DIRECT carte-contre-carte s'affichait côté joueur
    comme "Vous avez acheté Granite à Bruno6 pour 0 jetons" (Dernière
    activité) et "Acheté à X  −0" (Historique) — contraire à la règle 6
    du troc ("jamais de jetons ni de valeur monétaire",
    `docs/10-etape-plugins-troc.md`) et la carte REÇUE en retour
    n'apparaissait nulle part. Corrigé (`player-view.js` uniquement,
    dette/libre inchangées) : "Vous avez échangé {donnée} contre {reçue}
    avec {nom}", badge neutre ⇄ dans l'historique.
  Vérifié à chaque étape par de nouveaux tests (`GameServiceReuseJoin
  AndTurnEndTest`, `StatsServiceCampaignFixesTest`), des reproductions
  HTTP déterministes AVANT/APRÈS, et une campagne de confirmation
  complète rejouée sur le jar corrigé (plus aucune erreur serveur, plus
  aucun écart de cohérence inattendu) — voir `docs/03-architecture-
  technique.md` pour le détail complet, y compris les points trouvés
  MAIS PAS corrigés (nécessitent une décision utilisateur, notamment une
  mise à jour importante de la décision du 17/10/2026 sur le bug
  "Annuler" — son énoncé d'origine s'est révélé inexact, voir ce même
  renvoi) et la réflexion (non implémentée) sur un futur mécanisme
  d'échange carte-contre-pioche commun aux trois systèmes smartphone.
- **Décision utilisateur RECONFIRMÉE le 18/10/2026, avec la description
  EXACTE cette fois** (corrige la décision du 17/10/2026 ci-dessus, prise
  sur un énoncé imprécis — voir le point (b) de la campagne de test
  2/6/16 joueurs juste au-dessus, et `docs/03-architecture-technique.md`
  pour le détail complet de l'audit qui a établi cette description) : en
  dette+smartphone, un "Annuler"/suppression/édition d'un événement SANS
  AUCUN RAPPORT efface l'effet monétaire de TOUS les achats de cartes
  depuis le DÉBUT de la partie (les cartes achetées restent acquises,
  seuls les SOLDES individuels sont faux — la masse monétaire globale,
  elle, reste exacte) ; en libre, l'effet est plus limité (seulement les
  achats depuis le dernier point de contrôle `WEALTH_CHECKPOINT` du
  joueur) mais existe aussi. Présentée avec cette description précise
  (CARTES conservées, JETONS/soldes faux — pas l'inverse décrit par
  erreur le 17/10/2026), la décision de l'utilisateur reste la MÊME :
  **accepter tel quel, documenter, aucun correctif à prévoir** ("Accepter
  tel quel, documenter"). Ne pas rouvrir ce point sans nouvelle demande
  explicite — le comportement est désormais un choix assumé en toute
  connaissance de cause, pas une limite mal comprise.
