package jyt.geconomicus.helper.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Réglages globaux de l'application (écran Paramètres) : langue par défaut,
 * coupure du son, volume sonore. Persistés dans un simple fichier JSON sur le
 * disque de la machine hôte - même principe que
 * {@link jyt.geconomicus.helper.server.plugins.PluginPreferences} : ce n'est
 * pas une donnée de partie, c'est une préférence globale de l'installation.
 */
public class AppSettings
{
	private static final String DEFAULT_LANGUAGE = "fr"; //$NON-NLS-1$

	private final Path mFile;
	private String mDefaultLanguage = DEFAULT_LANGUAGE;
	private boolean mSoundMuted;
	private int mSoundVolume = 100;
	// Remonté par un utilisateur : URL (encore inconnue au moment de l'écriture
	// de ce code) d'un fichier JSON décrivant la dernière version disponible -
	// voir UpdateCheckService. Vide par défaut : la vérification reste
	// silencieusement désactivée tant que l'animateur n'a pas renseigné une URL
	// dans les Paramètres.
	private String mUpdateCheckUrl = ""; //$NON-NLS-1$
	// Remonté par un utilisateur : protection par code PIN (par partie) et jeton
	// individuel (par joueur) - voir Game.pin, Player.accessToken,
	// GecoServer (vérification sur les routes /api/games/{id}/*). Désactivée
	// par défaut : ne change rien pour les installations existantes tant que
	// l'animateur ne l'active pas explicitement.
	private boolean mProtectionEnabled;
	// Étape 3 : bascule "classique" (cartes/jetons physiques, comportement
	// historique inchangé) / "smartphone" (chaque joueur avec son téléphone) -
	// choix exclusif par bouton radio (écran Paramètres), pas une case à
	// cocher indépendante. "classique" par défaut : aucune installation
	// existante n'est affectée tant que l'animateur ne bascule pas
	// explicitement. Voir GecoServer pour la validation des valeurs acceptées.
	public static final String GAME_MODE_CLASSIC = "classique"; //$NON-NLS-1$
	public static final String GAME_MODE_SMARTPHONE = "smartphone"; //$NON-NLS-1$
	private String mGameMode = GAME_MODE_CLASSIC;

	public AppSettings(final Path pFile)
	{
		mFile = pFile;
		load();
	}

	private void load()
	{
		if (!Files.isRegularFile(mFile))
			return; // pas encore de réglages enregistrés - on garde les valeurs par défaut
		try
		{
			final Data data = new ObjectMapper().readValue(mFile.toFile(), Data.class);
			if ((data.defaultLanguage != null) && !data.defaultLanguage.isBlank())
				mDefaultLanguage = data.defaultLanguage;
			mSoundMuted = data.soundMuted;
			mSoundVolume = clampVolume(data.soundVolume);
			if (data.updateCheckUrl != null)
				mUpdateCheckUrl = data.updateCheckUrl;
			mProtectionEnabled = data.protectionEnabled;
			if ((data.gameMode != null) && (GAME_MODE_CLASSIC.equals(data.gameMode) || GAME_MODE_SMARTPHONE.equals(data.gameMode)))
				mGameMode = data.gameMode;
		}
		catch (final IOException e)
		{
			System.out.println("Réglages illisibles (" + mFile + "), on repart des valeurs par défaut : " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage());
		}
	}

	private void save()
	{
		try
		{
			if (mFile.getParent() != null)
				Files.createDirectories(mFile.getParent());
			final Data data = new Data();
			data.defaultLanguage = mDefaultLanguage;
			data.soundMuted = mSoundMuted;
			data.soundVolume = mSoundVolume;
			data.updateCheckUrl = mUpdateCheckUrl;
			data.protectionEnabled = mProtectionEnabled;
			data.gameMode = mGameMode;
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(mFile.toFile(), data);
		}
		catch (final IOException e)
		{
			System.out.println("Impossible d'enregistrer les réglages (" + mFile + ") : " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private static int clampVolume(final int pVolume)
	{
		return Math.max(0, Math.min(100, pVolume));
	}

	public String getDefaultLanguage()
	{
		return mDefaultLanguage;
	}

	public void setDefaultLanguage(final String pDefaultLanguage)
	{
		mDefaultLanguage = (pDefaultLanguage == null) || pDefaultLanguage.isBlank() ? DEFAULT_LANGUAGE
				: pDefaultLanguage;
		save();
	}

	public boolean isSoundMuted()
	{
		return mSoundMuted;
	}

	public void setSoundMuted(final boolean pSoundMuted)
	{
		mSoundMuted = pSoundMuted;
		save();
	}

	public int getSoundVolume()
	{
		return mSoundVolume;
	}

	public void setSoundVolume(final int pSoundVolume)
	{
		mSoundVolume = clampVolume(pSoundVolume);
		save();
	}

	public String getUpdateCheckUrl()
	{
		return mUpdateCheckUrl;
	}

	public void setUpdateCheckUrl(final String pUpdateCheckUrl)
	{
		mUpdateCheckUrl = pUpdateCheckUrl == null ? "" : pUpdateCheckUrl.trim(); //$NON-NLS-1$
		save();
	}

	public boolean isProtectionEnabled()
	{
		return mProtectionEnabled;
	}

	public void setProtectionEnabled(final boolean pProtectionEnabled)
	{
		mProtectionEnabled = pProtectionEnabled;
		save();
	}

	public String getGameMode()
	{
		return mGameMode;
	}

	/** Ignore silencieusement toute valeur autre que "classique"/"smartphone" (garde-fou identique à {@link #load()}). */
	public void setGameMode(final String pGameMode)
	{
		if (GAME_MODE_CLASSIC.equals(pGameMode) || GAME_MODE_SMARTPHONE.equals(pGameMode))
			mGameMode = pGameMode;
		save();
	}

	// Simple structure de (dé)sérialisation JSON - volontairement minimale.
	private static class Data
	{
		public String defaultLanguage;
		public boolean soundMuted;
		public int soundVolume = 100;
		public String updateCheckUrl;
		public boolean protectionEnabled;
		public String gameMode;
	}
}
