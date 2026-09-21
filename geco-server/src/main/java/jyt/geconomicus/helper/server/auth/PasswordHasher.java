package jyt.geconomicus.helper.server.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hachage de mot de passe pour les comptes animateurs (voir Animator).
 *
 * Choix technique : PBKDF2WithHmacSHA256, disponible nativement dans le JDK
 * (javax.crypto, déjà présent partout où Java tourne) - volontairement PAS de
 * nouvelle dépendance externe (bcrypt/argon2) pour un besoin proportionné à
 * l'usage réel du projet (authentification d'une poignée d'animateurs sur un
 * serveur associatif LAN, pas un système exposé au grand public). 210 000
 * itérations : recommandation OWASP 2023 pour PBKDF2-HMAC-SHA256 au moment de
 * l'écriture de ce code (21/09/2026) - à relever si cette recommandation
 * évolue, voir https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html.
 *
 * Format de sortie stocké dans Animator.passwordHash :
 * "pbkdf2:<itérations>:<sel en Base64>:<hash en Base64>" - le nombre
 * d'itérations est inclus dans chaque hash plutôt que codé en dur côté
 * vérification, pour pouvoir l'augmenter un jour sans invalider les mots de
 * passe déjà enregistrés avec l'ancienne valeur.
 */
public final class PasswordHasher
{
	private static final String ALGORITHM = "PBKDF2WithHmacSHA256"; //$NON-NLS-1$
	private static final int DEFAULT_ITERATIONS = 210_000;
	private static final int KEY_LENGTH_BITS = 256;
	private static final int SALT_LENGTH_BYTES = 16;

	private PasswordHasher()
	{
		// Classe utilitaire, jamais instanciée.
	}

	/**
	 * Calcule un nouveau hash pour pPlainPassword, avec un sel aléatoire frais.
	 * @param pPlainPassword le mot de passe en clair, jamais conservé au-delà de cet appel.
	 * @return la chaîne à stocker telle quelle dans Animator.passwordHash.
	 */
	public static String hash(final String pPlainPassword)
	{
		final byte[] salt = new byte[SALT_LENGTH_BYTES];
		new SecureRandom().nextBytes(salt);
		final byte[] hash = pbkdf2(pPlainPassword, salt, DEFAULT_ITERATIONS);
		return "pbkdf2:" + DEFAULT_ITERATIONS + ":" //$NON-NLS-1$
				+ Base64.getEncoder().encodeToString(salt) + ":" //$NON-NLS-1$
				+ Base64.getEncoder().encodeToString(hash);
	}

	/**
	 * Vérifie pPlainPassword contre un hash précédemment produit par {@link #hash}.
	 * Ne lève jamais d'exception pour un hash corrompu/d'un format inattendu :
	 * retourne simplement false (un mot de passe corrompu en base ne doit
	 * jamais faire planter l'écran de connexion, juste refuser l'accès).
	 */
	public static boolean matches(final String pPlainPassword, final String pStoredHash)
	{
		if (pStoredHash == null)
			return false;
		final String[] parts = pStoredHash.split(":"); //$NON-NLS-1$
		if (parts.length != 4 || !"pbkdf2".equals(parts[0])) //$NON-NLS-1$
			return false;
		try
		{
			final int iterations = Integer.parseInt(parts[1]);
			final byte[] salt = Base64.getDecoder().decode(parts[2]);
			final byte[] expected = Base64.getDecoder().decode(parts[3]);
			final byte[] actual = pbkdf2(pPlainPassword, salt, iterations);
			return java.security.MessageDigest.isEqual(expected, actual);
		}
		catch (final RuntimeException e)
		{
			// Format de hash inattendu (nombre d'itérations non numérique,
			// Base64 invalide...) - traité comme "mot de passe refusé", pas
			// comme une erreur serveur.
			return false;
		}
	}

	private static byte[] pbkdf2(final String pPlainPassword, final byte[] pSalt, final int pIterations)
	{
		try
		{
			final PBEKeySpec spec = new PBEKeySpec(pPlainPassword.toCharArray(), pSalt, pIterations, KEY_LENGTH_BITS);
			final SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
			return factory.generateSecret(spec).getEncoded();
		}
		catch (final NoSuchAlgorithmException | InvalidKeySpecException e)
		{
			// PBKDF2WithHmacSHA256 est garanti disponible dans tout JDK
			// standard (JEP/spec Java SE) - ne devrait jamais arriver en
			// pratique.
			throw new IllegalStateException("PBKDF2WithHmacSHA256 indisponible sur ce JDK", e); //$NON-NLS-1$
		}
	}
}
