package jyt.geconomicus.helper.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Sauvegarde complète de la base de données (toutes les parties, tous les
 * systèmes confondus) - demandé par l'utilisateur pour finaliser l'étape 2 :
 * jusqu'ici, la seule copie des données était le fichier {@code ~/geco.h2}
 * lui-même, sans aucun moyen d'en obtenir une copie propre depuis
 * l'application.
 * <p>
 * Utilise la commande native H2 {@code BACKUP TO} plutôt qu'une copie brute du
 * fichier sur le disque : elle produit un instantané cohérent même si le
 * serveur est en train d'écrire au même moment (une copie de fichier brute
 * pourrait capturer un état à moitié écrit) - voir la documentation H2,
 * commande SQL BACKUP.
 */
public class BackupService
{
	private final EntityManagerFactory mEntityManagerFactory;
	private final Path mTempDir;

	public BackupService(final EntityManagerFactory pEntityManagerFactory, final Path pTempDir)
	{
		mEntityManagerFactory = pEntityManagerFactory;
		mTempDir = pTempDir;
	}

	/**
	 * Produit une sauvegarde complète de la base (.zip, format natif H2) dans
	 * un fichier temporaire, et retourne son chemin - à charge de l'appelant de
	 * servir puis supprimer ce fichier une fois la réponse envoyée (voir
	 * GecoServer, route /api/backup).
	 */
	public Path createBackup() throws IOException
	{
		Files.createDirectories(mTempDir);
		final String fileName = "geconomicus-backup-" + //$NON-NLS-1$
				LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) //$NON-NLS-1$
				+ "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip"; //$NON-NLS-1$ //$NON-NLS-2$
		final Path backupFile = mTempDir.resolve(fileName);
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			// Requête SQL native H2, pas JPQL : BACKUP est une commande propre à H2,
			// sans équivalent dans le langage de requête standard de JPA.
			em.createNativeQuery("BACKUP TO '" + backupFile.toAbsolutePath() + "'").executeUpdate(); //$NON-NLS-1$ //$NON-NLS-2$
		}
		finally
		{
			em.close();
		}
		return backupFile;
	}
}
