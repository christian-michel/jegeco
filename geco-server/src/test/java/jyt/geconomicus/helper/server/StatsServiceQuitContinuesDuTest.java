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
import jyt.geconomicus.helper.server.StatsService.FinalReport;
import jyt.geconomicus.helper.server.StatsService.PlayerWealth;
import jyt.geconomicus.helper.server.StatsService.PlayerWealthPoint;
import jyt.geconomicus.helper.server.StatsService.PlayerWealthSeries;
import jyt.geconomicus.helper.server.StatsService.WealthOverTimeReport;

/**
 * Vérifie la décision utilisateur du 04/10/2026 ("vaut-il mieux continuer à
 * lui verser le dividende ou arrêter ?" - réponse : "garde-le visible mais
 * distingué, vas-y") : un joueur qui QUITTE une partie libre+strict
 * TRM+smartphone EN COURS DE ROUTE continue de toucher le Dividende
 * Universel à chaque tour, et ses stats (courbe de richesse, rapport final)
 * suivent cette croissance continue plutôt que de rester figées à sa valeur
 * de sortie - voir Player.quit (nouveau champ), Event.java (cas QUIT),
 * Game.computeMoneyMassFromActivePlayersJetons/computeCurrentDU, et
 * StatsService.computeWealthByPlayer/computeWealthOverTime.
 */
class StatsServiceQuitContinuesDuTest
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
	 * Un joueur quitte au tour 1 (pas au dernier tour de la partie, 5 tours
	 * prévus) puis continue de recevoir un WEALTH_CHECKPOINT à chaque tour
	 * suivant (ce que fait désormais l'assistant de fin de tour, voir
	 * app.js/renderStepAllPlayersMoney) - sa série de richesse dans le temps
	 * doit continuer de recevoir un nouveau point à CHAQUE tour, avec une
	 * valeur monétaire qui CROÎT (jamais figée à sa valeur de sortie).
	 */
	@Test
	void testQuitPlayerSeriesKeepsGrowingAfterQuitting() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 5, "AnimTest", null, //$NON-NLS-1$
				"Test DU continu apres QUIT", "2026-10-04", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int aliceId = sService.addPlayer(gameId, "Alice").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));

		// Alice quitte dès le tour 1, bien avant le dernier tour prévu (5) -
		// scénario explicitement demandé par l'utilisateur ("un joueur qui
		// quitte la partie en cours de jeu"), distinct du cas déjà couvert par
		// StatsServiceWealthOverTimeTest (sortie au tout dernier tour).
		sService.recordEvent(gameId, "Q", aliceId, 0, 0, 0, 0, 0, null, 0, 0, 7, 0, 0, 0, 0, 0); //$NON-NLS-1$

		Game freshGame = sService.getGame(gameId);
		assertTrue(freshGame.getPlayers().stream().filter(p -> p.getName().equals("Alice")).findFirst() //$NON-NLS-1$
				.orElseThrow().isQuit(), "Alice doit être marquée quit=true après son QUIT"); //$NON-NLS-1$

		// Quatre tours supplémentaires, chacun avec un WEALTH_CHECKPOINT pour
		// Alice (exactement ce que fait désormais app.js pour un joueur
		// active||quit) - son solde grandit de +1 jeton par tour, simulé ici.
		int weakCoins = 7;
		for (int turn = 2; turn <= 5; turn++)
		{
			weakCoins += 1;
			sService.recordEvent(gameId, "W", aliceId, 0, 0, 0, 0, 0, null, 0, 0, weakCoins, 0, 0, 0, 0, 0); //$NON-NLS-1$
			sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		}

		freshGame = sService.getGame(gameId);
		final WealthOverTimeReport report = sStats.computeWealthOverTime(freshGame);
		final PlayerWealthSeries aliceSeries = report.series().stream().filter(s -> s.playerName().equals("Alice")) //$NON-NLS-1$
				.findFirst().orElseThrow();

		assertEquals(true, aliceSeries.quitEarly(), "la série d'Alice doit être marquée quitEarly=true"); //$NON-NLS-1$
		assertEquals(5, aliceSeries.points().size(),
				"un point par tour (1 à 5), la série doit continuer après le QUIT, jamais s'arrêter"); //$NON-NLS-1$
		assertEquals(List.of(1, 2, 3, 4, 5), aliceSeries.points().stream().map(PlayerWealthPoint::turn).toList());

		// monetaryValue = weakCoins / 3, DIVISION ENTIÈRE (formule historique,
		// voir computeMonetaryGain) - doit AU MOINS NE JAMAIS DÉCROÎTRE au fil
		// des tours, jamais rester figé à sa valeur de sortie (7/3 = 2) :
		// 7,8,9,10,11 jetons -> 2,2,3,3,3 (division entière par 3).
		final List<Integer> monetaryValues = aliceSeries.points().stream().map(PlayerWealthPoint::monetaryValue).toList();
		assertEquals(List.of(2, 2, 3, 3, 3), monetaryValues,
				"la valeur monétaire doit suivre la croissance réelle du DU au fil des tours (division entière par 3), jamais rester figée à 2"); //$NON-NLS-1$
	}

	/**
	 * Vérifie que la richesse "finalisée" (computeFinalReport, moyenne/
	 * médiane/Gini) d'un joueur sorti suit elle aussi sa croissance continue,
	 * SANS JAMAIS compter plusieurs fois le même argent : Alice meurt UNE FOIS
	 * (renaissance), rejoue un peu, PUIS quitte, PUIS continue de recevoir le
	 * DU. Si StatsService.computeWealthByPlayer additionnait naïvement chaque
	 * WEALTH_CHECKPOINT (au lieu d'un suivi par delta), ce total serait
	 * absurdement gonflé - voir le commentaire détaillé dans
	 * computeWealthByPlayer ("openQuitSegmentValue").
	 */
	@Test
	void testFinalReportWealthAccumulatesAcrossDeathAndQuitWithoutDoubleCounting() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 6, "AnimTest", null, //$NON-NLS-1$
				"Test rapport final, mort puis sortie", "2026-10-04", "Ceres", 1, 180, 1.0, false, 4, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int aliceId = sService.addPlayer(gameId, "Alice").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));

		// GameService.recordEvent RECALCULE weakCards/mediumCards/strongCards
		// d'un DEATH/QUIT depuis le VRAI inventaire du joueur suivi par
		// smartphone (voir son commentaire détaillé) - on lit l'inventaire
		// RÉEL juste avant chaque événement pour calculer la contribution
		// "cartes" attendue, plutôt que de deviner des nombres arbitraires qui
		// seraient de toute façon écrasés (même principe que
		// StatsServiceWealthOverTimeTest).
		final Map<String, Integer> invAtDeath = sService.computePlayerCardInventory(gameId, aliceId);
		final int cardsValueAtDeath = cardsValueFromInventory(invAtDeath);

		// Tour 1 : Alice meurt avec 20 jetons déclarés (sa "première vie" se
		// termine ici) - addGain ajoute 20/3=6 + la valeur de ses cartes à son
		// total cumulé. Renaît ensuite avec une main fraîche.
		sService.recordEvent(gameId, "D", aliceId, 0, 0, 0, 0, 0, null, 0, 0, 20, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Map<String, Integer> invAtQuit = sService.computePlayerCardInventory(gameId, aliceId);
		final int cardsValueAtQuit = cardsValueFromInventory(invAtQuit);

		// Tour 2 : Alice quitte avec 7 jetons (sa "seconde vie", qui restera
		// ouverte jusqu'à la fin) - addGain ajoute 7/3=2 + la valeur de sa
		// main fraîche (cardsValueAtQuit), comptée UNE SEULE FOIS ici.
		sService.recordEvent(gameId, "Q", aliceId, 0, 0, 0, 0, 0, null, 0, 0, 7, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		// Tours 3 à 6 : Alice continue de recevoir le DU (+1 jeton/tour),
		// jusqu'à 11 jetons au tour 6 - un WEALTH_CHECKPOINT ne porte JAMAIS
		// de mouvement de cartes (toujours 0, voir Event.java), seule la
		// composante monétaire doit donc continuer de croître ici.
		int weakCoins = 7;
		for (int turn = 3; turn <= 6; turn++)
		{
			weakCoins += 1;
			sService.recordEvent(gameId, "W", aliceId, 0, 0, 0, 0, 0, null, 0, 0, weakCoins, 0, 0, 0, 0, 0); //$NON-NLS-1$
			sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		}

		final Game freshGame = sService.getGame(gameId);
		final FinalReport report = sStats.computeFinalReport(freshGame, false);
		final PlayerWealth aliceWealth = report.playerWealths().stream().filter(p -> p.playerName().equals("Alice")) //$NON-NLS-1$
				.findFirst().orElseThrow();

		assertEquals(true, aliceWealth.quitEarly(), "Alice doit être marquée quitEarly=true dans le rapport final"); //$NON-NLS-1$
		// Vie 1 (mort : 20/3=6 + cardsValueAtDeath) + vie 2 (sortie : 7/3=2 +
		// cardsValueAtQuit, comptée UNE FOIS par addGain au moment du QUIT) +
		// croissance monétaire continue jusqu'au dernier WEALTH_CHECKPOINT
		// (11/3=3, soit +1 par rapport aux 7/3=2 du QUIT) - jamais les cartes
		// comptées une seconde fois (bug trouvé et corrigé en écrivant ce
		// test - voir openQuitSegmentMonetaryValue dans computeWealthByPlayer).
		final int expected = 6 + cardsValueAtDeath + 2 + cardsValueAtQuit + 1;
		assertEquals(expected, aliceWealth.wealth(),
				"la richesse finale doit sommer chaque vie UNE SEULE FOIS (mort + sortie + croissance monétaire continue), sans jamais recompter les cartes"); //$NON-NLS-1$
	}

	/**
	 * BUG TROUVÉ ET CORRIGÉ (04/10/2026, seconde relecture indépendante du
	 * commit 8006d14, AVANT tout push) : le test {@code
	 * testFinalReportWealthAccumulatesAcrossDeathAndQuitWithoutDoubleCounting}
	 * ci-dessus ne pose AUCUN WEALTH_CHECKPOINT pour Alice entre sa
	 * renaissance (tour 1) et son QUIT (tour 2) - un scénario qui, dans un
	 * usage RÉEL, ne se produit QUE si le joueur quitte au tour qui suit
	 * immédiatement sa mort. Or en usage réel normal, un joueur libre+
	 * smartphone reçoit déjà un WEALTH_CHECKPOINT à CHAQUE tour qu'il joue
	 * EN TANT QUE JOUEUR ACTIF (mécanisme pré-existant, voir app.js/
	 * wizFinish) - un joueur qui quitte APRÈS avoir joué plusieurs tours
	 * (le cas le plus courant, pas une exception) accumule donc PLUSIEURS
	 * WEALTH_CHECKPOINT reçus alors qu'il était encore actif, AVANT son
	 * propre événement QUIT. La version d'origine de {@code
	 * computeWealthByPlayer} (cas WEALTH_CHECKPOINT) testait
	 * {@code event.getPlayer().isQuit()} - qui reflète l'état FINAL
	 * (actuel) du joueur, pas son état au moment précis de CET événement -
	 * si bien que CES WEALTH_CHECKPOINT antérieurs au QUIT (reçus quand le
	 * joueur était encore parfaitement actif) étaient eux aussi traités
	 * comme faisant partie de son "segment de sortie", ajoutant leur valeur
	 * EN PLUS de ce que addGain(QUIT) compte déjà intégralement (une valeur
	 * ABSOLUE qui inclut déjà toute la croissance jusqu'au QUIT, y compris
	 * celle déjà vue par ces WEALTH_CHECKPOINT antérieurs) - un DOUBLE
	 * COMPTAGE. Mesuré par une campagne HTTP réelle (voir le rapport de
	 * l'agent de contrôle, 04/10/2026) : +64 unités (+5,8%) sur la richesse
	 * finale d'un joueur ayant joué 5 tours actifs avant de quitter.
	 * Corrigé en suivant la chronologie RÉELLE du rejeu (un ensemble dédié,
	 * rempli uniquement au moment où le QUIT de CE joueur est effectivement
	 * traité dans la boucle, jamais avant) plutôt que l'état final du
	 * joueur - voir {@code quitSoFarInReplay} dans
	 * {@code computeWealthByPlayer}.
	 */
	@Test
	void testFinalReportDoesNotDoubleCountWealthCheckpointsReceivedWhileStillActiveBeforeQuitting() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 6, "AnimTest", null, //$NON-NLS-1$
				"Test non-double-comptage WEALTH_CHECKPOINT pre-QUIT", "2026-10-04", "Ceres", 1, 180, 1.0, false, 4, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				true, 0.5);
		final int gameId = game.getId();
		final int bobId = sService.addPlayer(gameId, "Bob").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));

		// Tours 1 et 2 : Bob joue ACTIVEMENT (jamais mort, jamais sorti) -
		// reçoit un WEALTH_CHECKPOINT à CHAQUE tour, exactement comme n'importe
		// quel joueur libre+smartphone en cours de partie (mécanisme PAS
		// spécifique aux joueurs sortis). Ces deux WEALTH_CHECKPOINT ne doivent
		// RIEN ajouter à computeWealthByPlayer (Bob encore actif, "pas encore
		// comptabilisé tant qu'actif" - voir la Javadoc de cette méthode).
		sService.recordEvent(gameId, "W", bobId, 0, 0, 0, 0, 0, null, 0, 0, 10, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "W", bobId, 0, 0, 0, 0, 0, null, 0, 0, 13, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		// Tour 3 : Bob quitte enfin, avec 16 jetons (sa valeur ABSOLUE
		// cumulée, qui inclut déjà toute la croissance vue par les deux
		// WEALTH_CHECKPOINT ci-dessus - addGain(QUIT) la compte UNE SEULE FOIS).
		final Map<String, Integer> invAtQuit = sService.computePlayerCardInventory(gameId, bobId);
		final int cardsValueAtQuit = cardsValueFromInventory(invAtQuit);
		sService.recordEvent(gameId, "Q", bobId, 0, 0, 0, 0, 0, null, 0, 0, 16, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		// Tour 4 : Bob continue de toucher le DU (post-QUIT) - 19 jetons.
		sService.recordEvent(gameId, "W", bobId, 0, 0, 0, 0, 0, null, 0, 0, 19, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final Game freshGame = sService.getGame(gameId);
		final FinalReport report = sStats.computeFinalReport(freshGame, false);
		final PlayerWealth bobWealth = report.playerWealths().stream().filter(p -> p.playerName().equals("Bob")) //$NON-NLS-1$
				.findFirst().orElseThrow();

		// Attendu : 16/3=5 (QUIT, valeur ABSOLUE incluant déjà toute la
		// croissance pré-QUIT) + cardsValueAtQuit + (19/3=6 - 16/3=5 = 1, la
		// SEULE croissance post-QUIT) = 5 + cardsValueAtQuit + 1 - JAMAIS les
		// WEALTH_CHECKPOINT à 10 et 13 jetons (reçus alors que Bob était
		// encore actif), qui ajouteraient à tort 10/3=3 (voire plus) si le
		// bug corrigé ci-dessus réapparaissait.
		final int expected = 5 + cardsValueAtQuit + 1;
		assertEquals(expected, bobWealth.wealth(), "les WEALTH_CHECKPOINT recus par Bob AVANT son QUIT (alors qu'il " //$NON-NLS-1$
				+ "etait encore actif) ne doivent JAMAIS etre comptes - seule la valeur ABSOLUE au QUIT et la " //$NON-NLS-1$
				+ "croissance APRES le QUIT doivent compter"); //$NON-NLS-1$
	}

	/** (faible + 2×moyenne + 4×forte/tresforte) - même formule que computeCardsGain, facteur technologique=1 ici. */
	private int cardsValueFromInventory(final Map<String, Integer> pInventory)
	{
		int total = 0;
		for (final Map.Entry<String, Integer> e : pInventory.entrySet())
		{
			if (e.getKey().startsWith("faible")) total += e.getValue(); //$NON-NLS-1$
			else if (e.getKey().startsWith("moyenne")) total += 2 * e.getValue(); //$NON-NLS-1$
			else total += 4 * e.getValue(); // "forte"/"tresforte"
		}
		return total;
	}
}
