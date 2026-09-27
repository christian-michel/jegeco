package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;

/**
 * Régressions du 27/09/2026 (campagne de test 2/4/10 joueurs x 12 tours en
 * HTTP réel) sur le chrono de tour et la fenêtre d'échanges smartphone :
 * <ul>
 * <li>isTradingAllowed renvoyait false pendant TOUT le dernier tour prévu
 * (test "turnNumber >= nbTurnsPlanned" alors que turnNumber est le tour EN
 * COURS) - aucun échange possible au tour 12/12 ;</li>
 * <li>"+30 s" (extendCurrentTurn) RETIRAIT 30 s au tour en cours au lieu d'en
 * ajouter (turnStartedAt reculé au lieu d'avancé).</li>
 * </ul>
 */
class GameServiceTurnTimerTest
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

	private static void turn(final int pGameId) throws Exception
	{
		sService.recordEvent(pGameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
	}

	@Test
	void tradingIsAllowedDuringTheLastPlannedTurnAndStopsAtEndOfGame() throws Exception
	{
		final int gameId = sService.createGame(Game.MONEY_LIBRE, 3, "A", null, "dernier-tour", "2026-09-27", "X", 1, 300, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				1.0, false, 4, true, 0.5).getId();
		sService.addPlayer(gameId, "J1"); //$NON-NLS-1$
		sService.addPlayer(gameId, "J2"); //$NON-NLS-1$
		assertFalse(sService.isTradingAllowed(sService.getGame(gameId)), "avant le premier tour"); //$NON-NLS-1$
		for (int t = 1; t <= 3; t++)
		{
			turn(gameId);
			final Game game = sService.getGame(gameId);
			assertEquals(t, game.getTurnNumber());
			assertTrue(sService.isTradingAllowed(game), "échanges bloqués pendant le tour " + t + "/3"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		sService.recordEvent(gameId, "E", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		assertFalse(sService.isTradingAllowed(sService.getGame(gameId)), "après la fin de partie"); //$NON-NLS-1$
	}

	@Test
	void tradingStopsWhenTheTimerOfTheLastTurnRunsOut() throws Exception
	{
		final int gameId = sService.createGame(Game.MONEY_LIBRE, 1, "A", null, "chrono-dernier-tour", "2026-09-27", "X", 1, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				300, 1.0, false, 4, true, 0.5).getId();
		sService.addPlayer(gameId, "J1"); //$NON-NLS-1$
		turn(gameId);
		assertTrue(sService.isTradingAllowed(sService.getGame(gameId)));
		sService.extendCurrentTurn(gameId, -301); // chrono arrivé à 0
		assertFalse(sService.isTradingAllowed(sService.getGame(gameId)));
	}

	@Test
	void plusThirtySecondsAddsTimeToTheRunningTurn() throws Exception
	{
		final int gameId = sService.createGame(Game.MONEY_LIBRE, 12, "A", null, "plus-30s", "2026-09-27", "X", 1, 300, 1.0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				false, 4, true, 0.5).getId();
		sService.addPlayer(gameId, "J1"); //$NON-NLS-1$
		turn(gameId);
		final long before = sService.getGame(gameId).getTurnStartedAt().getTime();
		sService.extendCurrentTurn(gameId, 30);
		final long after = sService.getGame(gameId).getTurnStartedAt().getTime();
		// temps restant = durée - (maintenant - turnStartedAt) : +30 s de temps
		// restant <=> turnStartedAt avancé de 30 s.
		assertEquals(30_000L, after - before);
		// En pause, le temps figé augmente directement (comportement inchangé).
		sService.pauseTurn(gameId);
		final int paused = sService.getGame(gameId).getPausedRemainingSeconds();
		sService.extendCurrentTurn(gameId, 30);
		assertEquals(paused + 30, sService.getGame(gameId).getPausedRemainingSeconds());
	}
}
