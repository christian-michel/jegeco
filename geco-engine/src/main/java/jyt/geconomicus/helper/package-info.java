/**
 * Moteur métier de Ğeconomicus Helper : entités {@link jyt.geconomicus.helper.Game},
 * {@link jyt.geconomicus.helper.Player} et {@link jyt.geconomicus.helper.Event}, règles de calcul de la
 * monnaie dette et de la monnaie libre, persistance JPA (Jakarta Persistence + EclipseLink) sur une base
 * H2 embarquée.
 * <p>
 * Ce module ne dépend d'aucune bibliothèque d'interface graphique (ni Swing, ni web) : il est partagé tel
 * quel par {@code geco-app} (l'interface Swing historique de l'animateur) et {@code geco-server} (l'API
 * REST/WebSocket qui prépare les étapes 2 et 3 du projet). Toute évolution des règles du jeu doit se faire
 * ici, et sera automatiquement disponible dans les deux interfaces.
 * <p>
 * Origine : ce code descend directement du projet de jytou
 * (<a href="https://gitlab.com/jytou/geconomicus_helper">geconomicus_helper</a>), migré vers Java 21 et
 * Jakarta EE. Voir {@code docs/03-architecture-technique.md} à la racine du projet pour le détail des choix
 * techniques.
 */
package jyt.geconomicus.helper;
