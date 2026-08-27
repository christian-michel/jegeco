# Étape 3 — Ergonomie et manipulation des cartes (mobile)

Ce document consigne les décisions d'ergonomie pour l'écran "Mes cartes" côté
joueur, à partir de la maquette fournie. **Périmètre volontairement réduit à
l'ergonomie/manipulation** — le catalogue de cartes (illustrations, noms,
couleurs) fait l'objet d'un échange séparé à venir.

## Décision actée : échange uniquement contre jetons pour l'instant

Le jeu compare l'incidence de la création monétaire (monnaie dette vs monnaie
libre) sur les échanges et la qualité de vie — **tout échange se fait donc
carte-contre-jetons**, jamais carte-contre-carte (troc), pour l'instant.

Le troc (échange direct entre joueurs, sans passer par la monnaie) reste une
piste d'enrichissement possible, mais **hors périmètre actuel** : ce serait un
second protocole d'échange à concevoir séparément (comment les deux joueurs se
mettent d'accord, qui initie, comment les deux côtés se valident en même temps),
et une seconde configuration de partie à comparer. Traité comme un développement
futur distinct, pas une variante à ajouter au passage.

## Deux modes d'affichage

Plutôt qu'une seule vue avec des réglages, deux modes distincts avec un bouton de
bascule en haut de l'écran "Mes cartes" (comme un sélecteur d'onglets) :

- **Mode Table** — cartes étalées librement façon table physique, immersif,
  navigable au doigt.
- **Mode Collection** — un exemplaire affiché par type de carte + badge de
  comptage (`×N`), plus lisible dès que le nombre de types augmente. C'est le
  mode par défaut le plus pratique : comme chaque carte existe en **5
  exemplaires maximum** en circulation (limite déjà actée pour le catalogue),
  les badges resteront toujours des petits nombres (×0 à ×5), jamais un mur de
  chiffres.

Le tri et la recherche s'appliquent au mode Collection (voir plus bas) ; le mode
Table reste volontairement libre, sans tri imposé.

## Interactions gestuelles sur une carte

Quatre gestes, dans cet ordre de découverte naturelle :

1. **Sélectionner** — un tap sur une carte la met en avant.
2. **Zoomer / déplacer** — pincer pour zoomer, glisser pour déplacer. Reprend la
   proposition retenue précédemment : le pincement sert à la **densité
   d'affichage** (voir plus de petites cartes / moins de plus grandes cartes),
   pas à zoomer sur l'image elle-même.
3. **Retourner** — glisser la carte vers la gauche la fait pivoter pour révéler
   son QR code de vente, avec le temps restant avant expiration de l'offre
   (cohérent avec la pioche limitée déjà actée).
4. **Fermer** — tap en dehors, ou glisser vers le bas, referme la carte agrandie.

**Point de vigilance technique à garder en tête pour l'implémentation** : sur
iPhone/Safari, un glissement démarré depuis le bord gauche de l'écran est aussi
le geste système "retour en arrière" du navigateur. Il faudra soit démarrer la
zone de detection du geste de retournement plus loin du bord, soit désactiver
spécifiquement le geste navigateur sur cet écran, et le tester réellement sur un
iPhone avant de considérer cette interaction terminée.

## Organisation et recherche (mode Collection)

- **Tri** : par nombre d'exemplaires (croissant/décroissant), par nom, par
  secteur/couleur.
- **Recherche** : un champ de recherche propre à l'écran "Mes cartes" (pas dans
  la barre de navigation du bas, qui sert à changer d'écran), pour retrouver une
  carte précise dans sa propre collection.
- **Regroupement automatique** par type de carte : c'est ce que fait déjà le
  mode Collection nativement (chaque type = une case avec son compteur), pas de
  travail supplémentaire.
- **Regroupement libre** (le joueur organiserait lui-même ses cartes en tas
  personnalisés, par glisser-déposer) : **non retenu pour l'instant** — le mode
  Collection avec tri/recherche couvre déjà le besoin de retrouver/organiser ses
  cartes. Envisageable plus tard comme fonctionnalité à part entière si le
  besoin se confirme à l'usage (glisser-déposer tactile, mémorisation de
  l'organisation entre sessions).

## Contrôle du chrono : uniquement l'animateur

Précision actée : seul le maître du jeu contrôle le temps (pause, +30s). Les
joueurs voient le minuteur sur leur écran, en lecture seule, sans aucun contrôle
dessus. Cohérent avec l'implémentation actuelle : les boutons Pause/+30s sont
déjà exclusivement dans le tableau de bord animateur (`index.html`/`app.js`),
jamais exposés côté `join.html` (écran joueur) — pas de changement requis.

## Galerie d'avatars filtrable (photos/illustrations, avec repli SVG)

En complément du générateur SVG (toujours disponible, onglet "Personnalisé"), une
**galerie d'avatars prêts à l'emploi** est proposée par défaut (onglet
"Galerie") : chaque image est associée à des caractéristiques — genre
(homme/femme/neutre), catégorie d'âge (enfant/adulte/senior), teint — via un
petit catalogue (`avatars/avatars-catalog.js`), filtrable par 3 sélecteurs.

Une image de galerie pas encore fournie affiche un repli automatique (avatar
généré, dérivé de manière stable de l'identifiant de l'entrée) plutôt que de
casser l'affichage — même principe que pour le gabarit d'assemblage des cartes.
Le nombre d'avatars n'est pas limité et peut être étoffé à tout moment sans
changement de code (voir `avatars/README.md`).

Cohérent avec la maquette d'inscription déjà validée : Accueil / Cartes /
Échanges / Historique / Profil. C'est via "Profil" que le joueur retrouve ses
propres statistiques et un résumé de sa partie en cours — à construire à partir
du même principe que les statistiques d'activité déjà implémentées côté
animateur (nombre de transactions, volume brassé, historique des échanges),
mais vues du point de vue d'un seul joueur plutôt que de toute la partie. Comme
pour le graphique de convergence (module Galilée), la précision de ce suivi
dépendra du système de cartes numériques (chaque échange enregistré
individuellement), qui n'existe pas encore.

## Fluidité et équité des échanges

- **Règlement atomique des deux côtés** : les deux joueurs voient leur solde mis
  à jour ensemble, jamais d'état intermédiaire incohérent (repris du principe déjà
  posé pour le protocole QR).
- **Mise à jour optimiste côté client** (l'action semble instantanée), confirmée
  ensuite par le serveur — évite toute sensation de lenteur perçue.
- **Micro-animations** (carte qui glisse/se retourne, compteur qui s'incrémente)
  et **retour haptique** à la confirmation d'un échange ou d'un carré réussi —
  disponible sur Android, **pas disponible sur iPhone** (limite connue de Safari
  iOS, à ne pas promettre côté iOS).

## Ce qui reste à préciser plus tard

- Le catalogue de cartes définitif (illustrations, noms, couleurs) — échange en
  cours séparément.
- Le protocole de troc, si retenu comme développement futur.
