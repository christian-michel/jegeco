# Cahier des charges — Ğeconomicus Helper / GecoLab, Étape 3

Rédigé en fin d'étape 2, à partir de tout ce qui a été discuté et
construit ensemble.

---

## 1. Résumé pour Claude (à lire en premier)

Tu reprends le développement de **Ğeconomicus Helper** (bientôt renommé
**GecoLab**), la refonte Java 21 + interface web d'un jeu pédagogique qui fait
vivre à ses joueurs une vie économique complète, une fois en monnaie dette,
une fois en monnaie libre, pour en comparer concrètement les effets sur les
inégalités.

**L'étape 2 est terminée** : l'animateur peut désormais gérer une partie
complète de bout en bout, en monnaie dette comme en monnaie libre, avec un
assistant de fin de tour qui le guide pas à pas, et des statistiques de fin de
partie. Le projet original de jytou
(https://gitlab.com/jytou/geconomicus_helper) reste la référence en cas de
doute sur une règle de jeu - il a été lu directement à plusieurs reprises pour
retrouver des mécaniques non documentées ailleurs (voir §7).

**L'étape 3**, dont ce document précise le périmètre, ajoute la gestion des
cartes numériques, le jeu sur smartphone pour chaque joueur, les profils/
avatars, et des statistiques plus riches. Avant de commencer à coder quoi que
ce soit, lis entièrement ce document : il contient à la fois les décisions déjà
prises, les points encore ouverts nécessitant une clarification avec
l'utilisateur, et les pièges déjà rencontrés à ne pas reproduire.

**Méthode de travail qui a bien fonctionné pendant l'étape 2**, à reproduire :
- Quand une règle métier est ambiguë (ex. la classification saisie/
  banqueroute/prison, le calcul du DU), **demander un exemple chiffré concret**
  plutôt que de deviner - ça a évité plusieurs allers-retours coûteux.
- **Toujours vérifier le code source original** (jytou, GitHub/GitLab) avant
  d'inventer un algorithme quand le comportement attendu existe déjà quelque
  part - l'algorithme de suggestion des morts et le calcul du DU en sont de
  bons exemples, retrouvés en lisant `HelperUI.java`/`StatsFrame.java`/
  `Event.java` plutôt que réinventés.
- **Tester réellement** (scénarios concrets exécutés, pas seulement une
  relecture de code ou une vérification de compilation) avant de livrer -
  plusieurs bugs subtils (staleness de données, confusion entre code court et
  nom complet d'énumération renvoyé par le serveur) n'ont été trouvés que
  comme ça.
- Après chaque changement JS : vérifier la syntaxe (`node -c`) et l'absence
  d'incohérence entre les `id` HTML et les `el("...")` référencés en JS.
- Documenter les décisions et leurs raisons directement en commentaire dans le
  code (beaucoup de commentaires du type "Remonté par un utilisateur : ...").

---

## 2. Contexte du projet

- Jeu de société **Ğeconomicus** : règles officielles sur
  https://geconomicus.glibre.org/rules.html, théorie de fond sur
  https://trm.creationmonetaire.info/ (Théorie Relative de la Monnaie,
  Stéphane Laborde).
- Refonte d'un outil d'animation existant, developpé par **jytou** :
  https://gitlab.com/jytou/geconomicus_helper (Java 8, Swing). L'intégralité
  de la logique métier de ce projet original a été conservée sans être
  réinventée ; l'étape 1-2 a porté sur la modernisation technique (Java 21,
  Jakarta) et sur l'ajout d'une interface web pour l'animateur.
- Nom de projet à terme : **GecoLab** ("Le laboratoire ludique de l'économie").

---

## 3. Vision produit — GecoLab

Vision à terme communiquée par l'utilisateur, à garder en tête sans
nécessairement l'implémenter dès l'étape 3 :

- **Architecture modulaire** : à terme, chaque brique du jeu (règles du
  système monétaire, gestion des morts/du temps, interface, stockage des
  données) serait un module remplaçable indépendamment. Objectif final :
  pouvoir remplacer le module "monnaie dette" par un module "troc", ou ajouter
  un module "monnaie digitale de banque centrale" programmable, sans toucher
  au reste de l'application - un système de plugins activables par partie.
- **Le bon moment pour s'y atteler sérieusement** : une fois la monnaie libre
  aussi mature que la monnaie dette (deux implémentations concrètes à
  comparer rendent plus facile de voir où tracer la frontière d'une interface
  commune, plutôt que de la deviner avec un seul exemple). Ce moment est
  probablement atteint maintenant que l'étape 2 est complète des deux côtés.
- **Deux façons de jouer** : "classique" (cartes physiques et jetons) et
  "smart" (chaque joueur avec son smartphone) - l'étape 3 porte sur ce second
  mode.
- Écran de paramètres à terme (colonne latérale gauche) : choix de la langue,
  gestion des avatars (par défaut + personnalisés), thème de couleurs,
  visuels des fonds de cartes/illustrations/jetons-billets, bascule classique/
  smart, gestion des plugins de règles.

---

## 4. État à la fin de l'étape 2 (ce qui fonctionne déjà)

### 4.1 Monnaie dette
- Création de partie, ajout/gestion des joueurs (tri alphabétique partout).
- Assistant de fin de tour complet et dans le bon ordre : **Décès (sélection)
  → Bilan des joueurs endettés (obligation de payer pour les mourants,
  facultatif pour les autres) → Inventaire des morts → Nouveaux-nés →
  Nouveaux crédits → Récap final avec case "démarrer le tour suivant"**.
- Classification automatique Saisie/Banqueroute/Prison à partir d'un montant
  visé par la banque (jamais de banqueroute/prison pour un joueur qui meurt ou
  au dernier tour de la partie - la banque saisit, c'est tout).
- Assistant de fin de partie dédié au dernier tour : règlement des crédits →
  inventaire de sortie de tous les joueurs → bilan de la banque (traitée comme
  un "joueur" de plus, comme dans l'app originale) → case à cocher (décochée
  par défaut) pour rediriger vers les statistiques plutôt que "Nouvelle
  partie".
- Chrono synchronisé côté serveur (vraie pause partagée entre tous les écrans
  connectés, pas seulement visuelle).
- Diagramme temps réel "Crédits en cours par joueur" sur le tableau de bord,
  animé (mis à jour en place, pas reconstruit).

### 4.2 Monnaie libre
- Distribution du DU calculée automatiquement (DU = masse monétaire / (7 ×
  joueurs actifs), tronqué - formule confirmée dans les règles officielles).
- Étape dédiée de l'assistant : inventaire individuel + nouveau DU par joueur.
- Case "Pénalité d'un jeton" à la création de partie.
- Facteur carte/monnaie : 1 par défaut (aligné avec la monnaie dette depuis le
  24/08/2026 - remonté par un utilisateur, pour préparer un futur mode "strict
  TRM" qui verrouillera ce facteur à 1). Modifiable par l'animateur.
- Pas de notion de crédit/banque : boutons et statistiques correspondants
  masqués.

### 4.3 Statistiques
- Rapport de fin de partie : moyenne, médiane, écart-type, indice de Gini,
  histogramme de répartition des richesses, courbe de masse monétaire.
- **Seuil de pauvreté (60% de la médiane) et seuil de condition modeste (75%
  de la médiane)**, définitions officielles Eurostat/INSEE.
- Vue "avec/sans la banque" en monnaie dette (la banque comptée comme un
  joueur de plus dans l'histogramme).
- Diagramme "Richesse par joueur" (nommé, pas par tranches de %) sur le
  tableau de bord en monnaie libre.
- Page de documentation dédiée dans l'app (`docs/fr/markdown/statistiques.md`)
  expliquant seuil de pauvreté et indice de Gini, avec sources.

### 4.4 Reprise de joueurs entre parties
- Option A implémentée (reprise simple par nom, sans nouvelle route API) :
  à la création d'une partie, choix "Nouveaux joueurs" ou "Reprendre d'une
  partie existante", avec liste à cocher des joueurs de la partie choisie.
- Utile pour comparer monnaie dette et monnaie libre avec les mêmes joueurs.

---

## 5. Objectifs de l'étape 3

### 5.1 Cartes numériques et jeu sur smartphone (le cœur de l'étape 3)

Contexte déjà exploré en étape 2 (Phase A) : un écran de connexion joueurs
existe déjà (`join.html`, `js/player.js`, `css/player.css`), avec détection
réseau local et QR code pour rejoindre une partie depuis un smartphone. **À
vérifier/reprendre en premier**, avant de construire par-dessus.

Un visuel de référence complet a été fourni par l'utilisateur (maquette
"GecoLab" façon écrans mobiles), à retrouver dans l'historique de conversation
si besoin - il détaille :
- **Écran "Mon profil"** : avatar, solde, statistiques personnelles (jetons,
  cartes, achats, ventes, classement de la partie).
- **Écran "Tableau de bord"** : résumé, courbe d'évolution du solde,
  historique des dernières opérations, classement.
- **Écran "Mes cartes"** : vue par piles/catégories (alimentation,
  agriculture, ressources...), avec icônes et compteurs, tri (valeur, dernière
  opération, nombre d'exemplaires).
- **Sélection d'une carte** → **retournement de la carte** (façon carte à
  jouer) pour afficher son **QR code** de vente, avec un compte à rebours
  visible pendant que le QR code est actif.
- **Scan d'un QR code** côté acheteur → écran de confirmation d'achat/vente
  avec les soldes mis à jour automatiquement des deux côtés.
- **Historique des échanges**, avec le type d'opération et le montant.

Design retenu (déjà discuté) : interface épurée et douce, couleurs par
secteur de carte (jaune=primaire, bleu=secondaire, vert=électronique,
rouge=informatique...), icônes illustratives, animations fluides.

**Points à trancher avec l'utilisateur avant de coder** :
- Le catalogue de cartes (v2) a déjà été esquissé en étape 2 - le retrouver et
  vérifier s'il correspond encore au besoin.
- Comment les transactions entre joueurs (carte contre jetons) sont-elles
  enregistrées côté serveur ? C'est ce qui manque aujourd'hui pour un suivi de
  richesse individuelle continu (voir limite documentée dans
  `StatsService.computeWealthOverTime` : la richesse n'est aujourd'hui connue
  qu'aux évaluations Mort/Fin de partie, pas en continu) - l'étape 3 est
  précisément l'occasion de combler cette lacune.
- Authentification/identification des joueurs sur leur smartphone : simple
  code de partie + choix du nom, ou quelque chose de plus robuste ?

### 5.2 Profils joueurs persistants (à remplacer la reprise par nom)

Décision déjà actée avec l'utilisateur : l'option A (reprise par nom, sans
nouvelle table) suffit pour l'étape 2, mais l'étape 3 est le bon moment pour
construire l'**option B** envisagée : une vraie entité **Profil**, séparée de
"Joueur" (qui reste liée à une partie précise) :

- Un Profil peut participer à plusieurs parties dans le temps.
- Il porte l'avatar et l'identité de façon fiable, sans dépendre d'une
  correspondance par nom (qui peut être ambiguë en cas d'homonymes).
- La reprise de joueurs entre parties (dette → libre notamment) s'appuierait
  naturellement sur cette même infrastructure plutôt que d'être un mécanisme
  à part.
- C'est aussi la fondation naturelle pour les avatars et avec eux, l'ensemble
  de l'écran "Mon profil" du §5.1.

### 5.3 Paramètres avancés (colonne latérale)

Voir le détail au §3 (vision GecoLab) - à prioriser avec l'utilisateur, tout
n'a pas nécessairement besoin d'être fait en une fois : langue, avatars,
thème de couleurs, visuels de cartes/jetons, bascule classique/smart,
gestion des plugins.

### 5.4 Statistiques avancées

Deux volets identifiés par l'utilisateur, non traités en étape 2 :

- **Module Galilée** (TRM, https://rml.creationmonetaire.info/modules/) :
  courbes de masse monétaire relative par joueur, montrant la convergence des
  comptes individuels vers la moyenne au fil du temps en monnaie libre.
  `StatsService.computeWealthOverTime` existe déjà comme base (portée avec sa
  limite documentée : richesse connue seulement aux évaluations, pas en
  continu) - deviendra bien plus précis une fois les transactions
  individuelles enregistrées via les cartes numériques (§5.1).
- **Comparaison agrégée monnaie dette / monnaie libre en fin de partie**
  (histogrammes combinés "agrégés standards" et "agrégés corrigés" par
  rapport à l'augmentation de la masse monétaire en monnaie libre, avec
  moyenne/écart-type/seuil de pauvreté) : nécessite un concept de **liaison
  entre deux parties** (laquelle partie dette correspond à quelle partie
  libre) qui n'existe pas encore dans les données - à concevoir avec
  l'utilisateur avant de coder. Peut s'appuyer sur le Profil persistant du
  §5.2 pour savoir que "ce sont les mêmes joueurs".

---

## 6. Points reportés d'étape 2, à reprendre en priorité

Ces points ont été identifiés pendant l'étape 2 mais volontairement laissés
de côté (hors périmètre du moment, ou nécessitant plus de cadrage) :

- **Séquence détaillée de l'entre-deux-tours en monnaie libre** : un dernier
  document de spécification (juste avant la fin de l'étape 2) précise que le
  joueur qui meurt **rend d'abord toutes ses pièces** (inventaire), **puis**
  renaît en recevant le DU moyen du moment - une séquence en deux temps
  légèrement différente de l'étape combinée "inventaire + DU pour tous les
  joueurs actifs" actuellement implémentée. À vérifier/ajuster avec
  l'utilisateur : faut-il distinguer le traitement d'un joueur qui meurt
  (inventaire → DU seul, sans ajouter à d'anciennes pièces) de celui d'un
  joueur qui reste (inventaire de ce qu'il a déjà + DU ajouté) ?
- **"Rendu de monnaie" lors d'une saisie qui dépasse la cible** : demandé par
  l'utilisateur, mais l'algorithme de répartition automatique en dénominations
  précises s'est avéré peu fiable en pratique (voir le commentaire dans
  `computeAutoSeizure`) - seul le montant brut du trop-perçu est affiché pour
  l'instant. Une vraie solution nécessiterait de repenser l'algorithme de
  saisie lui-même (par exemple en cherchant activement une combinaison de
  cartes qui tombe pile sur la cible plutôt que de saisir gloutonnement du
  plus fort au plus faible).
- **Badge WebSocket "hors ligne"** : reste parfois bloqué sur "hors ligne"
  malgré une connexion fonctionnelle - jamais définitivement corrigé, sans
  impact pratique constaté sur l'utilisation réelle.
- Voir aussi `docs/09-etape2-aide-contextuelle.md` (à la racine du dépôt) pour
  d'éventuelles fonctionnalités du manuel original non encore portées.

---

## 7. Architecture technique actuelle

**Lire `docs/00-vue-ensemble.md` à la racine du dépôt en tout premier** :
structure du projet, comment lancer l'app, où trouver quoi, philosophie des
choix techniques (pas de build tool JS, moteur partagé web/Swing...).

Résumé rapide :
- Maven multi-module : `geco-engine` (moteur, JPA/H2, partagé web+Swing) /
  `geco-app` (Swing, conservé) / `geco-server` (Javalin 6 + Jetty, web).
- Frontend web : vanilla JS (`app.js`, ~2300 lignes, voir sa carte de sections
  en en-tête de fichier), Chart.js et QRCode.js hébergés localement (pas de
  CDN).
- Beaucoup de mécaniques (suggestion des morts, comportement banque
  investissement/bilan, calcul du DU, banqueroute/prison) ont été retrouvées
  en lisant le vrai code source de jytou plutôt que réinventées - le réflexe à
  garder pour l'étape 3 aussi, en particulier pour tout ce qui touche aux
  cartes/échanges si l'original en a une gestion (même partielle).

---

## 8. Annexes — sources utiles

- Règles officielles du jeu : https://geconomicus.glibre.org/rules.html
- Monnaie dette : https://geconomicus.glibre.org/debt_money.html
- Monnaie libre : https://geconomicus.glibre.org/libre_money.html
- TRM : https://trm.creationmonetaire.info/
- Module Galilée (TRM) : https://rml.creationmonetaire.info/modules/
- Projet original (jytou) : https://gitlab.com/jytou/geconomicus_helper
- Seuil de pauvreté (Eurostat) :
  https://ec.europa.eu/eurostat/fr/web/income-and-living-conditions/database
- Seuil de pauvreté (Observatoire des inégalités) :
  https://www.inegalites.fr/La-pauvrete-en-Europe
- Étude DREES n°1349 (sept. 2025), *Personnes pauvres et modestes en
  Europe : qui sont-elles ?* - fournie par l'utilisateur, archivée dans
  `geco-server/.../public/docs-offline/DREES_2025_pauvres_et_modestes_en_Europe.pdf`
- Indice de Gini : https://fr.wikipedia.org/wiki/Coefficient_de_Gini ·
  https://www.insee.fr/fr/metadonnees/definition/c1551 ·
  https://www.insee.fr/fr/statistiques/2491918
