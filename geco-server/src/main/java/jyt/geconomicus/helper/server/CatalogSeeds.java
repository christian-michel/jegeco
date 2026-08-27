package jyt.geconomicus.helper.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contenu de départ des quatre catalogues étape 3 (mode smartphone, écran
 * Paramètres) - voir {@link CatalogService}. Utilisé UNIQUEMENT au tout
 * premier lancement (si le fichier JSON correspondant n'existe pas encore
 * sur le disque de l'installation) : une fois le catalogue édité par
 * l'animateur, ce contenu embarqué n'est plus jamais relu.
 * <p>
 * Le vrai contenu (104 cartes, 104 visuels, 76 avatars, fourni par
 * l'utilisateur le 27/08/2026 via le classeur Excel modèle) est embarqué tel
 * quel dans des fichiers JSON sous {@code src/main/resources/catalog-seeds/}
 * plutôt qu'écrit en dur ici en Java : à cette échelle (des centaines
 * d'entrées), un fichier de données se relit et se corrige bien plus
 * facilement qu'un mur d'appels de constructeur - et ça reste le même geste
 * si le catalogue est de nouveau régénéré plus tard à partir d'un classeur
 * mis à jour.
 * <p>
 * ⚠️ Deux points remontés en important ce premier catalogue réel (voir
 * conversation du 27/08/2026), pas corrigés ici volontairement :
 * <ul>
 * <li>Le champ "secteur" du catalogue "Cartes" vaut exactement la même chose
 *     que "niveau" sur les 104 lignes (aucune vraie taxonomie de secteur
 *     pour l'instant, ex. "alimentation"/"primaire"/... comme envisagé plus
 *     tôt) - à confirmer avec l'utilisateur avant de bâtir quoi que ce soit
 *     dessus (un design par couleur de secteur, notamment).</li>
 * <li>Seuls 76 avatars sur les 100 prévus par la convention de nommage ont
 *     été fournis (id/filename vont jusqu'à avatar_100 dans le classeur
 *     modèle, mais seules les 76 premières lignes étaient renseignées) - ce
 *     catalogue de 76 entrées est donc réellement complet pour l'instant, ce
 *     n'est pas une troncature accidentelle.</li>
 * </ul>
 * Toujours ouvert par ailleurs (voir le cahier des charges §5.1, non traité
 * ici) : l'impact du niveau "tresforte" sur le MOTEUR de jeu (Event.java/
 * StatsService) - ce fichier ne fait que décrire des visuels/métadonnées, il
 * ne change aucune règle.
 */
final class CatalogSeeds
{
	private CatalogSeeds()
	{
		// Classe utilitaire, jamais instanciée.
	}

	static List<LinkedHashMap<String, Object>> seedCards()
	{
		return loadResource("cartes.json"); //$NON-NLS-1$
	}

	static List<LinkedHashMap<String, Object>> seedVisuals()
	{
		return loadResource("visuels.json"); //$NON-NLS-1$
	}

	static List<LinkedHashMap<String, Object>> seedBackgrounds()
	{
		return loadResource("fonds.json"); //$NON-NLS-1$
	}

	static List<LinkedHashMap<String, Object>> seedAvatars()
	{
		return loadResource("avatars.json"); //$NON-NLS-1$
	}

	private static List<LinkedHashMap<String, Object>> loadResource(final String pFileName)
	{
		final String path = "/catalog-seeds/" + pFileName; //$NON-NLS-1$
		try (InputStream in = CatalogSeeds.class.getResourceAsStream(path))
		{
			if (in == null)
				throw new IllegalStateException("Ressource de seed introuvable : " + path); //$NON-NLS-1$
			final ObjectMapper mapper = new ObjectMapper();
			return mapper.readValue(in, mapper.getTypeFactory().constructCollectionType(List.class, LinkedHashMap.class));
		}
		catch (final IOException e)
		{
			throw new IllegalStateException("Ressource de seed illisible : " + path, e); //$NON-NLS-1$
		}
	}
}
