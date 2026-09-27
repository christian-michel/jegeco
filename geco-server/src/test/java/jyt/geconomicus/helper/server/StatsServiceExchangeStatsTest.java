package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import jyt.geconomicus.helper.Transaction;
import jyt.geconomicus.helper.server.StatsService.ExchangeAndMoneyReport;
import jyt.geconomicus.helper.server.StatsService.ExchangeStats;
import jyt.geconomicus.helper.server.StatsService.MoneyMassDetailPoint;
import jyt.geconomicus.helper.server.StatsService.MoneyMassDetailReport;
import jyt.geconomicus.helper.server.StatsService.PlayerExchangeStat;

/**
 * Vérifie {@link StatsService#computeExchangeAndMoneyReport} - demandé par un
 * utilisateur (27/09/2026) : "connaître le nombre global d'échanges au cours
 * de la partie... la répartition de ces échanges (dans le temps au cours des
 * tours - et parmi les joueurs)... la masse monétaire globale en circulation
 * (création et destruction)... le ratio masse monétaire globale / nb de
 * joueurs à chaque tour".
 * <p>
 * Scénario DÉTERMINISTE (pas de hasard, contrairement à
 * {@code GameServiceFullGameSimulationTest}) : chaque transaction est
 * construite explicitement avec sa valeur exacte, pour pouvoir vérifier des
 * totaux PRÉCIS plutôt que de simples invariants de conservation - c'est ici
 * l'arithmétique même du nouveau service qui est sous test, pas le moteur de
 * jeu.
 */
class StatsServiceExchangeStatsTest
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
	 * Remonte le solde de chaque joueur BIEN au-dessus du prix actuel d'une
	 * carte (voir GameService.levelValue : en monnaie libre, ce prix dépend du
	 * DU COURANT, jamais des weakCoins passés à recordTransaction). Mesuré
	 * dynamiquement à chaque appel plutôt qu'une constante fixe devinée à
	 * l'avance : la masse monétaire (donc le DU) est recalculée à CHAQUE tour
	 * en mode strict TRM à partir des jetons réels des joueurs (voir
	 * Game.computeMoneyMassFromActivePlayersJetons) - un solde fixe boosté une
	 * fois se retrouverait donc lui-même réinjecté dans la masse au tour
	 * suivant, avec un DU qui grandit d'autant, jusqu'à dépasser ce même
	 * solde fixe (observé empiriquement en écrivant ce test). Un multiple
	 * large du DU courant reste, lui, toujours devant le prix réel (au plus
	 * 4 × DU pour une carte "tresforte", voir CLAUDE.md) quelle que soit son
	 * évolution.
	 */
	private void boostAllBalances(final int pGameId, final List<Integer> pPlayerIds) throws Exception
	{
		final int currentDu = sService.getGame(pGameId).computeCurrentDU();
		final int boost = (int) Math.min(Integer.MAX_VALUE / 4L, (100L * currentDu) + 100_000L);
		for (final int playerId : pPlayerIds)
			sService.recordEvent(pGameId, "W", playerId, 0, 0, 0, 0, 0, null, 0, 0, boost, 0, 0, 0, 0, 0); //$NON-NLS-1$
	}

	/** Un modèle de carte "faible" que ce joueur possède réellement (transaction valide). */
	private String aWeakCardHeldBy(final int gameId, final int playerId)
	{
		final Map<String, Integer> inventory = sService.computePlayerCardInventory(gameId, playerId);
		return inventory.entrySet().stream().filter(e -> e.getKey().startsWith("faible") && (e.getValue() > 0)) //$NON-NLS-1$
				.map(Map.Entry::getKey).findFirst()
				.orElseThrow(() -> new AssertionError("Aucune carte faible détenue par le joueur " + playerId)); //$NON-NLS-1$
	}

	@Test
	void testExchangeStatsMatchHandTrackedGroundTruth() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 3, "AnimTest", null, //$NON-NLS-1$
				"Test exchange-stats", "2026-09-27", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();

		final int aliceId = sService.addPlayer(gameId, "Alice").getId(); //$NON-NLS-1$
		final int bobId = sService.addPlayer(gameId, "Bob").getId(); //$NON-NLS-1$
		final int carolId = sService.addPlayer(gameId, "Carol").getId(); //$NON-NLS-1$
		final List<Integer> playerIds = List.of(aliceId, bobId, carolId);

		// Premier tour + mise en place (4 cartes + jetons de départ, voir
		// GameService.dealStartingHandsForLibreIfNeeded) - même séquence que
		// GameServiceFullGameSimulationTest.
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));

		// Solde TRÈS confortable pour chaque joueur (voir GameService.levelValue :
		// en monnaie libre, le prix réel d'une carte dépend du DU courant, pas
		// des weakCoins passés à recordTransaction - inutile donc de deviner un
		// prix à l'avance, on s'assure juste que personne ne soit jamais à
		// court). Un montant énorme (1 million) est nécessaire : avec
		// nbTurnsPlanned=3 (espérance de vie simulée ev=24 ans, volontairement
		// minuscule pour que ce test reste rapide), le taux de croissance TRM
		// composé sur 8 ans est ÉNORME (voir Game.computeDuGrowthRatePerTurn) -
		// le DU, et donc le prix d'une carte, explose après un seul recalcul de
		// la masse monétaire (au TOUR suivant, mode strict TRM). Reboosté APRÈS
		// CHAQUE tour, pas seulement au départ, pour rester devant cette
		// inflation à chaque tour. WEALTH_CHECKPOINT ("W") n'a par ailleurs
		// aucun effet sur la masse monétaire globale elle-même (voir Event.java) :
		// seule la masse RECALCULÉE au prochain TOUR/MORT (mode strict TRM) en
		// tiendra compte, ce qui est exactement ce qu'on veut vérifier ci-dessous.
		boostAllBalances(gameId, playerIds);

		// --- Tour 1 : deux échanges. ---
		sService.recordTransaction(gameId, aliceId, bobId, aWeakCardHeldBy(gameId, aliceId), "faible", 0, 0, 0, 0, 0, //$NON-NLS-1$
				0, "nonce-turn1-a", System.currentTimeMillis() + 60_000); //$NON-NLS-1$
		sService.recordTransaction(gameId, bobId, carolId, aWeakCardHeldBy(gameId, bobId), "faible", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$
				"nonce-turn1-b", System.currentTimeMillis() + 60_000); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		boostAllBalances(gameId, playerIds);

		// --- Tour 2 : un seul échange. ---
		sService.recordTransaction(gameId, carolId, aliceId, aWeakCardHeldBy(gameId, carolId), "faible", 0, 0, 0, 0, //$NON-NLS-1$
				0, 0, "nonce-turn2-a", System.currentTimeMillis() + 60_000); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		boostAllBalances(gameId, playerIds);

		// --- Tour 3 : aucun échange (doit apparaître avec count=0, pas absent).
		// Pas de nouveau "T" ici : le tour 3 est déjà EN COURS (celui ouvert par
		// le "T" juste au-dessus) - en ajouter un ferait avancer au tour 4 et
		// décalerait tout le test. ---

		final Game freshGame = sService.getGame(gameId);
		final List<Transaction> transactions = sService.listTransactions(gameId);
		assertEquals(3, transactions.size(), "les 3 transactions doivent toutes être persistées"); //$NON-NLS-1$

		// Vérité terrain calculée ICI, indépendamment de StatsService, à partir
		// des VRAIES transactions persistées (leur valeur exacte dépend du DU
		// courant au moment de chacune, non prévisible à l'avance en monnaie
		// libre - voir le commentaire sur "W" ci-dessus - mais chaque
		// Transaction.totalCoinsValue() est la source de vérité, la même que
		// celle utilisée par le moteur pour tout le reste).
		final Map<Integer, int[]> expectedByTurn = new java.util.TreeMap<>();
		for (int t = 1; t <= 3; t++)
			expectedByTurn.put(t, new int[2]);
		final Map<String, int[]> expectedByPlayer = new java.util.LinkedHashMap<>();
		for (final String name : List.of("Alice", "Bob", "Carol")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			expectedByPlayer.put(name, new int[2]);
		int expectedGlobalCount = 0;
		int expectedGlobalValue = 0;
		final List<Integer> expectedSortedValues = new ArrayList<>();
		for (final Transaction tx : transactions)
		{
			final int value = tx.totalCoinsValue();
			assertTrue(value > 0, "chaque transaction doit avoir une valeur réelle positive"); //$NON-NLS-1$
			expectedGlobalCount++;
			expectedGlobalValue += value;
			expectedSortedValues.add(value);
			final int[] turnBucket = expectedByTurn.get(tx.getTurnNumber());
			turnBucket[0]++;
			turnBucket[1] += value;
			final int[] buyerBucket = expectedByPlayer.get(tx.getBuyer().getName());
			buyerBucket[0]++;
			buyerBucket[1] += value;
			final int[] sellerBucket = expectedByPlayer.get(tx.getSeller().getName());
			sellerBucket[0]++;
			sellerBucket[1] += value;
		}

		final ExchangeAndMoneyReport report = sStats.computeExchangeAndMoneyReport(freshGame, transactions);
		assertTrue(report.applicable(), "libre + smartphone doit être applicable"); //$NON-NLS-1$

		final ExchangeStats stats = report.exchangeStats();
		assertEquals(expectedGlobalCount, stats.globalCount());
		assertEquals(expectedGlobalValue, stats.globalValue());
		assertEquals(Math.round((expectedGlobalValue / (double) expectedGlobalCount) * 10) / 10.0,
				stats.averageValuePerExchange(), 0.001);
		java.util.Collections.sort(expectedSortedValues);
		final int n = expectedSortedValues.size();
		final double expectedMedianValue = (n % 2 == 1) ? expectedSortedValues.get(n / 2)
				: (expectedSortedValues.get((n / 2) - 1) + expectedSortedValues.get(n / 2)) / 2.0;
		assertEquals(Math.round(expectedMedianValue * 10) / 10.0, stats.medianValuePerExchange(), 0.001);

		// Répartition par tour : présente pour les 3 tours joués, y compris le
		// tour 3 sans aucun échange (count=0, pas absent).
		assertEquals(3, stats.byTurn().size(), "un point par tour joué (1, 2, 3)"); //$NON-NLS-1$
		for (int i = 0; i < 3; i++)
		{
			final int turn = i + 1;
			final int[] expected = expectedByTurn.get(turn);
			assertEquals(turn, stats.byTurn().get(i).turn());
			assertEquals(expected[0], stats.byTurn().get(i).count(), "tour " + turn); //$NON-NLS-1$
			assertEquals(expected[1], stats.byTurn().get(i).totalValue(), "tour " + turn); //$NON-NLS-1$
		}
		assertEquals(0, stats.byTurn().get(2).count(), "tour 3 sans échange doit apparaître à 0, pas être absent"); //$NON-NLS-1$
		// Moyenne/médiane des comptes par tour : [2, 1, 0] triés [0,1,2] -> médiane 1.
		assertEquals(1.0, stats.averageCountPerTurn(), 0.001);
		assertEquals(1.0, stats.medianCountPerTurn(), 0.001);

		// Répartition par joueur : chaque transaction compte pour l'acheteur ET
		// le vendeur (voir la Javadoc de PlayerExchangeStat).
		final Map<String, PlayerExchangeStat> byName = new java.util.HashMap<>();
		for (final PlayerExchangeStat p : stats.byPlayer())
			byName.put(p.playerName(), p);
		for (final Map.Entry<String, int[]> e : expectedByPlayer.entrySet())
		{
			assertEquals(e.getValue()[0], byName.get(e.getKey()).count(), e.getKey() + " : count"); //$NON-NLS-1$
			assertEquals(e.getValue()[1], byName.get(e.getKey()).totalValue(), e.getKey() + " : totalValue"); //$NON-NLS-1$
		}
		// Chaque joueur a participé à exactement 2 échanges dans ce scénario
		// (Alice : vend tx1 + achète tx3 ; Bob : achète tx1 + vend tx2 ; Carol :
		// achète tx2 + vend tx3).
		for (final int[] v : expectedByPlayer.values())
			assertEquals(2, v[0]);
		// Somme des valeurs par joueur = 2x la valeur globale (chaque
		// transaction comptée deux fois, une par participant).
		final int sumPlayerValues = byName.values().stream().mapToInt(PlayerExchangeStat::totalValue).sum();
		assertEquals(2 * stats.globalValue(), sumPlayerValues);

		// --- Masse monétaire détaillée : un point par tour joué, delta
		// cohérent avec la masse réelle observée (dérivée, jamais recalculée
		// séparément - voir la Javadoc de MoneyMassDetailPoint). ---
		final MoneyMassDetailReport massDetail = report.moneyMassDetail();
		assertEquals(3, massDetail.points().size());
		int previousMass = 0;
		for (final MoneyMassDetailPoint point : massDetail.points())
		{
			assertEquals(point.moneyMass() - previousMass, point.massDelta(),
					"tour " + point.turn() + " : massDelta doit être exactement mass(t) - mass(t-1)"); //$NON-NLS-1$ //$NON-NLS-2$
			assertEquals(3, point.activePlayers(), "les 3 joueurs restent actifs tout du long"); //$NON-NLS-1$
			final double expectedRatio = Math.round((point.moneyMass() / 3.0) * 10) / 10.0;
			assertEquals(expectedRatio, point.massPerPlayer(), 0.001);
			previousMass = point.moneyMass();
		}
		assertEquals(freshGame.getMoneyMass(), previousMass, "le dernier point doit égaler la masse actuelle du jeu"); //$NON-NLS-1$

		final List<Integer> deltas = massDetail.points().stream().map(MoneyMassDetailPoint::massDelta).sorted()
				.toList();
		final double expectedAvgDelta = Math.round(deltas.stream().mapToInt(Integer::intValue).average().orElse(0) * 10)
				/ 10.0;
		assertEquals(expectedAvgDelta, massDetail.averageMassDelta(), 0.001);
	}

	@Test
	void testNotApplicableForTroc() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_TROC, 3, "AnimTest", null, "Test troc non applicable", //$NON-NLS-1$ //$NON-NLS-2$
				"2026-09-27", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		sService.addPlayer(gameId, "Alice"); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Game freshGame = sService.getGame(gameId);
		final ExchangeAndMoneyReport report = sStats.computeExchangeAndMoneyReport(freshGame,
				sService.listTransactions(gameId));
		assertFalse(report.applicable(), "le troc n'a jamais de valeur monétaire, jamais applicable"); //$NON-NLS-1$
		assertNull(report.exchangeStats());
		assertNull(report.moneyMassDetail());
	}

	@Test
	void testNotApplicableForClassicGameWithoutSmartphone() throws Exception
	{
		// Monnaie libre, mais SANS dealStartingHandsForLibreIfNeeded (jamais
		// appelé) : aucun joueur n'a de startingCardsJson - partie "classique"
		// (animateur seul), même discriminant que Event.isSmartphoneTrackedGame.
		final Game game = sService.createGame(Game.MONEY_LIBRE, 3, "AnimTest", null, "Test libre classique", //$NON-NLS-1$ //$NON-NLS-2$
				"2026-09-27", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		sService.addPlayer(gameId, "Alice"); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Game freshGame = sService.getGame(gameId);
		final ExchangeAndMoneyReport report = sStats.computeExchangeAndMoneyReport(freshGame,
				sService.listTransactions(gameId));
		assertFalse(report.applicable(), "une partie libre classique (sans smartphone) n'a pas de vraies Transaction"); //$NON-NLS-1$
	}
}
