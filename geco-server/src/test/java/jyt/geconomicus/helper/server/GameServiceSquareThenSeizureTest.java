package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jyt.geconomicus.helper.CardSquareEvent;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Audit indépendant (25/10/2026, campagne de validation du PDF "Retours -
 * 20261024", commit c71a74c) : combine le mécanisme de "carré" PARTAGÉ aux
 * trois systèmes monétaires (CLAUDE.md, "pioche/carré/promotion PARTAGÉE",
 * {@code GameService.checkAndCashInSquares}, inchangé par ce commit) avec le
 * NOUVEAU mécanisme de saisie réelle de cartes introduit par c71a74c
 * ({@code resolveCannotPaySeizure}) - un achat qui déclenche un carré POUR UN
 * JOUEUR, suivi d'une saisie "Ne peut pas payer" sur ce MÊME joueur juste
 * après, demandée explicitement par l'audit pour vérifier qu'ils ne se
 * marchent pas dessus et que la pioche partagée reste cohérente
 * (conservation stricte : pioche + inventaires de TOUS les joueurs = total
 * constant) tout du long.
 */
class GameServiceSquareThenSeizureTest
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

	private Map<String, java.util.List<String>> smallCatalog(final int pModelsPerLevel)
	{
		final Map<String, java.util.List<String>> byLevel = new LinkedHashMap<>();
		for (final String level : java.util.List.of("faible", "moyenne", "forte", "tresforte")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		{
			final java.util.List<String> ids = new java.util.ArrayList<>();
			for (int i = 0; i < pModelsPerLevel; i++)
				ids.add(level + "_modele_" + i); //$NON-NLS-1$
			byLevel.put(level, ids);
		}
		return byLevel;
	}

	private void forceInventory(final int pGameId, final int pPlayerId, final Map<String, Integer> pInventory)
			throws Exception
	{
		final Map<String, Integer> previouslyHeld = sService.computePlayerCardInventory(pGameId, pPlayerId);
		final EntityManager em = sEmf.createEntityManager();
		try
		{
			em.getTransaction().begin();
			final Game game = em.find(Game.class, pGameId);
			final Map<String, Map<String, Integer>> pilesByLevel = new ObjectMapper().readValue(
					game.getSmartphoneCardPileJson(), new TypeReference<Map<String, Map<String, Integer>>>()
					{
					});
			for (final Map.Entry<String, Integer> e : previouslyHeld.entrySet())
			{
				final String level = e.getKey().split("_modele_")[0]; //$NON-NLS-1$
				pilesByLevel.get(level).merge(e.getKey(), e.getValue(), Integer::sum);
			}
			for (final Map.Entry<String, Integer> e : pInventory.entrySet())
			{
				final String level = e.getKey().split("_modele_")[0]; //$NON-NLS-1$
				pilesByLevel.get(level).merge(e.getKey(), -e.getValue(), Integer::sum);
			}
			game.setSmartphoneCardPileJson(new ObjectMapper().writeValueAsString(pilesByLevel));

			final Player player = em.find(Player.class, pPlayerId);
			player.setStartingCardsJson(new ObjectMapper().writeValueAsString(pInventory));
			player.setCardInventoryResetAt(new java.util.Date());
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
	}

	private int totalCardsInGame(final int pGameId) throws Exception
	{
		final Game game = sService.getGame(pGameId);
		final Map<String, Map<String, Integer>> piles = new ObjectMapper().readValue(
				game.getSmartphoneCardPileJson(), new TypeReference<Map<String, Map<String, Integer>>>()
				{
				});
		int total = piles.values().stream().mapToInt(m -> m.values().stream().mapToInt(Integer::intValue).sum())
				.sum();
		for (final Player p : game.getPlayers())
			total += sService.computePlayerCardInventory(pGameId, p.getId()).values().stream()
					.mapToInt(Integer::intValue).sum();
		return total;
	}

	@Test
	void testSquareCashInImmediatelyFollowedBySeizureKeepsPileConsistent() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimSquareSeizure", null, //$NON-NLS-1$ //$NON-NLS-2$
				"test carré puis saisie", "2026-10-25", "Ceres", 1, 180, 1.0, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final Player victim = sService.addPlayer(gameId, "VictimeCarre"); //$NON-NLS-1$
		final Player other = sService.addPlayer(gameId, "AutreJoueur"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(10));

		final int totalBefore = totalCardsInGame(gameId);
		assertFalse(totalBefore == 0, "la partie doit contenir des cartes"); //$NON-NLS-1$

		// Force un CARRÉ : 4 exemplaires du même modèle faible chez la victime.
		final Map<String, Integer> squareInventory = new LinkedHashMap<>();
		squareInventory.put("faible_modele_0", 4); //$NON-NLS-1$
		squareInventory.put("moyenne_modele_0", 2); //$NON-NLS-1$
		forceInventory(gameId, victim.getId(), squareInventory);
		assertEquals(totalBefore, totalCardsInGame(gameId), "conservation après forceInventory (carré)"); //$NON-NLS-1$

		// Encaisse le carré : les 4 "faible_modele_0" repartent à la pioche, UNE
		// carte de niveau supérieur ("moyenne") est promue à la victime (voir
		// checkAndCashInSquares/promotion) - mécanisme PARTAGÉ aux 3 systèmes,
		// inchangé par c71a74c, revérifié ici en combinaison avec le NOUVEAU.
		final java.util.List<CardSquareEvent> squares = sService.checkAndCashInSquares(gameId, victim.getId());
		System.out.println("Carrés encaissés: " + squares.size()); //$NON-NLS-1$
		assertEquals(totalBefore, totalCardsInGame(gameId),
				"conservation stricte après encaissement du carré : pioche+mains inchangées au total"); //$NON-NLS-1$

		// Donne immédiatement un crédit à la victime (dette) puis la fait
		// condamner en PRISON (saisie totale réelle) - le TOUT juste après le
		// carré, pour vérifier qu'ils ne se marchent pas dessus.
		sService.recordEvent(gameId, "N", victim.getId(), 5, 1, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		assertEquals(totalBefore, totalCardsInGame(gameId), "conservation après l'octroi du crédit (aucun effet sur les cartes)"); //$NON-NLS-1$

		sService.recordEvent(gameId, "P", victim.getId(), 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.resolveCannotPaySeizure(gameId, victim.getId(), "P", 0, 0, 0); //$NON-NLS-1$

		final Map<String, Integer> victimInventoryAfterPrison = sService.computePlayerCardInventory(gameId,
				victim.getId());
		assertEquals(0, victimInventoryAfterPrison.values().stream().mapToInt(Integer::intValue).sum(),
				"la victime n'a plus aucune carte après la prison, y compris la carte promue par le carré"); //$NON-NLS-1$
		assertEquals(totalBefore, totalCardsInGame(gameId),
				"conservation stricte FINALE : carré + carte promue + saisie totale par la prison n'ont ni créé ni perdu de carte au total"); //$NON-NLS-1$

		// L'AUTRE joueur, totalement étranger à cette séquence, ne doit avoir
		// subi aucun effet de bord.
		final Map<String, Integer> otherUnaffected = sService.computePlayerCardInventory(gameId, other.getId());
		System.out.println("Inventaire de l'autre joueur (jamais touché): " + otherUnaffected); //$NON-NLS-1$
	}
}
