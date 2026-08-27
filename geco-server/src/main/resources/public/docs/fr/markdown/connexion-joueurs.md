# Connecter les joueurs (smartphones)

Ce guide explique comment permettre aux smartphones des joueurs de rejoindre
une partie via le QR code affiché par l'écran "Connexion joueurs".

## Le principe

Les smartphones des joueurs doivent être sur le **même réseau local** que
l'ordinateur qui fait tourner le logiciel. Une fois connectés au même réseau,
il suffit aux joueurs de scanner le QR code affiché à l'écran avec l'appareil
photo natif de leur téléphone (aucune application à installer) pour rejoindre
la partie.

## Deux façons de mettre les téléphones sur le même réseau

### Option recommandée : partage de connexion depuis l'ordinateur

L'ordinateur crée son propre point d'accès Wifi, auquel les téléphones se
connectent. C'est l'option la plus fiable : elle ne dépend d'aucune
infrastructure réseau existante sur le lieu de la partie.

- **Windows** : Paramètres → Réseau et Internet → Point d'accès mobile.
- **macOS** : Réglages Système → Partage → Partage internet.
- **Linux** : dépend de l'environnement de bureau ; cherchez "Point d'accès
  Wifi" ou "Hotspot" dans les paramètres réseau.

Donnez un nom de réseau et un mot de passe, activez le partage, puis faites
rejoindre ce réseau aux téléphones avant de scanner le QR code.

### Option alternative : Wifi local du lieu

Si le lieu dispose déjà d'un réseau Wifi, les téléphones peuvent s'y connecter
directement — à condition que ce réseau n'ait pas d'**isolation client**
(un réglage de sécurité fréquent sur les réseaux publics/invités, qui empêche
les appareils connectés de communiquer entre eux). Si les téléphones n'arrivent
pas à joindre l'application malgré une connexion au même Wifi, c'est la cause
la plus probable : utilisez alors l'option du partage de connexion à la place.

## Pourquoi pas le Bluetooth ?

Le Bluetooth est pensé pour une connexion entre deux appareils, avec un
appairage individuel fastidieux à plusieurs joueurs, et un débit plus faible
que le Wifi. Il n'est pas adapté pour connecter plusieurs smartphones en même
temps à l'application.

## Choisir la bonne adresse réseau

L'écran "Connexion joueurs" peut afficher plusieurs adresses réseau si
l'ordinateur est connecté à plusieurs réseaux à la fois (Wifi et Ethernet, par
exemple). Celle marquée **"Probable"** correspond généralement au bon réseau à
utiliser. En cas de doute, essayez la première adresse proposée.
