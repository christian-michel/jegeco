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

### Autres systèmes monétaires
- **Troc** : jouable sur smartphone (échange carte contre carte,
  entièrement fonctionnel).
- **Dette** : mécanismes communs au smartphone (pioche, carrés, compte à
  rebours, comptage de cartes) déjà réutilisables — **extension complète
  au smartphone (banque/crédits) pas encore commencée**, voir feuille de
  route.

## Reste à faire (connu, pas encore commencé ou partiel)

Par ordre approximatif de priorité, à ajuster selon les retours de test :

1. **Tester en conditions réelles la refonte du DU/masse monétaire du
   09/09/2026** — c'est un changement de fond sur le mécanisme économique
   central du mode monnaie libre smartphone ; recommandé de repartir sur
   une partie fraîche plutôt que de continuer une ancienne partie, le
   format de données ayant changé.
2. **Écran animateur pour traiter les demandes de crédit** (monnaie dette,
   smartphone) — le reste de la chaîne (demande côté joueur, backend)
   fonctionne déjà.
3. **Extension du smartphone à la monnaie dette** (au-delà des demandes de
   crédit) : banque, remboursements, intérêts. Voir la note d'architecture
   du 06/09/2026 dans `03-architecture-technique.md` sur ce qui est déjà
   commun aux trois systèmes et ce qui reste propre à chacun.
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
