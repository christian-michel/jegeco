package jyt.geconomicus.helper.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Étape 3 (écran Paramètres, mode smartphone) : catalogue générique de
 * métadonnées pour les trois tableaux "Cartes" / "Visuels" / "Avatars" (voir
 * §5.3 du cahier des charges). Un catalogue est une simple liste d'entrées
 * (chacune un {@code Map<String,Object>} identifiée par un champ "id"),
 * persistée dans un fichier JSON sur le disque de la machine hôte - même
 * principe que {@link AppSettings} et {@link LanguageService} : ce n'est pas
 * une donnée de partie (pas dans la base H2), c'est une préférence globale de
 * l'installation.
 * <p>
 * Volontairement générique plutôt que trois classes quasi identiques : les
 * trois tableaux ont des colonnes différentes (niveau/secteur/nom/règle pour
 * les cartes, niveau/étiquette pour les visuels, genre/ageCategory pour les
 * avatars) mais le même comportement (lister, éditer les métadonnées d'une
 * entrée par son id, jamais l'image elle-même - voir la zoombox côté front).
 * <p>
 * ⚠️ Ne gère aujourd'hui QUE les métadonnées, pas les fichiers image : déposer
 * les vraies images reste une opération manuelle sur le disque (voir
 * §5.1 du cahier des charges, convention de nommage). L'import/export de
 * catalogue CSV et la sélection d'un catalogue par défaut (aussi prévus au
 * §5.3) ne sont pas encore construits - à ajouter dans un second temps,
 * probablement encore sur ce même modèle (voir PluginRegistry pour le
 * précédent le plus proche : liste/activation/téléchargement/suppression).
 */
public class CatalogService
{
	private final Path mFile;
	private final Supplier<List<LinkedHashMap<String, Object>>> mSeedSupplier;
	private List<LinkedHashMap<String, Object>> mEntries;

	public CatalogService(final Path pFile, final Supplier<List<LinkedHashMap<String, Object>>> pSeedSupplier)
	{
		mFile = pFile;
		mSeedSupplier = pSeedSupplier;
		mEntries = load();
	}

	/** Copie de la liste actuelle des entrées (id + toutes les métadonnées). */
	public synchronized List<LinkedHashMap<String, Object>> list()
	{
		return new ArrayList<>(mEntries);
	}

	/**
	 * Applique un patch partiel (seules les clés présentes dans {@code pFields}
	 * sont modifiées, les autres champs existants de l'entrée sont conservés
	 * tels quels) à l'entrée d'id {@code pId}. Ne permet jamais de changer le
	 * champ "id" lui-même via cette méthode (il resterait incohérent avec la
	 * clé de recherche) - une entrée se renomme en changeant son "nom"/
	 * "etiquette", pas son identifiant technique.
	 *
	 * @return l'entrée mise à jour
	 * @throws IllegalArgumentException si aucune entrée ne porte cet id
	 */
	public synchronized Map<String, Object> patch(final String pId, final Map<String, Object> pFields)
	{
		final LinkedHashMap<String, Object> entry = mEntries.stream()
				.filter(e -> pId.equals(String.valueOf(e.get("id")))) //$NON-NLS-1$
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Entrée de catalogue inconnue : " + pId)); //$NON-NLS-1$
		if (pFields != null)
			pFields.forEach((key, value) -> {
				if (!"id".equals(key)) //$NON-NLS-1$
					entry.put(key, value);
			});
		save();
		return entry;
	}

	private List<LinkedHashMap<String, Object>> load()
	{
		if (!Files.isRegularFile(mFile))
		{
			// Première utilisation : on part du catalogue de démonstration fourni
			// par l'appelant, et on l'écrit tout de suite sur le disque pour que
			// les prochains lancements le retrouvent tel quel (y compris s'il a
			// déjà été édité entre-temps).
			final List<LinkedHashMap<String, Object>> seed = mSeedSupplier.get();
			mEntries = seed;
			save();
			return seed;
		}
		try
		{
			final ObjectMapper mapper = new ObjectMapper();
			final List<LinkedHashMap<String, Object>> loaded = mapper.readValue(mFile.toFile(),
					mapper.getTypeFactory().constructCollectionType(List.class, LinkedHashMap.class));
			return (loaded == null) ? new ArrayList<>() : loaded;
		}
		catch (final IOException e)
		{
			System.out.println("Catalogue illisible (" + mFile + "), on repart du catalogue de démonstration : " //$NON-NLS-1$ //$NON-NLS-2$
					+ e.getMessage());
			return mSeedSupplier.get();
		}
	}

	private void save()
	{
		try
		{
			if (mFile.getParent() != null)
				Files.createDirectories(mFile.getParent());
			new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(mFile.toFile(), mEntries);
		}
		catch (final IOException e)
		{
			System.out.println("Impossible d'enregistrer le catalogue (" + mFile + ") : " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}
}
