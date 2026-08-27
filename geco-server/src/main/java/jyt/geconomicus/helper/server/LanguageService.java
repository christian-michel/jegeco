package jyt.geconomicus.helper.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Langues "personnalisées" ajoutées par l'animateur (écran Paramètres) : des
 * fichiers .po supplémentaires, déposés sur le disque de la machine hôte
 * (jamais dans le fichier .jar lui-même, qui est en lecture seule une fois
 * empaqueté) - même principe que {@code PluginRegistry} pour les plugins.
 * <p>
 * Les langues déjà fournies avec l'application (français, anglais) restent
 * servies telles quelles par le serveur de fichiers statiques classique (voir
 * {@code public/lang/*.po}, chemin {@code /lang/*.po}) - ce service ne
 * s'occupe que des langues AJOUTÉES après coup, servies depuis un second
 * chemin ({@code /lang-custom/*.po}, voir GecoServer). Le front (i18n.js)
 * essaie d'abord le chemin intégré, puis celui-ci en repli.
 * <p>
 * Remonté par un utilisateur : le drapeau/libellé affiché pour une langue
 * personnalisée est d'abord deviné automatiquement (table de référence
 * LANGUAGE_COUNTRY_MAP côté i18n.js), mais cette déduction peut se tromper
 * (beaucoup de langues sont parlées dans plusieurs pays) - ce service stocke
 * donc aussi un réglage manuel optionnel par code de langue, qui prend
 * toujours le pas sur la déduction automatique.
 */
public class LanguageService
{
	// Un code de langue doit rester simple et prévisible : on n'autorise que des
	// minuscules et chiffres (ex. "es", "pt-br"), jamais de chemin ("../") ou de
	// caractère spécial - le code sert directement de nom de fichier sur le disque.
	private static final Pattern VALID_CODE = Pattern.compile("^[a-z0-9-]{2,10}$"); //$NON-NLS-1$

	private final Path mDirectory;
	private final Path mOverridesFile;
	private Map<String, LanguageDisplay> mOverrides;

	/** Réglage manuel de drapeau/libellé pour un code de langue donné. */
	public record LanguageDisplay(String flag, String label)
	{
	}

	public LanguageService(final Path pDirectory)
	{
		mDirectory = pDirectory;
		mOverridesFile = pDirectory.resolve("language-overrides.json"); //$NON-NLS-1$
		mOverrides = loadOverrides();
	}

	/** Codes des langues personnalisées actuellement présentes sur le disque. */
	public List<String> listCustomLanguageCodes()
	{
		final List<String> codes = new ArrayList<>();
		if (!Files.isDirectory(mDirectory))
			return codes;
		try (Stream<Path> entries = Files.list(mDirectory))
		{
			entries.filter(p -> p.getFileName().toString().endsWith(".po")) //$NON-NLS-1$
					.forEach(p -> {
						final String fileName = p.getFileName().toString();
						codes.add(fileName.substring(0, fileName.length() - 3)); // retire ".po"
					});
		}
		catch (final IOException e)
		{
			System.out.println("Impossible de lister les langues personnalisées (" + mDirectory + ") : " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage());
		}
		return codes;
	}

	/**
	 * Enregistre un fichier .po personnalisé sur le disque. Rejette tout code de
	 * langue mal formé (voir {@link #VALID_CODE}), pour ne jamais laisser
	 * quelqu'un écrire en dehors du dossier prévu via un code du genre
	 * "../../etc/passwd".
	 */
	public void saveCustomLanguage(final String pCode, final String pPoContent) throws IOException
	{
		if ((pCode == null) || !VALID_CODE.matcher(pCode).matches())
			throw new IllegalArgumentException("Code de langue invalide : " + pCode); //$NON-NLS-1$
		Files.createDirectories(mDirectory);
		Files.writeString(mDirectory.resolve(pCode + ".po"), pPoContent); //$NON-NLS-1$
	}

	/**
	 * Supprime une langue personnalisée du disque - jamais autorisé pour le
	 * français/anglais (fournis avec l'application, jamais stockés dans ce
	 * dossier de toute façon, donc rien à y supprimer pour eux) - voir
	 * l'utilisateur, écran Paramètres. Retire aussi son éventuel réglage manuel
	 * de drapeau/libellé, pour ne pas laisser une entrée orpheline.
	 */
	public void deleteCustomLanguage(final String pCode) throws IOException
	{
		if ((pCode == null) || !VALID_CODE.matcher(pCode).matches())
			throw new IllegalArgumentException("Code de langue invalide : " + pCode); //$NON-NLS-1$
		if ("fr".equals(pCode) || "en".equals(pCode)) //$NON-NLS-1$ //$NON-NLS-2$
			throw new IllegalArgumentException("Le français et l'anglais sont fournis avec l'application, ils ne peuvent pas être supprimés."); //$NON-NLS-1$
		final Path file = mDirectory.resolve(pCode + ".po"); //$NON-NLS-1$
		if (!Files.isRegularFile(file))
			throw new IllegalArgumentException("Langue inconnue : " + pCode); //$NON-NLS-1$
		Files.delete(file);
		if (mOverrides.remove(pCode) != null)
			saveOverrides();
	}

	/** Réglages manuels de drapeau/libellé actuellement définis, par code de langue. */
	public Map<String, LanguageDisplay> getOverrides()
	{
		return mOverrides;
	}

	/**
	 * Définit (ou efface, si {@code pDisplay} est {@code null}) le drapeau/
	 * libellé manuel d'une langue - toujours autorisé, y compris pour fr/en (on
	 * peut vouloir personnaliser leur libellé aussi, contrairement à la
	 * suppression du fichier .po lui-même qui reste, elle, interdite pour eux).
	 */
	public void setOverride(final String pCode, final LanguageDisplay pDisplay) throws IOException
	{
		if ((pCode == null) || !VALID_CODE.matcher(pCode).matches())
			throw new IllegalArgumentException("Code de langue invalide : " + pCode); //$NON-NLS-1$
		if (pDisplay == null)
			mOverrides.remove(pCode);
		else
			mOverrides.put(pCode, pDisplay);
		saveOverrides();
	}

	private Map<String, LanguageDisplay> loadOverrides()
	{
		if (!Files.isRegularFile(mOverridesFile))
			return new HashMap<>();
		try
		{
			final ObjectMapper mapper = new ObjectMapper();
			final Map<String, LanguageDisplay> loaded = mapper.readValue(mOverridesFile.toFile(),
					mapper.getTypeFactory().constructMapType(HashMap.class, String.class, LanguageDisplay.class));
			return loaded == null ? new HashMap<>() : new HashMap<>(loaded);
		}
		catch (final IOException e)
		{
			System.out.println("Réglages de drapeaux/libellés illisibles (" + mOverridesFile + "), on repart à vide : " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage());
			return new HashMap<>();
		}
	}

	private void saveOverrides()
	{
		try
		{
			Files.createDirectories(mDirectory);
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(mOverridesFile.toFile(), mOverrides);
		}
		catch (final IOException e)
		{
			System.out.println("Impossible d'enregistrer les réglages de drapeaux/libellés (" + mOverridesFile + ") : " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage());
		}
	}
}
