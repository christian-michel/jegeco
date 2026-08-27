package jyt.geconomicus.helper.server.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Préférences de l'animateur sur les plugins actifs (écran Paramètres) :
 * quels systèmes d'échange proposer sur l'écran "Nouvelle partie". Persistées
 * dans un simple fichier JSON sur le disque de la machine hôte (pas en base
 * H2 : ce n'est pas une donnée de partie, c'est une préférence globale de
 * l'installation, voir docs/11-plugin-api-contrat.md pour la logique
 * "tout sur le disque, rien sur le réseau" déjà retenue pour les plugins
 * eux-mêmes).
 * <p>
 * Toujours initialisée avec un ensemble EXPLICITE de plugins activés (jamais
 * un état "pas encore de préférence" à traiter au cas par cas) : au tout
 * premier démarrage, {@code pDefaultEnabledIds} (les plugins déjà "prêts",
 * voir {@link PluginManifest#isEngineReady()}) sert de point de départ, puis
 * cet ensemble est immédiatement écrit sur le disque - ce qui évite un bug
 * sournois où activer/désactiver UN plugin desactiverait accidentellement
 * tous les autres faute de préférence déjà enregistrée pour eux.
 */
public class PluginPreferences
{
	private final Path mFile;
	private final Set<String> mEnabledIds;

	public PluginPreferences(final Path pFile, final Set<String> pDefaultEnabledIds)
	{
		mFile = pFile;
		final Set<String> loaded = load();
		if (loaded != null)
			mEnabledIds = loaded;
		else
		{
			mEnabledIds = new HashSet<>(pDefaultEnabledIds);
			save(); // matérialise le choix par défaut dès le premier démarrage
		}
	}

	private Set<String> load()
	{
		if (!Files.isRegularFile(mFile))
			return null;
		try
		{
			final ObjectMapper mapper = new ObjectMapper();
			final PreferencesFile parsed = mapper.readValue(mFile.toFile(), PreferencesFile.class);
			return new HashSet<>(parsed.enabledPluginIds == null ? Set.of() : parsed.enabledPluginIds);
		}
		catch (final IOException e)
		{
			System.out.println("Préférences de plugins illisibles (" + mFile + "), on repart des valeurs par défaut : " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage());
			return null;
		}
	}

	private void save()
	{
		try
		{
			if (mFile.getParent() != null)
				Files.createDirectories(mFile.getParent());
			final PreferencesFile toSave = new PreferencesFile();
			toSave.enabledPluginIds = mEnabledIds;
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(mFile.toFile(), toSave);
		}
		catch (final IOException e)
		{
			System.out.println("Impossible d'enregistrer les préférences de plugins (" + mFile + ") : " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	public boolean isEnabled(final String pPluginId)
	{
		return mEnabledIds.contains(pPluginId);
	}

	public void setEnabled(final String pPluginId, final boolean pEnabled)
	{
		if (pEnabled)
			mEnabledIds.add(pPluginId);
		else
			mEnabledIds.remove(pPluginId);
		save();
	}

	// Simple structure de (dé)sérialisation JSON - volontairement minimale.
	private static class PreferencesFile
	{
		@SuppressWarnings("unused")
		public Set<String> enabledPluginIds;
	}
}
