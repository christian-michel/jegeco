package jyt.geconomicus.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Vérifie la formule de "rotation des valeurs" (Game.cardPriceInDU) - remonté
 * par l'utilisateur (22/09/2026) : "quand on arrive à faire un carré de
 * cartes très fortes... il peut être intéressant de mettre en place une
 * rotation des valeurs, d'autant que cela est conforme à la règle du jeu"
 * (voir geconomicus.glibre.org/rules.html, "révolution économique"). À
 * chaque carré réellement bouclé depuis le niveau physique le plus haut
 * ("tresforte") vers "faible" (voir GameService.checkAndCashInSquares),
 * Game.revolutionCount augmente de 1 - ce test vérifie UNIQUEMENT la formule
 * de prix qui en découle, pas le déclenchement lui-même (couvert par
 * GameServiceSquareEncashmentTest, qui a besoin d'un GameService/JPA réel).
 * <p>
 * Ne touche JAMAIS l'ordre physique de tirage des cartes (toujours
 * faible->moyenne->forte->tresforte, y compris après une révolution) - seule
 * la valeur marchande de chaque niveau tourne.
 */
class CardValueRevolutionTest
{
	private Game newGame()
	{
		// weakCardValueInDU par défaut = 0.5 (voir son commentaire dans Game.java) -
		// barème de base attendu : faible=0,5 / moyenne=1 / forte=2 / tresforte=4.
		return new Game(Game.MONEY_LIBRE, 12, "animateur", "a@b.c", "partie de test", "today", "ici", 1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	}

	@Test
	void testNoRevolutionKeepsTheNormalScale()
	{
		final Game game = newGame();
		assertEquals(0, game.getRevolutionCount(), "aucune révolution au départ"); //$NON-NLS-1$
		assertEquals(0.5, game.cardPriceInDU("faible"), 1e-9); //$NON-NLS-1$
		assertEquals(1.0, game.cardPriceInDU("moyenne"), 1e-9); //$NON-NLS-1$
		assertEquals(2.0, game.cardPriceInDU("forte"), 1e-9); //$NON-NLS-1$
		assertEquals(4.0, game.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}

	@Test
	void testOneRevolutionMakesFaibleTheMostExpensive()
	{
		final Game game = newGame();
		game.setRevolutionCount(1);
		// "les cartes faibles deviennent les nouvelles plus fortes" (demande
		// utilisateur, verbatim) : faible prend le prix que "tresforte" avait
		// avant la révolution.
		assertEquals(4.0, game.cardPriceInDU("faible"), 1e-9); //$NON-NLS-1$
		assertEquals(0.5, game.cardPriceInDU("moyenne"), 1e-9); //$NON-NLS-1$
		assertEquals(1.0, game.cardPriceInDU("forte"), 1e-9); //$NON-NLS-1$
		assertEquals(2.0, game.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}

	@Test
	void testSecondRevolutionContinuesTheRotation()
	{
		final Game game = newGame();
		game.setRevolutionCount(2);
		assertEquals(2.0, game.cardPriceInDU("faible"), 1e-9); //$NON-NLS-1$
		assertEquals(4.0, game.cardPriceInDU("moyenne"), 1e-9); //$NON-NLS-1$
		assertEquals(0.5, game.cardPriceInDU("forte"), 1e-9); //$NON-NLS-1$
		assertEquals(1.0, game.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}

	@Test
	void testThirdRevolutionContinuesTheRotation()
	{
		final Game game = newGame();
		game.setRevolutionCount(3);
		assertEquals(1.0, game.cardPriceInDU("faible"), 1e-9); //$NON-NLS-1$
		assertEquals(2.0, game.cardPriceInDU("moyenne"), 1e-9); //$NON-NLS-1$
		assertEquals(4.0, game.cardPriceInDU("forte"), 1e-9); //$NON-NLS-1$
		assertEquals(0.5, game.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}

	@Test
	void testFourthRevolutionReturnsToTheNormalScale()
	{
		// Cycle fermé : après 4 révolutions (autant que de niveaux), le
		// barème redevient EXACTEMENT celui de départ - jamais une valeur
		// qui s'envole ou s'effondre sans fin sur une partie très longue.
		final Game game = newGame();
		game.setRevolutionCount(4);
		assertEquals(0.5, game.cardPriceInDU("faible"), 1e-9); //$NON-NLS-1$
		assertEquals(1.0, game.cardPriceInDU("moyenne"), 1e-9); //$NON-NLS-1$
		assertEquals(2.0, game.cardPriceInDU("forte"), 1e-9); //$NON-NLS-1$
		assertEquals(4.0, game.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}

	@Test
	void testRotationRespectsACustomBasePrice()
	{
		// weakCardValueInDU réglable par partie (voir son commentaire) - la
		// rotation doit continuer à s'appliquer sur CETTE base, jamais une
		// constante figée à 0.5.
		final Game game = newGame();
		game.setWeakCardValueInDU(0.2);
		game.setRevolutionCount(1);
		assertEquals(1.6, game.cardPriceInDU("faible"), 1e-9); // 0.2*8 //$NON-NLS-1$
		assertEquals(0.2, game.cardPriceInDU("moyenne"), 1e-9); //$NON-NLS-1$
		assertEquals(0.4, game.cardPriceInDU("forte"), 1e-9); //$NON-NLS-1$
		assertEquals(0.8, game.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}
}
