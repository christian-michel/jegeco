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

## Les trois systèmes monétaires — et une distinction importante à l'intérieur de la monnaie libre

- **Troc** : échange carte contre carte(s), jamais de jetons ni de valeur
  monétaire.
- **Dette** : banque, crédits, intérêts. Les cartes ont un prix FIXE en
  jetons (voir `player-view.js`, `LEVEL_JETON_PRICE` — jamais changé).
- **Libre** : Dividende Universel (DU) régulier à chaque joueur, sans dette.
  **Deux variantes à ne jamais confondre** :
  - **Mode classique** (sans suivi par smartphone) : l'animateur suit tout
    manuellement dans l'assistant de fin de tour — code plus ancien,
    largement inchangé depuis l'étape 2.
  - **Mode smartphone** (le cœur de l'étape 3) : chaque joueur a son
    téléphone, ses jetons réels sont suivis avec précision
    (`Player.jetonWeak/jetonMedium/jetonStrong`), et **seuls les jetons
    FAIBLES circulent réellement** (voir plus bas, "Le calcul du DU").
    Un correctif touchant l'un des deux modes ne doit, sauf demande
    explicite, jamais affecter l'autre.

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
  l'autre) coïncident.
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

**Séquencement important** (`GameService.recordEvent`) : pour un événement
`WEALTH_CHECKPOINT`/`DEATH` en monnaie libre smartphone, `player.jetonWeak`
est mis à jour **avant** l'appel à `event.applyEvent()` — c'est ce qui
permet à `computeMoneyMassFromActivePlayersJetons()` (appelée depuis
`applyEvent()`) de voir la valeur à jour. Si tu déplaces ce code, vérifie
que cet ordre est préservé.

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
print('manquantes:', sorted((html_keys | js_keys) - fr_keys))
```

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
