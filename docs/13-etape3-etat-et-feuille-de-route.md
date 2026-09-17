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
3. **Verrou de concurrence sur les échanges smartphone (troc, dette, libre)**
   : `recordTransaction`/`recordCardSwap` valident (solde, réciprocité...)
   puis persistent sans verrou base de données explicite - deux échanges
   distincts portant sur la même ressource rare, rédimés quasi
   simultanément, pourraient théoriquement passer tous les deux. Schéma
   présent depuis le début pour dette/libre, identifié explicitement le
   18/09/2026 en revue croisée du troc+smartphone - à corriger si confirmé
   gênant en usage réel.
4. **Retirer les traces de diagnostic temporaires** une fois un test
   concluant confirmé (voir le code pour les commentaires "[DIAG]" restants
   éventuels — la plupart ont déjà été retirées ou pérennisées via le
   système de journalisation).
5. **Déploiement Phase 2** : Docker + Caddy (la Phase 1, sécurité applicative,
   est terminée).
6. **Module Galilée** (convergence vers la moyenne, voir
   https://yyy-vox.gitlab.io/encyyyclopedie/articles/module_galilee.html) —
   idée mentionnée par l'utilisateur pour exploiter les données réelles
   d'une partie jouée en mode smartphone avec le DU désormais calculé
   correctement ; pas encore commencé, dépend de la validation du point 1.
7. **Documentation intégrée à l'app (vue "Documentation" d'`index.html`)
   jamais traduite** (texte français en dur, sans `data-i18n`) — identifié
   le 18/09/2026 en vérifiant la couverture multilingue complète après le
   travail dette/troc+smartphone ; toutes les chaînes d'interface elles-
   mêmes sont, elles, intégralement couvertes en français ET en anglais
   (vérifié par un contrôle automatisé comparant chaque clé `data-i18n`/
   `t("...")` utilisée dans le code aux fichiers `lang/fr.po`/`lang/en.po` -
   aucune clé manquante des deux côtés). Ce bloc de documentation reste donc
   le seul texte utilisateur non traduisible identifié à ce jour - à
   traduire si confirmé prioritaire (un travail de traduction à part
   entière, pas une simple vérification).
8. **Mode "monnaie numérique"** (alternative sans dénominations physiques,
   un solde global par joueur) — évoqué comme variante future de la
   monnaie libre, voir la note d'architecture du 07/09/2026 dans
   `03-architecture-technique.md`. Reconsidéré depuis : la décision prise
   le 09/09/2026 a été de **garder le système de jetons entiers**
   plutôt que d'aller vers des montants exacts à virgule (voir
   `03-architecture-technique.md`, entrée du 09/09/2026, pour le
   raisonnement complet) — ce point de la feuille de route est donc
   probablement caduc, à confirmer avant de le reprendre.
9. Objectifs non encore abordés du cahier des charges d'origine à
   revérifier : profils joueurs persistants au-delà de la reprise par nom,
   statistiques avancées spécifiques à l'étape 3 (voir
   `CAHIER_DES_CHARGES_ETAPE3.md` §5.2-5.4 pour le détail).

## Comment garder ce document à jour

À chaque session de travail notable : déplacer les éléments terminés de
"Reste à faire" vers "Ce qui est construit", et ajouter toute nouvelle
piste identifiée en cours de route. Ce document doit rester lisible en
quelques minutes — pour l'historique détaillé et le raisonnement complet
derrière chaque décision, c'est `03-architecture-technique.md` qui fait foi.
