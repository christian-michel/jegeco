package jyt.geconomicus.helper.server.auth;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.NoResultException;

import jyt.geconomicus.helper.Animator;
import jyt.geconomicus.helper.Animator.Role;
import jyt.geconomicus.helper.Game;

/**
 * Couche de service pour les comptes animateurs (voir Animator) - le pendant,
 * pour les comptes, de ce que {@link jyt.geconomicus.helper.server.GameService}
 * fait pour les parties : même pattern "un EntityManager par méthode" (voir sa
 * javadoc pour le détail du choix), volontairement dans un package séparé
 * (jyt.geconomicus.helper.server.auth) plutôt que mélangé à GameService, pour
 * que la brique "comptes/authentification" reste isolée et remplaçable
 * indépendamment du moteur de jeu (voir la discussion de modularité du
 * 21/09/2026, feuille de route multi-session : geco-engine porte uniquement
 * les données - Animator, Game.owner - toute la logique de comptes vit ici).
 *
 * Étape 1 du chantier multi-session (21/09/2026) : uniquement le modèle de
 * comptes et le rattachement des parties déjà existantes. PAS encore de
 * notion de session/connexion HTTP (voir la suite de la feuille de route) -
 * cette classe ne touche à aucune route de GecoServer pour l'instant.
 */
public class AnimatorService
{
	private final EntityManagerFactory mEntityManagerFactory;

	public AnimatorService(final EntityManagerFactory pEntityManagerFactory)
	{
		mEntityManagerFactory = pEntityManagerFactory;
	}

	/**
	 * Crée un nouveau compte animateur. Si c'est le tout premier compte créé
	 * sur ce serveur (aucun Animator en base avant cet appel), rattache
	 * automatiquement à ce compte toutes les parties encore sans propriétaire
	 * (Game.owner == null) - choix utilisateur explicite (21/09/2026) plutôt
	 * que de les laisser orphelines ou visibles par tous sans propriétaire :
	 * l'historique déjà joué avant l'introduction des comptes ne doit pas se
	 * perdre ni devenir inaccessible.
	 * @throws DuplicateLoginException si pLogin est déjà pris par un autre compte.
	 */
	public Animator createAnimator(final String pLogin, final String pDisplayName, final String pPlainPassword,
			final Role pRole) throws DuplicateLoginException
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			if (findByLogin(em, pLogin) != null)
				throw new DuplicateLoginException(pLogin);

			final boolean isFirstAnimator = countAnimators(em) == 0;

			em.getTransaction().begin();
			final Animator animator = new Animator(pLogin, pDisplayName, PasswordHasher.hash(pPlainPassword), pRole);
			em.persist(animator);

			if (isFirstAnimator)
			{
				final List<Game> orphanGames = em
						.createQuery("SELECT g FROM Game g WHERE g.owner IS NULL", Game.class) //$NON-NLS-1$
						.getResultList();
				for (final Game orphanGame : orphanGames)
					orphanGame.setOwner(animator);
			}

			em.getTransaction().commit();
			return animator;
		}
		finally
		{
			em.close();
		}
	}

	private long countAnimators(final EntityManager pEm)
	{
		return pEm.createQuery("SELECT COUNT(a) FROM Animator a", Long.class).getSingleResult(); //$NON-NLS-1$
	}

	public List<Animator> listAnimators()
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			return em.createQuery("SELECT a FROM Animator a ORDER BY a.login", Animator.class).getResultList(); //$NON-NLS-1$
		}
		finally
		{
			em.close();
		}
	}

	public Animator getAnimator(final int pAnimatorId)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			return em.find(Animator.class, pAnimatorId);
		}
		finally
		{
			em.close();
		}
	}

	/**
	 * Réinitialise le mot de passe d'un compte existant (écran de gestion des
	 * comptes, réservé au rôle ADMIN - voir GecoServer). L'ancien mot de passe
	 * n'a pas besoin d'être connu : c'est un ADMIN authentifié qui agit ici,
	 * pas l'animateur concerné lui-même retrouvant l'accès à son propre
	 * compte (pas encore de mécanisme de ce type - hors périmètre de cette
	 * phase, un serveur LAN de confiance restreinte n'en a pas un besoin
	 * urgent).
	 * @throws IllegalArgumentException si pAnimatorId ne correspond à aucun compte.
	 */
	public void resetPassword(final int pAnimatorId, final String pNewPlainPassword)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Animator animator = em.find(Animator.class, pAnimatorId);
			if (animator == null)
				throw new IllegalArgumentException("Animator not found: " + pAnimatorId); //$NON-NLS-1$
			em.getTransaction().begin();
			animator.setPasswordHash(PasswordHasher.hash(pNewPlainPassword));
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	private Animator findByLogin(final EntityManager pEm, final String pLogin)
	{
		try
		{
			return pEm.createQuery("SELECT a FROM Animator a WHERE a.login = :login", Animator.class) //$NON-NLS-1$
					.setParameter("login", pLogin) //$NON-NLS-1$
					.getSingleResult();
		}
		catch (final NoResultException e)
		{
			return null;
		}
	}

	/**
	 * Vérifie un couple login/mot de passe. Retourne le compte en cas de
	 * succès, null sinon (login inconnu OU mot de passe incorrect - jamais de
	 * distinction dans le résultat, pour ne pas révéler quels logins existent
	 * réellement sur le serveur).
	 */
	public Animator verifyLogin(final String pLogin, final String pPlainPassword)
	{
		final EntityManager em = mEntityManagerFactory.createEntityManager();
		try
		{
			final Animator animator = findByLogin(em, pLogin);
			if (animator == null)
				return null;
			return PasswordHasher.matches(pPlainPassword, animator.getPasswordHash()) ? animator : null;
		}
		finally
		{
			em.close();
		}
	}

	/** Levée quand on tente de créer un compte avec un login déjà pris. */
	public static class DuplicateLoginException extends Exception
	{
		public DuplicateLoginException(final String pLogin)
		{
			super(pLogin);
		}
	}
}
