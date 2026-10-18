# Réflexion : « échanger une carte contre une carte aléatoire de même valeur dans la pioche »

*Document de travail, rédigé pendant la campagne de test 2/6/16 joueurs du
18/10/2026 (voir `docs/03-architecture-technique.md`, entrée du même jour) —
**rien n'est implémenté**, à discuter avec l'utilisateur avant toute suite.
Ne pas démarrer l'implémentation sans validation explicite, en particulier
sur le garde-fou anti-abus (§3) et le tirage par carte vs par modèle (§6).*

## 1. Le besoin, chiffré par la campagne

Les mesures (instantané au début de chaque tour, pour chaque joueur actif)
montrent deux formes de blocage :

* **Blocage total** : le joueur ne peut ni acheter ni vendre (libre/dette),
  ou n'a aucun échange valide (troc).
* **Blocage de progression** : il peut échanger, mais **aucun autre joueur
  ne détient un exemplaire d'un modèle qu'il collectionne** : les
  exemplaires qui lui manquent sont tous dans la pioche, qui n'est
  accessible que par un carré (4 cartes rendues, 4 repiochées) ou une
  renaissance. Aucun échange entre joueurs ne peut alors le rapprocher d'un
  carré.

Résultats détaillés : voir `docs/03-architecture-technique.md`, entrée du
18/10/2026, section "Fluidité des échanges". En résumé, le blocage de
progression concerne une grande partie des joueur-tours à 2 joueurs, en
libre comme en dette. En troc à 2 joueurs, le blocage est total sur la
majorité de la partie, à cause de la règle de réciprocité : chacun doit
déjà posséder le modèle qu'il reçoit, donc les deux joueurs doivent avoir
**deux modèles de même niveau en commun**.

La pioche ne se mélange **que** par les carrés et les morts. Un joueur sans
carré possible n'a donc aucun moyen de faire entrer de nouvelles cartes dans
le jeu des autres, ni dans le sien. C'est exactement le cas que vise la
fonctionnalité proposée.

## 2. Mécanique proposée (commune aux trois systèmes smartphone)

**Action joueur** : au dos d'une carte retournée (même écran que « Vendre »
ou « Échanger »), un bouton **« Échanger avec la pioche »** demande une
confirmation, puis :

1. La carte donnée (modèle `m`, niveau `L`, niveau lu **côté serveur** dans
   la pioche de la partie et jamais déclaré par le client, comme
   `recordCardSwap` et `recordTransaction`) **retourne dans SA pioche de
   niveau** : `pile[L][m] += 1`.
2. Une carte est tirée au hasard dans `pile[L]`, **parmi les modèles
   différents de `m`** et en stock : `pile[L][m'] -= 1`.
3. Le joueur détient `m'` à la place de `m`, ce qui est neutre en valeur :
   même niveau, donc même prix (libre/dette) et même poids 1/4/16 (troc).
4. On appelle `checkAndCashInSquares` pour ce joueur, car la carte tirée
   peut compléter un carré, et on diffuse « square » comme après un
   échange.

**Ordre recommandé** : tirer d'abord en **excluant `m`**, puis remettre
`m`. Le joueur ne récupère jamais sa propre carte, et l'opération ne peut
pas être un aller-retour nul.

### Conservation stricte (aucune carte créée ni perdue)

* Par modèle : `m` passe de la main à la pioche (−1/+1) et `m'` de la
  pioche à la main (−1/+1). Le total par modèle (main + pioche = 5) est
  conservé.
* L'inventaire d'un joueur est **dérivé** (dotation de départ +
  `Transaction` + `CardSquareEvent`, filtrés par `cardInventoryResetAt`,
  voir `computePlayerCardInventory`). Il faut donc un **journal** de
  l'échange, persisté **dans la même transaction JPA** que l'écriture de
  `Game.smartphoneCardPileJson`, sinon la pioche et les inventaires
  divergent. C'est exactement le piège déjà rencontré le 27/09/2026 avec
  les « carrés fantômes » non persistés.
  * Option A (recommandée) : une nouvelle entité
    `PileExchangeEvent(game, player, givenCardTypeId, receivedCardTypeId,
    level, turnNumber, tstamp)`, rejouée dans
    `computePlayerCardInventory` avec le même filtre `resetAt`.
  * Option B : réutiliser `CardSquareEvent` (cashed = m ×1 ?) est
    **déconseillé** : la sémantique « −4/+1/+4 » est codée en dur partout
    (inventaire, statistiques, animation).
* Tout se fait sous `withGameLock(gameId)`. Les contrôles (possession de
  `m`, stock disponible) se font **à l'intérieur du verrou**, pour éviter
  la famille de bugs TOCTOU (contrôle puis écriture) déjà rencontrée le
  27/09 et le 11/10.
* La limite connue de l'horodatage à la milliseconde
  (`cardInventoryResetAt` comparé à `tstamp`) s'applique aussi à ce
  nouveau journal : un échange avec la pioche et une mort dans la même
  milliseconde sont aussi improbables, mais aussi possibles, qu'un achat
  et une mort.
* **Test de non-régression** à écrire sur le modèle de
  `GameServicePileConservationTest` : pour chaque modèle, mains + pioche =
  5 après N échanges avec la pioche aléatoires mêlés à des achats, des
  carrés et des morts.

### Respect de la règle 6 du troc (« jamais de jetons ni de valeur monétaire »)

L'échange avec la pioche est **carte contre carte**, comme le troc
lui-même : aucune valeur monétaire n'apparaît. Il respecte donc la règle 6
**à condition d'être gratuit ou limité par un moyen non monétaire** (voir
§3). Une version payante en jetons serait **exclue du troc** et réservée à
la libre et à la dette, mais cela casserait le principe « un seul mécanisme
de pioche partagé par les trois systèmes » (`CLAUDE.md`), d'où la
recommandation d'un mécanisme non monétaire commun.

## 3. Garde-fous anti-abus (à trancher)

Sans limite, un joueur pourrait « relancer » jusqu'à tomber sur le modèle
qui lui manque, et fabriquer des carrés sans jamais échanger avec personne.
Cela viderait de son sens l'objectif pédagogique, qui est de faire
circuler la monnaie ou les biens entre joueurs. Options :

| Option | Pour | Contre |
|---|---|---|
| **1 échange avec la pioche par joueur et par tour** (recommandée) | simple, identique pour les 3 systèmes, respecte la règle 6 du troc, compté depuis le journal (pas d'état en mémoire) | un peu arbitraire |
| Uniquement si le joueur est « bloqué » (aucun échange utile possible) | ciblé sur le vrai problème | détection coûteuse et contestable côté serveur ; le joueur ne comprend pas pourquoi le bouton est grisé |
| Coût en jetons (ex. 0,5 DU en libre, détruit ; prix fixe à la banque en dette) | « prix » de la flexibilité | interdit en troc (règle 6) ; en libre strict TRM, détruire de la monnaie fait **baisser** la masse, contraire au principe « la masse ne diminue jamais » ; à écarter |
| Donner 2 cartes pour en recevoir 1 de même niveau | non monétaire | détruit de la « valeur » détenue (les cartes retournent à la pioche, pas de perte globale), mais pénalise justement les joueurs déjà en difficulté |

## 4. Cas limites

* **Pioche du niveau vide**, ou ne contenant que le modèle `m` : refus avec
  un message clair (« Aucune autre carte de cette valeur dans la
  pioche »). **Rien n'est consommé**, et l'échange quotidien éventuel n'est
  pas décompté.
* **Le joueur tirerait sa propre carte** : impossible par construction
  (exclusion de `m` au tirage, §2).
* **Tirage d'une carte qui complète un carré** : encaissement automatique
  normal (`checkAndCashInSquares`), animation incluse. Cela inclut les cas
  « carré fantôme » (pioche cible épuisée) et « monopole tresforte », déjà
  documentés comme voulus.
* **Niveau « tresforte »** : à autoriser ou non ? Avec peu de joueurs, la
  pioche tresforte (N+2 modèles × 5) peut rester pleine, ce qui ferait de
  l'échange avec la pioche un raccourci vers les révolutions. Proposition :
  autoriser, la limite par tour suffit.
* **Partie en pause, tour terminé (y compris le nouveau blocage après « Fin
  de tour », voir `docs/03-architecture-technique.md` du 18/10/2026),
  joueur en prison, joueur sorti (QUIT) ou mort** : refus, avec les mêmes
  contrôles que `/trade-offers/{code}/redeem` (`isTradingAllowed`,
  `isActive`, `prisonUntilTurn`).
* **Joueur non suivi par smartphone** (partie classique) : fonctionnalité
  absente (pas de pioche partagée).
* **Annuler/éditer un événement** : non concerné, puisque l'échange avec la
  pioche n'est pas un `Event`. Il reste validé, comme les transactions
  (décision du 17/10/2026, voir `CLAUDE.md`).

## 5. Effets de bord à prévoir

* **Statistiques** : ne **pas** compter ces opérations comme des
  « échanges entre joueurs » (`computeExchangeStats` et
  `computeTrocExchangeReport` comptent les `Transaction`). Ajouter un
  compteur séparé « échanges avec la pioche » (par tour et par joueur),
  utile pour évaluer l'effet de la fonctionnalité.
* **Richesse** : neutre (même niveau). Le classement est inchangé à
  l'instant de l'échange.
* **Tableau de bord animateur** : ajouter une ligne « ⇄ pioche » dans
  l'historique des échanges, et `goodsCount` inchangé en troc (1 carte
  rendue, 1 reçue ; à vérifier, voir le correctif du 10/10/2026).
* **API** : `POST /api/games/{id}/pile-exchange {playerId, accessToken,
  cardTypeId}`. La route doit être exemptée de PIN comme les autres routes
  joueur (`isPlayerFacingGameRoute`) et limitée en débit (`allowRequest`).
* **i18n** : 4 à 6 nouvelles clés (bouton, confirmation, refus « pioche
  vide », refus « déjà utilisé ce tour », ligne d'historique) en fr et en.
* **Seconde relecture indépendante** obligatoire selon `CLAUDE.md` : c'est
  un nouveau mécanisme touchant la pioche partagée des trois systèmes.

## 6. Recommandation

Implémenter l'**option « 1 échange avec la pioche gratuit par joueur et par
tour, même niveau, jamais le même modèle »**, commune aux trois systèmes
smartphone, avec un journal dédié (`PileExchangeEvent`) et un test de
conservation. Avant toute implémentation, valider avec l'utilisateur sur un
exemple chiffré : par exemple, partie à 2 joueurs, Alice détient 3 ×
« Abeilles » et 1 × « Blé », les 2 « Abeilles » restantes sont dans la
pioche ; elle échange son « Blé » contre une carte faible tirée parmi les
autres modèles en stock (« Abeilles » ×2, « Laine » ×3). Point à trancher
au passage : le tirage existant (`pickRandomAvailable`, utilisé par les
carrés) est uniforme **par modèle** et non par carte. Avec lui, Alice a 1
chance sur 2 de compléter son carré ; avec un tirage pondéré par le nombre
d'exemplaires, elle en aurait 2 sur 5. Le tirage par carte est plus
« physique » (une vraie pioche mélangée), et ce choix vaut aussi pour les
carrés.
