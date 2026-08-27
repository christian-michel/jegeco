package jyt.geconomicus.helper.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Vérifie si une version plus récente de l'application est disponible, en
 * lisant un simple fichier JSON à une URL configurée par l'animateur (voir
 * AppSettings.updateCheckUrl, écran Paramètres). Volontairement limité à la
 * LECTURE SEULE : cette classe ne télécharge ni n'installe jamais rien, elle
 * se contente de comparer un numéro de version et de renvoyer, le cas
 * échéant, le lien de téléchargement fourni par le serveur distant - c'est
 * toujours à l'animateur de décider et d'agir manuellement, jamais à
 * l'application elle-même (remonté par un utilisateur : c'est le point de
 * conception le plus important de cette fonctionnalité).
 * <p>
 * Format attendu à l'URL configurée (un simple fichier JSON statique suffit,
 * pas besoin d'un serveur applicatif dédié) :
 * <pre>
 * { "latestVersion": "2.1.0", "downloadUrl": "https://...", "releaseNotes": "..." }
 * </pre>
 */
public class UpdateCheckService
{
	/**
	 * @param checkConfigured Une URL de vérification a-t-elle été renseignée par
	 *                        l'animateur (voir AppSettings) ? Si non, aucune tentative
	 *                        réseau n'est faite - c'est l'état par défaut tant qu'aucune
	 *                        URL n'existe.
	 * @param success         La vérification a-t-elle abouti (URL jointe, réponse valide) ?
	 * @param error           Message d'erreur lisible par l'animateur si {@code success} est faux.
	 */
	public record UpdateCheckResult(boolean checkConfigured, boolean success, String currentVersion,
			boolean updateAvailable, String latestVersion, String downloadUrl, String releaseNotes, String error)
	{
	}

	public UpdateCheckResult check(final String pUpdateCheckUrl)
	{
		if ((pUpdateCheckUrl == null) || pUpdateCheckUrl.isBlank())
			return new UpdateCheckResult(false, false, AppVersion.CURRENT, false, null, null, null, null);

		try
		{
			final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			final HttpRequest request = HttpRequest.newBuilder(URI.create(pUpdateCheckUrl))
					.timeout(Duration.ofSeconds(5)).GET().build();
			final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
				return new UpdateCheckResult(true, false, AppVersion.CURRENT, false, null, null, null,
						"Le serveur de mise à jour a répondu avec le code " + response.statusCode()); //$NON-NLS-1$

			final RemoteInfo info = new ObjectMapper().readValue(response.body(), RemoteInfo.class);
			if ((info.latestVersion == null) || info.latestVersion.isBlank())
				return new UpdateCheckResult(true, false, AppVersion.CURRENT, false, null, null, null,
						"Réponse du serveur de mise à jour invalide (pas de \"latestVersion\")."); //$NON-NLS-1$

			final boolean updateAvailable = isNewer(info.latestVersion, AppVersion.CURRENT);
			return new UpdateCheckResult(true, true, AppVersion.CURRENT, updateAvailable, info.latestVersion,
					info.downloadUrl, info.releaseNotes, null);
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return new UpdateCheckResult(true, false, AppVersion.CURRENT, false, null, null, null,
					"Vérification interrompue."); //$NON-NLS-1$
		}
		catch (final IOException | IllegalArgumentException e)
		{
			// IllegalArgumentException : URL mal formée (ex. saisie par erreur par
			// l'animateur) - traitée comme une simple erreur de vérification, pas une
			// exception qui remonterait jusqu'au client sans explication.
			return new UpdateCheckResult(true, false, AppVersion.CURRENT, false, null, null, null,
					"Impossible de contacter le serveur de mise à jour : " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Compare deux numéros de version "x.y.z", purement numérique. Volontairement
	 * simple (pas de suffixes "-beta"/"-rc" pour l'instant) - à étoffer le jour où
	 * un vrai processus de publication existera.
	 */
	private boolean isNewer(final String pCandidate, final String pCurrent)
	{
		final int[] a = parse(pCandidate);
		final int[] b = parse(pCurrent);
		for (int i = 0; i < Math.max(a.length, b.length); i++)
		{
			final int x = i < a.length ? a[i] : 0;
			final int y = i < b.length ? b[i] : 0;
			if (x != y)
				return x > y;
		}
		return false;
	}

	private int[] parse(final String pVersion)
	{
		final String[] parts = pVersion.trim().split("\\."); //$NON-NLS-1$
		final int[] result = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			try
			{
				result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", "")); //$NON-NLS-1$ //$NON-NLS-2$
			}
			catch (final NumberFormatException e)
			{
				result[i] = 0;
			}
		}
		return result;
	}

	// Simple structure de désérialisation JSON - volontairement minimale.
	private static class RemoteInfo
	{
		public String latestVersion;
		public String downloadUrl;
		public String releaseNotes;
	}
}
