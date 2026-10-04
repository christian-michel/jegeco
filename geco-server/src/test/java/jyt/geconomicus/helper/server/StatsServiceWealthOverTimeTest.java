package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import jyt.geconomicus.helper.server.StatsService.PlayerWealthPoint;
import jyt.geconomicus.helper.server.StatsService.PlayerWealthSeries;
import jyt.geconomicus.helper.server.StatsService.WealthOverTimeReport;

/**
 * Vérifie {@link StatsService#computeWealthOverTime} - deux correctifs du
 * 04/10/2026, demandés par un utilisateur sur une vraie partie libre+
 * smartphone (PDF "Retours_-_20261004.pdf") :
 * <ol>
 * <li>La courbe doit éclater la richesse en trois grandeurs distinctes
 * (unités monétaires seules, cartes seules, combinées) plutôt qu'un seul
 * nombre qui mélangeait les deux - voir {@link PlayerWealthPoint}.</li>
 * <li>Un joueur qui QUITTE la partie au dernier tour ne doit laisser
 * qu'UN SEUL point sur la courbe à ce tour (son bilan réel), jamais deux
 * (l'ancien point d'ouverture de tour ET le bilan QUIT) - la présence des
 * deux, reliés par une ligne, donnait l'impression trompeuse d'une chute
 * du compte juste avant la fin, ce que l'utilisateur avait interprété
 * comme "les comptes qui retombent à 0" avant la sortie.</li>
 * </ol>
 */
class StatsServiceWealthOverTimeTest
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

	private Map<String, List<String>> catalog(final int pModelsPerLevel)
	{
		final Map<String, List<String>> byLevel = new LinkedHashMap<>();
		for (final String level : List.of("faible", "moyenne", "forte", "tresforte")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		{
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < pModelsPerLevel; i++)
				ids.add(level + "_modele_" + i); //$NON-NLS-1$
			byLevel.put(level, ids);
		}
		return byLevel;
	}

	/**
	 * Un seul tour, un seul joueur : le tour 1 pose à la fois un point
	 * d'ouverture (TURN, valeur 0) ET, immédiatement après sans aucun
	 * "TURN" intermédiaire, un bilan QUIT au MÊME tour - exactement le
	 * scénario qui produisait un doublon avant le correctif (une vraie
	 * partie à son dernier tour : les joueurs quittent sans qu'un nouveau
	 * "TURN" ne soit jamais posté derrière).
	 */
	@Test
	void testQuitDeduplicatesSameTurnPointAndSplitsMonetaryFromCards() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 1, "AnimTest", null, //$NON-NLS-1$
				"Test wealth-over-time dedup", "2026-10-04", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int aliceId = sService.addPlayer(gameId, "Alice").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));

		// GameService.recordEvent RECALCULE weakCards/mediumCards/strongCards
		// depuis le VRAI inventaire du joueur suivi par smartphone au moment de
		// l'événement (voir son commentaire détaillé, "calculés ICI à partir du
		// VRAI inventaire du smartphone") - les valeurs passées ici pour ces
		// trois champs sont donc ignorées pour un joueur tracké ; on lit
		// l'inventaire RÉEL avant d'envoyer le QUIT pour calculer la valeur
		// attendue à partir de la même source de vérité, plutôt que de deviner
		// des nombres arbitraires qui seraient de toute façon écrasés.
		final Map<String, Integer> inventory = sService.computePlayerCardInventory(gameId, aliceId);
		int weak = 0, medium = 0, strong = 0;
		for (final Map.Entry<String, Integer> e : inventory.entrySet())
		{
			if (e.getKey().startsWith("faible")) weak += e.getValue(); //$NON-NLS-1$
			else if (e.getKey().startsWith("moyenne")) medium += e.getValue(); //$NON-NLS-1$
			else strong += e.getValue(); // "forte"/"tresforte" repliés dans strongCards, voir le commentaire ci-dessus
		}
		assertTrue(weak + medium + strong > 0, "la main de départ doit contenir au moins une carte"); //$NON-NLS-1$

		// Bilan de fin de partie : 42 jetons faibles (unités monétaires,
		// weakCoinValue=1.0) - jamais écrasés par le serveur (seules les
		// cartes le sont, voir ci-dessus).
		sService.recordEvent(gameId, "Q", aliceId, 0, 0, weak, medium, strong, null, 0, 0, 42, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Game freshGame = sService.getGame(gameId);
		final WealthOverTimeReport report = sStats.computeWealthOverTime(freshGame);
		final PlayerWealthSeries aliceSeries = report.series().stream().filter(s -> s.playerName().equals("Alice")) //$NON-NLS-1$
				.findFirst().orElseThrow();

		assertEquals(1, aliceSeries.points().size(),
				"un seul point doit rester au tour du QUIT, pas un doublon (ouverture de tour + bilan QUIT)"); //$NON-NLS-1$
		final PlayerWealthPoint point = aliceSeries.points().get(0);
		assertEquals(1, point.turn());
		// Monnaie libre : monetaryValue = (weakCoins + 2*medium + 4*strong) / 3
		// = 42/3 = 14 (voir StatsService.computeMonetaryGain, formule
		// historique vérifiée contre l'app Swing d'origine - voir CLAUDE.md).
		assertEquals(14, point.monetaryValue(), "monetaryValue doit refléter UNIQUEMENT les jetons (42/3)"); //$NON-NLS-1$
		final int expectedCardsValue = weak + (2 * medium) + (4 * strong);
		assertEquals(expectedCardsValue, point.cardsValue(),
				"cardsValue doit refléter UNIQUEMENT les cartes du vrai inventaire"); //$NON-NLS-1$
		assertEquals(14 + expectedCardsValue, point.combinedValue(), "combinedValue doit être la somme exacte des deux"); //$NON-NLS-1$
	}

	/**
	 * Contre-épreuve : un joueur qui ne quitte jamais et dont le tour
	 * d'ouverture n'est jamais suivi d'un DEATH/QUIT au même tour doit
	 * conserver son point normal (pas de sur-correction qui supprimerait
	 * des points légitimes à des tours différents).
	 */
	@Test
	void testNormalTurnsAreNeverDeduplicated() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 3, "AnimTest", null, //$NON-NLS-1$
				"Test wealth-over-time pas de doublon", "2026-10-04", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		sService.addPlayer(gameId, "Bob"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Game freshGame = sService.getGame(gameId);
		final WealthOverTimeReport report = sStats.computeWealthOverTime(freshGame);
		final PlayerWealthSeries bobSeries = report.series().stream().filter(s -> s.playerName().equals("Bob")) //$NON-NLS-1$
				.findFirst().orElseThrow();

		assertEquals(3, bobSeries.points().size(), "un point par tour joué (1,2,3), aucun doublon ni suppression"); //$NON-NLS-1$
		assertEquals(List.of(1, 2, 3), bobSeries.points().stream().map(PlayerWealthPoint::turn).toList());
	}
}
