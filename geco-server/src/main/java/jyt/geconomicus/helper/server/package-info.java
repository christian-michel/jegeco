/**
 * Serveur web local (étapes 2 et 3 du projet) : expose le moteur métier de
 * {@code geco-engine} via une API REST + un canal WebSocket, et sert un front
 * HTML5/CSS3/JS moderne et responsive.
 * <p>
 * Architecture volontairement simple, dans l'esprit du projet d'origine de jytou :
 * <ul>
 * <li>{@link jyt.geconomicus.helper.server.GecoServer} : point d'entrée, démarre Javalin,
 * enregistre les routes et le WebSocket ;</li>
 * <li>{@link jyt.geconomicus.helper.server.GameService} : couche de service qui encapsule les
 * opérations JPA (équivalent web de ce que fait {@code HelperUI} directement en Swing) ;</li>
 * <li>{@link jyt.geconomicus.helper.server.Dtos} : objets de transfert JSON, pour ne jamais
 * sérialiser directement les entités JPA (lazy-loading, références cycliques).</li>
 * </ul>
 * Le canal WebSocket (/ws) diffuse déjà les changements à tous les clients connectés : c'est le
 * mécanisme qui, à l'étape 3, permettra de synchroniser plusieurs smartphones en temps réel sans
 * changement d'architecture.
 */
package jyt.geconomicus.helper.server;
