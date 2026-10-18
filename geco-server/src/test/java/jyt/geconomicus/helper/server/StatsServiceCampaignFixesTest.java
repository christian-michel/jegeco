package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;
import jyt.geconomicus.helper.server.StatsService.ComparisonReport;
import jyt.geconomicus.helper.server.StatsService.PlayerWealthPoint;
import jyt.geconomicus.helper.server.StatsService.PlayerWealthSeries;

/**
 * Campagne de test 2/6/16 joueurs (18/10/2026) - trois défauts des
 * statistiques trouvés sur de vraies parties HTTP, voir les commentaires
 * datés dans StatsService :
 * <ul>
 * <li>tous les joueurs marqués "(sorti)" (quitEarly) dans toute partie
 * libre+smartphone terminée normalement ;</li>
 * <li>module Galilée : ratio à la moyenne exprimé dans deux unités
 * différentes (0,67 au lieu de 1,0 pour un joueur exactement moyen avec
 * "Valeur d'une pièce faible" = 0,5, et 0 pour tous au tour 1) ;</li>
 * <li>comparaison : une partie troc étiquetée "Libre".</li>
 * </ul>
 */
class StatsServiceCampaignFixesTest
{
	private static EntityManagerFactory sEmf;
	private static GameService sService;
	private static final StatsService sStats = new StatsService();

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

	private static Map<String, List<String>> catalog(final int pModelsPerLevel)
	{
		final Map<String, List<String>> byLevel = new LinkedHashMap<>();
		for (final String level : List.of("faible", "moyenne", "forte", "tresforte")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		{
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < pModelsPerLevel; i++)
				ids.add(level + "_m" + i); //$NON-NLS-1$
			byLevel.put(level, ids);
		}
		return byLevel;
	}

	private static void ev(final int pGameId, final String pType, final Integer pPlayerId, final int pWeakCoins) throws Exception
	{
		sService.recordEvent(pGameId, pType, pPlayerId, 0, 0, 0, 0, 0, null, 0, 0, pWeakCoins, 0, 0, 0, 0, 0);
	}

	/** Partie libre+smartphone, 2 joueurs toujours à égalité, 3 tours, fin de partie normale. */
	private static int playEqualLibreGame(final String pDesc) throws Exception
	{
		final int gameId = sService.createGame(Game.MONEY_LIBRE, 3, "A", null, pDesc, "2026-10-18", "X", 1, 300, 0.5, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				false, 4, true, 0.4).getId();
		final int a = sService.addPlayer(gameId, "Alice").getId(); //$NON-NLS-1$
		final int b = sService.addPlayer(gameId, "Bruno").getId(); //$NON-NLS-1$
		ev(gameId, "T", null, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));
		int jetons = sService.getGame(gameId).computeStartingJetonsPerPlayer(); // 14 avec 0,5
		for (int turn = 2; turn <= 3; turn++)
		{
			jetons += 12; // même DU pour les deux, aucun échange : toujours exactement à la moyenne
			ev(gameId, "W", a, jetons); //$NON-NLS-1$
			ev(gameId, "W", b, jetons); //$NON-NLS-1$
			ev(gameId, "T", null, 0); //$NON-NLS-1$
		}
		// inventaire de fin de partie (renderEndGameInventory) : QUIT pour tous
		ev(gameId, "Q", a, jetons); //$NON-NLS-1$
		ev(gameId, "Q", b, jetons); //$NON-NLS-1$
		ev(gameId, "E", null, 0); //$NON-NLS-1$
		return gameId;
	}

	@Test
	void playersLeavingAtTheNormalEndOfALibreGameAreNotMarkedAsQuitEarly() throws Exception
	{
		final Game game = sService.getGame(playEqualLibreGame("fin-normale-quitEarly")); //$NON-NLS-1$
		assertTrue(game.getPlayers().stream().allMatch(Player::isQuit), "prérequis : strict TRM pose Player.quit à la sortie"); //$NON-NLS-1$
		for (final PlayerWealthSeries s : sStats.computeWealthOverTime(game).series())
			assertFalse(s.quitEarly(), "sortie de FIN de partie, pas en cours de route : " + s.playerName()); //$NON-NLS-1$
		sStats.computeFinalReport(game, false).playerWealths()
				.forEach(w -> assertFalse(w.quitEarly(), "rapport final : " + w.playerName())); //$NON-NLS-1$
	}

	@Test
	void galileeRatioIsOneForPlayersExactlyAtTheAverage() throws Exception
	{
		final Game game = sService.getGame(playEqualLibreGame("galilee-ratio")); //$NON-NLS-1$
		for (final PlayerWealthSeries s : sStats.computeWealthOverTime(game).series())
		{
			assertEquals(List.of(1, 2, 3), s.points().stream().map(PlayerWealthPoint::turn).toList());
			for (final PlayerWealthPoint p : s.points())
				assertEquals(1.0, p.relativeToAverage(), 1e-9,
						s.playerName() + " tour " + p.turn() + " : un joueur exactement dans la moyenne doit valoir 1,0"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	@Test
	void trocGameIsLabelledTrocInAComparison() throws Exception
	{
		final int libre = playEqualLibreGame("comparaison-libre"); //$NON-NLS-1$
		final int troc = sService.createGame(Game.MONEY_TROC, 3, "A", null, "comparaison-troc", "2026-10-18", "X", 1, 300, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				1.0, false, 4, false, 0).getId();
		sService.addPlayer(troc, "Alice"); //$NON-NLS-1$
		final ComparisonReport report = sStats.computeComparison(List.of(sService.getGame(libre), sService.getGame(troc)));
		final StatsService.ComparisonGameInfo trocInfo = report.games().stream().filter(g -> g.gameId() == troc).findFirst()
				.orElseThrow();
		assertTrue(trocInfo.label().startsWith("Troc"), trocInfo.label()); //$NON-NLS-1$
		assertTrue(trocInfo.label().contains("comparaison-troc"), "nom de la partie dans le libellé : " + trocInfo.label()); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Game.MONEY_TROC, trocInfo.moneySystem());
	}
}
