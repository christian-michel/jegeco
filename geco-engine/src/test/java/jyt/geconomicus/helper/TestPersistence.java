package jyt.geconomicus.helper;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

/**
 * Fabrique une unité de persistance de test ("geco-test", voir
 * src/test/resources/META-INF/persistence.xml) pointant vers une base H2 en
 * mémoire au nom UNIQUE à chaque appel, plutôt que le nom fixe "geco_test"
 * défini dans le fichier XML.
 * <p>
 * Pourquoi c'est nécessaire : Maven Surefire exécute par défaut toutes les
 * classes de test dans la MÊME JVM. Si chaque test se contentait de
 * {@code Persistence.createEntityManagerFactory("geco-test")}, toutes les
 * classes de test partageraient la même base H2 en mémoire tout au long de
 * l'exécution - un test pourrait alors voir les données laissées par un
 * autre (parties, identifiants auto-incrémentés...), rendant les résultats
 * dépendants de l'ordre d'exécution. Avec un nom de base généré ici, chaque
 * appel obtient une base entièrement vierge et isolée.
 */
public final class TestPersistence
{
	private TestPersistence()
	{
		// Classe utilitaire, non instanciable.
	}

	public static EntityManagerFactory newIsolatedFactory()
	{
		final String dbName = "test_" + UUID.randomUUID().toString().replace('-', '_');
		// Type explicite (Map.<String,Object>of plutôt que Map.of) : sans lui, le
		// type inféré serait Map<String,String>, qui ne correspond pas forcément
		// exactement à la signature de Persistence.createEntityManagerFactory selon
		// la version de l'API JPA - ce type witness compile quelle que soit la
		// variante exacte (Map<?,?> ou Map<String,Object>).
		return Persistence.createEntityManagerFactory("geco-test",
				Map.<String, Object>of("jakarta.persistence.jdbc.url", "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1"));
	}
}
