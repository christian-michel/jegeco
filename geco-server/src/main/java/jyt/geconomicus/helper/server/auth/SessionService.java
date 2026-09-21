package jyt.geconomicus.helper.server.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions de connexion animateur (multi-session serveur, Phase 2, 21/09/2026).
 *
 * Volontairement EN MÉMOIRE (une simple table, pas de table JPA/H2) plutôt que
 * persistée : proportionné à l'usage réel (serveur LAN, un redémarrage est
 * rare et bien visible pour l'animateur, qui se reconnecte simplement en un
 * clic) - évite d'alourdir le schéma de base pour un besoin qui n'a pas
 * besoin de survivre à un redémarrage du serveur. Mécanisme volontairement
 * symétrique à Game.pin/Player.accessToken déjà en place : un jeton opaque
 * transmis par le client dans un en-tête (voir GecoServer, en-tête
 * "X-Session-Token", même convention que "X-Game-Pin"), jamais un cookie -
 * cohérence avec le reste de l'application plutôt qu'un nouveau mécanisme
 * (gestion CSRF, SameSite...) à maintenir en plus.
 *
 * Thread-safe (ConcurrentHashMap) : Javalin traite les requêtes HTTP sur des
 * threads séparés, comme documenté dans GameService.
 */
public class SessionService
{
	// Nombre d'octets aléatoires du jeton avant encodage - 256 bits, largement
	// suffisant pour être infaisable à deviner par force brute sur un serveur
	// LAN (voir aussi le frein de débit déjà en place sur /api/auth/login,
	// même raisonnement que pour /api/games/{id}/unlock).
	private static final int TOKEN_LENGTH_BYTES = 32;

	private final SecureRandom mRandom = new SecureRandom();
	private final Map<String, Integer> mTokenToAnimatorId = new ConcurrentHashMap<>();

	/** Crée une nouvelle session pour pAnimatorId, retourne le jeton à transmettre au client. */
	public String createSession(final int pAnimatorId)
	{
		final byte[] bytes = new byte[TOKEN_LENGTH_BYTES];
		mRandom.nextBytes(bytes);
		final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		mTokenToAnimatorId.put(token, pAnimatorId);
		return token;
	}

	/** @return l'id de l'animateur associé à pToken, ou null si le jeton est inconnu/invalide. */
	public Integer resolveSession(final String pToken)
	{
		if (pToken == null)
			return null;
		return mTokenToAnimatorId.get(pToken);
	}

	/** Invalide pToken (déconnexion) - sans effet si déjà invalide/inconnu. */
	public void invalidateSession(final String pToken)
	{
		if (pToken != null)
			mTokenToAnimatorId.remove(pToken);
	}
}
