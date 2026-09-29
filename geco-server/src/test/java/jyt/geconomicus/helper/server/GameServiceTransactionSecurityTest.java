package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/**
 * Régression de sécurité (29/09/2026) : une transaction carte-contre-jetons
 * en monnaie DETTE suivie par smartphone ne doit JAMAIS pouvoir se faire
 * gratuitement - remonté par une relecture indépendante de la campagne de
 * test du 27-28/09/2026 (voir docs/03-architecture-technique.md, entrée du
 * 27-28/09/2026), confirmé par l'utilisateur le 29/09/2026 : "que l'on soit
 * en partie monnaie dette classique ou en partie monnaie dette avec
 * smartphone, la transaction doit toujours se faire contre autre chose. Elle
 * ne peut pas être gratuite."
 * <p>
 * Cause : {@code GameService.recordTransactionUnlocked} sautait entièrement
 * la vérification de solde dès que le client envoyait un prix nul
 * ({@code else if (price > 0)}) - reproduit en HTTP réel avant correctif (un
 * acheteur obtenait la carte d'un vendeur pour 0 jeton, 201, inventaire du
 * vendeur bien décrémenté), via l'ancienne route directe
 * {@code POST /transactions} ET via {@code /trade-offers/{code}/redeem} (les
 * deux passent par cette même méthode - voir son commentaire).
 */
class GameServiceTransactionSecurityTest
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

	private String aWeakCardHeldBy(final int gameId, final int playerId)
	{
		final Map<String, Integer> inventory = sService.computePlayerCardInventory(gameId, playerId);
		return inventory.entrySet().stream().filter(e -> e.getKey().startsWith("faible") && (e.getValue() > 0)) //$NON-NLS-1$
				.map(Map.Entry::getKey).findFirst()
				.orElseThrow(() -> new AssertionError("Aucune carte faible détenue par le joueur " + playerId)); //$NON-NLS-1$
	}

	@Test
	void testDebtTransactionAtZeroPriceIsRejected() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimTest", null, //$NON-NLS-1$
				"Test sécurité prix nul", "2026-09-29", "Ceres", 1, 180, 1.0, false, 0, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int sellerId = sService.addPlayer(gameId, "Vendeur").getId(); //$NON-NLS-1$
		final int buyerId = sService.addPlayer(gameId, "Acheteur").getId(); //$NON-NLS-1$

		// Mise en place (mode smartphone, dette) - même séquence que les autres
		// tests de ce fichier de test pour la dette+smartphone.
		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));
		// Crédit confortable pour l'acheteur - sans lui, un prix positif
		// échouerait de toute façon pour "solde insuffisant", ce qui masquerait
		// le vrai test (le prix nul doit être rejeté AVANT même de regarder le
		// solde).
		sService.recordEvent(gameId, "N", buyerId, 100, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final String card = aWeakCardHeldBy(gameId, sellerId);
		// Compte de départ, jamais supposé égal à 1 : la main de départ est
		// distribuée au hasard depuis la pioche partagée et peut légitimement
		// contenir plusieurs exemplaires du même modèle.
		final int countBefore = sService.computePlayerCardInventory(gameId, sellerId).getOrDefault(card, 0);
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> sService.recordTransaction(gameId, sellerId, buyerId, card, "faible", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$
						"nonce-prix-nul", System.currentTimeMillis() + 60_000), //$NON-NLS-1$
				"une transaction dette à prix nul doit être refusée"); //$NON-NLS-1$
		assertTrue(ex.getMessage().toLowerCase().contains("gratuit") || ex.getMessage().toLowerCase().contains("positif"), //$NON-NLS-1$ //$NON-NLS-2$
				"le message doit expliquer que le prix doit être positif : " + ex.getMessage()); //$NON-NLS-1$

		// Rien ne doit avoir été persisté ni déplacé - la carte reste chez le
		// vendeur, aucune Transaction enregistrée.
		final List<Transaction> transactions = sService.listTransactions(gameId);
		assertTrue(transactions.isEmpty(), "aucune transaction ne doit avoir été enregistrée"); //$NON-NLS-1$
		assertEquals(countBefore, sService.computePlayerCardInventory(gameId, sellerId).getOrDefault(card, 0),
				"la carte doit être restée chez le vendeur, en même quantité qu'avant la tentative"); //$NON-NLS-1$
	}

	/** Contre-épreuve : un prix STRICTEMENT positif, avec un acheteur solvable, doit toujours réussir. */
	@Test
	void testDebtTransactionAtPositivePriceStillWorks() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimTest", null, //$NON-NLS-1$
				"Test sécurité prix positif", "2026-09-29", "Ceres", 1, 180, 1.0, false, 0, false, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int sellerId = sService.addPlayer(gameId, "Vendeur").getId(); //$NON-NLS-1$
		final int buyerId = sService.addPlayer(gameId, "Acheteur").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog(6));
		sService.recordEvent(gameId, "N", buyerId, 100, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$

		final String card = aWeakCardHeldBy(gameId, sellerId);
		final Transaction tx = sService.recordTransaction(gameId, sellerId, buyerId, card, "faible", 3, 0, 0, 0, 0, 0, //$NON-NLS-1$
				"nonce-prix-positif", System.currentTimeMillis() + 60_000); //$NON-NLS-1$
		assertEquals(3, tx.totalCoinsValue());
		assertEquals(1, sService.listTransactions(gameId).size());
	}
}
