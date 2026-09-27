# Architecture technique et choix effectués

Ce document explique, pour toute personne souhaitant comprendre ou contribuer au
code, **ce qui a été fait** et **pourquoi**, depuis le projet original de jytou
(https://gitlab.com/jytou/geconomicus_helper) jusqu'à l'état actuel.

## Vue d'ensemble du projet

Le projet évolue en 3 grandes étapes :

1. **Étape 1 — Modernisation technique minimale** : faire tourner le code existant
   (Java 8, Swing) sur une version récente de Java, sans changer une seule ligne de
   logique métier. *(terminée)*
2. **Étape 2 — Interface moderne, web** : remplacer l'interface Swing par une
   interface HTML5/CSS3/JS moderne et responsive, servie par un petit serveur local.
   *(en cours)*
3. **Étape 3 — Jeu "sans matériel"** : permettre de jouer uniquement avec des
   smartphones (scan d'un QR code affiché par l'ordinateur de l'animateur, pas de
   cartes ni jetons physiques), fonctionnement 100% local (Wi-Fi ou Bluetooth),
   installation simplifiée via Docker. *(à venir)*

## Étape 1 : migration Java 8 → Java 21

### Constat de départ

Le projet original de jytou était un projet Eclipse classique (pas d'outil de build
comme Maven), ciblant Java 8, utilisant Swing pour l'interface, JPA 2.1
(EclipseLink) + H2 pour la persistance, et JAXB pour l'export/import XML.

### Pourquoi Java 21 ?

Java 21 est la version **LTS** (Long Term Support) la plus récente au moment de la
migration : elle bénéficie d'un support long terme par la communauté/Oracle, contrairement
aux versions intermédiaires (non-LTS) qui cessent rapidement d'être maintenues. C'est
le choix le plus pérenne pour un projet destiné à durer.

### Le vrai point de blocage : JAXB

Passer de Java 8 à Java 21 n'est pas qu'un changement de numéro de version : **le
module JAXB (`javax.xml.bind`, utilisé pour l'export/import XML des parties) a été
retiré du JDK depuis Java 11**. C'était le seul point de blocage réel identifié dans
tout le code (environ 6000 lignes) : tout le reste (Swing, le moteur métier)
compile sans modification sous Java 21.

### Ce qui a été fait

- **Migration vers Jakarta EE** : `javax.persistence` → `jakarta.persistence` (JPA
  2.1 → Jakarta Persistence 3.1, EclipseLink 2.7.1 → 4.0.4) et `javax.xml.bind` →
  `jakarta.xml.bind` (Jakarta XML Binding 4.0.2). Il s'agit du même standard, sous sa
  nouvelle gouvernance (Eclipse Foundation) : le renommage de package est mécanique,
  aucune logique n'a changé. C'est le choix le plus pérenne, `javax.*` étant en fin
  de vie dans l'écosystème.
- **H2** mis à jour de 1.4.197 à 2.2.224 (base de données embarquée, changement de
  version sans impact sur le code applicatif).
- **Introduction de Maven** : le projet ne disposait d'aucun outil de build. Un
  `pom.xml` a été mis en place pour gérer les dépendances et automatiser la
  compilation/l'empaquetage — un prérequis pour toute intégration future dans un
  pipeline Docker (étape 3).
- Quelques API dépréciées corrigées au passage (`new Integer(0)` →
  `Integer.valueOf(0)`, `new URL(String)` → `URI.create(String)`).

## Étape 2 : vers une interface web moderne

### Le choix structurant : ne pas repartir de zéro à l'étape 3

Plutôt que de moderniser l'apparence de Swing (étape 2) pour ensuite tout jeter et
réécrire en Node.js pour le web (étape 3), le choix a été fait de considérer
**l'étape 2 comme le début technique de l'étape 3** : le code HTML/CSS/JS et l'API
écrits maintenant seront directement réutilisés, pas remplacés.

### Découpage en 3 modules Maven

| Module | Rôle | Dépend de |
|---|---|---|
| `geco-engine` | Moteur métier pur : entités `Game`/`Player`/`Event`, calculs monnaie dette/monnaie libre, persistance JPA/H2. Aucune dépendance UI. | — |
| `geco-app` | Interface Swing historique + CLI (étape 1). | `geco-engine` |
| `geco-server` | Serveur web local (Javalin) : API REST + WebSocket, sert le front HTML/CSS/JS (étape 2 → 3). | `geco-engine` |

Ce découpage garantit que **la logique métier (calculs TRM, monnaie dette, gestion
des tours et des morts/renaissances) ne dépend d'aucun choix d'interface** : elle
est écrite une seule fois dans `geco-engine`, et partagée à l'identique par
l'interface bureau et l'interface web. Aucun risque de "double calcul" ou de
divergence entre les deux interfaces.

### Pourquoi Javalin plutôt que Node.js pour le serveur ?

Un serveur web dédié était nécessaire pour servir l'interface HTML/CSS/JS et exposer
une API. Le choix s'est porté sur **Javalin** (bibliothèque Java légère, construite
sur Jetty) plutôt que sur une réécriture en Node.js, pour plusieurs raisons :

- **Aucune réécriture de la logique métier** : `geco-engine` (Java) est réutilisé tel
  quel. Réécrire les calculs économiques dans un autre langage aurait introduit un
  risque réel de divergence/bug dans une logique qui doit rester rigoureusement
  exacte.
- **Simplicité de déploiement** : un seul jar exécutable, pas de runtime Node.js
  séparé à installer côté animateur.
- **Java moderne (21) gère très bien le temps réel multi-clients** nécessaire pour
  l'étape 3 (threads virtuels, WebSocket natif via Javalin/Jetty).
- Node.js n'est pas exclu pour la suite : il pourra être introduit ponctuellement
  pour un composant précis où son écosystème apporte un vrai bénéfice (par exemple
  la génération de QR codes à l'étape 3), sans nécessiter de réécrire le moteur.

### Architecture du serveur

```
Navigateur (HTML/CSS/JS)
        │  HTTP (REST) + WebSocket
        ▼
  geco-server (Javalin)
        │  appels directs (même JVM)
        ▼
   geco-engine (JPA/H2)
```

- **API REST** (`/api/games`, `/api/games/{id}/players`, `/api/games/{id}/events`,
  etc.) pour les actions ponctuelles (créer une partie, ajouter un joueur,
  enregistrer un événement).
- **WebSocket** (`/ws`) pour diffuser en temps réel les changements à tous les
  clients connectés. C'est ce mécanisme qui, à l'étape 3, permettra de synchroniser
  plusieurs smartphones sans changement d'architecture : il suffira que chaque
  téléphone se connecte au même canal.
- **DTO (Data Transfer Objects)** plutôt que sérialisation directe des entités JPA :
  les entités `Game`/`Player`/`Event` ont des références croisées (`Game` contient
  ses `Player`, chaque `Player` référence son `Game`) qui produiraient une boucle
  infinie en JSON. Les DTO (`Dtos.java`) ne gardent que les champs utiles à
  l'affichage, dans un seul sens.

### Choix du front : HTML/CSS/JS natif, sans framework ni build tool

Le premier front (module `geco-server/src/main/resources/public`) est écrit en
JavaScript natif (pas de React/Vue), sans étape de build (pas de Webpack/Vite). Ce
choix reprend l'esprit de simplicité du projet original de jytou : lancer
l'application ne nécessite qu'un jar et un navigateur, aucune chaîne d'outils
supplémentaire à installer ou maintenir. Si l'interface web se complexifie fortement
par la suite, l'introduction d'un framework pourra être reconsidérée.

**Aperçu du nouveau front (Phase A — refonte visuelle selon la maquette fournie) :**

![Écran Nouvelle partie](images/web/new_game_screen.png)

![Tableau de bord - Monnaie dette](images/web/dashboard_screen.png)

![Thème dynamique - Monnaie libre](images/web/dashboard_libre.png)

Le thème (bleu/vert, logo, badges) bascule automatiquement selon le système
monétaire de la partie ouverte (`document.body.classList.toggle("money-libre", ...)`
dans `app.js`), sans dupliquer les styles.

### Phase A : ce qui est fait, ce qui est volontairement différé

**Fait et fonctionnel :**
- Refonte visuelle complète (sidebar sombre, cartes de contenu claires, thème
  dynamique dette/libre) fidèle à la maquette fournie.
- Écran "Nouvelle partie" : sélection du type de monnaie, formulaire complet,
  résumé calculé en direct.
- Tableau de bord de partie avec **cartes statistiques calculées à partir de
  vraies données** : nombre de joueurs actifs, masse monétaire, crédits en
  cours (déjà exposés par le moteur), et un nouveau calcul d'**âge moyen** (en
  tours écoulés depuis la dernière naissance/renaissance de chaque joueur,
  calculé en rejouant l'historique des événements côté serveur — voir
  `Dtos.GameDetailDto`).
- Liste des joueurs et des événements, création d'événements (inchangé depuis la
  correction précédente).

**Volontairement laissé en placeholder à l'époque, désormais fait en Phase B (voir
ci-dessous) :** les graphiques de masse monétaire et de répartition des richesses.

**Reste en placeholder :**
- Le minuteur de tour, la séquence de fin de tour (décès/naissances), l'onglet
  Banque et l'écran de statistiques de fin de partie : prévus aux Phases C/D.

### Phase B : graphiques réels (masse monétaire, répartition des richesses)

Portage fidèle de la logique déjà utilisée par `StatsFrame.java` côté Swing (classes
`HistoryStats` et `computeValues`/`addFromEvent`, environ 600 lignes), plutôt qu'une
réécriture : le nouveau `StatsService.java` (module `geco-server`) réutilise
directement `Game.recomputeAll()` — une méthode du moteur déjà prévue pour cet usage
("*Very useful to make historical graphs*", cf. sa Javadoc) — pour rejouer
l'historique complet des événements et reconstituer :

- **la masse monétaire à la fin de chaque tour** (courbe), en capturant
  `Game.getMoneyMass()` à chaque événement `TURN` rencontré ;
- **la richesse accumulée par chaque joueur** (répartition Top 20% / 20-80% /
  Bottom 20%), en portant l'algorithme `addFromEvent` : chaque événement crédite (ou
  débite) un montant au joueur concerné selon son type (crédit, remboursement, mort,
  rupture technologique qui double le facteur de valeur des cartes, etc.).

Nouvelle route `GET /api/games/{id}/stats`, consommée par le front via **Chart.js**
(chargé en CDN, cohérent avec le choix "pas de build tool" du projet) : un graphique
en ligne pour la masse monétaire, un anneau pour la répartition des richesses, avec
une légende détaillée. Les couleurs suivent le thème dynamique dette/libre déjà en
place depuis la Phase A.

**Différence assumée avec la version Swing :** le calcul de répartition des
richesses se limite ici aux joueurs (sans inclure la banque), pour correspondre au
graphique de la maquette qui est centré sur les joueurs ; la version Swing propose
en plus une option pour inclure la banque dans ses propres statistiques agrégées.

**Point de vigilance non vérifiable dans mon environnement de préparation** :
Chart.js n'a pas pu être testé visuellement ici — mon outil de capture d'écran
utilise un moteur JavaScript ancien (QtWebKit, via `wkhtmltoimage`) qui échoue même à
*interpréter* le bundle Chart.js minifié (`SyntaxError` sur une simple déclaration
`let`), bien avant tout problème de rendu. Ce n'est pas un problème dans un vrai
navigateur (Chrome, Firefox, Safari, Edge supportent tous Chart.js sans problème),
mais je n'ai donc pas pu produire de capture d'écran réelle des graphiques pour
cette phase, contrairement aux écrans de la Phase A. Le code Java a été validé par
compilation ; le code JS par vérification de syntaxe (`node -c`) et relecture
attentive de l'API Chart.js v4 (documentée officiellement). **Merci de confirmer
visuellement une fois `mvn clean package` puis `java -jar geco-server/target/geco-server.jar`
lancés chez vous.**

### Phase C : minuteur de tour synchronisé + séquence de fin de tour

**Minuteur synchronisé entre plusieurs clients.** Plutôt que de faire tourner un
décompte indépendant dans chaque navigateur (qui dériverait inévitablement d'un
client à l'autre au bout de quelques minutes), le serveur retient deux informations
sur chaque partie : `turnDurationSeconds` (durée d'un tour, réglée à la création) et
`turnStartedAt` (horodatage de début du tour en cours). Chaque client calcule alors
localement le temps restant par simple différence avec l'heure actuelle. Ces deux
champs ont été ajoutés à l'entité `Game` (`geco-engine`), avec une valeur par défaut
(300 s) pour rester compatible avec les parties créées avant cet ajout et avec l'app
Swing, qui ne les renseigne pas.

- `turnStartedAt` est réinitialisé automatiquement à chaque nouvel événement `TURN`.
- Nouvelle route `POST /api/games/{id}/turn/extend?seconds=30` (bouton "+30s") :
  recule `turnStartedAt`, ce qui allonge le temps restant pour **tous** les clients
  connectés sans état supplémentaire à synchroniser.
- Le bouton "Pause" du minuteur, en revanche, est **volontairement local à chaque
  navigateur** (il ne fait qu'arrêter la mise à jour visuelle côté client) : une
  vraie pause partagée par tous les écrans demanderait de stocker un état "en pause"
  côté serveur, hors du périmètre de cette phase.

**Séquence de fin de tour**, fidèle à la maquette (résumé → décès → nouveaux-nés →
préparation) : le bouton "Nouveau tour" n'enregistre plus directement l'événement,
il ouvre un assistant à 4 étapes qui :
1. résume le tour qui se termine (événements enregistrés, crédits accordés,
   intérêts prélevés, remboursements, masse monétaire) à partir des événements déjà
   chargés côté client ;
2. laisse l'animateur sélectionner le(s) joueur(s) qui meurent ce tour ;
3. rappelle qui vient de "renaître" ;
4. affiche une checklist de préparation, puis déclenche réellement le nouveau tour
   (événements `DEATH` pour les joueurs sélectionnés, puis `TURN`).

**Différence assumée avec les règles officielles du jeu** : la notice officielle
prévoit que l'ordre des décès est **tiré au sort et fixé secrètement dès le début de
la partie** ("*Seul l'animateur connaît à l'avance le nom du ou des défunts de
chaque tour*"). Cette version ne fait pas encore ce tirage au sort à la création :
l'animateur choisit manuellement qui meurt à chaque tour, comme le fait déjà
l'application Swing existante aujourd'hui. Ajouter un vrai tirage au sort
pré-assigné (avec révélation progressive plutôt que sélection libre) est une
amélioration possible d'une phase ultérieure, mais représente un changement de
mécanique de jeu qu'il valait mieux ne pas décider unilatéralement.

### Phase D : écran de fin de partie et rapport statistique

Nouvelle route `GET /api/games/{id}/report`, accessible depuis l'entrée
"Statistiques" du menu (désormais activée). Les indicateurs affichés reprennent
explicitement ceux demandés par la notice officielle du jeu (section
["Compte rendu"](https://geconomicus.glibre.org/rules.html#compte-rendu)) :

- le nombre total de valeurs produites par joueur (agrégé en "Production totale"),
- la moyenne globale des valeurs produites,
- l'écart type de production,
- complétés par la médiane et un **indice de Gini** (mesure standard d'inégalité,
  0 = égalité parfaite, 100 = inégalité maximale), formule validée numériquement sur
  des cas de référence avant intégration (égalité parfaite → 0, cas d'inégalité
  extrême à 4 joueurs → 0,75, cf. tests manuels effectués pendant le développement).

Un histogramme (graphique en barres, Chart.js) répartit les joueurs par tranches de
richesse finale ; ces tranches sont calculées dynamiquement à partir de l'étendue
réelle des valeurs de la partie plutôt que des seuils fixes, pour rester pertinentes
quelle que soit l'échelle de jeu. La courbe de masse monétaire réutilise le calcul
déjà fait en Phase B.

**Point d'attention important, documenté explicitement dans l'interface** : la
richesse d'un joueur n'est comptabilisée qu'au moment de son événement "Mort /
Renaissance" ou "Fin de partie" - c'est le principe même du tableur original ("*tous
les joueurs sont appelés un par un devant l'animateur*" en fin de partie). Si des
joueurs sont encore actifs au moment de consulter le rapport, un bandeau
d'avertissement l'indique clairement plutôt que d'afficher un total silencieusement
incomplet.

**Export réel** : le bouton "Exporter le rapport" télécharge un fichier JSON
contenant toutes les données du rapport - fonctionnel dès maintenant, pas une simple
maquette. Un export plus élaboré (PDF, ou format compatible avec les tableurs
LibreOffice mentionnés dans la notice officielle) pourra être envisagé plus tard si
le besoin s'en fait sentir.

**Non fait dans cette phase** : la comparaison visuelle entre deux parties (par
exemple monnaie dette vs monnaie libre jouées par les mêmes joueurs), visible sur la
maquette sous forme d'une courbe à deux couleurs. Cela suppose de savoir associer
deux parties entre elles, ce qui n'existe pas encore dans le modèle de données -
prévu comme amélioration possible d'une phase ultérieure plutôt que d'être ajouté
au forceps ici.

### Correctif : graphiques qui ne s'affichaient pas (zones blanches)

Remonté après un premier test réel. Cause identifiée : les `<canvas>` des
graphiques (Phases B et D) étaient configurés avec `maintainAspectRatio: false`
(pour occuper toute la largeur disponible) mais placés directement dans des cartes
sans conteneur parent à **hauteur CSS explicite**. C'est un piège classique et bien
documenté de Chart.js : sans hauteur définie sur le conteneur, le canvas reste à
hauteur nulle et le graphique n'apparaît jamais, sans la moindre erreur JavaScript
visible dans la console — d'où le symptôme "zone blanche silencieuse".

**Corrigé** : chaque `<canvas>` est désormais enveloppé dans une `<div
class="chart-container">` avec une hauteur fixe en CSS (220px, ou 140×140px pour
l'anneau de répartition des richesses), conformément au pattern documenté par
Chart.js pour les conteneurs responsives.

En complément, une garde défensive a été ajoutée côté JS (`typeof Chart ===
"undefined"`) : si jamais la bibliothèque ne se charge pas (connexion internet
absente, blocage réseau de `cdnjs.cloudflare.com`...), un message explicite
s'affiche à la place d'une zone vide incompréhensible.

### Assistant tutoriel (infobulles guidées), conçu pour être détachable

Nouveau fichier `js/tutorial.js`, ajouté à la demande explicite d'un module
**totalement autonome et retirable en une seule modification** : la suppression
d'une unique ligne (`<script src="/js/tutorial.js">`) dans `index.html` désactive
complètement la fonctionnalité, sans toucher à aucun autre fichier.

Choix d'implémentation qui permettent cette séparation stricte :
- Le fichier cible les éléments à mettre en avant via les **ID déjà existants**
  dans `index.html` (`#btnNewGame`, `#turnTimer`, `#navStats`...) : aucun attribut
  `data-*` supplémentaire n'a été ajouté au HTML pour ce module.
- Ses propres styles CSS sont **injectés par le fichier lui-même** au chargement
  (`injectStyles()`), sans rien ajouter à `style.css`.
- `app.js` n'a **aucune dépendance** vers `tutorial.js` (le sens inverse existe :
  `tutorial.js` observe passivement le DOM produit par `app.js` via un
  `MutationObserver`, sans jamais appeler de fonction de `app.js`).

Fonctionnement : deux parcours définis (`home` : création de partie, `game` :
tableau de bord en cours de partie), affichage automatique uniquement au premier
passage sur chaque écran (mémorisé en `localStorage`), bouton "Ignorer" qui
désactive définitivement les futurs déclenchements automatiques, et un bouton
flottant "?" toujours visible pour rejouer le tutoriel à la demande. Une étape dont
la cible n'existe plus dans le DOM (interface modifiée par la suite) est ignorée
silencieusement plutôt que de bloquer le parcours.

### Correctif : les tests automatiques utilisaient la base de données réelle

Remonté après un premier build complet chez l'utilisateur, en deux temps.

**Premier symptôme** : `mvn clean package` échouait avec `Database may be already
in use: "~/geco.h2.mv.db"` dès qu'une instance de l'application tournait déjà.
Cause : `CreateGameTestCase.java` appelait
`Persistence.createEntityManagerFactory("geco")`, l'unité de **production**,
pointant vers `~/geco.h2` — la vraie base de l'utilisateur. Corrigé en ajoutant
`geco-engine/src/test/resources/META-INF/persistence.xml`, une unité dédiée aux
tests (`"geco-test"`, nom volontairement différent pour éviter toute ambiguïté)
pointant vers une base **H2 en mémoire**, indépendante de `~/geco.h2`.

**Deuxième symptôme, une fois le premier corrigé** : nouvelle erreur,
`The converter class [jyt.geconomicus.helper.EventTypeConverter] ... was not
found`. Cause : `Event.evt` utilise `@Convert(converter = EventTypeConverter.class)`,
et en production ce convertisseur est enregistré via
`META-INF/orm.xml` (`<converter class="...EventTypeConverter"/>`). Ce fichier
`orm.xml` "implicite" n'est recherché par EclipseLink qu'à côté du
`persistence.xml` qui l'a chargé — celui de test (dans `src/test/resources/`)
n'a pas le même voisin que celui de production (`src/main/resources/`), donc le
convertisseur restait invisible pour l'unité de test. Corrigé en listant
directement `EventTypeConverter` dans les classes gérées par l'unité
`"geco-test"`, plutôt que de dupliquer `orm.xml`.

**Validation effectuée** : XML validé (structure + présence de la classe
convertisseur), test recompilé avec de vrais jars JUnit 5 (dépôts système).
Comme précédemment, je n'ai pas pu exécuter le test de bout en bout avec la pile
exacte de production (Jakarta Persistence + EclipseLink 4.0.4 + H2 2.2.224) :
ces versions ne sont disponibles ni via les dépôts système (EclipseLink 2.7.9
seulement, namespace `javax.*` incompatible) ni via Maven Central (bloqué), et
une tentative de récupération des jars via les releases GitHub d'EclipseLink a
échoué (quota d'API atteint). Le raisonnement est solide et suit exactement ce
que le message d'erreur suggère lui-même ("*ensure the converter class ...
exists with the persistence unit definition*"), mais **la confirmation finale
reste à faire par l'utilisateur** via un nouveau `mvn clean package`.

### Statistiques d'activité par joueur (rapport de fin de partie)

Nouvelle route `GET /api/games/{id}/activity`, affichée dans un tableau sous les
indicateurs statistiques du rapport de fin de partie : nombre de transactions,
montant total emprunté, et volume total de monnaie ayant transité par chaque
joueur (crédits + intérêts + remboursements), plus le volume global de la
partie. Ne compte que les événements réellement "transactionnels"
(`NEW_CREDIT`, `INTEREST_ONLY`, `REIMB_CREDIT`, `CANNOT_PAY`, `BANKRUPT`,
`PRISON`) — volontairement pas `JOIN`/`TURN`/`DEATH`/`MM_CHANGE`, qui relèvent
du cycle de vie de la partie plutôt que d'un échange.

### Réflexion : module Galilée (convergence vers la moyenne, monnaie libre) — implémenté

Recherche menée sur le "module Galilée" (exercice d'approfondissement de la TRM,
https://rml.creationmonetaire.info/modules/) et lecture du PDF complet de la TRM
transmis par l'utilisateur (archivé dans `docs-offline/`, voir plus bas) : le
principe consiste à observer que les comptes des joueurs, exprimés **en valeur
relative** (par rapport à la moyenne de la masse monétaire par personne, `M(t)/N(t)`
— formule exacte trouvée dans le PDF : le Dividende Universel vaut
`DU(t) = c × M(t)/N(t)`) plutôt qu'en valeur absolue, **convergent tous vers la
moyenne** au fil du temps.

**Nouvelle route `GET /api/games/{id}/wealth-over-time`**, affichée dans le rapport
de fin de partie : un graphique multi-courbes (une par joueur), avec bascule
valeur absolue / valeur relative (ligne pointillée de référence à 1.0 en mode
relatif), et légende cliquable pour isoler/comparer les joueurs individuellement.

**Erreur de conception trouvée et corrigée en testant réellement** (simulation en
mémoire, sans base de données) : la première version accumulait un gain en continu
à chaque événement financier (crédit, remboursement, intérêt). En comparant avec
la logique déjà existante de `computeWealthByPlayer` (utilisée pour le rapport de
fin de partie), il est apparu que **seuls les événements Mort/Fin de partie
représentent un vrai bilan de richesse** dans le modèle actuel — les échanges
directs entre joueurs (achat/vente de cartes valeur) ne sont pas enregistrés comme
événements individuels aujourd'hui, ils se déroulent physiquement, hors logiciel.
Corrigé : la courbe retient la **dernière valeur réellement connue** (le bilan
constaté à chaque mort), plutôt que d'inventer une évolution continue qui ne
reposerait sur aucune donnée réelle. Un joueur qui meurt puis renaît en cours de
partie a donc plusieurs segments en "dents de scie" sur sa courbe - un par vie -
avec un point de bilan distinct à chaque mort, même si plusieurs morts adviennent
à des tours différents pour des joueurs différents (bug d'alignement corrigé au
passage : chaque point porte son propre tour en abscisse plutôt que de s'appuyer
sur un axe partagé, qui désalignait les courbes dès que les séries avaient des
longueurs différentes).

**Cette limite disparaîtra avec l'étape 3** : le système de cartes numériques
enregistrera chaque échange individuellement, rendant alors possible une courbe de
richesse réellement continue et précise, plutôt qu'un simple "dernier bilan
connu, maintenu constant jusqu'au suivant".

**Validation effectuée** : compilation Java des 3 modules, et surtout **exécution
réelle** (pas seulement compilation) d'un scénario de test en mémoire simulant
plusieurs vies pour plusieurs joueurs, confirmant que le motif en dents de scie et
le point de bilan à chaque mort sont corrects. Les graphiques eux-mêmes n'ont pas
pu être vérifiés visuellement (même limite connue de mon outil de capture, qui ne
peut pas interpréter le bundle Chart.js) - les données sous-jacentes ont donc été
vérifiées textuellement à la place.

## Archive locale de la TRM (PDF)

Le PDF complet de la Théorie Relative de la Monnaie, transmis par l'utilisateur,
est archivé dans `geco-server/src/main/resources/public/docs-offline/`, servi par
l'application et détecté automatiquement par la page Documentation (lien "Ouvrir
le PDF (archive locale)" affiché uniquement si le fichier est présent).

### Documentation multilingue, servie en HTML (corrige un lien cassé)

Remonté par l'utilisateur : le lien "Documentation" de l'écran "Connexion
joueurs" renvoyait vers la page de documentation générale du jeu (monnaie
dette/libre), sans rapport avec la connexion réseau — un contenu inadapté à ce
contexte, pas juste un lien technique cassé (déjà corrigé une première fois
vers un fichier `.md` inaccessible, cette fois vers le bon contenu).

**Nouvelle arborescence**, dans `geco-server/.../public/docs/` (distincte du
dossier `docs/` à la racine du dépôt, qui reste la documentation de travail
pour le développeur, jamais servie) :
```
docs/<langue>/markdown/*.md   <- source, éditable
docs/<langue>/html/*.html     <- généré, servi par l'application
docs/build-docs.py            <- script de conversion (bibliothèque Python "markdown")
```
Deux pages pour l'instant : `regles-du-jeu` (liée depuis la page Documentation
intégrée) et `connexion-joueurs` (liée depuis l'écran "Connexion joueurs"),
en français et anglais. Le contenu est écrit spécifiquement pour les
utilisateurs finaux (pas une réutilisation brute des notes de développement) :
`connexion-joueurs.md` par exemple reprend les instructions pratiques de
`docs/05-etape3-connectivite.md` (racine du dépôt) en retirant tout ce qui
concerne le développement du logiciel lui-même.

**Langue résolue dynamiquement** : le lien vers chaque page est construit côté
client via `window.GecoI18n.getActiveLang()`, pointant vers
`/docs/<langue active>/html/<page>.html`, ouvert dans un nouvel onglet
(`target="_blank"`). Un mécanisme de callback (`GecoI18n.onChange`) a été
ajouté au module i18n pour que ce lien reste correct après un changement de
langue en cours d'utilisation (le texte visible du lien est recréé par
`data-i18n-html`, ce qui effacerait un `href` fixé dynamiquement sans ce
recalcul).

### Annuler / supprimer / éditer un événement

Trois fonctionnalités du manuel original (touche `[z]` pour annuler, suppression
et édition d'un événement) manquaient à l'appel côté web - ajoutées.

**Bonne surprise en creusant le moteur** : le mécanisme nécessaire existait déjà.
`Game.recomputeAll()` (utilisé jusqu'ici uniquement pour les calculs de
statistiques) fait exactement ce que faisait le menu "Recalcul des événements"
de l'app Swing originale : réinitialise tout à zéro (dettes, masse monétaire,
numéro de tour...) puis rejoue chaque événement restant dans l'ordre. Trois
nouvelles méthodes dans `GameService` (`deleteEvent`, `editEvent`,
`undoLastEvent`) s'appuient dessus : retirer/modifier un événement, puis
recalculer intégralement l'état de la partie - nécessaire, puisqu'un événement
au milieu de l'historique peut avoir des conséquences en cascade (supprimer un
crédit change la dette de tous les remboursements suivants).

**Deux vraies erreurs trouvées et corrigées en compilant** (pas seulement des
suppositions non testées cette fois) :
1. `Event.java` n'avait **aucun accesseur public** pour son champ `game` -
   nécessaire pour vérifier qu'un événement à modifier appartient bien à la
   partie demandée. Ajouté (`getGame()`), sans effet de bord sur le reste.
2. Mon premier jet utilisait `em.merge(game)` après recalcul - une erreur,
   trouvée par le compilateur (méthode absente du stub de test), qui a mené à
   une correction plus large : le reste du fichier `GameService.java`
   n'utilise **jamais** `em.merge()`, une entité déjà récupérée par
   `em.find()` dans la même transaction se persiste automatiquement dès qu'on
   modifie ses champs (comportement JPA standard). Retiré pour rester cohérent
   avec le reste du code, plutôt que d'introduire un pattern différent.

**Validation effectuée** : compilation des 3 modules (y compris non-régression
Swing), et surtout un **test d'exécution réelle** (pas seulement une
compilation) simulant un scénario concret - un joueur avec un crédit puis un
remboursement d'intérêt, suppression du crédit, vérification que la dette
recalculée tombe bien à 0 en cascade.

**Nouvelles routes** : `DELETE /api/games/{id}/events/{eventId}`,
`PUT /api/games/{id}/events/{eventId}`, `POST /api/games/{id}/undo`. Diffusées
via WebSocket (`game_recomputed`) avec le détail complet de la partie (pas
juste l'événement modifié), puisque plusieurs joueurs peuvent être affectés en
cascade par un recalcul.

**Interface** : bouton "↩ Annuler" dans le panneau Événements, et deux icônes
(✎ modifier, ✕ supprimer) sur chaque ligne d'événement. L'édition se limite au
principal, à l'intérêt et à la date - les seuls champs saisissables à la
création dans l'interface web actuelle.

### Restructuration du tableau de bord (retours utilisateur, PDF étape 2)

Refonte assez large suite à un retour détaillé (deux versions du PDF, la seconde
corrigeant un point sur la banque) :

- **Chrono qui ne démarre plus à la création de la partie.** Nouveau bouton
  "▶ Démarrer la partie" (`POST /api/games/{id}/start`), distinct d'un
  "Nouveau tour" classique : ne fait pas avancer `turnNumber` ni n'enregistre
  d'événement, il se contente de fixer `turnStartedAt`. Tant qu'il n'a pas été
  cliqué, `turnStartedAtEpochMs` vaut 0 côté API et le chrono reste masqué.
- **Nom de l'animateur**, remplaçant le stepper "nombre de joueurs" (jamais
  réellement utilisé - vérifié en amont : le champ n'était même pas envoyé au
  serveur). Bonne surprise : `animatorPseudo` existait déjà dans le moteur
  (hérité du code original), juste jamais branché côté web.
- **Suppression et renommage de joueur**, avec vérification de nom dupliqué
  pour le renommage. La suppression retire aussi les événements associés au
  joueur (le modèle de données n'a pas de relation directe Player→Event dans
  ce sens, il faut donc les retirer explicitement un par un avant de retirer
  le joueur, sous peine de laisser des événements orphelins en base).
- **Séparation actions par joueur / actions générales** : l'ancien bouton
  générique "+ Événement" (qui mélangeait tous les types dans un seul
  formulaire) est retiré, remplacé par (a) une 3ᵉ icône sur chaque ligne de
  joueur ouvrant un formulaire restreint aux types pertinents pour un joueur
  (mort, crédit, remboursement, défaut/faillite/prison), et (b) six boutons
  d'actions générales directement sur la page (masse monétaire, un joueur
  quitte, rupture technologique, investissement banque, bilan final banque,
  fin de partie).
- **Investissement/bilan banque** : classés comme actions générales sans
  joueur associé (le modèle de données n'a pas de concept de "joueur banquier"
  - point vérifié explicitement avec l'utilisateur, qui a confirmé cette
  interprétation dans la version corrigée de son retour).
- **Toast "Fin de tour"** affiché 3 secondes quand le compte à rebours atteint 0.

**Deux vrais bugs trouvés et corrigés en travaillant** (pas de simples
suppositions) :
1. `openDialog()` ne gère pas les erreurs asynchrones : elle fermait la boîte
   de dialogue immédiatement, sans attendre que l'appel réseau (souvent
   asynchrone) se termine. Ça n'avait causé aucun problème visible jusqu'ici
   (aucune saisie ne pouvait échouer), mais empêchait d'afficher un message
   d'erreur en cas de nom dupliqué au renommage. Corrigé : `onsubmit` attend
   maintenant la fin de `onSubmit()` et ne ferme qu'en cas de succès.
2. **Reliquat de code oublié entre deux messages** : le tour précédent
   s'étant arrêté avant d'avoir retiré l'ancien bloc `el("btnNewEvent")`, ce
   bloc était resté dans `app.js` alors que le bouton HTML correspondant avait
   déjà été supprimé - exactement le type de régression déjà rencontré
   précédemment dans le projet (référence à un élément DOM absent, qui aurait
   fait planter tout le script au chargement). Trouvé et corrigé via l'audit
   systématique des ID avant livraison - désormais un réflexe appliqué à
   chaque changement de cette ampleur.

**Validation effectuée** : compilation des 3 modules (aucune régression),
audit exhaustif de tous les ID (`el("...")`, `data-icon`) contre le HTML
réel, capture d'écran générée avec le vrai CSS du projet confirmant le rendu
visuel de la nouvelle structure.

### Algorithme de suggestion des morts (portage fidèle du programme original)

L'utilisateur a demandé de retrouver l'algorithme exact du programme original
plutôt que d'en réinventer un. Le code source complet a été récupéré (dépôt
GitHub/GitLab de jytou, module `HelperUI.java`, fonctions `createDeathSchedule`
et `suggestDeaths`) et porté fidèlement en Java côté `geco-server`
(`GameService.suggestDeaths`), plutôt que traduit dans un langage naturel qui
aurait risqué d'en perdre la subtilité.

**Principe** : une fonction d'interpolation linéaire (`rebornFunction`) calcule,
à tout instant, combien de joueurs *devraient* avoir déjà connu une renaissance
pour que **tous** les joueurs actifs en aient fait l'expérience avant le dernier
tour prévu. Le point de départ de cette interpolation (le "tour de référence")
se **rebase automatiquement** dès que le nombre réel de morts s'écarte de la
prédiction - sans jamais forcer de rattrapage brutal, l'algorithme se contente
de repartir de la situation réelle. La sélection des joueurs suggérés se fait
ensuite aléatoirement parmi ceux qui n'ont *encore jamais* connu la mort.

**Validation effectuée** : le port a été testé avec un scénario concret (4
joueurs, 10 tours, aucune mort après 4 tours), en traçant le calcul à la main
pour vérifier que le code produit exactement le résultat attendu - y compris un
premier résultat de test qui semblait "faux" au premier abord, mais qui s'est
avéré être une erreur dans mon *scénario de test* (mauvaise hypothèse sur le
moment du rebasage), pas dans le portage lui-même, une fois la trace manuelle
refaite correctement. Un second scénario confirme la garantie fondamentale de
l'algorithme : la somme des morts suggérées sur toute la partie est bien égale
au nombre de joueurs actifs.

**Nouvelle route** : `GET /api/games/{id}/suggested-deaths` (lecture seule),
appelée à l'ouverture de l'étape "Décès" de l'assistant de fin de tour, qui
pré-coche les joueurs suggérés dans la liste - l'animateur reste entièrement
libre de modifier la sélection.

### Badges de statut, robustesse des graphiques, assistant de fin de tour en 5 étapes

**Badges de statut par joueur** : dérivés des événements survenus **depuis le
dernier tour** plutôt que d'un champ persistant - vérification faite dans le
moteur : `DEATH`/`PRISON`/`BANKRUPT` ne modifient jamais le champ `active` du
joueur (seul `QUIT` le fait). Un joueur "mort ce tour" ou "en prison" n'est donc
pas distinguable par un simple champ booléen, il faut regarder son dernier
événement relatif au tour en cours.

**Graphiques** : impossible de confirmer la cause exacte sans retour navigateur
(Console, F12) de l'utilisateur, mais renforcé la robustesse en conséquence :
toute la création des graphiques est maintenant dans un `try/catch` qui
n'affiche jamais une zone vide silencieuse - soit le graphique s'affiche, soit
un message d'erreur explicite apparaît (avec l'erreur journalisée en console
pour diagnostic ultérieur). Point à confirmer avec l'utilisateur lors du
prochain test.

**Assistant de fin de tour, désormais en 5 étapes** (contre 4 avant) :
1. **[nouveau]** Bilan des joueurs endettés - pour chacun, accès rapide à
   "rembourse l'intérêt" / "rembourse le crédit" / "ne peut pas payer" (réutilise
   le même formulaire de classification automatique que l'icône "+" d'une ligne
   de joueur, pas de logique dupliquée).
2. Résumé du tour (inchangé).
3. Décès, avec suggestion automatique (ajoutée précédemment).
4. Nouveaux-nés (inchangé).
5. **[nouveau]** Nouveaux crédits - permet d'accorder des crédits aux joueurs qui
   en veulent avant de démarrer le tour suivant (fonctionnalité qui n'existait pas
   du tout auparavant, remontée explicitement par l'utilisateur : "actuellement on
   n'a pas la possibilité de refaire des crédits entre chaque tour").
6. Préparation / démarrage du tour suivant (inchangé, juste renuméroté).

### Chart.js et QRCode.js hébergés localement (confirmation d'un vrai bug)

L'utilisateur a communiqué le message d'erreur exact affiché à l'écran, qui
correspondait précisément au message de repli déjà prévu pour ce cas : le CDN
`cdnjs.cloudflare.com` n'était pas joignable depuis son réseau. Corrigé en
récupérant les vrais fichiers (`chart.umd.js` via le paquet npm officiel
`chart.js@4.4.3`, `qrcode.js` déjà récupéré plus tôt dans le projet) et en les
embarquant dans `public/js/vendor/`, comme le reste des dépendances du projet
(cohérent avec le principe déjà appliqué au PDF de la TRM, aux avatars, etc :
fonctionne sans connexion internet).

### "Nouveau tour" et "Fin de tour" : deux actions distinctes (schéma fourni par l'utilisateur)

Un schéma clair a permis de lever une ambiguïté du retour précédent : ce sont
deux actions **séparées**, pas une seule bouton ouvrant un assistant :
- **"Fin de tour"** (renommé depuis l'ancien bouton "Nouveau tour") ouvre le
  bilan complet (remboursements des joueurs endettés, décès avec suggestion,
  nouveaux-nés, nouveaux crédits).
- **"▶ Nouveau tour"** (nouveau bouton, vert, bien visible) est une action
  simple et directe, sans aucune fenêtre : elle enregistre l'événement de tour
  et relance immédiatement le chrono.

Le passage de l'un à l'autre est piloté par un indicateur côté client
(`state.turnEnded`), le serveur ne distinguant pas ces deux sous-états ("tour en
cours" vs "bilan terminé, en attente du prochain tour") - une modélisation plus
riche côté serveur serait possible mais non nécessaire pour ce comportement,
purement une question d'affichage. Les morts sont désormais enregistrées dès
leur confirmation à l'étape "Décès" (plutôt qu'en différé à la toute fin), pour
que "Nouveau tour" n'ait plus qu'à enregistrer l'événement de tour lui-même.

### Formulaire "Ne peut pas payer" : saisie automatique précisée

Trois précisions supplémentaires de l'utilisateur ont permis de remplacer la
saisie manuelle des cartes par un vrai calcul automatique
(`computeAutoSeizure`, testé avec un scénario complet reproduisant l'exemple
donné) :
1. **Ordre de saisie strict** : jetons d'abord, puis cartes fortes (valeur 4),
   puis moyennes (valeur 2), puis faibles (valeur 1) - une carte est toujours
   saisie en entier, jamais fractionnée, donc le montant récupéré peut dépasser
   la cible visée.
2. **La banque définit un montant cible** ("valeur que la banque décide de
   saisir"), plutôt que de saisir chaque carte une par une.
3. Les champs "Principal"/"Intérêt" sont remplacés, pour ce type d'événement
   spécifiquement, par l'inventaire du joueur (monnaie restante + cartes par
   valeur) : le programme calcule ensuite lui-même ce qui est réellement saisi,
   affiché en direct avant validation.

### Deux vrais bugs trouvés suite à un retour utilisateur en conditions réelles

**Bug 1 - "Démarrer la partie" n'enregistrait aucun événement.** L'ancien
mécanisme (`GameService.startGame`) se contentait de fixer `turnStartedAt` sans
jamais enregistrer d'événement TURN ni faire avancer `turnNumber` - rien
n'apparaissait donc dans l'historique pour le début du tour 1, et le badge
affichait "Tour 0/10" pendant que le chrono comptait pourtant le temps du tour
1. Corrigé : "Démarrer la partie" utilise désormais exactement le même
mécanisme que le bouton "Nouveau tour" (`recordEvent` de type TURN), ce qui
corrige les deux problèmes d'un coup.

**Bug 2 - un crédit accordé dans l'étape "Nouveaux crédits" de l'assistant
fermait le dialogue sans être enregistré.** Cause trouvée : contrairement à
`openDialog()`, la fonction `openEndOfTurnWizard()` ne réinitialise jamais le
gestionnaire de soumission du formulaire `#dlgForm` - il restait donc accroché
à celui laissé par la **dernière** boîte de dialogue ouverte via `openDialog()`
ailleurs dans l'application (ex: "+ Joueur"). Une simple touche Entrée dans un
champ de l'assistant (ex: le montant d'un crédit) déclenchait alors une
soumission implicite du formulaire HTML natif, exécutant ce gestionnaire périmé
et fermant le dialogue - sans jamais exécuter le vrai code de l'étape en cours.
Corrigé en neutralisant explicitement `onsubmit` dès l'ouverture de l'assistant.

**Validation effectuée** : reproduit le bug avec un vrai DOM (jsdom) simulant
exactement le scénario (gestionnaire périmé accroché, soumission implicite du
formulaire déclenchée), confirmé que le comportement défectueux se produit
sans le correctif et disparaît avec.

### Bouton "Valider" invisible : bug confirmé par capture d'écran, corrigé à la racine

L'utilisateur a fourni une capture d'écran montrant la boîte de dialogue
"Nouvel événement" sans aucun bouton "Valider" visible (seulement "Fermer") :
preuve directe, pas une hypothèse. Cause : `openDialog()` corrigeait le
**texte** du bouton ("Valider") mais ne le rendait jamais visible s'il avait
été masqué par l'assistant de fin de tour juste avant (qui le cache
temporairement pour ses propres besoins), et ne réinitialisait jamais le
libellé "Annuler" si celui-ci avait été changé en "Fermer". Concrètement, tout
chemin fermant l'assistant sans repasser par sa fonction de restauration
(notamment "Ne peut pas payer" dans l'étape "Bilan des joueurs endettés", qui
enchaîne directement sur `openPlayerEventDialog`) laissait la boîte de dialogue
suivante inutilisable - aucun moyen de valider quoi que ce soit.

**Corrigé à la racine** plutôt qu'au cas par cas : `openDialog()` restaure
désormais systématiquement l'état par défaut des deux boutons à chaque
ouverture, sans dépendre de la discipline de chaque appelant à faire le
ménage avant. Ce correctif résout d'un coup tous les endroits où ce problème
aurait pu se manifester, pas seulement celui observé dans la capture.

### Inventaire à la mort d'un joueur, et intérêts sur les nouveaux crédits

Deux lacunes confirmées par l'utilisateur ("actuellement, rien ne m'est demandé
à la mort d'un joueur" / "il n'y a que le montant du crédit qui est demandé") :

- **Nouvelle étape intermédiaire** dans l'assistant, entre la sélection des
  morts et les nouveaux-nés : pour chaque joueur qui meurt, un formulaire
  demande sa monnaie restante et ses cartes faibles/moyennes/fortes avant
  d'enregistrer l'événement - remet correctement son capital en circulation à
  la renaissance, comme demandé. La séquence respecte l'ordre précisé par
  l'utilisateur (le bilan des joueurs endettés, où la banque se paie en
  premier, a déjà lieu à l'étape 0, avant cette étape d'inventaire).
- **Champ "Intérêts"** ajouté au formulaire "Nouveaux crédits" de l'assistant
  (jusqu'ici, seul le principal était demandé, l'intérêt était silencieusement
  enregistré à 0 sans que l'animateur ne puisse le choisir).

### Reprendre les joueurs d'une partie existante (option A)

Sur l'écran "Nouvelle partie", un choix "Nouveaux joueurs" / "Reprendre d'une
partie existante" - dans ce second cas, une liste déroulante des parties
existantes puis une liste à cocher des joueurs de la partie choisie (tous
cochés par défaut, décochables). Permet de comparer monnaie dette et monnaie
libre avec les mêmes joueurs, l'intérêt même du jeu.

Choix d'architecture délibéré : implémenté en pur front-end, sans nouvelle
route API - s'appuie uniquement sur `GET /api/games` (liste des parties) et
`POST /api/games/{id}/players` (déjà utilisée pour l'ajout manuel d'un joueur),
appelée une fois par joueur sélectionné après la création de la partie. Reprise
simple par nom (option A, discutée avec l'utilisateur), qui correspond à ce que
faisait déjà le programme original. Une option B plus robuste (un vrai profil
joueur, séparé de la partie, réutilisable de façon fiable sans dépendre d'une
correspondance par nom) a été envisagée et documentée comme piste pour
l'étape 3, quand les profils/avatars seront de toute façon construits.

### Distribution du nouveau DU (monnaie libre, entre deux tours)

Formule confirmée avec l'utilisateur, en s'appuyant sur les règles officielles
(geconomicus.glibre.org/libre_money.html, qui indique une moyenne de monnaie
par joueur de 7 DU) : **DU(t) = masse monétaire / (7 × joueurs actifs)**,
tronqué. Le facteur de croissance "c" de la formule générale DU(t) = c × M(t) /
N(t) vaut donc 1/7 pour les règles standard (4 couleurs, 3 en jeu + 1 en
attente) - pas un paramètre libre à deviner, une conséquence directe du "7"
déjà présent dans la formule de convergence du moteur.

Nouvelle étape de l'assistant de fin de tour, spécifique à la monnaie libre
(remplace "Bilan des joueurs endettés" et "Nouveaux crédits", propres à la
monnaie dette) : pour chaque joueur actif, l'animateur compte ses jetons
actuels (faibles/moyennes/fortes), le DU du tour est ajouté, et le nouveau
total à lui redonner est calculé et affiché en direct.

**Volontairement un pur outil de calcul, sans événement enregistré** : comme
pour le calculateur de saisie automatique en monnaie dette, cette étape aide
l'animateur à faire le bon calcul mental et à distribuer physiquement les bons
jetons, sans prétendre suivre en continu l'inventaire de chaque joueur en
base. La masse monétaire globale continue d'être suivie séparément par la
formule de convergence déjà existante dans le moteur - aucun risque de double
comptage, puisque cette étape ne modifie jamais `game.moneyMass`.

**Validé par un test réel** (scénario à l'équilibre : 4 joueurs, masse
monétaire 28, DU=1 ; joueur avec 5 de valeur en jetons → nouveau total 6).

### Formulaires sensibles au contexte de la partie (retours utilisateur)

`openPlayerEventDialog` (icône "+" d'une ligne de joueur) accepte désormais des
options (`allowedTypes`, `defaultType`, valeurs pré-remplies) plutôt que de
toujours proposer les 5 types possibles :
- **Avant que la partie n'ait démarré** : seul "Nouveau crédit" est proposé
  (principal pré-rempli à 3, intérêt à 1, éditables) - mort/renaissance et
  remboursement n'ont pas de sens avant le premier tour.
- **En plein milieu d'un tour** (hors assistant de fin de tour) : "Mort/
  Renaissance" et "Ne peut pas payer" sont retirés (ces actions n'existent qu'au
  bilan de fin de tour) ; si le joueur a déjà un crédit en cours, le formulaire
  s'ouvre directement sur "Remboursement crédit" plutôt que "Nouveau crédit".
- **Dans le bilan des joueurs endettés** (étape 0 de l'assistant) : le bouton
  "Ne peut pas payer" ouvre directement le formulaire de saisie automatique,
  sans reproposer un choix de type déjà déterminé par le contexte.

**Étape "Nouveaux crédits"** : joueur, montant, intérêt et bouton (désormais
vert) sur une seule ligne. Chaque crédit accordé peut être retiré via une
croix (corrige une fausse manipulation possible, ex. double-clic).

**"Un joueur quitte la partie"** (monnaie dette), entièrement reconstruit
selon le processus précisé par l'utilisateur : sélection du joueur, puis - s'il
a un crédit en cours - remboursement à la banque (intégral, ou "Ne peut pas
payer" en réutilisant le formulaire de saisie déjà existant), puis inventaire
de départ (monnaie restante + cartes par valeur) avant l'enregistrement final
de sa sortie de partie.

### Protection double-clic sur "Nouveau tour", et validations de remboursement

**"Nouveau tour" cliquable pendant un tour** : le bouton était déjà masqué via
CSS pendant un tour actif, mais rien n'empêchait un double-clic rapide
d'enregistrer deux fois l'événement de tour avant que l'affichage ne se
mette à jour (fenêtre de course classique). Corrigé en désactivant le bouton
immédiatement au clic, avant même l'appel réseau, en plus du masquage habituel.

**Remboursement (intérêt seul ou crédit), trois précisions apportées par
l'utilisateur** :
- Si la dette totale d'un joueur dépasse la masse monétaire actuellement en
  circulation, les champs principal/intérêt restent vides par défaut (plutôt
  que pré-remplis à 3/1, ce qui serait trompeur pour un montant de toute façon
  impossible à honorer).
- Passer à "Remboursement intérêt seul" remet automatiquement le principal à 0.
- Validation bloquante avant tout enregistrement : le principal ne peut pas
  dépasser la dette du joueur, l'intérêt ne peut pas dépasser son intérêt dû,
  et leur somme ne peut pas dépasser la masse monétaire en circulation - message
  d'erreur explicite, dialogue qui reste ouvert tant que ce n'est pas corrigé.

**Testé réellement** avec 3 scénarios (remboursement valide, principal excessif,
somme dépassant la masse monétaire) - les 3 se comportent comme spécifié.

### Assistant de fin de partie (dernier tour), et bilan de la banque

Recherche dans le code source original (`StatsFrame.java`) confirmée par
l'utilisateur : **la banque y est traitée comme un "joueur" à part entière**
dans le bilan final, avec ses propres montants accumulés. Bonne nouvelle en
vérifiant notre propre moteur : ces montants (`interestGained`,
`seizedValues`, `moneyInvestBank`, `cardsInvestBank`) sont **déjà suivis en
continu** par le moteur à chaque événement pertinent - pas besoin de rejouer
l'historique, juste de les exposer (ajoutés à `GameDetailDto`).

**Nouveau flux au dernier tour de la partie**, entièrement distinct du flux
normal (remboursements/nouveaux crédits/DU n'apparaissent plus, comme demandé
explicitement) :
1. Bilan des joueurs endettés (étape déjà existante, réutilisée telle quelle -
   chaque joueur avec un crédit en cours doit payer la banque en premier).
2. **Nouveau** : chaque joueur actif quitte la partie - inventaire (monnaie +
   cartes par valeur) demandé pour chacun, dans le même esprit que "Un joueur
   quitte la partie" mais pour tout le monde d'un coup.
3. **Nouveau** : écran de félicitations avec le bilan de la banque affiché
   (les 4 montants ci-dessus), puis enregistrement de l'événement de fin de
   partie.

Le déclenchement (détection "on est au dernier tour") se fait à l'ouverture de
l'assistant lui-même (`game.turnNumber >= game.nbTurnsPlanned`), ce qui couvre
naturellement les deux déclencheurs demandés (clic manuel sur "Fin de tour", et
countdown à 0 qui ouvre déjà l'assistant automatiquement depuis un correctif
précédent) sans code supplémentaire.

La liaison entre une partie dette et une partie libre (redirection automatique
vers les stats ou vers une nouvelle partie pré-remplie) est explicitement
**mise de côté pour l'instant**, à la demande de l'utilisateur - un concept de
données qui n'existe pas encore et mériterait sa propre réflexion.

### Documentation du code, pour prise en main par un tiers

Demande explicite de l'utilisateur : que le projet reste facile à reprendre par
n'importe qui. Deux ajouts concrets plutôt qu'une déclaration d'intention :
- **`docs/00-vue-ensemble.md`** (nouveau) : point d'entrée pour quelqu'un de
  nouveau sur le projet - structure du dépôt, comment lancer l'app, où trouver
  quoi, philosophie des choix techniques. Distinct de ce fichier-ci
  (`03-architecture-technique.md`), qui reste un journal chronologique utile
  pour comprendre le RAISONNEMENT derrière chaque décision, mais long à lire
  d'une traite et pas pensé comme point d'entrée.
- **En-tête de `app.js` réécrit** avec une vraie carte des sections du fichier
  (le plus gros et le plus complexe du projet) - et un commentaire obsolète
  corrigé au passage ("chargé via CDN" ne correspondait plus depuis le passage
  à un hébergement local des bibliothèques tierces).

### Boucle infinie de l'assistant : vraie cause trouvée et corrigée

L'utilisateur décrivait "une sorte de boucle infinie qui repose sans arrêt les
mêmes questions". Cause trouvée : "Ne peut pas payer" déclenché depuis le
bilan des joueurs endettés fermait tout l'assistant (`renderGameDetail()`,
retour complet au tableau de bord) au lieu de revenir à cette étape. Combiné
au fait que `renderGameDetail()` rappelle `startTurnTimer()`, qui réinitialise
`endToastShown` à `false` - si le compte à rebours était déjà à 0 au moment de
cette fermeture involontaire, le tout premier `update()` suivant redéclenchait
`openEndOfTurnWizard()` immédiatement, créant la boucle.

**Corrigé à la racine** : nouveau paramètre `onSuccess` sur `openPlayerEventDialog`
(symétrique à `onCancel`, déjà existant), qui permet de revenir à une étape de
l'assistant plutôt que de le fermer. Un détail technique important : le simple
fait d'appeler `onSuccess()` ne suffit pas, il faut aussi empêcher `openDialog()`
d'appeler `dlg.close()` juste après (sinon la boîte de dialogue nouvellement
repeuplée par `onSuccess` se referme aussitôt) - fait en levant une exception
après l'appel à `onSuccess()`, sur le même principe déjà utilisé pour les
erreurs de validation.

**Le bouton "Fermer" est retiré des étapes de l'assistant** (demande explicite,
reformulée trois fois) : il n'a plus lieu d'être maintenant que la cause réelle
du blocage est corrigée - l'assistant doit être suivi jusqu'au bout.

### Pause du chrono, vraiment partagée entre tous les écrans

Jusqu'ici documentée comme limitation volontaire ("pause visuelle locale,
non partagée"), corrigée à la demande de l'utilisateur : nouveau champ
`pausedRemainingSeconds` sur `Game` (au lieu d'un indicateur purement côté
client), deux nouvelles routes (`POST /turn/pause`, `POST /turn/resume`),
diffusées par WebSocket comme le reste - le canal existant est déjà générique
(rafraîchit sur réception de n'importe quel message pour la partie affichée),
aucune modification nécessaire de ce côté-là pour que les autres écrans
(tableau de bord ET assistant, puisqu'ils partagent le même minuteur en
arrière-plan) se synchronisent automatiquement.

**Testé mathématiquement** (pas seulement compilé) : scénario avec un délai de
10 minutes simulé entre le clic pause et le clic reprise, confirmé que le temps
restant affiché est identique avant et après - la pause "gèle" bien le temps.

Le bouton "+30s" a aussi été adapté : s'il est actionné pendant une pause, il
ajoute directement au temps figé plutôt que de décaler `turnStartedAt` (qui ne
pilote plus l'affichage tant que la pause est active).

**Étape 3, mise à jour du 31/08/2026** : ce même champ `pausedRemainingSeconds`
pilote maintenant AUSSI le blocage des échanges par QR code entre joueurs -
remonté par l'utilisateur : "lorsque le compte à rebours s'arrête... les
transactions depuis le smartphone... soient bloquées aussi. Lorsque le compte
à rebours repart, les transactions sont automatiquement possibles de
nouveau." Voir `GameService.isTradingAllowed(Game)` : couvre en réalité trois
cas où le "compte à rebours est arrêté" au sens large (partie pas encore
démarrée, partie terminée, minuteur explicitement en pause) - vérifié côté
serveur à la fois à la création d'une offre et à sa rédemption (jamais
seulement côté client, qui n'affiche qu'un message anticipé pour éviter un
aller-retour serveur inutile). Aucune route supplémentaire nécessaire : la
mise en pause/reprise déjà existante suffit, le champ étant déjà partagé.

### Découverte en creusant "Vente réussie" : `player-view.js` n'avait pas de WebSocket

En construisant l'écran "Vente réussie" (le vendeur doit être notifié
automatiquement dès que sa carte est achetée, remonté par l'utilisateur avec
un code de référence exact), il est apparu que `player-view.js` (l'espace
joueur smartphone) n'avait, contrairement au tableau de bord animateur
(`app.js`, voir `connectWs()`), AUCUNE connexion WebSocket - le vendeur
n'avait donc aucun moyen de savoir que son QR avait été scanné avec succès,
seulement un compte à rebours qui finissait par atteindre 0, indiscernable
d'un QR simplement jamais scanné. Ajouté `connectPlayerWs()` (même schéma que
`connectWs()` côté animateur : une connexion, reconnexion automatique toutes
les 2s si coupée) qui écoute les diffusions de type `"transaction"` déjà
émises par `POST /trade-offers/{code}/redeem` (aucun changement serveur
nécessaire, ce canal existait déjà) et déclenche l'écran de succès dès que
`payload.sellerPlayerId` correspond au joueur courant ET qu'une modal carte
est actuellement ouverte sur une offre (sinon, pas de réaction - le solde/
inventaire à jour restera visible au prochain rafraîchissement automatique,
sans interrompre autre chose que le joueur ferait sur son téléphone).

### Prix automatique par niveau, plus de saisie manuelle (31/08/2026)

Remonté par l'utilisateur : "il faut limiter les risques d'erreurs donc le
nombre de saisies humaines. Tout ce qui peut être automatisé doit l'être...
la valeur de la carte est définie dans le code." Le vendeur ne fixe plus
librement un prix (steppers manuels, désormais réservés au troc - la notion
de "valeur automatique en jetons" ne s'y applique pas). En dette/libre, la
valeur d'une carte est déterminée par son NIVEAU, avec la même formule que
les règles officielles (`geconomicus.glibre.org/libre_money.html` : "les
cartes de valeur la plus basse valent chacune 3, les valeurs moyennes 6, les
valeurs hautes 12" - tresforte extrapolée à 24, absente des règles
officielles à 3 niveaux) :

| Niveau     | Valeur | Jetons (voir `LEVEL_JETON_PRICE`, player-view.js) |
|------------|--------|----------------------------------------------------|
| faible     | 3      | 3 jetons faibles                                    |
| moyenne    | 6      | 3 jetons moyens                                     |
| forte      | 12     | 3 jetons forts                                      |
| tresforte  | 24     | 6 jetons forts (pas de 4e dénomination de jeton)    |

Conséquence directe : le QR de vente est désormais généré IMMÉDIATEMENT à
l'ouverture de la modal carte (voir `openCardModal`), avant même le
retournement - "la personne clique sur la carte, swipe pour la vendre",
aucune étape de saisie intermédiaire. Corollaire ajouté au même moment,
lui aussi remonté par l'utilisateur ("au scan, on vérifie que l'acheteur ait
le montant en jetons") : `GameService.recordTransaction` vérifie désormais
réellement le solde de l'acheteur avant d'accepter une transaction - un vrai
trou jusque-là, aucune vérification n'existait. Le solde est vérifié via
`TradeOfferService.peek()` (consultation sans consommer) AVANT `redeem()`
(qui consomme l'offre de façon atomique), pour ne jamais gâcher le QR d'un
vendeur si l'achat échoue pour cette raison.

### Passe de sécurité (02/09/2026) - en anticipation d'un hébergement accessible depuis internet

L'utilisateur envisage, sans l'avoir encore tranché, d'héberger un jour le jeu
sur un serveur accessible depuis internet plutôt qu'un réseau local d'atelier
uniquement - une question sur les prérequis d'un tel déploiement a mené à
examiner le code existant et à trouver deux failles concrètes, jamais un
problème hypothétique.

**1. Le PIN de partie ne protégeait rien côté serveur.** `GameService.verifyGamePin()`
n'était appelé que par la route `/unlock` elle-même (qui ne fait QUE vérifier
le PIN) - toutes les autres routes de données/administration d'une partie
(`GET /api/games/{id}`, qui renvoie même le PIN en clair dans sa réponse,
création/modification d'événements, gestion des joueurs...) étaient
accessibles à quiconque connaissait (ou devinait, les identifiants de partie
étant séquentiels) un identifiant de partie, PIN ou pas, correct ou non. Le
client (voir `api()` dans app.js) anticipait pourtant DÉJÀ une réponse 403 et
savait y réagir (redemande le PIN via une invite, réessaie automatiquement) -
le mécanisme était à moitié construit, seule la vérification serveur
manquait. Corrigé par une nouvelle méthode `requireGamePin(Context, int)`
dans GecoServer, appliquée à 24 routes identifiées comme relevant strictement
de l'animateur (jamais les routes joueur, protégées par leur propre jeton
d'accès individuel - `Player.accessToken`, un mécanisme différent et déjà
correct). `GET /api/games/compare` (qui accepte PLUSIEURS identifiants de
partie à la fois) traitée séparément : chaque partie demandée est filtrée
individuellement (incluse seulement si non protégée ou si le PIN fourni lui
correspond), plutôt que de tout rejeter si les parties comparées ont des PIN
différents.

Classification vérifiée par script exhaustif (24 routes protégées, 28
laissées ouvertes à raison : jetons joueur, inscription publique, offres
d'échange protégées par leur propre jeton, `/unlock` lui-même).

**2. Les diffusions WebSocket n'étaient pas cloisonnées par partie.**
`broadcast()` envoyait chaque message à TOUTES les connexions ouvertes sur le
serveur, quelle que soit la partie suivie - un filtrage purement côté client
(`if (String(msg.gameId) !== String(state.gameId)) return;`, donc
contournable) faisait le tri. Sans conséquence tant qu'une seule partie
tourne à la fois sur le réseau local d'un atelier, mais un vrai risque de
fuite de données entre parties dès qu'un serveur en héberge plusieurs
simultanément. Corrigé : `Set<WsContext> mSessions` remplacé par
`Map<WsContext, Integer> mSessionGameIds` (session -> partie suivie),
alimentée soit par un paramètre `?gameId=` à la connexion (joueur, qui reste
toujours sur la même partie pendant toute sa session), soit par un message
`{"type":"subscribe","gameId":X}` envoyé par le client à chaque changement de
partie consultée (animateur - `connectWs()` reste ouvert en continu au
travers de plusieurs navigations, contrairement au joueur). `broadcast()` ne
cible plus que les sessions associées à la bonne partie.

**Trouvaille en creusant ce second point** : la diffusion `game_recomputed`
(déclenchée après CHAQUE action animateur) véhiculait `GameDetailDto`, qui
inclut le PIN en clair - un joueur de la même partie (qui connaît forcément
l'identifiant de partie, donc peut se connecter directement à `/ws?gameId=X`
sans passer par l'interface) aurait pu y lire le PIN sans jamais le deviner,
contournant entièrement la protection tout juste ajoutée. Vérifié que le
client n'utilise cette diffusion QUE comme signal pour redemander l'état à
jour via un appel REST classique (déjà authentifié) - jamais lue directement
- avant de retirer le PIN de la charge utile diffusée (`stripPin()`, une
copie du DTO avec pin -> null, jamais des données REST classiques,
uniquement les diffusions).

**3. Aucune limitation de débit n'existait nulle part.** Un PIN à 6 chiffres
ou un code d'échange QR à 6 caractères (32^6 combinaisons), même peu probables
à deviner en un seul essai, restent vulnérables à un grand nombre de
tentatives automatisées sans un tel frein - un vrai risque à l'échelle
d'internet, absent sur un réseau local isolé. Implémentée volontairement
SANS nouvelle dépendance Maven (impossible de vérifier qu'une bibliothèque
tierce se télécharge/compile correctement sans accès réseau dans cet
environnement de développement) : une fenêtre glissante simple, en mémoire,
par adresse IP et par point sensible (`allowRequest()`) - appliquée au PIN
(10 tentatives/minute) et aux codes d'échange QR, consultation et rédemption
confondues (30/minute).

**Limite assumée et signalée à l'utilisateur** : l'ajout de `ws.onMessage()`
côté serveur (réception du message `subscribe`) utilise `Context.message()` -
une méthode de l'API Javalin qui n'a pas pu être vérifiée avec certitude
absolue (aucune dépendance en cache localement, réseau bloqué pour un test de
compilation réel). Tout le reste de cette passe a été vérifié soit par
exécution directe (`bash -n`, `node -c`), soit par simulation de la logique
en Python (comptage exhaustif des routes classifiées, filtrage `broadcast()`,
correspondance champ à champ de `stripPin()`, fenêtre glissante testée sur 4
scénarios) - ce point précis reste la seule zone d'incertitude réelle de
cette session.

**Hors périmètre de cette passe, volontairement** (remonté par l'utilisateur
comme des questions distinctes, pas encore tranchées) : les routes
d'administration GLOBALE du serveur (liste/création de parties, plugins,
réglages, catalogues, langues) restent sans authentification - une question
différente de la protection PAR PARTIE traitée ici, qui nécessiterait un vrai
concept de compte administrateur si un serveur venait à héberger des parties
de plusieurs organisations indépendantes sans lien entre elles.

### Autres correctifs (retours utilisateur)

- **Actions conditionnelles dans le bilan des joueurs endettés** : "Rembourse
  l'intérêt" masqué si déjà à 0 ; "Rembourse l'intérêt"/"Rembourse le crédit"
  masqués si la masse monétaire en circulation est insuffisante pour couvrir le
  montant concerné (ne laissant alors que "Ne peut pas payer"). Testé avec le
  scénario exact fourni (masse monétaire = 1, intérêts déjà remboursés).
- **CSS responsive** : la ligne "Nouveaux crédits" (Joueur/Montant/Intérêt/
  bouton) passe à la ligne plutôt que de forcer un défilement horizontal sur
  petit écran ; correction d'un piège classique de CSS Grid (`1fr 1fr` sans
  `minmax(0, ...)` peut empêcher les colonnes de rétrécir sous leur contenu).
- **Bouton "Fin de tour"** déplacé dans l'en-tête à côté du chrono (il était
  égaré dans le panneau Événements, loin d'être "tout à droite" comme demandé).

### Débordement horizontal des cases à cocher/boutons radio dans les dialogues

L'utilisateur a fait le diagnostic lui-même (désactivation de propriétés CSS
une à une) et proposé un correctif précis : la règle générale `dialog input,
dialog select { width: 100%; padding: 0.55rem; ... }`, pensée pour les champs
texte/nombre/liste, s'appliquait aussi aux cases à cocher et boutons radio, qui
se retrouvaient à vouloir remplir toute la largeur de leur ligne - combiné à
`flex-shrink: 0`, ça provoquait le débordement horizontal observé sur les
étapes de l'assistant contenant une case à cocher (ex. "Démarrer
automatiquement le tour suivant").

**Corrigé de façon plus ciblée** que la proposition initiale : plutôt que de
retirer `width`/`padding` du sélecteur général (ce qui aurait aussi affecté
tous les autres champs texte/nombre/liste de l'app), une règle plus spécifique
(`dialog input[type="checkbox"], dialog input[type="radio"]`) exclut
uniquement ces deux types à la source. 6 endroits de l'app étaient concernés
(pas seulement celui visible sur la capture) : la case "Pénalité d'un jeton",
les boutons radio "Nouveaux joueurs"/"Reprendre d'une partie", les cases à
cocher de sélection des morts, "Démarrer automatiquement le tour suivant", et
la liste de reprise de joueurs - tous corrigés d'un coup.

**Vérifié avec un rendu réel** reproduisant l'écran exact de la capture
d'écran fournie : plus de débordement, case à cocher à sa taille normale.

### Détection réelle de fin de partie (verrouillage des contrôles de tour)

Trou trouvé grâce à une capture d'écran : après l'assistant de fin de partie
(qui enregistre bien l'événement "E"/Fin de partie), **rien côté client ne
vérifiait si la partie était terminée** - le tableau de bord se rafraîchissait
normalement, chrono actif inclus, laissant croire qu'un nouveau tour restait
possible alors que la partie était définitivement close.

Corrigé en dérivant `gameEnded` directement de l'historique
(`game.events.some(e => e.type === "E")`) plutôt que d'ajouter un nouveau
champ côté serveur pour ça - une seule ligne suffit, l'information existe déjà.
Une fois `gameEnded` vrai : chrono arrêté, tous les boutons de gestion de tour
masqués (Démarrer/Fin de tour/Nouveau tour/Pause/+30s), les 6 actions générales
masquées, et un bandeau "🏁 Partie terminée" remplace visuellement la zone du
chrono pour que ce soit sans ambiguïté.

### Bug de fond : code court vs nom complet d'énumération

Le verrouillage de fin de partie ajouté à la session précédente ne se
déclenchait jamais - cause trouvée : `EventDto.from()` (côté serveur) renvoie
`e.getEvt().name()`, c'est-à-dire le **nom complet** de la constante
d'énumération ("END", "INTEREST_ONLY"...), alors que les *requêtes*
d'enregistrement d'événement utilisent volontairement un **code court** à une
lettre ("E", "I"...), voir `EventTypeConverter`. Deux endroits du code
lisaient les événements reçus du serveur en comparant au code court au lieu du
nom complet - le contrôle "partie terminée" (`type === "E"` au lieu de `"END"`)
et la règle "pas d'obligation si intérêts déjà remboursés pendant le tour"
(`type === "I"` au lieu de `"INTEREST_ONLY"`) ne fonctionnaient donc jamais,
silencieusement, malgré des tests unitaires qui passaient (ces tests
utilisaient directement le code court comme donnée d'entrée, sans jamais
passer par le vrai format renvoyé par le serveur - un angle mort du test).

**Recherche exhaustive faite** pour ne pas laisser d'autre occurrence du même
bug : toutes les comparaisons `.type === "..."` du fichier vérifiées une par
une contre les vrais noms de constantes de `EventType` - confirmé qu'aucune
autre occurrence ne subsiste. Validé avec un test Java réel (pas une simple
relecture) confirmant les valeurs exactes renvoyées par `EventType.name()`.

**Autre demande de l'utilisateur** : une fois l'assistant de fin de partie
terminé, retour automatique sur l'écran "Nouvelle partie" plutôt que de rester
sur le tableau de bord (même verrouillé) - la partie est close, plus rien à y
faire dans l'immédiat. Le tableau de bord verrouillé reste néanmoins la bonne
expérience pour re-consulter une partie déjà terminée plus tard (ex. depuis
"Parties récentes") - les deux comportements coexistent, chacun pour son
contexte.

### Un joueur en prison/banqueroute ne doit pas pouvoir demander un nouveau crédit

Remonté avec un exemple précis (Porthos venant d'être mis en prison, toujours
proposé dans la liste "Nouveaux crédits" de l'étape suivante de l'assistant).
Corrigé en réutilisant directement `getPlayerStatusBadge` (même détection que
le badge affiché sur sa ligne de joueur) plutôt que de dupliquer une troisième
fois la logique "événements depuis le dernier tour" - un joueur en prison, en
banqueroute, ou mort ce tour-ci est désormais exclu de la liste des joueurs
éligibles à un nouveau crédit. Testé avec le scénario exact du retour (un
joueur en prison parmi trois joueurs normaux) : bien exclu, les trois autres
restent proposés normalement.

### Assistant bloqué après un décès avec crédit en cours

Remonté avec une capture d'écran caractéristique : le titre de l'étape se
mettait à jour ("Nouveaux crédits") mais le contenu restait celui de l'étape
précédente, symptôme classique d'une exécution interrompue en cours de rendu.
Cause trouvée : `renderStepDeathInventory()` enregistrait bien les événements
de mort mais ne rafraîchissait jamais l'état local de la partie avant de
passer à l'étape suivante, contrairement au reste de l'assistant - les étapes
suivantes travaillaient donc sur des données périmées.

Corrigé à la racine (rafraîchissement ajouté, sur le même principe que les
autres transitions de l'assistant) et, en plus, un filet de sécurité ajouté à
l'étape "Nouveaux crédits" : si le calcul des joueurs éligibles échoue pour une
raison quelconque, l'étape se rabat sur un filtrage plus simple plutôt que de
rester bloquée avec un contenu à moitié à jour - pour qu'une éventuelle cause
non identifiée ne puisse plus reproduire ce blocage.

### Restructuration complète de l'ordre de l'assistant (monnaie dette)

L'utilisateur a fourni un document de spécification détaillé décrivant
précisément le déroulement attendu de l'entre-deux-tours. Changement de fond,
confirmé par ce document : il faut savoir QUI MEURT avant de regarder les
crédits, pas l'inverse - un joueur qui meurt a l'**obligation** de régler son
crédit maintenant (la banque ne peut pas "attendre" avec lui puisqu'il va
disparaître), alors qu'un joueur qui reste en jeu peut très bien continuer à
devoir de l'argent d'un tour sur l'autre, parfois toute la partie, au bon
vouloir de la banque.

**Nouvel ordre** : Décès (sélection) → Bilan des joueurs endettés (deux
listes distinctes : les mourants avec dette, obligatoires - "Continuer" est
bloqué tant qu'ils ne sont pas réglés ; les autres joueurs endettés,
facultatifs) → Inventaire des morts (si décès) → Nouveaux-nés → Nouveaux
crédits → Récap final.

**Un joueur qui meurt (ou n'importe qui au dernier tour de la partie) n'est
jamais mis en banqueroute/prison** - confirmé explicitement par le document
("le joueur ne va pas en prison puisqu'il meurt" / "on ne retrouve pas le
concept de banqueroute ou de prison après le dernier tour"). `classifyCannotPay`
accepte désormais un paramètre `exemptOfStatus` : la banque saisit toujours,
mais la classification se limite à une simple saisie dans ces deux cas.

**Nettoyage de code mort** : l'étape "Résumé du tour" (`renderStep1`), devenue
inaccessible avec ce nouvel ordre, a été retirée plutôt que laissée orpheline -
son contenu utile (crédits accordés, remboursements, intérêts prélevés) a été
repris dans le récap final enrichi, désormais complété par les morts/
renaissances et prison/banqueroute, comme demandé.

**Bug de staleness du même type que celui corrigé précédemment, trouvé en
creusant** : `turnEvents`/`sumBy`, calculés une seule fois à l'ouverture de
l'assistant, ne se mettaient jamais à jour même quand `game` était rafraîchi en
cours de route - remplacés par `computeEventsSinceLastTurn()`, qui recalcule à
chaque appel depuis les données actuelles.

**Autres précisions du document, également implémentées** : info-bulle "Fin du
dernier tour" distincte au dernier tour (au lieu du "Fin de tour" habituel),
affichée aussi bien au clic manuel qu'au compte à rebours à 0 (les deux
déclencheurs se comportaient différemment avant) ; info-bulle "Nouveau tour"
symétrique quand le tour suivant démarre automatiquement depuis la case à
cocher du récap final.

**Testé réellement** : scénario d'un joueur endetté sélectionné pour mourir -
confirmé que "Continuer" est bloqué avant règlement du crédit, puis débloqué
une fois réglé.

**Ce qui reste du document de spécification, pas encore traité dans cette
session** (volume trop important pour une seule livraison) :
- Le "rendu de monnaie" : afficher automatiquement ce que la banque rend au
  joueur si la saisie dépasse le montant visé (ex: une carte forte saisie pour
  couvrir un petit reste rend de la monnaie en retour).
- La case à cocher (décochée par défaut) sur l'écran de fin de partie pour
  rediriger vers les statistiques plutôt que vers "Nouvelle partie".
- Vérifier que la liste des joueurs et la masse monétaire globale se mettent
  bien à jour en direct pendant l'octroi de nouveaux crédits.

### Les 3 derniers points du document de spécification

**Trop-perçu lors d'une saisie** : `computeAutoSeizure` expose désormais
`overshoot` (le montant récupéré au-delà de la cible visée), affiché à
l'animateur dans le formulaire "Ne peut pas payer". Écart assumé par rapport à
la demande initiale (rendre le trop-perçu réparti en dénominations précises,
"X cartes faibles et Y cartes moyennes") : en creusant le calcul, une telle
répartition automatique s'avère quasiment toujours vide dans la pratique -
l'algorithme de saisie épuise systématiquement les jetons/cartes les plus
petits disponibles avant de devoir dépasser la cible avec une carte plus
grosse, donc il ne reste jamais de petite coupure à rendre au moment du calcul.
Plutôt que d'afficher une répartition fausse la plupart du temps, le montant
brut est affiché tel quel - l'animateur choisit physiquement comment le rendre.

**Case à cocher de redirection en fin de partie** : décochée par défaut,
ajoutée à l'écran final de l'assistant de fin de partie ("Afficher les
statistiques de la partie") - cochée, elle redirige vers `renderReport()`
plutôt que vers "Nouvelle partie".

**Mise à jour en direct pendant l'octroi de nouveaux crédits** : `game` (et
l'affichage de la masse monétaire, désormais visible dans cette étape) sont
rafraîchis à chaque crédit accordé, pas seulement à la suppression comme
c'était le cas jusqu'ici.

### Le chrono ne s'arrêtait pas au clic manuel sur "Fin de tour"

Seul le passage naturel du compte à rebours à 0 arrêtait le chrono
(indirectement, via la garde déjà présente dans `update()`) - un clic manuel
sur "Fin de tour" avant l'échéance naturelle laissait l'intervalle tourner en
arrière-plan pendant toute la durée de l'assistant. Corrigé en appelant
`stopTurnTimer()` explicitement dans ce gestionnaire, quelle que soit la façon
dont la fin de tour a été déclenchée.

### Statistiques : seuil de pauvreté, vue avec/sans banque, diagramme temps réel

En creusant l'existant avant de se lancer, bonne surprise : l'essentiel de ce
qui était demandé était déjà là (histogramme, moyenne, écart-type, indice de
Gini, courbe de masse monétaire dans le rapport de fin de partie) - le
périmètre réel restant était plus réduit qu'il n'y paraissait.

**Seuil de pauvreté** ajouté à `FinalReport` (50% de la médiane, convention
statistique courante - ex. Eurostat - assumée en l'absence d'une définition
différente précisée), avec le nombre de joueurs en-dessous. Testé avec un
scénario concret.

**Vue avec/sans banque** (monnaie dette uniquement) : portage d'un concept déjà
présent dans l'app Swing originale (StatsFrame.java) - la banque comptée comme
un "joueur" de plus dans l'histogramme, en réutilisant les montants déjà suivis
en continu par le moteur (intérêts perçus, valeurs saisies, investissements).
Sélecteur ajouté sur l'écran de rapport.

**Diagramme "Crédits en cours par joueur"**, en remplacement du donut de
répartition des richesses jugé peu clair (demande explicite, avec une
explication du donut fournie en réponse) - un diagramme en barres qui montre
la dette actuelle de chaque joueur, **mis à jour en place plutôt que détruit/
recréé** pour que Chart.js anime réellement la transition quand les dettes
diminuent (contrairement aux autres graphiques de l'écran, qui n'ont pas ce
besoin d'animation continue). Le donut d'origine est conservé pour la monnaie
libre, où la notion de crédit/dette ne s'applique pas.

**Reporté, hors périmètre de cette session** : la comparaison agrégée entre
une partie en monnaie dette et une partie en monnaie libre nécessite le
concept de liaison entre deux parties, volontairement mis de côté par
l'utilisateur plus tôt dans le projet - à reprendre ensemble le moment venu.
Le module Galilée (courbes de masse monétaire relative par joueur) reste
prévu pour l'étape 3, comme déjà noté par l'utilisateur.

### "Histogramme" du rapport : reconstruit fidèlement au vrai modèle original

L'utilisateur a fourni une capture d'écran de l'app Swing originale montrant
ce qu'il attendait réellement : une **barre par joueur (nommé)**, avec 3
lignes de référence horizontales (moyenne, écart-type, seuil de pauvreté) -
pas des tranches groupées comme précédemment construit (`WealthBucket`, un
vrai histogramme statistique au sens strict, mais pas ce qui était demandé).

Code source de `StatsFrame.AggregatedStats` (dessin `Graphics2D` bas niveau)
retrouvé et lu pour comprendre exactement la construction : confirme au
passage que le seuil de pauvreté à 60% de la médiane (`mPoors.set(i, median *
0.6)`) était déjà exactement la même formule dans l'original - la correction
faite un peu plus tôt dans la session était donc la bonne valeur.

**Écart assumé par rapport à l'original**, sur la ligne "Écart-type"
uniquement : l'original la dessine sur une échelle en pourcentage séparée
(0-150%, l'écart-type y étant stocké comme un coefficient de variation), un
mécanisme à double échelle qui aurait pu prêter à confusion une fois modernisé.
Choix fait ici : la ligne "Écart-type" est positionnée à **moyenne - écart-
type**, en valeur absolue, **sur la même échelle que les barres et les deux
autres lignes** - directement comparable, plus simple à lire, tout en gardant
le même esprit (une bande de référence sous la moyenne).

Reconstruit avec un graphique Chart.js mixte (barres + lignes superposées,
supporté nativement), testé avec un scénario reproduisant l'esprit de la
capture fournie.

### Monnaie libre : décès avant DU, et distinction mourant/non-mourant

Précisé par l'utilisateur : en monnaie libre aussi, il faut savoir **qui
meurt AVANT** de faire les inventaires - l'étape "Décès" (déjà construite pour
la monnaie dette, `renderStep2`) est donc réutilisée telle quelle en tête de
l'assistant pour les deux systèmes monétaires, plutôt que dupliquée.

**Distinction importante à l'étape suivante**, désormais implémentée : un
joueur qui **reste en jeu** garde son inventaire actuel, auquel le DU
s'ajoute (calcul indicatif, comme avant) ; un joueur qui **meurt ce tour**
voit son inventaire dressé puis renaît avec le **DU seul** (pas ses
anciennes pièces + DU) - un vrai événement de mort est donc enregistré pour
lui (même principe que l'inventaire des morts en monnaie dette), là où
l'étape restait jusqu'ici un pur outil de calcul sans aucun enregistrement.

Le récap final (`renderStep4`) masque désormais les lignes propres au crédit
en monnaie libre (aucun sens dans ce système), remplacées par le montant du
DU distribué ce tour-ci.

**Testé réellement** avec un scénario contrastant un joueur qui meurt (9 de
patrimoine avant, doit repartir avec le DU seul = 3, pas 12) et un joueur qui
reste (5 + DU 3 = 8) - comportement confirmé conforme.

## Ce qui n'a volontairement pas changé

Sur l'ensemble de ces étapes, **aucune ligne de la logique métier
(`Game`/`Player`/`Event`/calculs TRM/monnaie dette) n'a été modifiée** — seules les
annotations techniques de persistance/sérialisation ont été renommées. C'est un
choix délibéré : cette logique est le cœur du projet, elle a été conçue et
éprouvée par jytou et la communauté, et toute réécriture serait un risque inutile
pour la fiabilité des résultats produits par le jeu.

## Prochaines étapes techniques envisagées

- Étoffer l'API REST et le front (écran de statistiques en temps réel, gestion
  complète des crédits/remboursements).
- Étape 3 : routes d'inscription joueur via QR code, gestion de sessions
  multi-clients, script d'installation Docker.

### Note d'architecture (06/09/2026) : ce qui sera commun à troc/dette, ce qui sera propre à chacun

Remontée par l'utilisateur en prévision de l'extension du smartphone aux
systèmes troc et dette (pas encore commencée à cette date) - à garder à
l'esprit pour ne pas coder ces mécanismes de façon trop spécifique à la
monnaie libre, ce qui compliquerait leur réutilisation plus tard :

**Mécanismes COMMUNS aux trois systèmes monétaires (déjà construits pour la
monnaie libre, à réutiliser tels quels)** :
- La pioche de cartes et l'encaissement automatique des carrés (voir
  `CardSquareEvent`, `GameService.checkAndCashInSquares`,
  `computePlayerCardInventory`) - un mécanisme de cartes, indépendant de la
  façon dont on les paie.
- La synchronisation avec le compte à rebours de tour (voir
  `GameService.isTradingAllowed`, qui bloque les échanges hors tour actif -
  ne dépend d'aucun système monétaire).
- Le comptage/l'affichage des cartes détenues (`Player.startingCardsJson`,
  `cardInventoryResetAt`, l'écran "Mes cartes" et son regroupement par
  valeur/catégorie/quantité).

**Le comptage des jetons (`computeTradeBalance`, la lecture des points de
contrôle `WEALTH_CHECKPOINT`)** est un mécanisme de la monnaie LIBRE, mais
sera AUSSI commun à la monnaie DETTE (qui utilise elle aussi des jetons entre
joueurs, contrairement au troc).

**Ce qui sera PROPRE à chaque système, jamais partagé** :
- Monnaie dette : tout ce qui touche à la banque et aux crédits (émission,
  remboursement, intérêts) - un mécanisme entièrement absent de la monnaie
  libre et du troc.
- Troc : un système d'échange de cartes SANS jetons - une transaction "carte
  contre carte(s)" directe, à part entière, sans passer par
  `computeTradeBalance` ni aucun calcul de valeur en jetons.

### Note d'architecture (07/09/2026) : futur choix "mode jetons" vs "mode monnaie numérique"

Remontée par l'utilisateur, à prévoir pour plus tard (pas encore commencée à
cette date - la priorité reste de finir et tester le mode jetons ci-dessus) :
sur l'écran des paramètres, pour une partie avec smartphones, l'animateur
pourra choisir entre deux modes :

- **Mode jetons** (celui qu'on construit actuellement, voir la section
  "Traçabilité réelle des jetons par dénomination" plus haut) : simule les
  jetons physiques faible/moyen/fort d'un Geconomicus classique, avec rendu
  de monnaie si besoin.
- **Mode monnaie numérique** (à construire) : plus de dénominations du tout -
  chaque joueur n'a qu'un MONTANT GLOBAL, comme un compte bancaire simple.
  Simplifie en cascade : plus besoin de `findPaymentWithChange`/
  `tryMakeChange` (une transaction ne fait que soustraire/ajouter une valeur,
  jamais de "compte exact" à trouver), et le formulaire d'inventaire de
  l'assistant à l'entre-deux-tours n'aurait plus qu'UN SEUL champ par joueur
  (au lieu des trois - faible/moyen/fort - actuels).

Point de vigilance pour l'implémentation future : concevoir le nouveau champ
de configuration (ex. `Game.jetonMode` ou similaire) et les DEUX chemins de
code (transaction, formulaire d'inventaire, affichage smartphone) de façon à
ce qu'ils cohabitent proprement dans le même système de monnaie libre - pas
un système monétaire de plus, seulement une variante d'affichage/mécanique à
l'intérieur de la monnaie libre existante.

### Refonte majeure (09/09/2026) : le DU était confondu avec un simple décompte de jetons

Remontée par l'utilisateur, après plusieurs sessions de test réel en
conditions réelles : "le programme actuel confond le DU et la monnaie...
l'assistant prétend donner 1 DU mais il ne donne qu'1 jeton car il confond
le jeton et le DU." L'ancienne formule (`masse / (7 × joueurs actifs)`,
document au §4.2 du cahier des charges) était une approximation qui ne
correspondait pas à la vraie Théorie Relative de la Monnaie.

**Formule TRM exacte** (vérifiée par recherche, plusieurs sources
concordantes) :

```
DU = c × (masse_monétaire / joueurs_vivants)
c  = ln(ev/2) / (ev/2)
```

où `ev` (espérance de vie) a été confirmée par l'utilisateur comme étant la
durée simulée de la partie précise (nombre de tours × 8 ans - convention du
jeu déjà en place, "80 ans / 10 tours"), jamais une référence fixe à 80 ans.
Vérifié numériquement : `c≈9,2%/an` pour `ev=80` ans, exactement la valeur
de référence documentée par la TRM.

**Deuxième bug trouvé en testant, pas en lisant le code** : faire grandir
la masse monétaire globale de façon INDÉPENDANTE (une formule côté serveur,
une autre côté client pour les jetons réellement distribués, chacune avec
son propre arrondi) crée un écart qui peut s'accumuler progressivement dès
que "Valeur d'une pièce faible" ne divise pas exactement la dotation de
référence. Corrigé en profondeur : la masse monétaire n'est plus jamais
ajoutée de façon indépendante - elle est **recalculée directement comme la
somme réelle des jetons physiquement détenus par tous les joueurs actifs**
(`Game.computeMoneyMassFromActivePlayersJetons`), garantissant une
cohérence parfaite par construction. Vérifié par simulation exhaustive sur
135 configurations différentes (2 à 8 joueurs, 9 valeurs de "Valeur d'une
pièce faible", 3 durées de partie) sur 10 tours chacune : pire écart
observé = 0,5 unité monétaire (la limite théorique incompressible avec des
montants entiers), et surtout, cet écart ne grandit jamais avec le temps.

**Prix des cartes fixé en DU**, également remonté par l'utilisateur : "il
faut fixer le prix d'une carte de monnaie libre en DU. Par exemple, une
carte faible est égale à 0.5DU." Uniquement pour la monnaie libre suivie
par smartphone - la monnaie dette garde son prix fixe en jetons, le mode
classique (libre sans smartphone) reprend le code existant, non concerné.
Un prix qui varie donc réellement d'un tour à l'autre désormais, suivant le
DU courant, contrairement à l'ancien système de valeur abstraite fixe.

**Décision de conception prise avec l'utilisateur** : garder le système de
jetons entiers (avec une imprecision résiduelle minime et bornée, prouvée
par simulation) plutôt que de passer à des montants exacts mais avec
plusieurs décimales - pour préserver l'ancrage pédagogique concret du jeu
(des jetons qu'on peut compter) et la cohérence avec le mode classique.

**Simplification connexe** (même session, avant la refonte du DU) : depuis
que le DU est toujours distribué en jetons faibles uniquement, les jetons
moyens/forts ne sont plus jamais réellement en circulation en mode
smartphone - simplifié en conséquence : un seul champ "Jetons" dans tous
les écrans de l'assistant (plus de détail faible/moyen/fort), et retiré de
l'affichage des écrans Accueil/Profil du smartphone.

**Nouveau système de journalisation**, remonté par l'utilisateur après
plusieurs sessions de débogage ralenties par des échecs silencieux : "un ou
plusieurs moyens à mettre en œuvre... pour ne plus avoir d'erreur
silencieuse et toujours avoir des logs ou des pistes en cas de problème...
aussi bien sur le serveur que sur les applications smartphone des joueurs
ou l'application de l'animateur." Gestionnaires d'exception globaux côté
serveur (`GecoServer.java`), capture automatique côté client (erreurs JS
non attrapées, promesses rejetées, requêtes réseau échouées) avec un
panneau de diagnostic consultable/copiable sur smartphone (pas d'outils de
développement disponibles là-bas). A permis de trouver et corriger, le soir
même, un vrai bug (un accroc réseau passager effaçait toute l'application
du joueur) - la valeur de ce système a été démontrée en conditions réelles
dès sa mise en place.

**Monnaie dette + smartphone (17/09/2026)** : demande explicite de
l'utilisateur, avec une question d'architecture posée directement - "est-il
possible de mettre le système de gestion de la pioche commun aux différents
types de parties (monnaie dette, monnaie libre et troc) ? Ainsi, si je
détecte un bug, je le notifie et il sera pris en compte sur les trois
systèmes en même temps." Réponse trouvée en auditant le code existant :
`checkAndCashInSquares` (le mécanisme du carré lui-même) était déjà
entièrement agnostique du système monétaire - seules
`captureDeckPlayerCountIfNeeded`/`dealStartingHandsForLibreIfNeeded`
excluaient explicitement la dette (et le troc, rejoint le lendemain, voir
plus bas). Élargies : un même correctif sur la pioche bénéficie désormais à
plusieurs systèmes à la fois, exactement comme demandé.

Réutilise donc directement l'infrastructure de la monnaie libre (pioche
partagée par modèle, carrés, `Player.jetonWeak` pour un compte réel et
mutable) avec les adaptations propres à la dette : "1 jeton faible = 1
unité monétaire", plus de jetons moyens/forts, prix des cartes fixe en
jetons faibles (3/6/12/24, même barème de valeur qu'avant), terminologie
"unités monétaires" au lieu de "jetons" (uniquement pour un joueur
réellement suivi par smartphone, jamais pour la dette classique). Un vrai
bug trouvé en jouant plusieurs parties complètes via l'API REST plutôt
qu'en se contentant d'une relecture de code (méthode déjà éprouvée dans ce
projet, voir plus haut) : `NEW_CREDIT`/`REIMB_CREDIT`/`INTEREST_ONLY` ne
mettaient jamais à jour `Player.jetonWeak` - un crédit accordé n'alimentait
donc jamais le solde physique du téléphone du joueur. Un second bug trouvé
en seconde relecture indépendante (l'utilisateur a explicitement demandé la
mise en place d'un second agent de contrôle pour cette phase de travail,
suivi depuis pour toutes les phases suivantes) : un achat de carte ne
déplaçait pas non plus réellement les jetons entre acheteur et vendeur en
dette+smartphone. Les deux corrigés le jour même, voir l'historique des
commits (`ba1e8d8`, `5d2d5cd`, `50c175c`, `a044d72`).

**Monnaie troc + smartphone (18/09/2026)**, suite directe du travail de la
veille sur la dette. Même question d'architecture reposée et reconfirmée
pour le troc : la pioche/le carré sont désormais partagés par les **trois**
systèmes monétaires sans aucune exclusion. Contrairement à la dette, le
troc n'a par principe "jamais de monnaie ni de jeton d'aucune sorte" (voir
`docs/10-etape-plugins-troc.md`) - seule la mécanique de CARTES (modèles,
carrés) est concernée par le partage, jamais une notion de jeton.

Le système d'échange smartphone du troc a été **entièrement repensé** sur
demande explicite de l'utilisateur, qui a fourni une proposition de
parcours détaillée (carte retournée → QR → bouton "Échanger" → scan de la
carte de l'autre joueur) : remplace un ancien mécanisme (quantité de biens
négociée via des compteurs) jamais réellement implémenté en pratique faute
de pioche partagée pour le troc avant ce jour. Deux règles de validation
ont été confirmées explicitement par l'utilisateur (question posée par
Claude, réponse actée) avant toute implémentation : même valeur obligatoire
(jamais faible contre fort) et réciprocité BIDIRECTIONNELLE (chacun des
deux joueurs doit déjà posséder au moins un exemplaire du modèle qu'il va
recevoir). Voir `docs/10-etape-plugins-troc.md` pour le détail complet des
règles et `CLAUDE.md` pour les fichiers/méthodes concernés
(`GameService.recordCardSwap`, `Transaction.forCardSwap`).

Trois bugs réels trouvés en campagne de test (là encore, en jouant de
vraies parties via l'API REST, jamais en se fiant à une simple relecture) :
un carré resté non auto-encaissé après un achat en dette+smartphone (bug de
la veille, découvert seulement maintenant faute d'avoir déclenché un carré
par hasard le 17/09/2026), le classement en direct jamais mis à jour pour
un joueur troc+smartphone (`Player.weakGoods&co`, jamais touchés par le
nouveau mécanisme d'échange direct), et - trouvé spécifiquement par le
second agent de relecture indépendante - la vérification "même valeur"
comparait des niveaux déclarés PAR CHAQUE CLIENT, jamais revérifiés côté
serveur contre le vrai catalogue : un client modifié aurait pu faire passer
un échange faible-contre-forte pour valide. Les trois corrigés le jour
même, voir l'historique des commits (`957b0f5`, `6af249a`, `997d715`).

**Méthode de travail consolidée sur ces deux chantiers** (à retenir pour la
suite) : (1) auditer le code existant et poser les questions d'architecture
ouvertes AVANT de coder plutôt que de deviner ; (2) pour toute règle
ambiguë touchant à une mécanique de jeu sensible (ici, la réciprocité d'un
échange), poser une question fermée à choix multiples plutôt que de
présumer une interprétation ; (3) une fois implémenté, rejouer de VRAIES
parties complètes via l'API REST (jamais une simulation de la logique en
Python) - c'est systématiquement cette étape, et elle seule, qui a révélé
les bugs réels ci-dessus, invisibles à la seule lecture du code ; (4) faire
relire le travail par un second agent indépendant, qui ne voit que le code
et les résultats, jamais le raisonnement du premier - a trouvé un bug de
sécurité réel (le contournement "même valeur" ci-dessus) qu'une simple
relecture par le même agent n'aurait probablement pas détecté.

### Comptes animateurs multi-session (21/09/2026)

Demande explicite de l'utilisateur : "proposer une version serveur capable
de gérer le multi session avec plusieurs animateurs qui ont chacuns leur
profil et leurs parties." Referme directement la faille laissée
volontairement hors périmètre par la passe de sécurité du 02/09/2026
ci-dessus ("les routes d'administration GLOBALE du serveur... restent sans
authentification - une question différente de la protection PAR PARTIE
traitée ici, qui nécessiterait un vrai concept de compte administrateur").

**Modèle de données** (`Animator`, dans `geco-engine` aux côtés de
Game/Player - même convention que les autres champs propres au web) : login
unique, nom affiché, hash de mot de passe, rôle (`ADMIN`/`ANIMATEUR`),
préférences personnelles (langue, mode de jeu par défaut). `Game.owner`
rattache chaque partie à l'animateur qui l'a créée. L'app Swing
(`geco-app`) ne lit ni n'écrit cette table - comportement inchangé.

**Toute la logique vit côté serveur** (`jyt.geconomicus.helper.server.auth`,
package dédié plutôt que mélangé à `GameService`) :
- `PasswordHasher` : PBKDF2WithHmacSHA256 natif au JDK (pas de nouvelle
  dépendance bcrypt/argon2), 210 000 itérations (recommandation OWASP au
  moment de l'écriture). Choix explicitement **proportionné à l'usage réel
  du projet** (authentification d'une poignée d'animateurs sur un serveur
  associatif LAN, pas un système exposé au grand public) - à revisiter si
  le projet devait un jour accueillir un public large et non maîtrisé.
- `SessionService` : jeton opaque de 256 bits transmis via l'en-tête
  `X-Session-Token` (même convention que `X-Game-Pin` déjà en place, jamais
  un cookie - pas de CSRF/SameSite à gérer en plus). Volontairement **en
  mémoire, sans expiration ni persistance** - un redémarrage de serveur LAN
  est rare et bien visible pour l'animateur (reconnexion en un clic) ; ce
  choix devra être revu pour un serveur public resté allumé longtemps avec
  de nombreux animateurs (voir `docs/13-etape3-etat-et-feuille-de-route.md`,
  "Reste à faire").
- `AnimatorService` : création de compte (le tout premier créé sur un
  serveur neuf devient automatiquement ADMIN et récupère toutes les parties
  orphelines déjà jouées avant l'introduction des comptes - jamais
  d'historique perdu), vérification login/mot de passe (jamais de
  distinction dans la réponse entre "login inconnu" et "mot de passe
  incorrect", pour ne pas révéler quels comptes existent), réinitialisation
  de mot de passe (réservée à ADMIN).

**Câblage côté routes** (`GecoServer`) : `requireAdmin`/`requireAnimator`
protègent désormais les routes de réglages/catalogues/plugins/comptes
(ADMIN uniquement) et la création/liste des parties (tout animateur
connecté, mais un non-ADMIN ne voit que les siennes -
`GameService.listGamesByOwner`) ; `checkGameOwnership` interdit à un
animateur non-ADMIN d'agir sur une partie qui ne lui appartient pas, en
plus - jamais à la place - du PIN par partie déjà en place depuis le
02/09/2026. Même frein de débit que `/unlock` sur `/api/auth/login` (10
tentatives/minute/IP) - un mot de passe se devine par force brute
exactement comme un PIN.

**Phase 4, même session** : préférences personnelles par animateur
(langue, mode de jeu par défaut) - avant les comptes, ces réglages étaient
globaux et modifiables par n'importe qui ; réservés à ADMIN depuis la
Phase 2 (route `/api/settings`), ce qui retirait de facto la main aux
animateurs simples sur un choix pourtant personnel. Corrigé en distinguant
clairement réglages d'INSTALLATION (partagés, ADMIN) et préférences
PERSONNELLES (`PUT /api/animators/me/preferences`, résolues depuis la
session plutôt que depuis un id dans l'URL - structurellement impossible
de modifier la préférence de quelqu'un d'autre par cette route).

**Ce que ça n'est PAS encore** : un serveur "prêt pour internet" au sens
plein. Le certificat HTTPS auto-signé (`SelfSignedCertService`, en place
depuis l'étape 3 pour l'accès caméra sur réseau local) reste un certificat
non reconnu par les navigateurs - `docs/13-etape3-etat-et-feuille-de-route.md`
liste précisément ce qui manque encore (empaquetage Docker + reverse proxy
Caddy pour un vrai certificat Let's Encrypt, persistance/expiration des
sessions, proportionnalité du hachage à revisiter selon l'échelle réelle).

### Animation "Mort du joueur" (26/09/2026)

Demande utilisateur, avec fichiers de référence fournis (une image de fond
cimetière/lune, deux mockups du texte "MORT DU JOUEUR", un prototype HTML
d'animation Tailwind/Google Fonts, et `cartoon-text.js` - un moteur de
rendu de texte cartoon 3D vectoriel multilingue, avec un exemple d'usage
pour un futur écran "En prison") : "à l'entre deux tours, lorsque les
morts sont annoncés... les smartphones des joueurs déclenchent une
animation."

**Décision de conception soumise à l'utilisateur avant implémentation** :
le prototype HTML fourni utilise un texte "MORT DU JOUEUR" à gradient
orange/cyan codé en dur, sur exactement 2 lignes fixes en français -
incompatible avec l'exigence multilingue du projet (une traduction ne se
découpe jamais comme le français). `cartoon-text.js`, fourni par
l'utilisateur dans le même lot mais pour un usage `i18n.t('jail')`
différent ("En prison"), résout exactement ce problème : rendu 100 %
vectoriel, retour à la ligne automatique selon la largeur réelle,
compatible avec n'importe quelle langue du jeu. Question posée
explicitement (deux options : suivre `cartoon-text.js` avec un style
légèrement différent du mockup, ou reproduire le gradient exact au prix
d'un découpage figé en 2 lignes) - réponse de l'utilisateur : suivre
`cartoon-text.js`. C'est donc le texte multilingue qui prime sur la
fidélité pixel-perfect au mockup, un choix assumé et documenté plutôt que
deviné.

**Mécanique reprise du précédent "carré encaissé"** (voir la revue de code
menée avant implémentation, qui a servi de cartographie complète du
mécanisme existant) :
- Diffusion WebSocket `"death"` avec `playerId`, déclenchée côté serveur
  (`GecoServer`, route `POST /api/games/{id}/events`, juste après
  `broadcast(id, "event", ...)`) uniquement si `event.getEvt() ==
  EventType.DEATH` et `event.getPlayer().getStartingCardsJson() != null`
  - jamais pour `QUIT` (un abandon volontaire n'est pas "une mort"), et
  jamais pour un joueur non suivi par smartphone (mode classique, aucun
  écran à animer). Agnostique du système monétaire PAR CONSTRUCTION :
  aucun test sur `Game.getMoneySystem()`, réutilisable d'emblée par
  dette/libre/troc, comme demandé explicitement ("pourra être réutilisée
  dans les parties en monnaie dette avec smartphone et... en troc avec
  smartphone").
- Client (`player-view.js`) : `msg.type === "death" && msg.payload.playerId
  === state.player.id`, avec une file d'attente
  (`enqueueDeathAnimation`/`drainDeathAnimQueue`) calquée sur
  `enqueueSquareAnimation`/`drainSquareAnimQueue` - par pure prudence
  défensive, puisqu'un joueur ne meurt normalement qu'une seule fois par
  confirmation de l'animateur (contrairement aux carrés, qui peuvent
  s'enchaîner plusieurs fois de suite).
- Overlay plein écran (`.death-anim-overlay`, `position: fixed; inset: 0;`)
  au même niveau que `.square-anim-overlay`, avec un `z-index` légèrement
  supérieur (3050 contre 3000) - un chevauchement entre une mort et un
  carré n'est pas attendu en pratique (les cartes du joueur mourant sont
  remises à zéro), mais autant trancher l'ordre explicitement.
- Timing : dézoom CSS (`transform: scale()`, transition ~0,72s, origine
  proche de la lune dans l'image) puis impact du titre façon coup de
  tampon (~0,45s) - environ 1,2 seconde au total, conforme à la demande
  ("une animation de 1 seconde"). Reste ensuite affichée jusqu'à 5
  secondes au total depuis le déclenchement (pas 1+5=6s), puis referme
  automatiquement et appelle `refreshPlayer()` pour refléter la
  renaissance (nouvelle main, solde remis à zéro).
- Son d'impact synthétisé (Web Audio API, aucun fichier externe) -
  réutilise le même `AudioContext` déjà partagé par
  `playSynthesizedPlayerWhistle` (nouveau tour) plutôt que d'en créer un
  second, contrairement au prototype de référence qui en recréait un à
  chaque appel.

**Vérifié par un premier test Playwright à deux joueurs** (pas seulement
une relecture de code, cohérent avec la méthode établie de ce projet) : le
joueur qui meurt voit bien l'animation se déclencher (overlay actif,
texte rendu en `<svg>` par `cartoon-text.js`, tenue à l'écran le temps
attendu), tandis qu'un second joueur de la même partie ne voit
STRICTEMENT rien sur son propre écran - confirme le ciblage par joueur
plutôt qu'une diffusion à toute la partie, ainsi que la réutilisation
réelle en dette+smartphone (pas seulement en libre). **Ce premier test ne
mesurait pas image par image l'amplitude du zoom** - une limite qui a
permis à un vrai bug visuel de passer inaperçu (voir la seconde relecture
ci-dessous).

**Bug trouvé en testant, pas en relisant le code** : la première version
pointait l'image de fond (`img/death-background.jpg`) vers le contenu de
l'image de fond de l'écran de connexion animateur (tâche précédente,
25/09/2026) - une confusion de chemin de fichier entre deux images
fournies par l'utilisateur au cours de la même session, jamais détectable
par une relecture du CSS/HTML (le nom de fichier référencé était le bon,
seul le contenu réellement copié sous ce nom était faux). Repéré
uniquement via une capture d'écran Playwright réelle de l'animation avant
d'être corrigé - encore un exemple de la valeur de "tester réellement"
plutôt que de faire confiance à une relecture de code, une méthode déjà
documentée plus haut dans ce journal.

**Seconde relecture indépendante (26/09/2026, demandée explicitement par
l'utilisateur pour ce chantier)** : un agent qui n'avait vu ni le
raisonnement ni les captures d'écran du premier travail a rejoué ses
propres scénarios Playwright (dette/libre/troc, deux morts consécutives,
échec simulé du rendu du titre, mode classique) plutôt que de se fier au
rapport fourni - a trouvé trois bugs réels, invisibles à la seule
relecture du code initial :
1. **Le dézoom depuis la lune était quasi invisible** : l'état de départ
   "zoomed-in" (`scale(3.6)`) héritait de la transition CSS de 0,72s
   destinée au DÉZOOM lui-même - l'image partait donc de `scale(1)` vers
   `3.6` puis repartait aussitôt vers `1` pendant les 30ms d'attente
   prévues pour "laisser peindre" cet état, mesuré à un facteur maximal
   réel de ~1,3-1,6 au lieu de 3,6. Corrigé en appliquant l'état de
   départ SANS transition (`transition: none` le temps d'une frame, via
   un forçage de reflow - `void element.offsetWidth`), technique reprise
   à l'identique pour l'overlay lui-même (fondu d'ouverture qui se
   superposait au même dézoom, rendant l'écran normal du joueur
   brièvement visible en transparence).
2. **Erreur "ResizeObserver loop" et badge 🐞 dès la 2e mort d'un même
   joueur** : `titleHost.innerHTML = ""` vidait le conteneur alors que le
   `ResizeObserver` posé par `cartoon-text.js` lors de la mort PRÉCÉDENTE
   restait actif, le faisant redessiner l'ancien titre en plein rappel
   d'observation. `CartoonText.render()` vide déjà lui-même le conteneur
   de façon synchrone avec le nouveau rendu - ce vidage manuel en trop a
   simplement été retiré.
3. **Écran bloqué indéfiniment en cas d'échec du rendu du titre** :
   aucun `try/finally` n'entourait la séquence - une exception (simulée
   par l'agent) laissait l'overlay plein écran actif (`pointer-events:
   auto`, téléphone du joueur bloqué jusqu'au rechargement) et la file
   d'attente bloquée pour toujours (`mDeathAnimRunning` jamais remis à
   `false`). Corrigé par un `try/finally` classique qui garantit la
   fermeture de l'overlay et l'appel à `refreshPlayer()` quoi qu'il
   arrive, en plus de journaliser l'erreur via `pushDebugLog` - jamais un
   échec silencieux.

Corrections supplémentaires apportées à la suite de cette relecture,
signalées comme améliorations plutôt que bugs bloquants : `fontFamily`
explicitement fixée sur `"Sora"` (déjà vendorisée, voir `--font-display`)
dans l'appel à `CartoonText.render` - aucune des polices cartoon par
défaut de la bibliothèque (Luckiest Guy, Rubik...) n'étant vendorisée
dans ce projet, le rendu retombait sur la police système générique ;
`transform-origin` affiné à `50% 44%` (mesure précise de la position de
la lune dans l'image, contre `50% 40%` estimé initialement à l'œil).

Suite de tests automatisés rejouée après ces corrections : toujours 100%
verte (geco-engine + geco-server), aucune régression.

**Troisième relecture (audit de la seconde relecture, demandée
explicitement par l'utilisateur : "un agent qui contrôle que l'agent de
contrôle a bien fait son travail")** : un troisième agent, indépendant des
deux précédents, a rejoué ses propres scénarios plutôt que de faire
confiance aux rapports fournis - a confirmé chacune des mesures précises
de la seconde relecture (facteur de zoom, timings, absence d'erreur
console) en les remesurant lui-même sur un serveur isolé, y compris en
rechargeant délibérément l'ANCIEN code (`54b4d7f`) pour vérifier que ses
propres scripts reproduisaient bien les bugs d'origine avant de les tester
sur le code corrigé. Verdict : la fonctionnalité est prête, aucun défaut
bloquant.

**Une dernière lacune de robustesse trouvée** (le `try/finally` de
`playDeathAnimation` ne protège que sa propre séquence - une exception
levée AVANT lui, ou dans la boucle englobante de `drainDeathAnimQueue`
elle-même, laisserait `mDeathAnimRunning` bloqué à `true` indéfiniment,
plus aucune animation de mort suivante ne se déclenchant jamais) : un
scénario peu probable en usage réel (nécessite une page HTML désynchronisée
de son propre JS), mais corrigé par prudence avec un second `try/finally`
englobant, au niveau de `drainDeathAnimQueue` elle-même.

### Animation "Renaissance !" (27/09/2026)

Demande utilisateur, avec fichiers de référence fournis (une image de fond
ciel ensoleillé/cercle doré vide, une image du même fond avec le texte
"RENAISSANCE !" et un avatar dans le cercle, un mockup du style de texte,
plus les mêmes `cartoon-text.js`/`En_prison.html` déjà fournis pour "Mort
du joueur") : "peux-tu faire de même avec la renaissance du joueur... le
fond dézoome et le texte apparaît comme un coup de tampon, comme pour
l'exemple précédent."

**Question d'architecture posée et tranchée AVANT implémentation** : en
lisant le code du wizard (`app.js`), le bouton qui déclenche l'événement
`DEATH` s'appelle déjà "Valider la renaissance"
(`t("wiz.validate_rebirth_btn")`, sur `wizNextDeathDU`/`wizNextDeathTroc`/
`wizNextDeathInventory`) - ce moteur de jeu ne modélise la mort et la
renaissance QUE comme un seul et même événement (voir `Event.java`, le
même `case QUIT: case DEATH:` qui gère aussi bien la fin de vie que la
redistribution d'une main fraîche). Il n'existe donc structurellement
AUCUN second point de déclenchement distinct pour "la renaissance" à
côté de celui déjà utilisé pour "Mort du joueur" - la seule question
restait de savoir comment articuler les deux animations sur ce même
événement. Question posée explicitement à l'utilisateur (deux options :
les deux à la suite, ou "Renaissance !" seule en remplacement) - réponse :
les deux à la suite. Décision qui a évité un contresens potentiellement
coûteux (construire "Renaissance !" comme si elle avait son propre
événement dédié, pour découvrir ensuite qu'il fallait la fusionner avec
"Mort du joueur").

**Implémentation, sans le moindre changement serveur** : `GecoServer.java`
diffuse toujours le même message WebSocket `"death"` qu'avant (aucune
modification) - tout l'enchaînement vit côté client
(`player-view.js`) : `drainDeathAnimQueue()` appelle désormais
`await playDeathAnimation(); await playRebirthAnimation();` l'une après
l'autre, dans la même boucle/le même `try/finally` déjà en place.
`playRebirthAnimation()` reste une fonction entièrement autonome et
réutilisable en elle-même (aucune dépendance à l'état de "Mort du
joueur") - seul CET enchaînement précis les relie.

**Toutes les leçons de la double relecture indépendante de "Mort du
joueur" (21-26/09/2026, voir plus haut) appliquées dès la première
version, jamais redécouvertes une seconde fois** :
- État de départ du zoom («zoomed-in», `scale(3.6)`) et ouverture de
  l'overlay rendus INSTANTANÉS via `transition: none` + reflow forcé
  (`void element.offsetWidth`) AVANT même d'écrire la première ligne de
  code de la séquence normale - plutôt que de laisser la transition CSS
  du dézoom s'appliquer par erreur à cette mise en place, comme ça avait
  été le cas pour "Mort du joueur" avant correction.
- Aucun `titleHost.innerHTML = ""` manuel avant `CartoonText.render` (qui
  s'en charge lui-même, de façon synchrone avec le nouveau rendu) - le
  bug du `ResizeObserver` qui redessinait un ancien titre à la 2e
  occurrence n'a donc jamais eu l'occasion d'apparaître ici.
- `try/finally` englobant TOUTE la séquence dès le départ, garantissant la
  fermeture de l'overlay et l'appel à `refreshPlayer()` quoi qu'il arrive.

**Position du cercle doré mesurée précisément**, pas estimée à l'œil
(même leçon que le dézoom sur la lune de "Mort du joueur", dont l'origine
avait dû être corrigée après coup) : détection par script Python du plus
gros disque quasi blanc dans l'image fournie
(`img/rebirth-background.webp`) - centre à 49,9%/48,9%, diamètre 45,8% de
la largeur (768×1376px) - utilisés comme `transform-origin` du dézoom et
comme position du cercle contenant l'avatar.

**Bug trouvé en seconde relecture indépendante, corrigé** : la taille de
ce cercle était initialement fixée en `width: 44%` (fraction de la seule
LARGEUR de l'écran) - correct uniquement quand `background-size: cover`
met le fond à l'échelle par la largeur, mais PAS sur un écran de téléphone
plus étroit/haut que l'image (cas fréquent, le fond se met alors à
l'échelle par la HAUTEUR à la place) : le disque doré réellement affiché
devient alors plus grand que prévu, laissant un bandeau blanc visible
autour de l'avatar (mesuré : seulement 77-79% de la taille réelle du
disque sur des résolutions de téléphone courantes). Corrigé en reprenant
le même principe que `cover` lui-même (le plus grand des deux calculs
possibles) : `width: max(44vw, 24,56vh)`.

**Avatar réel du joueur**, jamais un avatar générique : réutilise
`buildProfileAvatarHtml` (déjà utilisé par l'écran Profil, avec son repli
emoji déjà géré si aucun avatar n'est configuré) plutôt que de réinventer
un affichage d'avatar spécifique à cette animation.

**Son distinct**, pensé pour un moment positif : un carillon ascendant à
trois notes (Do5-Mi5-Sol5) synthétisé via Web Audio API, réutilisant le
même `AudioContext` partagé que "Mort du joueur" et le sifflet de nouveau
tour - jamais un fichier audio externe, cohérent avec le reste de l'app.

**Vérifié par un test Playwright dédié** : enchaînement bien séquentiel
(jamais les deux overlays actifs simultanément - vérifié explicitement à
1,5s puis 5,5s après le déclenchement), dézoom échantillonné image par
image (`requestAnimationFrame`) confirmant un démarrage à `scale(3,6)`
maintenu ~90ms avant transition fluide vers `1` (la première tentative de
vérification, avec un simple point de contrôle à un instant fixe, avait
donné un résultat trompeur - artefact de mesure, pas un bug, corrigé en
échantillonnant frame par frame plutôt qu'à un instant isolé), avatar réel
testé avec un avatar de galerie ET avec le repli emoji, aucune erreur
`ResizeObserver` sur la séquence complète (~10,6s), un second joueur de la
même partie ne voit ni l'une ni l'autre animation.

Suite de tests automatisés (geco-engine + geco-server) : toujours 100%
verte, aucune régression - cohérent avec le fait qu'aucun code serveur
n'a été modifié pour cette fonctionnalité.

**Seconde puis troisième relecture indépendantes** (demandées
explicitement par l'utilisateur, comme pour "Mort du joueur") : la
première (voir `docs/13`) a confirmé qu'aucun des trois bugs de "Mort du
joueur" n'était revenu, corrigé un vrai bug propre à cette animation
(avatar invisible en mode "animations réduites" - une opacité posée en
ligne jamais retirée, masquée en usage normal par l'animation CSS mais
pas quand celle-ci est désactivée) et signalé sans corriger le
dimensionnement du cercle avatar sur écran étroit (corrigé ensuite dans
cette même session, voir plus haut). La troisième (audit de la seconde) a
confirmé chaque mesure en la remesurant elle-même, et trouvé un dernier
défaut RÉEL avec une conséquence concrète non anticipée par la seconde
relecture : si `playRebirthAnimation` (ou `playDeathAnimation`) lève une
exception AVANT même d'entrer dans son propre `try` interne (élément DOM
manquant, page désynchronisée de son JS), la boucle `while` de
`drainDeathAnimQueue` était jusqu'ici purement et simplement ABANDONNÉE -
un événement de mort DÉJÀ retiré de la file (`shift()`) ne rejouait
jamais, mais un événement ULTÉRIEUR sans rapport rejouait alors DEUX
cycles complets à la suite (~20 secondes au lieu de 10), sans le moindre
message pour le joueur. Confirmé reproductible en conditions réelles
(élément retiré du DOM avant déclenchement). Corrigé par un `try/catch`
PAR ÉLÉMENT à l'intérieur de la boucle (journalise via `pushDebugLog` et
continue, jamais un abandon silencieux) - même politique d'erreur que
partout ailleurs dans ce fichier, cohérente avec le filet de sécurité déjà
posé sur cette même boucle lors de la relecture de "Mort du joueur" (voir
plus haut, entrée du 26/09/2026) qui ne couvrait, elle, que la remise à
zéro du drapeau `mDeathAnimRunning`, pas la poursuite de la boucle
elle-même. Vérifié par un test Playwright dédié reproduisant exactement
le scénario signalé : les deux échecs simulés sont bien journalisés
individuellement, aucune file ne reste bloquée, et un déclenchement propre
suivant ne rejoue plus jamais qu'un seul cycle (~10s), jamais deux.

**Amélioration complémentaire, non bloquante mais appliquée par
prudence** : la troisième relecture a aussi noté (uniquement simulé dans
le navigateur, jamais vérifié sur un vrai appareil) qu'un navigateur
mobile avec sa barre d'adresse encore affichée calcule parfois `vh` sur la
hauteur AVANT masquage de cette barre, ce qui pourrait légèrement
surdimensionner l'avatar par rapport au cercle réellement visible.
Ajout d'une déclaration CSS dupliquée avec l'unité `dvh` ("dynamic
viewport height", qui suit la hauteur réellement visible) juste après la
déclaration `vh` existante - un navigateur qui ne comprend pas `dvh`
ignore silencieusement cette ligne et garde la précédente, aucun
`@supports` nécessaire. Revérifié à l'identique (207px de diamètre à
390×844, aucune régression) après cet ajout.

Verdict final de cette troisième relecture : fonctionnalité prête, aucun
défaut bloquant restant après ces deux derniers correctifs. Limites
connues, non bloquantes (identiques en substance à celles déjà notées
pour "Mort du joueur") : aucun test sur un vrai appareil iOS/Android
(navigateur headless uniquement), avatar légèrement décentré sur un écran
très large/court (paysage) - jamais par-dessus l'anneau doré, juste pas
parfaitement centré - jugé cosmétique et non prioritaire.

### Timing mort+renaissance ajusté à 2s+3s, vérification en partie réaliste (27/09/2026)

Demande utilisateur, après la mise en place de "Renaissance !" ci-dessus :
"Peux-tu tester sur un vrai téléphone ? iOS Android ? Peux-tu t'assurer
dans une partie test que les écrans ne restent pas mais qu'il se déclenche
bien au bon moment ? Peux-tu modifier les timing puisque les écrans
d'animation de la mort et de la Renaissance s'enchaînent automatiquement
l'un l'autre, je souhaiterais que l'écran de la mort dure 2 secondes et
que celui de la Renaissance dure 3 secondes, ce qui fait un total de 5
secondes."

**Test sur un vrai téléphone (iOS/Android) : impossible dans cet
environnement.** À dire clairement, sans contourner la question : cette
session tourne dans un environnement cloud isolé, sans accès à un
appareil physique - seul Chromium headless (via Playwright) est
disponible. Toutes les vérifications ci-dessous (comme celles des deux
animations elles-mêmes) sont donc faites par ce biais, jamais sur un vrai
téléphone. Signalé explicitement à l'utilisateur dans le retour final,
pas seulement ici.

**Changement de timing** : `DEATH_ANIM_TOTAL_DISPLAY_MS` 5000 → 2000,
`REBIRTH_ANIM_TOTAL_DISPLAY_MS` 5000 → 3000 (`player-view.js`) - remplace
l'ancien couple 5s+5s=10s par 2s+3s=5s, exactement la demande. Vérifié que
la chorégraphie interne de chaque animation (dézoom 720ms + impact 450ms,
~1,2s) tient toujours largement dans ces budgets réduits (reste ~0,8s
d'affichage fixe pour la mort, ~1,8s pour la renaissance, contre ~3,8s
avant) - aucune étape visuelle n'est tronquée par ce changement, aucun
autre code (choix de couleurs, `cartoon-text.js`, sons synthétisés) n'a dû
être modifié.

**Vérification en "partie test" plus réaliste**, délibérément différente
des tests isolés à déclenchement unique utilisés pour les deux animations
elles-mêmes (répond directement à "dans une partie test que les écrans ne
restent pas mais qu'il se déclenche bien au bon moment") : partie à 3
joueurs, plusieurs tours de jeu normal (`"T"`) intercalés AVANT et ENTRE
deux morts distinctes touchant deux joueurs différents à des moments
différents de la session, avec deux onglets Playwright ouverts
simultanément (le joueur qui meurt et un joueur témoin). Résultats (9/9
contrôles passés) :
- Déclenchement de l'animation de mort en <300ms après l'événement
  serveur (jamais un délai perceptible côté joueur).
- Écran de mort refermé à ~2,0s (mesuré : 2047ms et 1961ms sur les deux
  cycles), jamais l'ancien ~5s.
- Renaissance enchaînée immédiatement (début mesuré à moins de 150ms
  après la fin de la mort les deux fois), refermée à ~5,0s au total
  (mesuré : 5048ms et 5058ms) - exactement la demande "un total de 5
  secondes".
- Le joueur témoin (non ciblé par l'événement) ne voit JAMAIS l'overlay
  s'activer, sur toute la durée de l'observation - confirme que le
  ciblage côté client (comparaison de `playerId`) reste correct.
- Dans les deux cas, plus aucun overlay actif 2s après la fin mesurée du
  cycle (marge de sécurité) - aucun écran ne reste bloqué affiché, sur le
  premier cycle comme sur le second, plus tard dans la même session.

Suite de tests automatisés (geco-engine + geco-server) toujours 100%
verte après le changement de timing (seul du JS pur, aucun code serveur
modifié).

**Test sur un vrai téléphone (iOS/Android) : toujours impossible.** Répété
ici pour mémoire (déjà indiqué plus haut) : ni cette vérification, ni les
deux tours de relecture indépendante qui suivent, n'ont pu tester autre
chose que Chromium headless (Playwright) - jamais un appareil physique.

**Deux agents de contrôle, mandat élargi** (demande utilisateur explicite,
27/09/2026) : "Mets en place un agent qui contrôle que tout soit ok au
niveau du code. Ok au niveau de l'exécution et ok au niveau de la
fluidité de la partie. Si ce n'est pas le cas, il faut qu'il modifie et
s'il n'y a pas de bug, il faut qu'il identifie les raisons du manque de
fluidité dans la partie et qu'il me fasse un retour. Mais ensuite en
place un second agent qui contrôle que le premier agent de contrôle est
bien fait son travail." - mandat plus large que les deux tours précédents
(code + exécution RÉELLE + ressenti de fluidité, avec autorité explicite
de corriger directement, et obligation de remonter une cause racine
concrète plutôt qu'une supposition si la fluidité semblait insuffisante
sans bug identifiable).

**Bug réel trouvé et corrigé par le premier agent** : `playDeathAnimation`/
`playRebirthAnimation` protègent leur séquence par un `try/finally`, mais
celui-ci ne protège que d'une EXCEPTION du rendu du titre - jamais d'une
promesse qui ne se termine JAMAIS. Or `CartoonText.render()` attend en
interne `document.fonts.load("900 ... Sora")`, police qui n'est utilisée
nulle part ailleurs sur l'écran joueur et n'était donc téléchargée
QU'AU MOMENT MÊME de la première mort d'une partie, en plein milieu de
l'animation - un simple ralentissement réseau à cet instant précis (pas
forcément un vrai échec) suffisait à laisser l'écran de mort bloqué en
plein écran INDÉFINIMENT (`pointer-events` actif, téléphone du joueur
inutilisable), la file d'attente restant figée derrière. Reproduit
explicitement en retenant artificiellement cette seule requête réseau
(écran resté bloqué au-delà de 14s dans le test).

Double correctif, dans le même fichier `player-view.js`, aucun changement
serveur :
1. Police préchargée dès l'événement `load` de la page (donc AVANT toute
   mort possible dans la partie), pour plus dépendre du réseau pendant
   l'animation elle-même. Volontairement démarré APRÈS `load` plutôt
   qu'avant : un chargement de police qui traîne retarderait sinon `load`
   lui-même (vérifié explicitement avant de choisir cet ordre).
2. Nouvel utilitaire partagé `renderAnimTitle()`, utilisé par les deux
   animations : plafonne l'attente du rendu à 1500ms
   (`ANIM_TITLE_RENDER_TIMEOUT_MS`, très au-dessus d'un rendu normal -
   mesuré 7 à ~190ms y compris processeur ralenti x6) via
   `Promise.race()` - au-delà, la séquence continue sans attendre et
   journalise via `pushDebugLog` (jamais un silence), l'écran se referme
   donc TOUJOURS. Une exception synchrone continue de remonter
   normalement au `catch` déjà en place.

**Seconde relecture indépendante (audit du premier agent)** : a reproduit
le même scénario de façon totalement indépendante (ses propres scripts,
son propre serveur isolé, en interceptant directement la requête du
fichier JS pour comparer le build AVANT/APRÈS le correctif au lieu de
faire confiance au rapport) - confirme intégralement : écran bloqué au-delà
de 14s avant correctif (`mDeathAnimRunning` restant à `true`, un tap sur
l'écran heurtant bien l'overlay au lieu de l'application), fermeture
propre à ~3,5s/~4,5s (mort/renaissance) après correctif dans le pire cas
(rendu qui ne se termine jamais), reprise normale (~2,0s/~3,0s) dès
l'événement suivant. A aussi vérifié spécifiquement le risque introduit
PAR le correctif lui-même (le point le plus délicat d'un `Promise.race`
avec délai : que devient la promesse "perdante" si elle finit quand même
plus tard ?) : le rendu abandonné continue effectivement en arrière-plan
et peut écrire dans le titre après coup, mais toujours dans le bon ordre
(chaque appel attend la même police, donc un ancien rendu ne peut jamais
écraser un rendu plus récent du même titre), sans collision d'identifiants
SVG entre deux titres (`cartoon-text.js` les numérote), et sans effet
visible si l'écran concerné est déjà refermé. Confirme également, en
conditions de réseau lent simulées (400ms de latence, 50 Ko/s), que le
préchargement se termine largement avant que le téléphone puisse même
recevoir un message WebSocket de mort - le filet de 1500ms ne sert donc,
en pratique, que d'ultime sécurité. Suite de tests toujours verte, aucune
régression trouvée, aucun nouveau correctif nécessaire. A aussi confirmé,
en les remesurant lui-même, que les 5 observations "non-bugs" du premier
agent (voir plus bas) n'en sont effectivement pas.

**Fluidité, verdict confirmé par les deux agents** : le budget plus court
ne raccourcit QUE le palier d'affichage fixe après le tampon (~0,9s pour
"Mort du joueur" pleinement affiché, ~1,9s pour "Renaissance !" avec
l'avatar) - la chorégraphie interne (dézoom + impact) reste inchangée et
rien n'est visuellement tronqué, y compris sous processeur ralenti x6 sur
un rendu "à froid" (premier rendu de police de la partie). Observations
notées par le premier agent, jugées non bloquantes par le second (les
deux prédatent ce changement de timing ou sont cosmétiques, aucune n'a
été corrigée) : un bref palier quasi statique (~300-400ms) avant
l'apparition du tampon (paramétrable via `DEATH_ANIM_DEZOOM_MS` si
l'utilisateur souhaite un jour ajuster ce ressenti précis - décision de
conception, pas un défaut) ; le toast "Nouveau tour" (z-index supérieur)
qui peut s'afficher par-dessus l'animation si l'animateur valide un
nouveau tour au même instant (mesuré : ne recouvre ni le titre ni
l'avatar, seulement le bas du décor sur petit écran) ; le bouton de
langue toujours visible par-dessus (cosmétique, préexistant) ; une frame
un peu plus longue (40-100ms) au moment précis où l'animation de
renaissance prend le relais de celle de mort ; un rendu à froid
légèrement plus long (100-160ms) sur le tout premier tampon d'une partie
(construction du SVG de `cartoon-text.js`) sans que cela tronque quoi que
ce soit.

Verdict final de ce troisième tour de relecture : fonctionnalité prête,
aucun défaut bloquant restant après ce correctif. Documentation du
correctif complétée après coup (l'audit du second agent a relevé que ce
fichier ne mentionnait pas encore `renderAnimTitle()` ni le préchargement
de police au moment de sa relecture).

### Campagne de test 2/4/10 joueurs, verrou par partie, carrés fantômes (27/09/2026)

Retour utilisateur sur une vraie partie test (libre + smartphone, 2
joueurs, 8 tours de 3 min) : "Montre mécanique x4" jamais encaissée en
carré, trois modèles "Très forte" à x5. Question posée : "Est-ce que la
révolution industrielle a eu lieue ?" - réponse : non, "Rupture
technologique" (voir plus haut, entrée initiale sur ce mécanisme) ne
change jamais le nombre de cartes requis pour un carré (toujours 4) ni
rien à la pioche - c'est un simple indicateur statistique (facteur de
richesse ×2), déclenché une seule fois par partie. Sa présence dans le
journal de CETTE partie prouvait au contraire qu'un carré avait
fonctionné au moins une fois, orientant l'enquête vers un défaut
intermittent plutôt qu'une panne totale.

Demande complémentaire : revue complète du code, campagne de 3 parties
réelles (2/4/10 joueurs + animateur, 12 tours de 5 min), vérification de
la cohérence des affichages (cartes/jetons/DU) entre smartphones et
tableau de bord animateur, attention à la pioche et aux morts, et surtout
que les échanges restent fluides et jamais bloqués - avec, si un blocage
est trouvé, l'obligation d'en identifier la cause et de proposer une
solution plutôt que de deviner. Mandat des deux agents de contrôle élargi
en conséquence, avec autorité de renvoyer le travail à refaire si le
résultat n'est pas bon (jamais utilisée cette fois : le second agent a
confirmé le fond du travail, seulement demandé deux corrections de
formulation/complétude avant le retour à l'utilisateur, détaillées
ci-dessous).

**Deux causes distinctes trouvées au symptôme exact de l'utilisateur** :

1. **Épuisement des petites pioches (décision de règle, pas un bug)** -
   à 2 joueurs, seuls 3 modèles × 5 exemplaires sont en jeu par niveau
   (règle "N+1 modèles" du 06/09/2026) ; un carré rend puis repioche dans
   la MÊME pioche, qui peut donc ne plus rien avoir de nouveau à offrir.
   Reproduit dans 44 à 52 parties simulées sur 100 (2 joueurs), 32/40 (4
   joueurs), 1/15 (10 joueurs, rare car plus de joueurs = plus de
   modèles). Question soumise à l'utilisateur (voir le retour final) :
   grossir la pioche (option "N+2 modèles", mesurée : fait tomber le taux
   à 13/100 pour 2 joueurs, au prix d'un peu moins de variété par carré)
   ou laisser en l'état.

2. **Vrai bug de concurrence, confirmé et corrigé** - le risque documenté
   dans CLAUDE.md ("Connu, non corrigé" : validation puis écriture sans
   verrou explicite) était jusqu'ici jamais observé en usage réel. Prouvé
   en HTTP réel (rachats simultanés) : jusqu'à +31073 unités monétaires
   créées de rien, jusqu'à 22 exemplaires d'un modèle qui n'en compte que
   5. Corrigé par `GameService.withGameLock` - un verrou en mémoire par
   partie (`ReentrantLock`, `ConcurrentHashMap<gameId, lock>`) sérialisant
   toute opération qui lit puis réécrit l'état partagé d'une partie
   (transactions, carrés, jetons de départ, mise en place de la pioche,
   undo/edit/delete d'événement...). Sûr vis-à-vis du cache EclipseLink
   (aucun réglage de cache dans persistence.xml, aucune écriture SQL
   native hors sauvegarde - le détenteur suivant voit toujours l'état
   validé par le précédent). Sans impact mesurable sur la fluidité
   (latence de rachat : +40ms au 95e percentile sous charge concurrente
   réelle, toujours sous 0,3s). Deux bugs de la même famille trouvés en
   creusant `recordTransaction` (jamais concernée par le correctif
   équivalent du 18/09/2026 sur `recordCardSwap`) : un vendeur pouvait
   présenter plusieurs QR pour le même exemplaire et se faire racheter la
   même carte deux fois ; le niveau (donc le prix en monnaie libre)
   déclaré par le client n'était jamais revérifié. Les deux corrigés en
   dérivant le niveau depuis la pioche de la partie et en revérifiant que
   le vendeur détient bien la carte, sous le même verrou. Commit `9c3e49e`.

**Trois défauts supplémentaires trouvés par la campagne de 12 tours,
corrigés** :
- `isTradingAllowed` comparait `turnNumber >= nbTurnsPlanned` au lieu de
  `>` - bloquait TOUS les échanges pendant l'intégralité du DERNIER tour
  prévu (mesuré : 0 échange possible en tour 12/12). Une partie
  explicitement terminée par l'animateur (événement END) bloque
  désormais aussi les échanges, plus fiable que la seule expiration du
  chrono.
- Le bouton "+30s" du minuteur RECULAIT `turnStartedAt` au lieu de
  l'avancer, RACCOURCISSANT le tour de 30s au lieu de l'allonger -
  présent depuis l'import initial du projet, jamais remarqué jusqu'ici.
- **Trouvé par le second agent de contrôle** (audit du premier correctif
  ci-dessus) : le correctif sur `isTradingAllowed` rouvrait, sans le
  vouloir, une fenêtre où un joueur déjà SORTI de la partie (QUIT via
  l'assistant de fin de partie, ou mort sans renaissance) pouvait quand
  même continuer à acheter/vendre - AVANT ce correctif, le dernier tour
  bloquait tout, masquant ce trou par accident. Reproduit en HTTP réel :
  un joueur rendu inactif achetait quand même une carte, ses jetons
  changeant après que son solde de sortie avait déjà été enregistré.
  Corrigé : `recordTransaction`/`recordCardSwap` refusent désormais tout
  échange impliquant un joueur inactif. Commit `90074c8`.

**Carrés fantômes, trouvés par le second agent de contrôle, corrigés** :
un carré qui ne fait progresser le joueur à AUCUN niveau supérieur
(pioche cible épuisée - conséquence directe du point 1 ci-dessus) était
quand même diffusé comme un VRAI carré : une animation "carré" se
rejouait sur le téléphone à chaque transaction suivante, pour un
résultat strictement nul, tant que le joueur concentrait tout le stock
d'un niveau. Corrigé en persistant TOUJOURS l'événement (conservation des
cartes inchangée - `computePlayerCardInventory` rejoue l'historique des
`CardSquareEvent` pour reconstituer un inventaire ; une première
tentative de ne plus le persister DU TOUT a cassé la conservation,
détectée par `GameServiceFullGameSimulationTest`), mais en ne le
retournant/diffusant plus jamais quand la pioche cible est épuisée -
généralisé de "carte rendue strictement identique" à "toute pioche cible
épuisée" après avoir découvert, en fiabilisant le test associé, que DEUX
modèles distincts d'un même niveau peuvent s'échanger ce rôle de "faux
carré" indéfiniment et consommer les 50 itérations du filet de sécurité
sans jamais atteindre un autre carré pourtant éligible. Question soumise
à l'utilisateur (voir le retour final) : un carré "tresforte" qui boucle
vers "faible" alors que le joueur détient déjà tout le stock du niveau
déclenche quand même une VRAIE révolution (rotation des prix) et donne 1
carte gratuite à chaque fois - un comportement de règle, pas un bug, mais
potentiellement disruptif en cas de monopole d'un niveau.

**Deux bugs de perte d'argent identifiés, non corrigés (redesign
nécessaire, décision utilisateur)** :
- "Annuler"/supprimer/éditer un événement (undo/delete/editEvent) rejoue
  l'historique, et un point de contrôle de richesse écrase les jetons du
  joueur - mais un achat/vente smartphone n'est PAS un événement.
  Résultat : annuler un événement SANS RAPPORT avec un achat smartphone
  peut remettre les jetons du joueur à leur valeur d'AVANT cet achat,
  alors que la carte, elle, reste transférée - l'acheteur obtient la
  carte gratuitement.
- Même cause racine, plus grave en dette+smartphone : chaque
  "Annuler"/suppression REJOUE aussi les crédits (NEW_CREDIT), qui
  RAJOUTENT leur principal aux jetons à chaque rejeu - mesuré : 3
  "Annuler" successifs après un crédit de 10 unités font passer les
  jetons du joueur de 10 à 40, sans que la dette n'augmente d'autant.
  Présent AVANT ce correctif aussi (pas une régression de cette
  campagne).
- Piste de correctif possible, non appliquée (nécessite sa propre revue
  et ses propres tests) : dans undo/delete/editEvent uniquement, ajuster
  les jetons ACTUELS du joueur par la DIFFÉRENCE entre rejouer avec et
  sans l'événement retiré, plutôt que de laisser le rejeu écraser purement
  et simplement leur valeur.
- Bug mineur lié : le graphique "masse monétaire" affiche, pour le point
  "Tour 1", la masse FINALE de la partie au lieu de la masse réelle à ce
  tour-là (même mécanisme de rejeu).

**Nettoyage** : suppression de 1836 fichiers (27 Mo, jsdom/undici,
dépendances de test Node) commités par erreur dans le dossier PUBLIC du
serveur depuis le commit `486a029` - aucun `package.json` du dépôt n'en
dépend, aucune page ne les charge, mais ils étaient servis sans
authentification et embarqués dans le jar.

**Bruit de console navigateur** (signalé par l'utilisateur,
`MaxListenersExceededWarning`/`ObjectMultiplex - orphaned data`, tagué
`contentscript.js`) : confirmé comme du bruit d'extension NAVIGATEUR
(type MetaMask), aucune trace de `EventEmitter`/`ObjectMultiplex` dans le
code de l'application ni dans le dépôt.

**Test sur un vrai téléphone iOS/Android : toujours impossible dans cet
environnement** - seul Chromium headless (Playwright), jamais un
appareil physique, répété pour mémoire dans cette entrée comme dans
toutes les précédentes de cette session.

### Timing plus dynamique pour mort/renaissance, inspiré d'un fichier de référence (27/09/2026, second changement le même jour)

Nouveau retour utilisateur, après le raccourcissement à 2s+3s ci-dessus :
"Concernant l'animation pour les morts/renaissance, les visuels (fonds.png
et textes éditables en html) sont très biens ! On les garde. En revanche,
il faudrait lui donner un autre timing afin de la rendre plus dynamique
quand on joue." - avec un fichier de référence fourni ("test1.html", une
démo autonome dessinant ses propres décors en CSS/JS, jamais utilisée
pour ses visuels, uniquement pour sa CHORÉGRAPHIE : dézoom plus rapide,
flash rouge à l'impact, flash blanc masquant l'enchaînement, durée totale
beaucoup plus courte).

**Question posée avant implémentation** (la durée totale cible n'était
pas déductible du fichier fourni, une simple démo sans fermeture
automatique) : "aussi rapide que la référence" (~1,5s+1,5s≈3s) ou "garder
2s+3s=5s (déjà validé la dernière fois)" - réponse : aussi rapide que la
référence.

**Implémentation** (`player-view.js`, `player.css`, `player-view.html`) :
- `.death-anim-zoom`/`.rebirth-anim-zoom` : transition 0,72s→0,55s, courbe
  reprise du fichier de référence (`cubic-bezier(0.16,1,0.3,1)` au lieu de
  `cubic-bezier(0.15,0.85,0.35,1)`).
- Nouveau `#animFlashOverlay`, un SEUL élément DOM partagé par les deux
  animations (jamais actives simultanément) : `.flash-red`/`.flash-white`
  + `.active`, transition d'opacité 150ms, désactivée sous
  `prefers-reduced-motion`.
  - Rouge à l'impact de "Mort du joueur" : posé instantanément (même
    technique `transition:none`+reflow que l'état "zoomed-in"), jamais
    attendu (fire-and-forget comme le tremblement d'écran/le son juste
    à côté), retiré 120ms plus tard par un minuteur détaché.
  - Blanc au tout début de "Renaissance !" : posé instantanément pendant
    que la scène suivante (déjà zoomée sur le cercle doré) se met en
    place EN DESSOUS, invisible sous le flash ; retiré exactement au
    moment où le dézoom démarre (pas avant, pas après) - la scène se
    révèle donc PROGRESSIVEMENT pendant que le flash s'estompe, plutôt
    que d'apparaître d'un coup une fois le flash retiré. Filet de
    sécurité dans le `finally` de `playRebirthAnimation` : retire le
    flash inconditionnellement si une exception interrompt la séquence
    avant son retrait normal.
- `DEATH_ANIM_ZOOM_START_DELAY_MS` 30→50, `DEATH_ANIM_DEZOOM_MS` 720→550,
  `DEATH_ANIM_TOTAL_DISPLAY_MS` 2000→1500. `REBIRTH_ANIM_ZOOM_START_DELAY_MS`
  30→150 (réutilisé comme durée de tenue du flash blanc),
  `REBIRTH_ANIM_DEZOOM_MS` 720→550, `REBIRTH_ANIM_TOTAL_DISPLAY_MS`
  3000→1500. `*_IMPACT_MS` inchangés (450ms) volontairement : synchronisés
  avec la durée du tremblement d'écran CSS déjà en place, le fichier de
  référence utilise une valeur très proche (400ms) pour son propre
  tremblement.

**Vérifié par le premier agent de contrôle** (mandat : code/exécution/
fluidité, comme les tours précédents) : mesuré indépendamment sur 30+
cycles - mort affichée 1511-1565ms, renaissance enchaînée 2-22ms après et
affichée 1508-1551ms, total 3025-3133ms (3127-3133ms sous ralentissement
CPU ×4) - conforme à la cible. Courbe de zoom réellement appliquée par le
navigateur (vérifié via `getComputedStyle`, pas seulement la déclaration
CSS) : erreur moyenne 0,004-0,008 contre la nouvelle courbe (contre 0,27
pour l'ancienne à 0,72s - preuve que le navigateur applique bien la
nouvelle valeur, pas une valeur mise en cache). Flash rouge synchronisé à
moins de 0,5ms du tampon/tremblement, flash blanc synchronisé à moins de
1ms du début du dézoom des deux côtés. 24 cycles mort/renaissance
consécutifs (y compris morts simultanées à deux joueurs, une mort
survenant PENDANT l'animation d'une autre) : nombre de nœuds DOM stable
(796), zéro minuteur en attente après coup, tas JS plat après garbage
collection forcé - aucune fuite mémoire. Comparaison A/B avec/sans
l'élément de flash : aucune différence mesurable de fluidité (95e
percentile du temps de frame 17,4ms contre 17,3ms). Injection de pannes
(rendu qui échoue, qui ne répond jamais, élément DOM manquant) : dans
tous les cas, écran refermé proprement, file jamais bloquée, cycle
suivant normal.

**Bug réel trouvé et corrigé par le premier agent, confirmé par le
second - AVEC UNE PRÉCISION IMPORTANTE DU SECOND, à retenir pour ne pas
propager une inexactitude dans un futur retour à l'utilisateur** : le
minuteur détaché qui referme le flash rouge (`setTimeout(...,120)`)
retirait "active" de l'ÉLÉMENT PARTAGÉ sans vérifier sa couleur - si "Mort
du joueur" était interrompue par une exception moins de 120ms après
l'impact, "Renaissance !" démarrait aussitôt et reprenait ce même élément
en blanc ; le minuteur du rouge, toujours en attente, retirait alors
"active" du flash BLANC à sa place. Mesuré (première relecture) : flash
blanc tenu 114ms au lieu de 150ms, fondu commencé 37ms avant le dézoom.
Corrigé (commit `f7fa455`) en ne retirant "active" que si l'élément est
encore en mode rouge à cet instant précis - revérifié à 150,2ms/0,3ms
d'écart après correctif. **Le message du commit `f7fa455` cite, à tort,
"un rendu du titre qui échoue" comme exemple concret de scénario
déclencheur - le SECOND agent a démontré expérimentalement que ce cas
précis NE PEUT PAS déclencher le bug** : `renderAnimTitle` se termine
TOUJOURS (avec ou sans erreur) AVANT que le flash rouge ne soit posé dans
le code (l'ordre des instructions le garantit structurellement), donc un
échec de rendu de titre n'a jamais l'occasion d'interrompre la séquence
à ce moment précis. Aucune circonstance de jeu réel identifiée à ce jour
ne peut déclencher ce bug - seule une injection de panne artificielle
(interception réseau modifiant délibérément le JS servi) y parvient. Le
correctif reste un filet de sécurité légitime à garder (défense en
profondeur), mais ne doit pas être présenté comme la correction d'un bug
utilisateur réellement rencontré - erreur de formulation du message de
commit corrigée ici plutôt que par une réécriture d'historique (le commit
est déjà poussé). Le second agent a aussi vérifié explicitement qu'un
minuteur de flash rouge "périmé" (mort interrompue) ne peut jamais
interférer avec le flash rouge d'un cycle ULTÉRIEUR : deux flashs rouges
consécutifs sont nécessairement séparés d'au moins 600ms (50+550ms de
délai/dézoom avant que le second puisse même se poser), largement
au-delà des 120ms du minuteur - vérifié par un test à 3 morts en file,
sous ralentissement CPU ×6 combiné à un blocage du thread principal de
700ms, sans aucune interférence observée.

**Deux points relevés par le second agent, non appliqués, décision
utilisateur en attente** :
1. `ANIM_TITLE_RENDER_TIMEOUT_MS` (1500ms, posé le 27/09/2026 dans le
   tour précédent pour un souci différent - le préchargement de police)
   équivaut désormais à la DURÉE ENTIÈRE d'un écran (1,5s) plutôt qu'à
   une petite fraction comme avant. Mesuré : un rendu pathologiquement
   lent (jamais observé en usage normal - mesuré à seulement 40-105ms
   dans les pires conditions testées) pourrait, dans le pire des cas
   (rendu qui n'aboutit jamais), faire grimper le total à 6s - plus lent
   que les 5s que l'utilisateur vient justement de juger insuffisamment
   dynamiques. Piste proposée par le second agent, non appliquée :
   abaisser ce plafond à 500-600ms (encore 2,5 à 3× le pire rendu normal
   mesuré à ce jour).
2. En mode "animations réduites" (`prefers-reduced-motion`), les deux
   flashs restent affichés mais sans fondu (transition instantanée) -
   conforme aux recommandations d'accessibilité sur les flashs répétés
   (bien en dessous de 3/seconde), mais l'utilisateur pourrait préférer
   les désactiver entièrement pour ce mode plutôt que les garder en
   version instantanée.

**Confirmé sans rapport avec ce changement** (vérifié explicitement par
le second agent à la demande du premier retour) : un message WebSocket
"Nouveau tour" ne déclenche jamais de `refreshPlayer()` sur le
téléphone (code confirmé) - un joueur peut donc voir des valeurs
obsolètes jusqu'à l'actualisation périodique suivante (jusqu'à 5s),
exactement comme n'importe quel autre joueur après n'importe quel tour -
comportement préexistant, non aggravé par ce changement (le pire cas
reste inchangé, seule la fenêtre où il pourrait se produire pendant
l'animation elle-même a changé de forme, sans rien exposer de nouveau).

Verdict final des deux agents : travail prêt à rapporter à l'utilisateur,
aucun correctif de code supplémentaire nécessaire - seules la formulation
du commit `f7fa455` (corrigée ci-dessus) et la documentation (cette
entrée) restaient à mettre à jour.

### Avatar de renaissance : ombre retirée, resserré contre l'anneau, bug de centrage trouvé et corrigé (27/09/2026, troisième changement le même jour)

Retour utilisateur, avec une maquette à l'appui : "Attention à l'écran de
la renaissance qui doit bien prendre l'avatar du joueur, pas un avatar
inventé. D'autre part, t'est-il possible de retirer l'ombre portée sur
l'avatar en essayant de placer l'image au plus proche du cercle pour
donner l'impression que l'image est à l'intérieur du cadre - tout en
conservant l'aspect responsive et sans déformer le ratio hauteur ×
largeur ?"

**Vérifié avant tout changement, déjà correct** : l'avatar RÉEL du joueur
(`buildProfileAvatarHtml`, image de galerie ou SVG personnalisé) était
déjà celui affiché - jamais un avatar générique inventé, sauf repli
emoji légitime si aucun avatar n'est configuré (joueur ajouté à la main
par l'animateur). Confirmé par les deux agents de contrôle sur des
sessions à 4 et 5 joueurs avec des types d'avatar différents (image de
galerie, SVG personnalisé, config manquante) : toujours le bon avatar,
jamais de mélange entre joueurs, jamais un ancien avatar affiché
(`avatarConfigJson` ne peut être modifié qu'à l'inscription, aucune route
ne permet de le changer en cours de partie).

**Deux ajustements CSS sur `.rebirth-anim-avatar-circle`** :
1. `box-shadow` supprimée entièrement (l'ombre portée donnait
   l'impression d'un disque flottant au-dessus du fond plutôt que serti
   dedans).
2. Marge de sécurité resserrée : `44vw/24,56vh` (~96% du disque réel
   mesuré, 45,8%/25,6%) → `45,3vw/25,3vh` (~99%) - quasiment au ras de
   l'anneau doré. `aspect-ratio: 1/1` et `object-fit: cover` (déjà en
   place) garantissent qu'aucune valeur de largeur ne déforme jamais
   l'avatar.

**Bug réel trouvé et corrigé par le premier agent de contrôle, confirmé
et affiné par le second** : la position `left: 49,9%; top: 48,9%`
utilisait des fractions de l'ÉCRAN, alors que ces valeurs mesurent le
centre de l'anneau dans l'IMAGE mise à l'échelle en "cover" - exactement
le même défaut déjà rencontré (et corrigé) pour la LARGEUR. Sur un écran
plus large que l'image (ratio > 768/1376 = 0,558 : tablette, téléphone
plié déplié, écran carré, paysage...), l'image déborde en hauteur et le
vrai centre de l'anneau remonte par rapport à 48,9% de l'écran - avec la
marge tout juste resserrée à ~99% ci-dessus, l'avatar mordait alors
visiblement sur le bas de l'anneau (mesuré : jusqu'à -5,6px à 667×375).
Corrigé en reprenant le même principe `max()` que la largeur pour la
position : `left: calc(50% - max(0,057vw, 0,032vh)); top: calc(50% -
max(1,934vw, 1,080vh));` (plus les doublons `dvh`), les constantes étant
dérivées d'un ajustement de cercle précis sur l'anneau réel de
`img/rebirth-background.webp`. Strictement identique à l'ancien
comportement sur un téléphone en portrait classique (écart < 0,3px) -
seuls les écrans plus larges que l'image sont concernés.

**Seconde relecture indépendante** : a revérifié la totalité (avatar réel
sur 47 morts, cinq types de configuration, jamais de mélange entre
joueurs) sur 21 tailles d'écran DIFFÉRENTES de celles du premier agent
(du très étroit à une grande tablette 2560×1440, en passant par le seuil
critique du ratio 0,558 testé des deux côtés à 1px près) - 141/141
contrôles géométriques passés, jamais le moindre débordement, jamais de
saut visible au franchissement du seuil critique (redimensionnement
pixel par pixel vérifié). A reconstruit les constantes du premier agent
depuis les mathématiques de `background-size: cover` (pas seulement
empiriquement) et confirmé leur cohérence interne. A trouvé UN écart
mineur dans la mesure du premier agent (biais d'un demi-pixel image dû à
un arrondi de coordonnée de pixel dans son script de mesure, effet
inférieur à 0,3px à l'écran, ne cause jamais de débordement) -
explicitement qualifié de raffinement optionnel, non nécessaire, non
appliqué. A confirmé que `10.webp` (la maquette fournie par
l'utilisateur) est byte-identique à `6.webp` (déjà utilisée comme
référence lors de la construction initiale de cette animation) - même
image, pas une nouvelle référence.

Verdict final des deux agents : travail prêt à rapporter à l'utilisateur
tel quel, aucun correctif supplémentaire nécessaire.

## 27/09/2026 — Écran de statistiques complété : échanges monétaires et masse détaillée (dette/libre + smartphone)

Demande utilisateur : compléter l'écran de statistiques pour les parties
en monnaie dette et libre suivies par smartphone, avec quatre besoins
précis - (1) nombre global d'échanges et sa répartition dans le temps
(tours) et parmi les joueurs, (2) masse monétaire globale (création ET
destruction) au cours de la partie et à chaque tour, (3) valeur des
échanges en unités monétaires à chaque tour, (4) "accès à la monnaie"
(ratio masse monétaire / nombre de joueurs) à chaque tour - avec moyennes
et médianes partout, tableaux/histogrammes/diagrammes. Le "certificat du
joueur" (points forts/faibles), demandé dans le même message, a été
explicitement **différé par l'utilisateur** ("le traiter séparément, plus
tard") : aucune décision de conception n'a été prise à son sujet.

**Nouveau endpoint** `GET /api/games/{id}/exchange-stats`
(`StatsService.computeExchangeAndMoneyReport`, `GecoServer.java`) :
combine deux nouveaux rapports dans une seule réponse, `ExchangeStats`
(échanges) et `MoneyMassDetailReport` (masse détaillée), plus un booléen
`applicable`. **Réservé à la dette et la libre suivies par smartphone**
(`isSmartphoneTrackedGame`, vérifie `Player.startingCardsJson != null`
sur TOUS les joueurs, actifs ou non - contrairement au discriminant privé
équivalent d'`Event.java`, qui ne regarde que les joueurs actifs : un
rapport de fin de partie n'a en général plus aucun joueur actif) - jamais
pour le troc (aucune valeur monétaire, voir docs/10-etape-plugins-troc.md,
règle 6) ni pour une partie classique sans smartphone (aucune
`Transaction` n'existe alors). `applicable=false` renvoie des rapports
`null` plutôt que des rapports vides trompeurs.

**Décisions de conception :**
- **Échanges** (`computeExchangeStats`) : rejoue la liste des
  `Transaction` individuelles (déjà horodatées par tour via
  `Transaction.turnNumber`, pas de recorrélation par timestamp
  nécessaire), en excluant les échanges troc+smartphone
  (`isCardSwap()`/`isGoodsTrade()`) - cette statistique n'a de sens qu'en
  monnaie dette/libre. Valeur nette par transaction lue directement via
  `Transaction.totalCoinsValue()` (déjà net de tout rendu de monnaie,
  aucune formule dupliquée). Chaque tour DÉJÀ JOUÉ apparaît dans
  `byTurn`, même à zéro échange (continuité temporelle du graphique).
  Chaque transaction est comptée **deux fois** dans `byPlayer` (une fois
  pour l'acheteur, une fois pour le vendeur) - `PlayerExchangeStat`
  documente explicitement que la somme de ses valeurs vaut donc 2×
  `globalValue`, pas une erreur d'arrondi.
- **Masse monétaire détaillée** (`computeMoneyMassDetailHistory`) : la
  variation par tour (`massDelta`, création si positive/destruction si
  négative) est dérivée **OBSERVATIONNELLEMENT** comme la différence
  entre deux points de masse consécutifs (même mécanisme que
  `computeMoneyMassHistory`, `Game.recomputeAll` + callback sur
  `EventType.TURN`), plutôt que recalculée depuis les multiples chemins
  de mutation de la masse dans `Event.applyEvent()` (TURN/DEATH/
  NEW_CREDIT/REIMB_CREDIT, qui diffèrent entre dette et libre, et entre
  mode strict TRM smartphone et mode classique) - choix délibéré pour
  ne jamais risquer une dérive entre deux calculs séparés de la même
  grandeur : la masse elle-même (déjà calculée ailleurs, seule source de
  vérité) suffit à en déduire la variation par simple soustraction.
- Moyennes et médianes calculées partout où demandé (valeur/échange,
  échanges/tour, masse/joueur, variation/tour), en réutilisant
  `computeMedian` existant (ajout d'un `computeMedianDouble` pour les
  séries de type `double`, ex. le ratio masse/joueurs).

**Investigation "bug" sur l'activité par joueur** (piste identifiée avant
implémentation, dans une session précédente compactée) : l'hypothèse
était que la section "Activité par joueur" du rapport de fin de partie
était masquée à tort pour la libre smartphone, alors qu'elle a
désormais de vraies `Transaction`. **Vérifié et infirmé** :
`computeActivityReport` ne rejoue QUE les événements liés au crédit
(`NEW_CREDIT`/`INTEREST_ONLY`/`REIMB_CREDIT`/`CANNOT_PAY`/`BANKRUPT`/
`PRISON`), jamais les `Transaction` d'achat/vente de cartes, et la
monnaie libre n'a par nature aucun de ces événements (pas de crédit).
Démasquer cette section pour la libre aurait donc affiché un tableau à
zéro partout, pas un vrai correctif - le masquage reste donc justifié
pour ce système. L'activité d'ÉCHANGE (achat/vente de cartes) propre à la
libre/dette smartphone est désormais couverte par la nouvelle section
"Échanges monétaires" ci-dessus, basée sur les vraies `Transaction` - pas
besoin de toucher `computeActivityReport`.

**Front-end** (`app.js`, `index.html`) : nouvelle section "Échanges
monétaires" + "Masse monétaire détaillée" sur l'écran de rapport
(`renderExchangeStatsSection`), entre "Activité par joueur" et "Richesse
des joueurs dans le temps" - masquée entièrement quand
`exchangeReport.applicable` est faux. Quatre nouveaux graphiques
(`chartExchangeByTurn` : barres+ligne double axe nombre/valeur par tour,
`chartExchangeByPlayer` : barres par joueur, `chartMoneyMassDelta` :
barres vert/rouge création/destruction par tour, `chartMoneyMassPerPlayer` :
courbe masse/joueurs actifs), tous via `trackChart` (convention zoom/
pinch existante). Toutes les nouvelles chaînes passent par `data-i18n`/
`t("...")`, ajoutées à `lang/fr.po` ET `lang/en.po` (vérifié par le
script de cohérence i18n de CLAUDE.md - 0 clé manquante).

**Vérification** : nouveau test unitaire dédié,
`StatsServiceExchangeStatsTest` (`geco-server`, 3 cas) - un scénario
déterministe (partie libre + smartphone, 3 joueurs, transactions
explicites sur 2 tours puis un tour sans échange) dont la vérité terrain
est calculée INDÉPENDAMMENT dans le test à partir des vraies
`Transaction` persistées (jamais des valeurs devinées à l'avance : en
monnaie libre, le prix réel d'une carte dépend du DU courant, pas des
`weakCoins` envoyés au serveur - piège rencontré en écrivant ce test,
voir le commentaire de `boostAllBalances` : un solde boosté à un montant
FIXE se retrouve réinjecté dans la masse au tour suivant en mode strict
TRM, avec un DU qui grandit d'autant jusqu'à dépasser ce même solde fixe
- corrigé en mesurant le DU courant à chaque tour et en boostant à un
multiple large de cette valeur, jamais une constante devinée à l'avance),
plus deux cas de non-applicabilité (troc, libre classique sans
smartphone). Suite complète (`mvn test`) verte après ajout. Vérifié
manuellement en conditions réelles (serveur démarré, partie dette +
smartphone à 4 joueurs, capture d'écran de la section rendue - voir
`docs/13-etape3-etat-et-feuille-de-route.md`).

Voir `docs/13-etape3-etat-et-feuille-de-route.md` pour l'état d'avancement
à jour de l'étape 3, et `CLAUDE.md` (racine du dépôt) pour les conventions
condensées à destination d'une session Claude Code.

### Relecture indépendante et campagne 2/4/10 joueurs du rapport "Échanges monétaires" (27/09/2026, même soir)

**Méthode** : relecture du diff 7e2f7fb par un second agent, puis 3 parties
libre + smartphone (2, 4 et 10 joueurs, 12 tours nominaux de 5 minutes -
simulées, comme `GameServiceFullGameSimulationTest`, mais pilotées en HTTP
RÉEL contre `geco-server.jar` : chaque "téléphone" depuis sa propre IP
loopback, offre de vente → consultation → rachat ; l'animateur rejoue
exactement l'assistant de fin de tour : D pour les mourants, W pour chaque
actif avec le DU, T ; fin de partie : Q pour chacun puis E). Une vérité
terrain est tenue INDÉPENDAMMENT (jetons de chaque joueur, masse "vivante"
recalculée aux mêmes instants que le moteur, DU recalculé par la formule
TRM, prix de chaque carte, révolutions et percée technologique observées
comme un téléphone/l'animateur les voit) et comparée à `/exchange-stats`,
`/report` et `/activity`. Résultat : 0 écart en direct (masse, DU, jetons,
valeur de chacun des 370 échanges) ; deux bugs du nouveau rapport :

1. **Unités** : `Transaction.totalCoinsValue()` compte des JETONS faibles,
   affichés sous l'axe "Unités monétaires" à côté d'une masse monétaire,
   elle, en unités monétaires. Identique si "Valeur d'une pièce faible" = 1,
   faux sinon - mesuré avec 0,5 : 24612 "unités" échangées au lieu de 12306.
   Corrigé (`StatsService.monetaryUnitsPerJeton`, valeurs en `double`) ; la
   dette smartphone garde 1 jeton = 1 unité par construction.
2. **Tour 1 = masse finale** : `Game.recomputeAll` ne remet jamais
   `Player.jetonWeak` à zéro, et le tout premier TURN rejoué voit des
   joueurs déjà suivis par smartphone - il recalcule donc la masse depuis
   leurs jetons de FIN de partie (mesuré : 14725 au lieu de 28 à 4 joueurs,
   avec une fausse destruction de -14673 au tour 2 et des médianes
   faussées). Corrigé LOCALEMENT au rapport
   (`resetJetonsToStartOfGameForReplay` : dotation de départ réelle avant le
   rejeu, jetons d'origine restaurés après, objet détaché jamais persisté) -
   le redesign du rejeu utilisé par Annuler/éditer reste une décision
   utilisateur en attente. Le graphique pré-existant "Évolution de la masse
   monétaire" (`computeMoneyMassHistory`) et la courbe Galilée
   (`computeWealthOverTime`) ont exactement le même défaut au tour 1, visible
   sur le même écran que le nouveau graphique désormais juste : volontairement
   NON modifiés ici (décision attendue), le même appel s'y applique en une
   ligne.

Également retiré : deux variables mortes et un commentaire "BUG TROUVÉ ET
CORRIGÉ" dans `renderReport` (app.js) qui décrivait comme corrigé un bug
dont l'hypothèse avait été infirmée. Deux tests de régression ajoutés à
`StatsServiceExchangeStatsTest` (échouent sur le code d'avant : masse tour 1
1610612733 au lieu de 21 ; valeur 6,0 au lieu de 3,0).
