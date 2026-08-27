# Étape "plugins" — Conception du système d'échange Troc

Ce document consigne les décisions de conception prises avec l'utilisateur au
sujet de l'architecture "plugins" pour les systèmes d'échange, et en
particulier les règles du premier système candidat après la monnaie dette et
la monnaie libre : **le troc**. Rien de ce qui suit n'est encore implémenté :
c'est un document de conception à valider avant tout code, comme convenu.

## Contexte : pourquoi une architecture à plugins ?

L'application ne compare pas des variantes d'un même système : elle compare
des **outils d'échange fondamentalement différents**, à effectif de joueurs,
nombre de tours et durée de tour strictement identiques. C'est cette rigueur
qui rend la comparaison pédagogiquement valable. L'architecture à plugins doit
donc permettre d'ajouter de nouveaux outils d'échange (troc, et d'autres plus
tard) sans jamais compromettre cette comparabilité.

Deux approches de plugin ont été envisagées :
- **Plugin exécutable** (`.jar`/JS avec code arbitraire) : puissant, mais
  risque réel d'exécution de code non maîtrisé - en tension directe avec
  l'audit sécurité mené juste avant ce chantier.
- **Plugin déclaratif** (configuration + vocabulaire d'événements limité) :
  plus sûr, mais on ne sait pas encore si ça suffira à exprimer des systèmes
  aussi différents que le troc.

Le choix entre les deux n'est pas encore tranché ; on avance d'abord sur la
conception fonctionnelle du troc lui-même, qui servira de cas de test pour
juger si l'approche déclarative est suffisante.

## Ce qui reste identique quel que soit le système d'échange

Invariants de comparabilité (non négociables, quel que soit le plugin) :
- Mêmes joueurs, même nombre de tours, même durée par tour.
- Mort/renaissance obligatoire, avec inventaire à chaque mort - jamais
  facultatif, même si le système n'a pas de notion de "vieillissement
  monétaire" à proprement parler.
- Seuls les joueurs qui meurent sont comptabilisés à la fin d'un tour "normal" ;
  **tous** les joueurs sont comptabilisés à la fin du dernier tour, pour
  connaître la situation de chacun.
- Chaque système doit être capable de produire, en fin de partie, **une valeur
  numérique de "richesse"** par joueur - c'est ce qui permet aux statistiques
  générales (moyenne, médiane, écart-type, indice de Gini, seuil de pauvreté)
  et à l'écran "Comparer des parties" de continuer à fonctionner, même entre
  systèmes dont la mécanique interne n'a rien à voir.

## Règles du troc, telles que définies avec l'utilisateur

1. **Dotation initiale et renaissance** : chaque joueur commence (et renaît)
   avec 4 cartes et 4 jetons de temps de vie.
2. **Renouvellement du temps** : chaque tour, chaque joueur reçoit 4 nouveaux
   jetons de temps de vie. Aucun report d'un tour à l'autre - ni surplus, ni
   dette. Contrairement à un bien, le temps ne se stocke jamais.
3. **Échange bien-contre-bien** : librement négocié entre deux joueurs, sans
   aucune limite liée au temps de vie.
4. **Échange bien-contre-service** : seul le joueur qui **fournit** le service
   dépense un jeton de temps de vie ; celui qui **reçoit** le service ne paie
   qu'en bien. Le service n'est jamais payé par le bénéficiaire en temps.
5. **Épuisement du temps** : un joueur qui n'a plus de jeton de temps ne peut
   plus **fournir** de nouveau service jusqu'au tour suivant, mais reste libre
   de continuer à échanger des biens s'il lui en reste.
6. **Aucune valeur fixe, sur rien** : ni sur les cartes, ni sur le temps -
   absolument tout se négocie au cas par cas, entre les joueurs eux-mêmes.
   L'animateur/le logiciel n'impose jamais de barème.
7. **Les carrés restent une mécanique de jeu utile, mais sans effet sur le
   calcul de la richesse** : la règle "4 cartes faibles → 1 carte moyenne, 4
   cartes moyennes → 1 carte forte" continue de s'appliquer en jeu, parce
   qu'elle crée de la rareté et une dynamique de négociation intéressante
   (une carte forte, plus dure à obtenir, est potentiellement plus facile à
   échanger contre un bien convoité). Mais pour le calcul de la richesse
   finale, **une carte compte pour 1, quel que soit son niveau** - une carte
   forte ne "vaut" pas plus qu'une carte faible dans le décompte. C'est
   cohérent avec la règle 6 : la rareté vécue en jeu ne se traduit jamais en
   pondération dans les statistiques.
8. **Mort/renaissance et sortie de partie** : inventaire obligatoire à chaque
   mort, comme dans les deux autres systèmes (règle commune, voir plus haut).
9. **Richesse finale = nombre d'objets possédés** en fin de partie, sans
   valeur monétaire attachée (conséquence directe de la règle 6). Cette
   valeur n'est pas directement comparable à une richesse exprimée en unités
   monétaires (dette/libre) - la comparaison inter-systèmes reste possible
   techniquement, mais change de nature philosophique pour le troc : on
   compare des quantités d'objets détenus, pas un pouvoir d'achat.

## Statistiques spécifiques au troc

En plus des statistiques générales communes à tous les systèmes (moyenne,
médiane, écart-type, indice de Gini, seuil de pauvreté - calculées sur le
nombre d'objets possédés, voir règle 9), le troc doit faire remonter :

- **Temps de vie donné** : total des jetons dépensés par un joueur en
  fournissant des services aux autres, cumulé sur toute la partie.
- **Temps de vie reçu** : total des jetons que les *autres* joueurs ont
  dépensé au bénéfice de ce joueur (le temps d'autrui dont il a profité).
- **Solde net (reçu − donné)** : la mesure la plus parlante des trois -
  distingue immédiatement qui a été net "donneur" de temps et qui a été net
  "bénéficiaire". Une idée forte à mettre en avant : un joueur qui vend
  beaucoup de services vend, très concrètement, des heures de sa propre vie -
  ce solde net rend cette réalité visible et chiffrée plutôt qu'implicite.
- Proportion d'échanges biens-contre-biens vs biens-contre-services, sur
  l'ensemble de la partie.

## ✅ Résolu (23/08/2026) : documentation en jeu adaptée au système d'échange choisi

Suggestion de l'utilisateur, retenue pour la conception de l'interface de
plugin : l'écran "Documentation" en jeu (auparavant des pages statiques
communes à toutes les parties) s'adapte désormais selon le système d'échange
de la partie ouverte - les règles du troc s'affichent uniquement quand une
partie en troc est ouverte, plutôt que de mélanger la documentation de tous
les systèmes possibles.

Implémenté exactement comme anticipé ci-dessous : chaque plugin fournit son
propre fragment de documentation/règles (`plugins/<id>/docs/regles.<langue>.html`,
déclaré dans le manifeste, champ `documentation`), que l'écran "Documentation"
récupère dynamiquement via `GET /api/plugins/{id}/docs/{lang}` selon le
contexte de la partie en cours - voir `docs/12-guide-creer-systeme-echange.md`
pour le détail technique complet (y compris la précaution de sécurité prise
sur la validation du paramètre de langue, qui sert à construire un chemin de
fichier).

## Prochaine étape

Avec ce premier cas de test concret (le troc) posé par écrit, l'étape
suivante est de vérifier si l'approche déclarative (plugin = fichier de
configuration + vocabulaire d'événements limité) suffit à exprimer ces règles,
ou si le troc a besoin d'un point d'extension que cette approche ne couvre
pas (ex. la notion de temps de vie non stockable, les deux natures d'échange
bien/service). Cette vérification déterminera le choix final entre plugin
déclaratif et plugin exécutable.

## Mise à jour (22/08/2026) : retrait des échanges de service et du temps de vie

Après un premier essai jouable (biens + jetons de temps + échanges bien-
contre-service), l'utilisateur est revenu sur ces deux points :

- **Les échanges de service et le temps de vie sont entièrement retirés.** Le
  troc ne connaît plus qu'un seul type de transaction : l'échange bien-
  contre-bien.
- **Uniquement des transactions d'échange** : jamais de don sans contrepartie,
  jamais de monnaie ni de jeton d'aucune sorte, quel qu'il soit.
- **Les cartes de valeur restent à 3 niveaux** (faible/moyen/fort, comme dans
  les deux autres systèmes) - confirmé explicitement, pas de 4ᵉ niveau
  "intermédiaire" malgré une formulation ambiguë dans la demande initiale.
- Les joueurs continuent de chercher à faire des **carrés** (4 cartes
  identiques → 1 carte de niveau supérieur), exactement comme en monnaie
  dette/libre - ça crée de la rareté et une dynamique de négociation, sans
  jamais influer sur le calcul de la richesse (toujours 1 objet = 1, quel que
  soit son niveau).

Les règles définitives du troc, telles qu'elles restent après ce retour en
arrière, sont donc les points 1, 3, 6 (biens seulement), 7, 8 et 9 de la
section "Règles du troc" ci-dessus - les points 2, 4 et 5 (temps de vie,
échange de service, épuisement du temps) ne s'appliquent plus.
