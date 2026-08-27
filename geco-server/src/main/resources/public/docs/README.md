# docs/ — Documentation multilingue de l'application (utilisateurs finaux)

⚠️ À ne pas confondre avec le dossier `docs/` à la racine du dépôt : celui-ci
contient les notes de développement du projet (pour le développeur), jamais
servi aux utilisateurs. **Ce dossier-ci** (`geco-server/.../public/docs/`) est
servi par l'application et lu par les vrais utilisateurs (animateurs).

## Arborescence

```
docs/
  fr/
    markdown/    <- source, à éditer (un fichier .md par page)
    html/        <- généré automatiquement, ne pas éditer à la main
  en/
    markdown/
    html/
  build-docs.py  <- script de génération markdown -> html
```

## Ajouter ou modifier une page

1. Éditez (ou créez) le fichier `.md` correspondant dans
   `docs/<langue>/markdown/`.
2. Si nouvelle page : ajoutez son titre dans `PAGE_TITLES` en haut de
   `build-docs.py`.
3. Régénérez le HTML :
   ```bash
   pip install markdown --break-system-packages   # une seule fois
   python3 build-docs.py
   ```
4. Recompilez l'application (`./run.sh --rebuild`) pour embarquer les nouveaux
   fichiers HTML dans le jar.

## Ajouter une langue

1. Créez `docs/<code_langue>/markdown/` avec les mêmes fichiers `.md` que
   `fr/`, traduits.
2. Ajoutez le code langue à la liste `LANGS` dans `build-docs.py`, et les
   titres correspondants dans `PAGE_TITLES`.
3. Relancez `python3 build-docs.py`.
4. Pensez aussi à ajouter le fichier `lang/<code>.po` (traduction de
   l'interface elle-même) et le drapeau dans `js/i18n.js` si ce n'est pas déjà
   fait — voir `docs/08-etape2-i18n.md` (à la racine du dépôt) pour le détail.

## Pages actuellement disponibles

| Fichier | Utilisé depuis |
|---|---|
| `regles-du-jeu.md` | Section "Pour aller plus loin" de la page Documentation intégrée |
| `connexion-joueurs.md` | Lien "Documentation" de l'écran "Connexion joueurs" |
| `statistiques.md` | Lien "Comprendre les statistiques..." de la page Documentation intégrée - seuils de pauvreté/condition modeste (Eurostat/INSEE) et indice de Gini |

D'autres pages pourront être ajoutées au même principe (ex. guide du système de
cartes une fois l'étape 3 avancée).
