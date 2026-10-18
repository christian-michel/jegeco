package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Campagne de test 2/6/16 joueurs (18/10/2026) - deux bugs trouvés en HTTP
 * réel/interface réelle, voir les commentaires datés dans GameService :
 * <ul>
 * <li>"Reprendre les joueurs" : un joueur pré-inscrit par l'animateur ne
 * pouvait pas rejoindre sa propre place par le QR "Inviter" (prénom "déjà
 * pris") - {@link GameService#joinAsPlayer} le rattache désormais au
 * téléphone, une seule fois.</li>
 * <li>"Fin de tour" manuel : les échanges smartphone restaient ouverts côté
 * serveur pendant l'assistant de fin de tour -
 * {@link GameService#markTurnEndAnnounced} les ferme pour le tour en cours.</li>
 * </ul>
 */
class GameServiceReuseJoinAndTurnEndTest
{
	private static EntityManagerFactory sEmf;
	private static GameService sService;

	@BeforeAll
	static void setUp()
	{
		sEmf = Persistence.createEntityManagerFactory("geco-server-test"); //$NON-NLS-1$
		sService = new GameService(sEmf);
	}

	@AfterAll
	static void tearDown()
	{
		if (sEmf != null)
			sEmf.close();
	}

	private static int newGame(final int pMoneySystem, final String pDesc)
	{
		return sService.createGame(pMoneySystem, 12, "A", null, pDesc, "2026-10-18", "X", 1, 300, 1.0, false, 4, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				pMoneySystem == Game.MONEY_LIBRE, 0.4).getId();
	}

	@Test
	void reusedPlayerPreRegisteredByAnimatorCanJoinWithTheSameNameExactlyOnce() throws Exception
	{
		final int gameId = newGame(Game.MONEY_DEBT, "reprise-join");
		// btnNewGame.onclick + "Reprendre les joueurs" : pré-inscription par prénom
		final Player preRegistered = sService.addPlayer(gameId, "Yasmine"); //$NON-NLS-1$
		// le téléphone suit le QR "Inviter" et tape son prénom (casse différente)
		final Player joined = sService.joinAsPlayer(gameId, "yasmine", 31, "#123456", "{\"type\":\"gallery\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals(preRegistered.getId(), joined.getId(), "le téléphone récupère SA place, pas un doublon"); //$NON-NLS-1$
		assertEquals(preRegistered.getAccessToken(), joined.getAccessToken(), "même jeton d'accès"); //$NON-NLS-1$
		assertEquals(1, sService.getGame(gameId).getPlayers().size(), "aucun joueur supplémentaire créé"); //$NON-NLS-1$
		final Player stored = sService.getGame(gameId).getPlayers().iterator().next();
		assertEquals(Integer.valueOf(31), stored.getDeclaredAge());
		assertNotNull(stored.getAvatarConfigJson());
		// un SECOND téléphone avec le même prénom est refusé, comme avant
		assertThrows(GameService.DuplicatePlayerNameException.class,
				() -> sService.joinAsPlayer(gameId, "Yasmine", 20, "#000000", "{}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	@Test
	void duplicateNameOfAPlayerAlreadyJoinedByPhoneIsStillRefused() throws Exception
	{
		final int gameId = newGame(Game.MONEY_LIBRE, "doublon-join");
		sService.joinAsPlayer(gameId, "Zoé", 25, "#111111", "{\"type\":\"gallery\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertThrows(GameService.DuplicatePlayerNameException.class,
				() -> sService.joinAsPlayer(gameId, "zoé", 25, "#111111", "{}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals(1, sService.getGame(gameId).getPlayers().size());
	}

	@Test
	void announcingTheEndOfTheTurnClosesTradingUntilTheNextTurn() throws Exception
	{
		final int gameId = newGame(Game.MONEY_LIBRE, "fin-de-tour-manuelle");
		sService.addPlayer(gameId, "J1"); //$NON-NLS-1$
		sService.addPlayer(gameId, "J2"); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		assertTrue(sService.isTradingAllowed(sService.getGame(gameId)), "tour 1 en cours, chrono loin de 0"); //$NON-NLS-1$
		sService.markTurnEndAnnounced(gameId); // clic "Fin de tour" (avant l'expiration du chrono)
		assertFalse(sService.isTradingAllowed(sService.getGame(gameId)), "échanges fermés pendant l'assistant"); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		assertTrue(sService.isTradingAllowed(sService.getGame(gameId)), "tour 2 : échanges rouverts"); //$NON-NLS-1$
	}
}
