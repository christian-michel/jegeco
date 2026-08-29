package jyt.geconomicus.helper.server;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Étape 3, mode smartphone : offres de vente à courte durée de vie (~90s),
 * identifiées par un code COURT plutôt qu'un gros contenu JSON - remonté par
 * l'utilisateur le 27/08/2026 ("il faut prévoir les deux [scan ET saisie
 * manuelle], au cas où") : un code de quelques caractères se tape à la main,
 * un identifiant complet (vendeur, carte, prix...) ne le pourrait pas
 * raisonnablement. Le QR affiché au vendeur encode simplement ce code - il
 * scanne donc plus vite et plus fiablement qu'avant (moins de données = QR
 * plus simple), tout en réutilisant EXACTEMENT le même mécanisme pour la
 * saisie manuelle : un seul chemin de données pour les deux, pas deux
 * systèmes parallèles à maintenir.
 * <p>
 * Volontairement en mémoire (pas de table JPA) : une offre ne vit que ~90
 * secondes, la perdre lors d'un redémarrage du serveur (rarissime en plein
 * milieu d'une partie) n'a aucune conséquence grave - le vendeur regénère
 * simplement un nouveau QR. Ça évite d'alourdir le schéma de base pour une
 * donnée aussi éphémère.
 * <p>
 * Le code est composé UNIQUEMENT de caractères non ambigus à l'oral/à l'écrit
 * (pas de 0/O, 1/I/L) - important pour la saisie manuelle, un détail qui ne
 * coûte rien pour le scan caméra mais évite des erreurs de frappe frustrantes
 * pour l'autre usage.
 */
final class TradeOfferService
{
	// Caractères non ambigus uniquement (ni 0/O, ni 1/I/L) - voir le
	// commentaire de tête de fichier.
	private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; //$NON-NLS-1$
	private static final int CODE_LENGTH = 6;
	private static final SecureRandom RANDOM = new SecureRandom();

	record Offer(int gameId, int sellerPlayerId, String sellerPlayerName, String cardTypeId, String cardLevel,
			Map<String, Object> cardName, int weakCoins, int mediumCoins, int strongCoins, int weakGoodsWanted,
			int mediumGoodsWanted, int strongGoodsWanted, long expiresAtEpochMs)
	{
	}

	private final Map<String, Offer> mOffers = new ConcurrentHashMap<>();

	/** Crée une offre et retourne son code (généré ici, jamais fourni par l'appelant). */
	String create(final int pGameId, final int pSellerPlayerId, final String pSellerPlayerName,
			final String pCardTypeId, final String pCardLevel, final Map<String, Object> pCardName,
			final int pWeakCoins, final int pMediumCoins, final int pStrongCoins, final int pWeakGoodsWanted,
			final int pMediumGoodsWanted, final int pStrongGoodsWanted, final long pTtlMillis)
	{
		evictExpired();
		String code;
		do
		{
			code = randomCode();
		}
		while (mOffers.containsKey(code)); // collision extrêmement improbable (34^6 combinaisons), mais on se protège quand même
		final Offer offer = new Offer(pGameId, pSellerPlayerId, pSellerPlayerName, pCardTypeId, pCardLevel,
				pCardName == null ? Map.of() : new LinkedHashMap<>(pCardName), pWeakCoins, pMediumCoins, pStrongCoins,
				pWeakGoodsWanted, pMediumGoodsWanted, pStrongGoodsWanted, System.currentTimeMillis() + pTtlMillis);
		mOffers.put(code, offer);
		return code;
	}

	/** Consultation SANS consommer l'offre (pour l'écran de confirmation avant achat) - null si absente/expirée. */
	Offer peek(final String pCode)
	{
		final Offer offer = mOffers.get(pCode);
		if ((offer == null) || (System.currentTimeMillis() > offer.expiresAtEpochMs()))
			return null;
		return offer;
	}

	/**
	 * Consomme l'offre de façon atomique (usage unique - protection anti-rejeu,
	 * voir la classe Transaction côté moteur) : une fois retirée ici, un second
	 * appel avec le même code échoue toujours, qu'il vienne du même acheteur ou
	 * d'un autre.
	 */
	Offer redeem(final String pCode)
	{
		final Offer offer = mOffers.remove(pCode);
		if ((offer == null) || (System.currentTimeMillis() > offer.expiresAtEpochMs()))
			return null;
		return offer;
	}

	private String randomCode()
	{
		final StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++)
			sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
		return sb.toString();
	}

	/** Nettoyage paresseux (pas de tâche de fond) : à chaque création d'offre, on purge celles déjà expirées. */
	private void evictExpired()
	{
		final long now = System.currentTimeMillis();
		mOffers.entrySet().removeIf(e -> now > e.getValue().expiresAtEpochMs());
	}
}
