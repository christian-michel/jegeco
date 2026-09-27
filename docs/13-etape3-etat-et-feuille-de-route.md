# Étape 3 — état d'avancement et feuille de route

Document d'état, à la différence de `CAHIER_DES_CHARGES_ETAPE3.md` qui
décrit la **vision et le périmètre d'origine** (rédigé en tout début
d'étape 3, désormais dépassé comme référence de statut). Ce document-ci
répond à "qu'est-ce qui marche déjà, qu'est-ce qu'il reste à faire" — à
mettre à jour à chaque avancée notable plutôt que de laisser dériver.

Pour le POURQUOI de chaque décision, voir `03-architecture-technique.md`
(journal chronologique complet). Pour un panorama session par session,
voir le catalogue des transcripts de développement (`journal.txt`, hors de
ce dépôt).

## Ce qui est construit et fonctionne

### Jeu sur smartphone (le cœur de l'étape 3)
- Inscription joueur par QR code, profils avec avatar (galerie ~136
  entrées), token d'accès individuel.
- Catalogue de cartes numériques (secteurs réels, visuels), pioche et tirage
  automatique.
- Écrans joueur complets : Accueil (tableau de bord), Mes cartes
  (regroupement par valeur/catégorie/quantité), Profil, Classement,
  Historique (transactions + carrés).
- Achat/vente de cartes par QR code (scan plein écran + saisie manuelle de
  secours), avec rendu de monnaie automatique si besoin
  (`findPaymentWithChange`/`tryMakeChange`).
- Détection et encaissement automatique des carrés (4 cartes de même
  modèle), avec animation, y compris le cas de pénurie de modèles (repli
  sur le niveau supérieur, filet de sécurité anti-boucle infinie).
- Compte à rebours de tour synchronisé serveur (vraie pause partagée),
  infobulles pause/fin de tour distinctes.
- Fil d'actualité temps réel par WebSocket (carrés, ventes, nouveau tour).
- Demandes de crédit (monnaie dette) : ct côté joueur et backend
  fonctionnels ; **écran animateur pour les traiter non commencé**
  (dormant, voir section "Reste à faire").
- Sécurité Phase 1 : 24 routes protégées par PIN, WebSocket cloisonné par
  partie, rate limiting.
- Système de journalisation pour éliminer les erreurs silencieuses (voir
  `CLAUDE.md`, section dédiée) — serveur (gestionnaires d'exception
  globaux) et client (panneau de diagnostic 🐞 sur smartphone et
  animateur).
- **Couverture multilingue à 100%** (vérifié le 18/09/2026, puis complété
  le même jour) : toutes les chaînes d'interface (`data-i18n`/`t("...")`)
  sont couvertes en français ET en anglais, y compris la vue
  "Documentation" intégrée à l'app (`index.html`), jusque-là en français en
  dur, désormais convertie en clés `data-i18n`/`data-i18n-html`, ainsi que
  les trois pages de documentation autonomes qu'elle référence
  (`docs/en/html/regles-du-jeu.html`, `statistiques.html`,
  `connexion-joueurs.html`, avec leurs sources `docs/en/markdown/`
  correspondantes) - n'existaient jusqu'ici qu'en français.

### Comptes animateurs multi-session et sécurité (construits le 21/09/2026 — vers un hébergement public)

Demande utilisateur explicite : "proposer une version serveur capable de
gérer le multi session avec plusieurs animateurs qui ont chacuns leur
profil et leurs parties." Transversal aux trois systèmes monétaires
ci-dessus (contrairement aux sections suivantes) - concerne le serveur
dans son ensemble, pas une mécanique de jeu précise.

- **Comptes animateurs réels** (`Animator`, `AnimatorService`) : identifiant
  + mot de passe (haché en PBKDF2WithHmacSHA256, 210 000 itérations,
  recommandation OWASP au moment de l'écriture), deux rôles - `ADMIN` (gère
  les réglages partagés : catalogues, plugins, langues, comptes) et
  `ANIMATEUR` (crée/gère seulement ses propres parties). Premier compte créé
  sur un serveur neuf → automatiquement ADMIN, et rattache à lui toutes les
  parties déjà jouées avant l'introduction des comptes (jamais orphelines).
- **Sessions de connexion** (`SessionService`) : jeton opaque transmis dans
  l'en-tête `X-Session-Token` (même convention que `X-Game-Pin`), jamais un
  cookie. Volontairement **en mémoire, sans expiration** - proportionné à un
  serveur associatif sur LAN où un redémarrage est rare et le nombre
  d'animateurs faible ; **à revoir avant un vrai déploiement public à
  grande échelle** (voir "Reste à faire" ci-dessous).
- **Cloisonnement par partie** : un animateur non-ADMIN ne voit et ne peut
  agir que sur SES PROPRES parties (`Game.owner`, `checkGameOwnership`
  dans `GecoServer`) - un ADMIN continue de tout voir/gérer, comme avant
  l'introduction des comptes.
- **Referme la faille identifiée le 02/09/2026** ("Passe de sécurité", voir
  `03-architecture-technique.md`) : les routes d'administration globale du
  serveur (liste/création de parties, réglages, catalogues, langues,
  plugins) étaient jusque-là accessibles sans aucune authentification -
  désormais protégées par `requireAdmin`/`requireAnimator`, en plus de la
  protection par PIN par partie déjà en place.
- **HTTPS** : un certificat auto-signé est généré automatiquement au
  premier lancement (`SelfSignedCertService`, Bouncy Castle) pour que le
  scan caméra (QR d'achat de cartes) fonctionne sur le réseau local - couvre
  `localhost` et les adresses IP locales détectées. **Ne remplace pas un
  vrai certificat reconnu** (chaque navigateur affiche un avertissement à
  accepter manuellement) : suffisant pour un atelier sur réseau local, pas
  pour une adresse publique sur internet (voir "Reste à faire").
- L'application Swing (`geco-app`) n'a aucune notion de compte et continue
  de fonctionner exactement comme avant - rien de ce qui précède ne la
  concerne.

### Animation "Mort du joueur" (construite le 26/09/2026)

Demande utilisateur : "à l'entre deux tours, lorsque les morts sont
annoncés... les smartphones des joueurs déclenchent une animation" - une
mise en scène (dézoom depuis la lune vers une scène de cimetière, puis
titre en coup de tampon) plutôt qu'une mort silencieuse.

- **Réutilisable d'emblée par les trois systèmes monétaires** (dette,
  libre, troc) en mode smartphone - agnostique du système par
  construction (voir `GecoServer`, route `POST /api/games/{id}/events`) :
  déclenchée uniquement par `EventType.DEATH` + `Player.startingCardsJson
  != null`, jamais par un test sur `Game.getMoneySystem()`, exactement le
  même principe que le mécanisme du carré partagé.
- **Ciblage PAR JOUEUR** (jamais toute la partie) : diffusion WebSocket
  `"death"` incluant `playerId`, filtrée côté client comme le carré
  (`msg.payload.playerId === state.player.id`) - vérifié par un vrai test
  Playwright à deux joueurs : celui qui meurt voit l'animation, l'autre
  ne voit rien du tout sur son écran.
- **Texte multilingue rendu en code**, jamais une image figée dans une
  langue : `js/vendor/cartoon-text.js` (fourni par l'utilisateur, rendu
  SVG vectoriel avec retour à la ligne automatique) plutôt que le gradient
  orange/cyan codé en dur du mockup de référence fourni en même temps -
  décision confirmée avec l'utilisateur avant implémentation (voir
  `03-architecture-technique.md`, entrée du même jour, pour le
  raisonnement complet). Un seul nouveau texte à traduire :
  `playerView.death_anim_title`.
- Dure ~1,2 seconde (dézoom + impact du tampon), reste affichée 5 secondes
  au total depuis le déclenchement, puis referme automatiquement et
  rafraîchit l'état du joueur (nouvelle main de départ après renaissance).
- Image de fond fournie par l'utilisateur
  (`img/death-background.jpg`) - **bug trouvé en testant** : une première
  version pointait par erreur vers l'image de fond de l'écran de
  connexion (réutilisation involontaire d'un chemin de fichier expiré
  d'une tâche précédente) - repéré uniquement grâce à une vraie capture
  d'écran Playwright avant d'être corrigé, jamais par une simple relecture
  de code (le nom de fichier `death-background.jpg` était pourtant
  correct, seul son CONTENU était faux).
- **Seconde relecture indépendante** (demandée explicitement par
  l'utilisateur) : trois bugs réels supplémentaires trouvés en rejouant
  ses propres scénarios plutôt qu'en relisant le rapport du premier
  travail - dézoom quasi invisible (transition CSS héritée par erreur sur
  l'état de départ), erreur JS + badge 🐞 dès la 2e mort d'un même joueur
  (conteneur du titre vidé alors qu'un `ResizeObserver` de la mort
  précédente restait actif), écran bloqué indéfiniment si le rendu du
  titre échouait (aucun `try/finally`). Les trois corrigés le jour même -
  voir `03-architecture-technique.md`, entrée du 26/09/2026, pour le
  détail complet.
- **Troisième relecture** (audit de la seconde, également demandée
  explicitement) : a confirmé chaque mesure de la seconde relecture en la
  remesurant elle-même, et trouvé une dernière lacune de robustesse (le
  `try/finally` ne protégeait pas la boucle englobante de la file
  d'attente) - corrigée par un second `try/finally` de sécurité. Verdict
  final : fonctionnalité prête, aucun défaut bloquant.
- **Limites connues, non bloquantes** : un téléphone hors-ligne ou
  verrouillé au moment précis de la mort ne voit jamais l'animation
  ensuite (même limite déjà assumée pour l'animation du carré) ; ni le
  parcours réel de l'assistant de fin de tour (clic par clic) ni un vrai
  appareil iOS/Android n'ont été testés par les agents de relecture -
  seulement le même appel HTTP que l'assistant envoie.

### Animation "Renaissance !" (construite le 27/09/2026)

Demande utilisateur : "peux-tu faire de même avec la renaissance du
joueur... le fond dézoome et le texte apparaît comme un coup de tampon,
comme pour l'exemple précédent."

- **Enchaînée automatiquement après "Mort du joueur", jamais un second
  déclenchement séparé** : ce moteur de jeu ne connaît qu'un seul
  événement (`DEATH`) qui déclenche à la fois la mort ET la renaissance
  immédiate - il n'existe pas de second point d'accroche distinct pour
  "la renaissance" (le bouton de l'assistant animateur s'appelle
  d'ailleurs déjà "Valider la renaissance"). Décision confirmée
  explicitement avec l'utilisateur (question posée avant implémentation,
  réponse actée) : les deux animations jouent l'une après l'autre sur le
  même événement, plutôt que l'une remplaçant l'autre - racontent les
  deux temps forts du même instant de jeu (~10 secondes au total).
- **Aucun changement serveur nécessaire** : le chaînage se fait
  entièrement côté client (file d'attente déjà en place pour "Mort du
  joueur"), sur la diffusion WebSocket "death" déjà agnostique du système
  monétaire - réutilisable d'emblée par dette/libre/troc en mode
  smartphone, sans le moindre `if` supplémentaire sur
  `Game.getMoneySystem()`.
- **Avatar RÉEL du joueur affiché dans le cercle doré** (jamais un avatar
  générique) : réutilise `buildProfileAvatarHtml`, déjà utilisé par
  l'écran Profil - avatar de la galerie, avatar personnalisé (SVG), ou
  repli emoji générique si aucun n'est configuré, exactement les trois
  mêmes cas que partout ailleurs dans l'app.
- **Toutes les leçons de la double relecture indépendante de "Mort du
  joueur" appliquées dès la première version** (jamais redécouvertes une
  seconde fois) : état de départ du zoom rendu instantané
  (`transition: none` + reflow forcé), aucun vidage manuel du conteneur du
  titre avant `CartoonText.render` (celui-ci s'en charge lui-même),
  `try/finally` englobant toute la séquence. Position/rayon du cercle doré
  mesurés précisément sur l'image fournie (détection du plus gros disque
  quasi blanc), jamais estimés à l'œil.
- Son de carillon ascendant synthétisé (Web Audio API), pensé pour un
  moment positif - contrairement au son sourd de "Mort du joueur".
- Vérifié par un test Playwright dédié : enchaînement bien séquentiel
  (jamais les deux overlays actifs en même temps), dézoom qui démarre bien
  très agrandi dès la première frame (échantillonnage image par image),
  avatar réel correctement affiché (testé avec un avatar de galerie ET
  avec le repli emoji), aucune erreur console sur la séquence complète,
  un second joueur de la même partie ne voit ni l'une ni l'autre
  animation.
- **Seconde puis troisième relecture indépendantes** (demandées
  explicitement) : confirment qu'aucun des trois bugs de "Mort du joueur"
  n'est revenu ; trouvent et corrigent un avatar invisible en mode
  "animations réduites", un mauvais dimensionnement du cercle sur écran
  étroit, et - trouvaille la plus significative de la troisième relecture,
  audit de la seconde - une file d'attente qui pouvait rester bloquée puis
  rejouer DEUX cycles complets à la suite (~20s au lieu de 10s) si une
  exception survenait avant même le traitement normal d'un élément.
  Verdict final : fonctionnalité prête, aucun défaut bloquant - voir
  `03-architecture-technique.md`, entrées du 27/09/2026, pour le détail
  complet.

### Timing mort+renaissance réduit à 2s+3s=5s, revérifié en partie test (27/09/2026)

Suite à la construction de "Renaissance !" ci-dessus, demande utilisateur :
timing raccourci ("l'écran de la mort dure 2 secondes... celui de la
Renaissance dure 3 secondes, ce qui fait un total de 5 secondes", contre
5s+5s=10s auparavant), plus une vérification en "partie test" que les
écrans ne restent jamais bloqués et se déclenchent au bon moment, plus un
troisième tour de relecture indépendante à deux agents, avec cette fois un
mandat élargi (code + exécution réelle + fluidité de la partie, autorité
explicite de corriger directement).

- **Test sur un vrai téléphone iOS/Android : non disponible dans cet
  environnement** - disclosure honnête faite à l'utilisateur, aucune
  affirmation contraire. Seul Chromium headless (Playwright) est
  accessible dans cette session cloud isolée.
- `DEATH_ANIM_TOTAL_DISPLAY_MS` 5000→2000, `REBIRTH_ANIM_TOTAL_DISPLAY_MS`
  5000→3000 (`player-view.js`, seul fichier modifié) - chorégraphie interne
  (~1,2s par animation) toujours largement dans le budget.
- Revérifié dans une partie test à 3 joueurs, plusieurs tours normaux
  intercalés, deux morts/renaissances distinctes à des moments différents
  de la partie, deux onglets simultanés (joueur ciblé + joueur témoin) :
  déclenchement en <300ms, mort refermée à ~2,0s, renaissance enchaînée
  aussitôt et refermée à ~5,0s au total, joueur témoin jamais affecté,
  aucun écran bloqué après coup - vérifié identique sur les deux cycles,
  à deux moments différents de la même session (9/9 contrôles passés).
- Suite de tests automatisés toujours 100% verte (aucun code serveur
  modifié).
- **Bug réel trouvé et corrigé par le premier agent, confirmé par le
  second** : l'écran de mort pouvait rester bloqué indéfiniment (téléphone
  du joueur inutilisable) si le rendu du titre stylé ne se terminait
  JAMAIS - cas réel identifié : la police "Sora" en graisse forte n'était
  téléchargée qu'au moment même de la première mort d'une partie, en plein
  milieu de l'animation, donc vulnérable à un simple ralentissement
  réseau à cet instant précis. Corrigé par (1) préchargement de cette
  police dès le chargement de la page, avant toute mort possible, et (2)
  un plafond de 1,5s sur l'attente du rendu du titre, au-delà duquel
  l'animation continue quand même (journalisé, jamais un silence).
  Seconde relecture indépendante : a reproduit le bug AVANT correctif de
  façon totalement autonome, confirmé la résolution APRÈS, vérifié le
  risque propre à ce type de correctif (le rendu abandonné ne peut jamais
  écraser un rendu plus récent), et re-testé le timing/la fluidité de
  fond en comble - verdict final : fonctionnalité prête, aucun défaut
  bloquant restant.
- Voir `03-architecture-technique.md`, entrée du 27/09/2026, pour le
  détail complet des mesures et du correctif.

### Timing mort+renaissance encore raccourci et rendu plus dynamique (27/09/2026, second changement le même jour)

Nouveau retour utilisateur, avec un second fichier de référence fourni
("test1.html", une démo dessinée en CSS/JS) : "il faudrait lui donner un
autre timing afin de la rendre plus dynamique quand on joue" - les
visuels (fonds réels, texte multilingue via cartoon-text.js) sont
explicitement CONSERVÉS, seul le RYTHME s'inspire du fichier fourni.
Question posée à l'utilisateur pour trancher la durée totale cible
("aussi rapide que la référence" vs "garder 2s+3s=5s") - réponse : aussi
rapide que la référence, soit ~1,5s + ~1,5s ≈ 3s au total (contre 5s
juste avant).

- Dézoom raccourci une seconde fois (720ms→550ms) avec la courbe du
  fichier de référence (`cubic-bezier(0.16,1,0.3,1)`).
- Nouveau flash plein écran partagé (`.anim-flash-overlay`) : rouge à
  l'impact de "Mort du joueur" (fire-and-forget), blanc masquant
  l'enchaînement vers "Renaissance !" (affiché instantanément pendant que
  la scène suivante se met en place en dessous, puis s'estompe PENDANT le
  début du dézoom plutôt qu'avant ou après).
- `DEATH_ANIM_TOTAL_DISPLAY_MS` 2000→1500, `REBIRTH_ANIM_TOTAL_DISPLAY_MS`
  3000→1500.
- **Bug réel trouvé et corrigé par le premier agent de contrôle,
  confirmé par le second, mais avec une précision importante apportée
  par le second** : le minuteur détaché qui referme le flash rouge (120ms
  après l'impact) pouvait, dans un cas précis, refermer le flash BLANC à
  sa place (élément DOM partagé entre les deux couleurs) si une exception
  interrompait "Mort du joueur" moins de 120ms après l'impact - mesuré :
  flash blanc tenu 114ms au lieu de 150ms. Corrigé en ne retirant le flash
  que s'il est encore en mode rouge à cet instant. **Correction du second
  agent, à noter ici pour ne pas propager une inexactitude** : le premier
  agent (et le message du commit du correctif) citait "un rendu du titre
  qui échoue" comme exemple concret de déclencheur - le second agent a
  démontré que ce cas précis NE PEUT PAS déclencher le bug (le rendu du
  titre se termine, avec ou sans erreur, AVANT que le flash rouge ne soit
  posé) : dans la pratique du jeu réel, ce bug n'est donc accessible que
  par injection de panne artificielle, jamais par un scénario de jeu
  normal identifié à ce jour - un filet de sécurité valable à garder,
  mais pas un bug utilisateur confirmé comme le message de commit
  `f7fa455` le laisse entendre à tort. Le second agent a aussi vérifié
  explicitement qu'un minuteur de flash rouge "périmé" (mort interrompue
  par une exception) ne peut jamais interférer avec le flash rouge d'un
  cycle ULTÉRIEUR (deux flashs rouges sont nécessairement séparés d'au
  moins 600ms, largement au-delà des 120ms du minuteur) - aucun souci de
  ce côté.
- **Deux points relevés par le second agent, laissés en décision
  utilisateur, non appliqués** :
  1. Le plafond de rendu du titre (`ANIM_TITLE_RENDER_TIMEOUT_MS`,
     1500ms, posé le même jour pour un souci différent - voir plus haut)
     équivaut maintenant à la DURÉE ENTIÈRE d'un écran (1,5s) - un rendu
     pathologiquement lent (jamais observé en usage normal, mesuré à
     seulement 40-105ms) pourrait donc, dans le pire des cas, faire
     grimper le total à 6s - plus lent que les 5s que l'utilisateur vient
     justement de juger trop lentes. Piste proposée, non appliquée :
     abaisser ce plafond à 500-600ms (encore 2,5 à 3× le pire rendu normal
     mesuré).
  2. En mode "animations réduites" (`prefers-reduced-motion`), les deux
     flashs restent affichés mais sans fondu (apparition/disparition
     instantanées) - conforme aux recommandations d'accessibilité sur les
     flashs (bien en dessous de 3 par seconde), mais l'utilisateur pourrait
     préférer les désactiver entièrement pour ce mode.
- Suite de tests automatisés toujours 100% verte (aucun code serveur
  modifié), vérification mémoire (fuite DOM/minuteurs) négative sur des
  sessions de 17 à 24 cycles mort/renaissance consécutifs par les deux
  agents indépendamment.
- Voir `03-architecture-technique.md`, entrée du 27/09/2026 ("timing plus
  dynamique"), pour le détail complet des mesures.

### Monnaie libre + smartphone (retravaillée en profondeur le 09/09/2026)
- **Calcul du DU conforme à la vraie formule de la Théorie Relative de la
  Monnaie** (`DU = c × masse_monétaire / joueurs_vivants`, avec `c` dépendant
  de la durée simulée de la partie précise) — remplace l'ancienne formule
  approximative (`masse / (7 × joueurs)`, qui confondait le DU avec un
  simple décompte de jetons). Voir `CLAUDE.md` pour le détail et les
  fonctions concernées (`Game.computeDuGrowthRatePerTurn`,
  `computeCurrentDU`).
- **Masse monétaire globale toujours exacte** : recalculée directement
  comme la somme réelle des jetons détenus par les joueurs actifs, jamais
  incrémentée de façon indépendante — élimine tout risque de dérive
  d'arrondi qui s'accumule au fil des tours (vérifié par simulation
  exhaustive : écart borné à 0,5 unité monétaire au pire, jamais
  croissant).
- **Seuls les jetons faibles circulent réellement** dans ce mode (DU
  toujours distribué en jetons faibles, jamais moyens/forts) — un choix
  délibéré après plusieurs itérations, qui simplifie considérablement
  l'algorithme de rendu de monnaie (plus jamais de "impossible de rendre
  la monnaie" pour cause de dénomination incompatible).
- **Prix des cartes fixé en DU** (faible=0,5 DU, moyenne=1, forte=2,
  tresforte=4), converti en jetons via le DU courant — un prix qui varie
  donc réellement à chaque tour, contrairement à l'ancien système de
  valeur abstraite fixe. Uniquement pour ce mode précis (voir la
  distinction mode classique/smartphone dans `CLAUDE.md`).
- Traçabilité réelle des jetons par dénomination
  (`Player.jetonWeak/jetonMedium/jetonStrong`), tenue à jour en direct à
  chaque mouvement réel — plus aucune reconstruction après coup depuis
  l'historique.
- Écrans de l'assistant d'entre-deux-tours (inventaire monétaire,
  renaissance, DU des joueurs restants, fin de partie) simplifiés à un
  seul champ "Jetons" par joueur (plus de faible/moyen/fort affichés),
  cohérent avec le fait que seuls les jetons faibles circulent désormais.

### Monnaie dette + smartphone (construite le 17/09/2026)
- **Pioche/carré/promotion partagée avec la libre** : `checkAndCashInSquares`
  était déjà agnostique du système monétaire ; `captureDeckPlayerCountIfNeeded`/
  `dealStartingHandsForLibreIfNeeded` élargies pour ne plus exclure la dette
  (ni, depuis le 18/09/2026, le troc — voir plus bas) — un même correctif sur
  la pioche bénéficie désormais aux trois systèmes à la fois.
- **1 jeton faible = 1 unité monétaire, plus aucun jeton moyen/fort** en
  dette+smartphone (contrairement à la dette classique, qui garde jetons
  faible/moyen/fort inchangés) — `LEVEL_JETON_PRICE` (prix fixe des cartes)
  converti en jetons faibles uniquement (3/6/12/24, même barème de valeur
  qu'avant).
- **Crédits/remboursements/intérêts/saisies alimentent réellement le solde
  physique du téléphone** (`Player.jetonWeak`, jusque-là seulement mis à
  jour par les achats de cartes) — un crédit accordé crédite désormais le
  téléphone, un remboursement le débite, une mort/sortie/saisie le remet à
  zéro (jamais de dotation gratuite comme en libre, la dette démarre
  toujours à 0 et emprunte).
- **Terminologie "unités monétaires"** (jamais "jetons") sur les écrans
  concernés (app ET smartphones), uniquement pour un joueur RÉELLEMENT suivi
  par smartphone (`hasStartingAllocation`, jamais une lecture du réglage
  global) — la dette classique garde entièrement son affichage historique.
- **Nouvel inventaire de cartes préempli à la mort et à la sortie de fin de
  partie** depuis le vrai solde/inventaire du joueur (assistant animateur),
  y compris pour l'écran "Ne peut pas payer" — l'animateur garde toujours la
  main pour corriger avant de valider.
- Voir `CLAUDE.md` et l'historique des commits (`ba1e8d8`, `5d2d5cd`,
  `50c175c`, `a044d72`) pour le détail complet, y compris deux bugs réels
  trouvés en campagne de test (jetons jamais déplacés lors d'un achat/
  remboursement) et corrigés le jour même.

### Monnaie troc + smartphone (construite le 18/09/2026)
- **Même pioche/carré partagée** que la libre/la dette (voir ci-dessus) —
  toujours ni jeton ni unité monétaire (règle intangible du troc, voir
  `10-etape-plugins-troc.md`), mais les cartes de valeur (faible/moyenne/
  forte/tresforte) et leur circulation suivent désormais le même mécanisme
  robuste que les deux autres systèmes.
- **Système d'échange entièrement repensé** : l'ancien mécanisme (quantité
  de biens négociée via des compteurs, jamais réellement utilisable faute de
  pioche partagée) est remplacé par un VRAI échange carte-contre-carte
  1-pour-1 : chaque joueur retourne sa propre carte (QR), scanne celle de
  l'autre via un nouveau bouton "Échanger" - le serveur vérifie que les deux
  cartes ont la MÊME VALEUR et que chacun des deux joueurs possède DÉJÀ au
  moins un exemplaire du modèle qu'il va recevoir (réciprocité
  bidirectionnelle) ; sinon "Échange refusé" (infobulle 3 secondes), sans
  aucun changement d'état des deux côtés. Niveaux redérivés côté serveur
  depuis le catalogue de la partie (jamais depuis ce que le client prétend),
  pour ne pas pouvoir être contournés.
- **Mort/renaissance et inventaire de sortie** suivent le même principe que
  la dette+smartphone : inventaire réel capturé avant la mort, main fraîche
  de 4 cartes à la renaissance, préremplissage de l'assistant — troc
  CLASSIQUE (déjà sans champ monétaire, déjà à 3 champs faible/moyenne/
  forte) entièrement inchangé.
- Historique des échanges (écran animateur) affiche "carte ⇄ carte" pour un
  échange direct plutôt qu'un montant en jetons (toujours 0, jamais correct
  pour ce système).
- Voir l'historique des commits (`957b0f5`, `6af249a`, `997d715`) pour le
  détail complet, y compris trois bugs réels trouvés en campagne de test
  (carré jamais auto-encaissé en dette+smartphone après un achat ; classement
  en direct jamais mis à jour pour un joueur troc+smartphone ; vérification
  "même valeur" contournable via des niveaux déclarés par le client plutôt
  que revérifiés côté serveur) et corrigés le jour même.
- **Point connu, non corrigé à ce stade** : la validation d'un échange
  (`GameService.recordCardSwap`, comme `recordTransaction` avant elle pour
  dette/libre) n'utilise pas de verrou base de données - deux échanges
  distincts portant sur la même carte rare, redimés quasi simultanément,
  pourraient théoriquement passer tous les deux (fenêtre de temps très
  courte). Schéma déjà présent depuis le début pour dette/libre, pas une
  régression introduite le 18/09/2026 - à corriger si confirmé gênant en
  usage réel (nécessiterait un verrou explicite ou une transaction DB
  sérialisée sur la paire de joueurs concernée).

- **Écran de statistiques complété : échanges monétaires + masse détaillée**
  (27/09/2026, demande utilisateur) — réservé à la dette et la libre
  suivies par smartphone (jamais le troc, jamais une partie classique sans
  smartphone) : nombre global d'échanges + répartition dans le temps
  (tours) et parmi les joueurs, valeur des échanges par tour, masse
  monétaire détaillée (création/destruction par tour, dérivée
  observationnellement de la masse déjà calculée), ratio masse/joueurs
  actifs ("accès à la monnaie"), avec moyennes et médianes partout. Nouvel
  endpoint `GET /api/games/{id}/exchange-stats`
  (`StatsService.computeExchangeAndMoneyReport`), nouvelle section sur
  l'écran de rapport (`renderExchangeStatsSection` dans `app.js`, 4
  nouveaux graphiques Chart.js). Voir `03-architecture-technique.md`,
  entrée du 27/09/2026, pour le détail complet (dont l'investigation qui a
  infirmé l'hypothèse d'un bug de masquage sur "Activité par joueur" en
  libre smartphone — masquage justifié, cette section ne couvre que les
  événements de crédit, jamais les achats/ventes de cartes). Vérifié par
  un test unitaire dédié (`StatsServiceExchangeStatsTest`, 3 cas) + capture
  d'écran manuelle. Campagne de test (3 parties libre+smartphone, 2/4/10
  joueurs, 12 tours) et double relecture indépendante en cours au moment
  de cette entrée — voir la suite de ce document/de
  `03-architecture-technique.md` pour leurs conclusions. Le "certificat du
  joueur" (points forts/faibles), demandé dans le même message, reste
  **différé** à la demande explicite de l'utilisateur.
  **Relecture indépendante + campagne (27/09/2026, même soir)** : 3
  parties libre+smartphone (2/4/10 joueurs, 12 tours) jouées en HTTP réel
  avec vérité terrain tenue indépendamment - 0 écart sur les jetons, la
  masse, le DU et le prix de chaque échange ; deux bugs du nouveau rapport
  trouvés et corrigés : (1) valeurs d'échange en JETONS affichées comme
  "unités monétaires" (faux dès que "Valeur d'une pièce faible" ≠ 1, ×2
  avec 0,5) ; (2) point "tour 1" de la masse détaillée égal à la masse
  FINALE (rejeu), d'où une fausse "destruction" au tour 2 - corrigé
  localement dans ce rapport (voir `03-architecture-technique.md`). Le
  graphique pré-existant "Évolution de la masse monétaire" et la courbe
  Galilée gardent ce défaut au tour 1 (point 4bis ci-dessous) - même
  correctif local applicable en une ligne, décision utilisateur attendue.

## Reste à faire (connu, pas encore commencé ou partiel)

Par ordre approximatif de priorité, à ajuster selon les retours de test :

1. **Tester en conditions réelles la refonte du DU/masse monétaire du
   09/09/2026** — c'est un changement de fond sur le mécanisme économique
   central du mode monnaie libre smartphone ; recommandé de repartir sur
   une partie fraîche plutôt que de continuer une ancienne partie, le
   format de données ayant changé.
2. **Écran animateur pour traiter les demandes de crédit** (monnaie dette,
   smartphone) — la demande côté joueur et le backend
   (`CreditRequestService`, approbation via `recordEvent`) fonctionnent déjà
   depuis un moment ; seul l'écran de traitement dédié côté animateur (liste
   des demandes en attente, bouton approuver/refuser) reste à construire —
   l'animateur peut toujours accorder un crédit manuellement via l'étape
   "Nouveaux crédits" de l'assistant en attendant, mais sans visibilité sur
   les demandes explicitement faites par les joueurs depuis leur téléphone.
3. ~~**Verrou de concurrence sur les échanges smartphone**~~ **Fait le
   27/09/2026** : `GameService.withGameLock`, un verrou en mémoire par
   partie - confirmé en usage réel (jusqu'à +31073 unités monétaires
   créées de rien mesuré en HTTP réel avant correctif), voir
   `03-architecture-technique.md`, entrée du 27/09/2026.
3bis. **Décisions de règle en attente de réponse utilisateur** (campagne
   de test 27/09/2026, voir `03-architecture-technique.md` pour le détail
   complet des mesures) :
   - Grossir la pioche des parties à peu de joueurs (option "N+2 modèles"
     par niveau au lieu de "N+1") pour réduire la fréquence des carrés qui
     ne peuvent plus rien promouvoir (mesuré : ferait passer le taux de
     44/100 à 13/100 sur une partie 2 joueurs simulée, au prix d'un peu
     moins de variété par carré) ?
   - Un carré "tresforte" qui boucle vers "faible" alors que le joueur
     détient déjà tout le stock d'un niveau (monopole) déclenche quand
     même une VRAIE révolution (rotation des prix) et une carte gratuite à
     chaque fois - à documenter comme un comportement voulu, ou à limiter
     dans ce cas précis ?
4bis. **Redesign du rejeu d'événements (undo/delete/edit) pour ne plus
   perdre d'argent** (identifié le 27/09/2026, campagne de test) : un
   achat/vente smartphone n'est pas un événement, donc n'est jamais rejoué
   - annuler un événement sans rapport peut remettre les jetons d'un
   joueur à leur valeur d'avant un achat déjà effectué (carte transférée,
   paiement effacé), et en dette+smartphone chaque rejeu réapplique aussi
   les crédits (jetons multipliés à chaque "Annuler" successif). Piste
   envisagée non appliquée : ajuster les jetons ACTUELS par la différence
   entre rejouer avec/sans l'événement retiré. Bug mineur lié : le
   graphique "masse monétaire" affiche pour "Tour 1" la masse finale de la
   partie au lieu de la masse réelle à ce tour.
4. **Retirer les traces de diagnostic temporaires** une fois un test
   concluant confirmé (voir le code pour les commentaires "[DIAG]" restants
   éventuels — la plupart ont déjà été retirées ou pérennisées via le
   système de journalisation).
5. **Déploiement sur un serveur accessible depuis internet** (au-delà d'un
   réseau local d'atelier) — la sécurité APPLICATIVE nécessaire est posée
   (PIN par partie, comptes animateurs avec rôles, cloisonnement par
   partie, isolation WebSocket par partie, limitation de débit sur les
   points sensibles - voir la section "Comptes animateurs multi-session et
   sécurité" ci-dessus et `03-architecture-technique.md` pour le détail),
   mais **rien de ce qui suit n'est encore fait** :
   - **Empaquetage Docker + reverse proxy Caddy** (obtention automatique
     d'un vrai certificat TLS via Let's Encrypt) - le certificat auto-signé
     actuel (`SelfSignedCertService`) ne convient qu'à un réseau local.
   - **Persistance/expiration des sessions animateur** : actuellement en
     mémoire, sans expiration (`SessionService`) - un choix assumé pour un
     serveur LAN à redémarrages rares, à revoir pour un serveur public
     resté allumé longtemps avec plusieurs animateurs.
   - **Proportionnalité du hachage de mot de passe** (`PasswordHasher`,
     PBKDF2 210 000 itérations) : documenté comme "proportionné à une
     poignée d'animateurs sur un serveur associatif, pas un système exposé
     au grand public" - à revisiter si le serveur devait accueillir un
     public large/non maîtrisé.
   - **Sauvegarde/supervision d'un serveur distant** : la sauvegarde
     existante (export de la base H2) suppose un accès direct à la
     machine - pas encore de procédure documentée pour un serveur hébergé
     à distance (sauvegarde automatisée, monitoring, mise à jour).
6. **Module Galilée** (convergence vers la moyenne, voir
   https://yyy-vox.gitlab.io/encyyyclopedie/articles/module_galilee.html) —
   idée mentionnée par l'utilisateur pour exploiter les données réelles
   d'une partie jouée en mode smartphone avec le DU désormais calculé
   correctement ; pas encore commencé, dépend de la validation du point 1.
7. **Mode "monnaie numérique"** (alternative sans dénominations physiques,
   un solde global par joueur) — évoqué comme variante future de la
   monnaie libre, voir la note d'architecture du 07/09/2026 dans
   `03-architecture-technique.md`. Reconsidéré depuis : la décision prise
   le 09/09/2026 a été de **garder le système de jetons entiers**
   plutôt que d'aller vers des montants exacts à virgule (voir
   `03-architecture-technique.md`, entrée du 09/09/2026, pour le
   raisonnement complet) — ce point de la feuille de route est donc
   probablement caduc, à confirmer avant de le reprendre.
8. Objectifs non encore abordés du cahier des charges d'origine à
   revérifier : profils joueurs persistants au-delà de la reprise par nom,
   statistiques avancées spécifiques à l'étape 3 (voir
   `CAHIER_DES_CHARGES_ETAPE3.md` §5.2-5.4 pour le détail).

## Comment garder ce document à jour

À chaque session de travail notable : déplacer les éléments terminés de
"Reste à faire" vers "Ce qui est construit", et ajouter toute nouvelle
piste identifiée en cours de route. Ce document doit rester lisible en
quelques minutes — pour l'historique détaillé et le raisonnement complet
derrière chaque décision, c'est `03-architecture-technique.md` qui fait foi.
