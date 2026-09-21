package jyt.geconomicus.helper.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Animator;
import jyt.geconomicus.helper.Animator.Role;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.server.GameService;
import jyt.geconomicus.helper.server.auth.AnimatorService.DuplicateLoginException;

/**
 * Vérifie le modèle de comptes animateurs posé le 21/09/2026 (Phase 1 du
 * chantier multi-session : "proposer une version serveur capable de gérer le
 * multi session avec plusieurs animateurs qui ont chacuns leur profil et
 * leurs parties"). Cette phase ne construit QUE le modèle de données et le
 * service - pas encore d'écran de connexion ni de filtrage des routes REST
 * par propriétaire (voir la suite de la feuille de route), donc ce test ne
 * touche à aucune route de GecoServer, uniquement à AnimatorService/GameService
 * en direct, comme GameServicePileConservationTest.
 * <p>
 * Chaque test utilise une base H2 EN MÉMOIRE fraîche (recréée dans
 * {@link #setUp()}) plutôt qu'une base partagée entre tests comme
 * GameServicePileConservationTest : le comportement "premier compte créé"
 * est sensible à l'ORDRE des créations, une base neuve par test rend chaque
 * scénario indépendant sans avoir à deviner l'état laissé par les tests
 * précédents.
 */
class AnimatorServiceTest
{
	private EntityManagerFactory mEmf;
	private AnimatorService mAnimatorService;
	private GameService mGameService;

	@BeforeEach
	void setUp()
	{
		// Réutilise l'unité de persistance "geco-server-test" (même classes,
		// mêmes propriétés que GameServicePileConservationTest), mais en
		// SURCHARGEANT l'URL JDBC avec un nom de base en mémoire UNIQUE à
		// chaque test (suffixe aléatoire) plutôt que le nom fixe du fichier
		// persistence.xml ("geco_server_test"). Nécessaire ici parce que
		// chaque test de cette classe ouvre/ferme sa PROPRE
		// EntityManagerFactory (@BeforeEach/@AfterEach, pas @BeforeAll comme
		// les autres classes de test) : avec DB_CLOSE_DELAY=-1, une base H2
		// en mémoire NOMMÉE reste vivante au-delà de la fermeture d'une
		// EntityManagerFactory - réutiliser le même nom fixe d'un test à
		// l'autre aurait fait persister les comptes/parties d'un test au
		// suivant (au minimum "premier compte créé" n'aurait plus été vrai
		// dès le second test exécuté), faussant silencieusement les
		// assertions sur le rattachement des parties orphelines.
		final String uniqueDbUrl = "jdbc:h2:mem:geco_animator_test_" + java.util.UUID.randomUUID() //$NON-NLS-1$
				.toString().replace("-", "") + ";DB_CLOSE_DELAY=-1"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		mEmf = Persistence.createEntityManagerFactory("geco-server-test", //$NON-NLS-1$
				java.util.Map.of("jakarta.persistence.jdbc.url", uniqueDbUrl)); //$NON-NLS-1$
		mAnimatorService = new AnimatorService(mEmf);
		mGameService = new GameService(mEmf);
	}

	@AfterEach
	void tearDown()
	{
		if (mEmf != null)
			mEmf.close();
	}

	@Test
	void testCreateAnimatorThenVerifyLoginSucceedsWithCorrectPassword() throws Exception
	{
		mAnimatorService.createAnimator("alice", "Alice", "correct horse battery staple", Role.ADMIN); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		final Animator verified = mAnimatorService.verifyLogin("alice", "correct horse battery staple"); //$NON-NLS-1$ //$NON-NLS-2$
		assertNotNull(verified, "la connexion doit réussir avec le bon mot de passe"); //$NON-NLS-1$
		assertEquals("alice", verified.getLogin()); //$NON-NLS-1$
		assertEquals(Role.ADMIN, verified.getRole());
	}

	@Test
	void testVerifyLoginFailsWithWrongPasswordOrUnknownLogin() throws Exception
	{
		mAnimatorService.createAnimator("bob", "Bob", "bonpassword123", Role.ANIMATEUR); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		assertNull(mAnimatorService.verifyLogin("bob", "mauvaispassword"), //$NON-NLS-1$ //$NON-NLS-2$
				"un mauvais mot de passe ne doit jamais réussir"); //$NON-NLS-1$
		assertNull(mAnimatorService.verifyLogin("inconnu", "bonpassword123"), //$NON-NLS-1$ //$NON-NLS-2$
				"un login inconnu ne doit jamais réussir"); //$NON-NLS-1$
	}

	@Test
	void testCreateAnimatorRejectsDuplicateLogin() throws Exception
	{
		mAnimatorService.createAnimator("carole", "Carole", "motdepasse1", Role.ANIMATEUR); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		assertThrows(DuplicateLoginException.class,
				() -> mAnimatorService.createAnimator("carole", "Carole 2", "autremotdepasse", Role.ANIMATEUR)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * Le cœur de la migration demandée par l'utilisateur : "les rattacher au
	 * premier compte créé" - toutes les parties déjà en base AVANT la
	 * création du tout premier compte animateur doivent lui être
	 * automatiquement rattachées, pour ne pas perdre l'accès à l'historique
	 * déjà joué.
	 */
	@Test
	void testFirstAnimatorInheritsAllPreviouslyOrphanGames() throws Exception
	{
		final Game legacyGame1 = mGameService.createGame(Game.MONEY_LIBRE, 12, "AnimTest", null, "partie historique 1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-01", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final Game legacyGame2 = mGameService.createGame(Game.MONEY_DEBT, 12, "AnimTest", null, "partie historique 2", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-02", "Ceres", 1, 180, 1.0, false, 0, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$

		assertNull(mGameService.getGame(legacyGame1.getId()).getOwner(), "avant tout compte, une partie n'a pas de propriétaire"); //$NON-NLS-1$

		final Animator firstAnimator = mAnimatorService.createAnimator("premier", "Premier Animateur", //$NON-NLS-1$ //$NON-NLS-2$
				"motdepasse1", Role.ADMIN); //$NON-NLS-1$

		assertEquals(firstAnimator.getId(), mGameService.getGame(legacyGame1.getId()).getOwner().getId(),
				"la partie historique 1 doit être rattachée au premier compte créé"); //$NON-NLS-1$
		assertEquals(firstAnimator.getId(), mGameService.getGame(legacyGame2.getId()).getOwner().getId(),
				"la partie historique 2 doit être rattachée au premier compte créé"); //$NON-NLS-1$
	}

	/**
	 * Contre-épreuve : un DEUXIÈME compte créé ne doit PAS hériter des
	 * parties déjà rattachées au premier, ni des nouvelles parties orphelines
	 * créées après lui - seul le TOUT PREMIER compte du serveur bénéficie de
	 * la migration automatique.
	 */
	@Test
	void testSecondAnimatorDoesNotInheritGamesOwnedByFirstOrCreatedLater() throws Exception
	{
		final Game legacyGame = mGameService.createGame(Game.MONEY_LIBRE, 12, "AnimTest", null, "partie historique", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-01", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final Animator firstAnimator = mAnimatorService.createAnimator("premier", "Premier", "motdepasse1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				Role.ADMIN);

		final Game newGameAfterSecondAccount = mGameService.createGame(Game.MONEY_TROC, 12, "AnimTest2", null, //$NON-NLS-1$ //$NON-NLS-2$
				"partie créée après le second compte", "2026-09-03", "Ceres", 1, 180, 1.0, false, 4, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final Animator secondAnimator = mAnimatorService.createAnimator("second", "Second", "motdepasse2", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				Role.ANIMATEUR);

		assertEquals(firstAnimator.getId(), mGameService.getGame(legacyGame.getId()).getOwner().getId(),
				"la partie historique reste au premier compte, jamais reprise par le second"); //$NON-NLS-1$
		assertNull(mGameService.getGame(newGameAfterSecondAccount.getId()).getOwner(),
				"une partie créée après le second compte ne doit PAS lui être automatiquement rattachée - seul le premier compte du serveur bénéficie de la migration"); //$NON-NLS-1$

		final List<Animator> all = mAnimatorService.listAnimators();
		assertEquals(2, all.size());
		assertTrue(all.stream().anyMatch(a -> "premier".equals(a.getLogin()))); //$NON-NLS-1$
		assertTrue(all.stream().anyMatch(a -> "second".equals(a.getLogin()))); //$NON-NLS-1$
	}

	@Test
	void testResetPasswordAllowsLoginWithNewPasswordAndRejectsOldOne() throws Exception
	{
		final Animator animator = mAnimatorService.createAnimator("dora", "Dora", "ancienMotDePasse", Role.ANIMATEUR); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		mAnimatorService.resetPassword(animator.getId(), "nouveauMotDePasse"); //$NON-NLS-1$

		assertNull(mAnimatorService.verifyLogin("dora", "ancienMotDePasse"), "l'ancien mot de passe ne doit plus fonctionner"); //$NON-NLS-1$ //$NON-NLS-2$
		assertNotNull(mAnimatorService.verifyLogin("dora", "nouveauMotDePasse"), "le nouveau mot de passe doit fonctionner"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Vérifie GameService.setGameOwner (ajouté en Phase 2, 21/09/2026) : une
	 * partie créée par un animateur connecté doit lui être rattachée dès sa
	 * création (voir POST /api/games côté GecoServer, qui appelle cette
	 * méthode juste après GameService.createGame).
	 */
	@Test
	void testSetGameOwnerAttachesGameToTheGivenAnimator() throws Exception
	{
		final Animator animator = mAnimatorService.createAnimator("erwan", "Erwan", "motdepasse1", Role.ANIMATEUR); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final Game game = mGameService.createGame(Game.MONEY_TROC, 12, "AnimTest", null, "partie fraîche", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-21", "Ceres", 1, 180, 1.0, false, 4, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$

		mGameService.setGameOwner(game.getId(), animator.getId());

		assertEquals(animator.getId(), mGameService.getGame(game.getId()).getOwner().getId());
	}

	/**
	 * Cœur de la Phase 3 (21/09/2026) : "chacun avec leur profil et leurs
	 * parties" - GameService.listGamesByOwner ne doit JAMAIS retourner la
	 * partie d'un autre animateur, ni une partie orpheline (sans
	 * propriétaire) qui n'appartient par définition à personne.
	 */
	@Test
	void testListGamesByOwnerOnlyReturnsThatAnimatorsOwnGames() throws Exception
	{
		final Animator alice = mAnimatorService.createAnimator("alice2", "Alice", "motdepasse1", Role.ANIMATEUR); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final Animator bob = mAnimatorService.createAnimator("bob2", "Bob", "motdepasse2", Role.ANIMATEUR); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		final Game aliceGame = mGameService.createGame(Game.MONEY_LIBRE, 12, "AnimTest", null, "partie d'Alice", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-21", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		mGameService.setGameOwner(aliceGame.getId(), alice.getId());
		final Game bobGame = mGameService.createGame(Game.MONEY_DEBT, 12, "AnimTest", null, "partie de Bob", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-21", "Ceres", 1, 180, 1.0, false, 0, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		mGameService.setGameOwner(bobGame.getId(), bob.getId());
		final Game orphanGame = mGameService.createGame(Game.MONEY_TROC, 12, "AnimTest", null, "partie orpheline", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-09-21", "Ceres", 1, 180, 1.0, false, 4, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$

		final List<Game> aliceGames = mGameService.listGamesByOwner(alice.getId());
		assertEquals(1, aliceGames.size(), "Alice ne doit voir QUE sa propre partie"); //$NON-NLS-1$
		assertEquals(aliceGame.getId(), aliceGames.get(0).getId());
		assertFalse(aliceGames.stream().anyMatch(g -> g.getId().equals(bobGame.getId())),
				"la partie de Bob ne doit jamais apparaître dans la liste d'Alice"); //$NON-NLS-1$
		assertFalse(aliceGames.stream().anyMatch(g -> g.getId().equals(orphanGame.getId())),
				"une partie orpheline n'appartient à personne, elle ne doit apparaître dans AUCUNE liste par propriétaire"); //$NON-NLS-1$

		final List<Game> bobGames = mGameService.listGamesByOwner(bob.getId());
		assertEquals(1, bobGames.size(), "Bob ne doit voir QUE sa propre partie"); //$NON-NLS-1$
		assertEquals(bobGame.getId(), bobGames.get(0).getId());
	}

	@Test
	void testPasswordHasherProducesDifferentHashesForSamePasswordAndVerifiesCorrectly()
	{
		final String hash1 = PasswordHasher.hash("memePassword"); //$NON-NLS-1$
		final String hash2 = PasswordHasher.hash("memePassword"); //$NON-NLS-1$

		assertNotEquals(hash1, hash2, "deux hachages du même mot de passe doivent différer (sel aléatoire)"); //$NON-NLS-1$
		assertTrue(PasswordHasher.matches("memePassword", hash1)); //$NON-NLS-1$
		assertTrue(PasswordHasher.matches("memePassword", hash2)); //$NON-NLS-1$
		assertFalse(PasswordHasher.matches("autrePassword", hash1)); //$NON-NLS-1$
	}

	@Test
	void testPasswordHasherRejectsCorruptedOrUnexpectedHashFormatWithoutThrowing()
	{
		assertFalse(PasswordHasher.matches("motdepasse", null)); //$NON-NLS-1$
		assertFalse(PasswordHasher.matches("motdepasse", "n'importe quoi")); //$NON-NLS-1$ //$NON-NLS-2$
		assertFalse(PasswordHasher.matches("motdepasse", "pbkdf2:pasunnombre:sel:hash")); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
