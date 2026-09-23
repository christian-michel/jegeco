package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.CardSquareEvent;
import jyt.geconomicus.helper.Game;

/**
 * Vérifie le mécanisme du carré (GameService.checkAndCashInSquares), en
 * particulier son évolution du 22/09/2026 ("rotation des valeurs" - suite
 * utilisateur : "quand on arrive à faire un carré de cartes très fortes... il
 * peut être intéressant de mettre en place une rotation des valeurs, d'autant
 * que cela est conforme à la règle du jeu", voir geconomicus.glibre.org/
 * rules.html et Game.revolutionCount/cardPriceInDU).
 * <p>
 * Historique : ce fichier vérifiait auparavant le correctif du même jour pour
 * un bug DIFFÉRENT ("les carrés s'emballent et ne se comptent pas, le joueur
 * peut arriver à 5 cartes sans avoir déclenché de carré") - un carré au
 * niveau physique le plus haut ("tresforte") restait alors bloqué à vie
 * (aucune promotion possible, par conception), et ce blocage empêchait à
 * tort l'encaissement de carrés à des niveaux inférieurs. La rotation des
 * valeurs introduite ensuite rend ce scénario obsolète : "tresforte" n'est
 * plus un cul-de-sac, un carré à ce niveau boucle désormais vers "faible" -
 * ce fichier vérifie donc maintenant CE nouveau comportement à la place.
 */
class GameServiceSquareEncashmentTest
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

	/** Catalogue synthétique - un seul modèle par niveau intermédiaire suffit
	 * ici, contrairement au catalogue plus large utilisé ailleurs pour tester
	 * une vraie cascade organique : ce test place les cartes directement via
	 * recordTransaction (voir son commentaire, plus bas), aucune cascade
	 * naturelle n'est nécessaire. */
	private Map<String, List<String>> smallCatalog()
	{
		final Map<String, List<String>> catalog = new LinkedHashMap<>();
		catalog.put("faible", List.of("faible_0", "faible_1", "faible_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("moyenne", List.of("moyenne_0", "moyenne_1", "moyenne_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("forte", List.of("forte_0", "forte_1", "forte_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("tresforte", List.of("tresforte_0")); //$NON-NLS-1$ //$NON-NLS-2$
		return catalog;
	}

	/** Prépare une partie libre+smartphone à 2 joueurs, prête à recevoir des
	 * cartes via recordTransaction (voir testStuckTopLevelSquareNeverBlocksALowerLevelSquare
	 * pour le raisonnement complet sur cette technique). */
	private int[] setUpGameWithTwoPlayers() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimSquareTest", null, //$NON-NLS-1$
				"test rotation des valeurs", "2026-09-22", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int p0 = sService.addPlayer(gameId, "P0").getId(); //$NON-NLS-1$
		final int p1 = sService.addPlayer(gameId, "P1").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog());
		sService.recordEvent(gameId, "W", p1, 0, 0, 0, 0, 0, null, 0, 0, 1_000_000, 0, 0, 0, 0, 0); //$NON-NLS-1$

		return new int[] { gameId, p0, p1 };
	}

	@Test
	void testTopLevelSquareLoopsBackToWeakInsteadOfBeingStuck() throws Exception
	{
		final int[] ids = setUpGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p0 = ids[1];
		final int p1 = ids[2];

		assertEquals(0, sService.getGame(gameId).getRevolutionCount(), "aucune révolution au départ"); //$NON-NLS-1$

		// Place directement 4 "tresforte_0" dans la main de p1 -
		// recordTransaction ne revérifie jamais côté vendeur que p0 possède
		// réellement la carte (contrairement à recordCardSwap, réservé au
		// troc), ce qui permet de construire ce scénario de façon
		// déterministe sans dépendre d'une cascade aléatoire.
		for (int i = 0; i < 4; i++)
			sService.recordTransaction(gameId, p0, p1, "tresforte_0", "tresforte", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$ //$NON-NLS-2$
					"tresforte-" + gameId + "-" + i, System.currentTimeMillis() + 60_000); //$NON-NLS-1$

		final List<CardSquareEvent> squares = sService.checkAndCashInSquares(gameId, p1);
		assertEquals(1, squares.size(), "un seul carré attendu (4 tresforte_0, rien d'autre)"); //$NON-NLS-1$
		final CardSquareEvent square = squares.get(0);
		assertTrue(square.isTriggeredRevolution(), "un carré tresforte réellement bouclé doit déclencher une révolution"); //$NON-NLS-1$
		assertEquals("tresforte", square.getCashedLevel()); //$NON-NLS-1$
		assertEquals("faible", square.getPromotedLevel(), //$NON-NLS-1$
				"la carte de récompense doit boucler vers le niveau faible, pas rester bloquée"); //$NON-NLS-1$
		assertEquals(1, square.getRevolutionCountAfter());

		final Game gameAfter = sService.getGame(gameId);
		assertEquals(1, gameAfter.getRevolutionCount(), "la révolution doit être persistée sur la partie"); //$NON-NLS-1$

		// Avec un seul modèle "tresforte" dans ce catalogue synthétique, les 4
		// cartes de remplacement piochées après le carré retombent forcément
		// sur ce même modèle (aucun autre disponible dans sa pioche) - le
		// compte de tresforte_0 reste donc à 4 (un "cas extrême assumé" déjà
		// documenté ailleurs dans ce fichier pour les pioches à un seul
		// modèle, pas un signe que le carré est resté bloqué). Ce qui compte
		// ici : le joueur a bien REÇU une carte "faible" en récompense (la
		// preuve concrète que le carré a été réellement traité, contrairement
		// au bug initial où rien ne se passait du tout).
		final Map<String, Integer> inventoryAfter = sService.computePlayerCardInventory(gameId, p1);
		final boolean receivedAFaibleCard = inventoryAfter.entrySet().stream()
				.anyMatch(e -> e.getKey().startsWith("faible_") && (e.getValue() > 0)); //$NON-NLS-1$
		assertTrue(receivedAFaibleCard,
				"le joueur doit avoir reçu une carte faible en récompense du carré tresforte bouclé"); //$NON-NLS-1$
	}

	@Test
	void testMultipleSimultaneousSquaresAcrossLevelsAreAllCashedIn() throws Exception
	{
		final int[] ids = setUpGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p0 = ids[1];
		final int p1 = ids[2];

		// Place 4 "tresforte_0" ET 4 "forte_0" en même temps - avant la
		// rotation des valeurs, ce scénario exact laissait "forte_0"
		// définitivement bloqué (voir le commentaire de tête de fichier).
		for (int i = 0; i < 4; i++)
			sService.recordTransaction(gameId, p0, p1, "tresforte_0", "tresforte", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$ //$NON-NLS-2$
					"tresforte-" + gameId + "-" + i, System.currentTimeMillis() + 60_000); //$NON-NLS-1$
		for (int i = 0; i < 4; i++)
			sService.recordTransaction(gameId, p0, p1, "forte_0", "forte", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$ //$NON-NLS-2$
					"forte-" + gameId + "-" + i, System.currentTimeMillis() + 60_000); //$NON-NLS-1$

		sService.checkAndCashInSquares(gameId, p1);

		// tresforte_0 reste à 4 (churn attendu, un seul modèle dans ce
		// catalogue synthétique - voir le commentaire détaillé du test
		// précédent) : ce qui compte ici est que la révolution ait bien eu
		// lieu (le mécanisme a tourné) ET que forte_0, à un niveau
		// INFÉRIEUR, ne soit pas resté bloqué par la présence du carré
		// tresforte - exactement le scénario du bug initial du 22/09/2026.
		assertTrue(sService.getGame(gameId).getRevolutionCount() >= 1,
				"la révolution du carré tresforte_0 doit avoir eu lieu"); //$NON-NLS-1$
		final Map<String, Integer> inventoryAfter = sService.computePlayerCardInventory(gameId, p1);
		assertTrue(inventoryAfter.getOrDefault("forte_0", 0) < 4, //$NON-NLS-1$
				"le carré forte_0 aurait dû être encaissé, pas resté bloqué par le carré tresforte"); //$NON-NLS-1$
	}

	@Test
	void testFourConsecutiveRevolutionsCycleThePriceScaleBackToNormal() throws Exception
	{
		final int[] ids = setUpGameWithTwoPlayers();
		final int gameId = ids[0];
		final int p0 = ids[1];
		final int p1 = ids[2];

		// Provoque 4 révolutions successives (une par carré tresforte
		// réellement bouclé, un appel à checkAndCashInSquares par révolution
		// - voir le garde-fou "une seule révolution par appel" dans
		// GameService). Game.revolutionCount lui-même ne "boucle" JAMAIS - il
		// compte simplement le nombre total de révolutions survenues depuis
		// le début de la partie (une trace, jamais remise à 0). C'est la
		// FORMULE de prix (Game.cardPriceInDU, voir sa vérification détaillée
		// dans CardValueRevolutionTest côté geco-engine) qui, elle, retombe
		// sur le barème normal tous les 4 révolutions (modulo interne à la
		// formule) - vérifié ici en conditions réelles (via GameService, pas
		// seulement Game seul).
		for (int rev = 1; rev <= 4; rev++)
		{
			for (int i = 0; i < 4; i++)
				sService.recordTransaction(gameId, p0, p1, "tresforte_0", "tresforte", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$ //$NON-NLS-2$
						"rev" + gameId + "-" + rev + "-" + i, System.currentTimeMillis() + 60_000); //$NON-NLS-1$ //$NON-NLS-2$
			sService.checkAndCashInSquares(gameId, p1);
		}

		final Game gameAfter = sService.getGame(gameId);
		assertEquals(4, gameAfter.getRevolutionCount(), "4 révolutions doivent avoir eu lieu, une par appel"); //$NON-NLS-1$
		assertEquals(0.5, gameAfter.cardPriceInDU("faible"), 1e-9, //$NON-NLS-1$
				"après 4 révolutions, le barème doit être revenu exactement à la normale"); //$NON-NLS-1$
		assertEquals(4.0, gameAfter.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}
}
