package jyt.geconomicus.helper.server;

/**
 * Version actuelle de l'application, centralisée ici plutôt que dupliquée en
 * dur dans le HTML (voir index.html, pied de la barre latérale) - utilisée
 * par {@link UpdateCheckService} pour savoir si une version plus récente est
 * disponible.
 * <p>
 * Remonté par un utilisateur : jamais mise à jour automatiquement par
 * l'application elle-même - seul un humain change cette constante avant de
 * publier une nouvelle version.
 */
public final class AppVersion
{
	public static final String CURRENT = "2.0.0"; //$NON-NLS-1$

	private AppVersion()
	{
		// Classe utilitaire, non instanciable.
	}
}
