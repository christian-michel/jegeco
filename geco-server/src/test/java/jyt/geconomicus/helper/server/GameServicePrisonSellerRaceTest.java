package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Audit indépendant (25/10/2026, campagne de validation du PDF "Retours -
 * 20261024", commit c71a74c) : reproduit la fenêtre de course réelle entre
 * l'enregistrement de l'événement "P" (PRISON, qui pose
 * {@code Player.prisonUntilTurn}) et la saisie effective des cartes
 * ({@code GameService.resolveCannotPaySeizure}, qui les retire RÉELLEMENT) -
 * voir le commentaire détaillé sur
 * {@code GameService.recordEventAndResolveCannotPaySeizure} pour le
 * raisonnement complet. Scénario : un vendeur en dette+smartphone a encore
 * une carte en main ; au tour suivant il est condamné à la PRISON ; PENDANT
 * la fenêtre où l'événement est déjà enregistré (donc
 * {@code isPlayerInPrison} répond déjà "oui") mais où ses cartes n'ont pas
 * encore été matériellement saisies, une vente de cette carte est tentée.
 * <p>
 * AVANT le correctif (reproduit explicitement ci-dessous en appelant les deux
 * étapes séparément, exactement comme le faisait l'ancienne route
 * {@code GecoServer}) : la vente passe, parce que
 * {@code recordTransactionUnlocked} ne vérifie que la possession RÉELLE de la
 * carte (pas encore retirée à cet instant), jamais {@code prisonUntilTurn}
 * lui-même - et la vérification de prison côté VENDEUR, dans
 * {@code GecoServer}, n'a lieu qu'à la CRÉATION de l'offre, jamais à sa
 * rédemption. APRÈS le correctif (en utilisant la nouvelle méthode atomique
 * {@code recordEventAndResolveCannotPaySeizure}), la fenêtre est fermée : les
 * cartes sont retirées avant qu'aucune autre opération sur cette partie (donc
 * aussi avant la vente concurrente) ne puisse s'exécuter, puisque les deux
 * étapes partagent désormais la MÊME acquisition du verrou de partie.
 */
class GameServicePrisonSellerRaceTest
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

	/**
	 * Montre que le correctif ferme bien la fenêtre : juste APRÈS l'appel
	 * atomique recordEventAndResolveCannotPaySeizure("P", ...), le joueur n'a
	 * plus AUCUNE carte - donc une vente tentée dès que cet appel est revenu
	 * (le plus tôt possible pour une requête HTTP suivante, dans ce même
	 * thread ou un autre) trouverait toujours un inventaire vide, peu importe
	 * l'ordonnancement réel des threads : il n'y a plus d'état intermédiaire
	 * "en prison mais encore en possession de cartes" observable depuis
	 * l'extérieur.
	 */
	@Test
	void testAtomicRecordAndSeizureLeavesNoWindowWithCardsStillHeld() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimRace1", null, "test course prison vente", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-25", "Ceres", 1, 180, 0.5, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player seller = sService.addPlayer(gameId, "VendeurCondamne"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		final Map<String, Integer> handBefore = sService.computePlayerCardInventory(gameId, seller.getId());
		assertFalse(handBefore.isEmpty(), "le vendeur doit avoir des cartes avant sa mise en prison"); //$NON-NLS-1$

		sService.recordEventAndResolveCannotPaySeizure(gameId, "P", seller.getId(), 0, 0, 0, 0, 0, null, 0, 0, 0, 0, //$NON-NLS-1$
				0, 0, 0, 0);

		final Map<String, Integer> handAfter = sService.computePlayerCardInventory(gameId, seller.getId());
		assertEquals(0, handAfter.values().stream().mapToInt(Integer::intValue).sum(),
				"immédiatement après l'appel atomique, le vendeur condamné ne doit déjà plus avoir aucune carte - plus de fenêtre où une vente pourrait encore réussir"); //$NON-NLS-1$

		final Player reloaded = sService.getGame(gameId).getPlayers().stream()
				.filter(p -> p.getId().equals(seller.getId())).findFirst().orElseThrow();
		assertTrue(sService.isPlayerInPrison(reloaded, sService.getGame(gameId)),
				"le vendeur doit être marqué en prison dès que l'appel atomique est revenu"); //$NON-NLS-1$
	}

	/**
	 * Démontre, à l'inverse, que l'ANCIEN enchaînement en deux appels séparés
	 * (recordEvent puis resolveCannotPaySeizure, chacun reprenant son propre
	 * verrou) laissait bien une fenêtre EXPLOITABLE : un thread concurrent qui
	 * s'intercale exactement entre les deux appels voit un joueur déjà
	 * "en prison" (prisonUntilTurn posé) mais encore en possession réelle de
	 * sa carte - la vérification de possession seule (celle que fait
	 * recordTransactionUnlocked) ne suffit donc pas à elle seule à fermer
	 * cette fenêtre, CE QUI JUSTIFIE le correctif (fusion sous un seul verrou)
	 * plutôt qu'un correctif plus local (ex. revérifier prisonUntilTurn du
	 * vendeur côté serveur à la rédemption, qui aurait aussi fonctionné mais
	 * différemment - voir la discussion dans le rapport d'audit).
	 */
	@Test
	void testOldTwoStepSequenceHadAnObservableWindow() throws Exception
	{
		final Game game = sService.createGame(Game.MONEY_DEBT, 12, "AnimRace2", null, "test fenêtre ancien code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"2026-10-25", "Ceres", 1, 180, 0.5, false, 0, false, 1.0); //$NON-NLS-1$ //$NON-NLS-2$
		final int gameId = game.getId();
		final Player seller = sService.addPlayer(gameId, "VendeurCondamne2"); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, smallCatalog(8));

		final CountDownLatch eventRecorded = new CountDownLatch(1);
		final CountDownLatch checkDone = new CountDownLatch(1);
		final AtomicReference<Boolean> observedInPrisonWithCardsStillHeld = new AtomicReference<>(false);
		final AtomicBoolean checkerError = new AtomicBoolean(false);

		final Thread checker = new Thread(() -> {
			try
			{
				eventRecorded.await(5, TimeUnit.SECONDS);
				// Pile dans la fenêtre : l'événement "P" vient d'être enregistré
				// (prisonUntilTurn posé) mais resolveCannotPaySeizure n'a volontairement
				// pas encore été appelé par ce test, exactement comme l'ANCIEN code le
				// faisait en deux temps.
				final Player reloaded = sService.getGame(gameId).getPlayers().stream()
						.filter(p -> p.getId().equals(seller.getId())).findFirst().orElseThrow();
				final boolean inPrison = sService.isPlayerInPrison(reloaded, sService.getGame(gameId));
				final int cardsStillHeld = sService.computePlayerCardInventory(gameId, seller.getId()).values()
						.stream().mapToInt(Integer::intValue).sum();
				observedInPrisonWithCardsStillHeld.set(inPrison && (cardsStillHeld > 0));
			}
			catch (final Throwable t)
			{
				checkerError.set(true);
			}
			finally
			{
				checkDone.countDown();
			}
		});
		checker.start();

		// Reproduit EXACTEMENT l'ancien enchaînement de GecoServer : deux appels
		// séparés, chacun reprenant indépendamment le verrou de partie.
		sService.recordEvent(gameId, "P", seller.getId(), 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		eventRecorded.countDown();
		assertTrue(checkDone.await(5, TimeUnit.SECONDS), "le thread vérificateur doit se terminer"); //$NON-NLS-1$
		assertFalse(checkerError.get(), "le thread vérificateur ne doit pas lever d'exception"); //$NON-NLS-1$
		sService.resolveCannotPaySeizure(gameId, seller.getId(), "P", 0, 0, 0); //$NON-NLS-1$

		assertTrue(observedInPrisonWithCardsStillHeld.get(),
				"reproduction de la fenêtre : avec l'ancien enchaînement en deux appels séparés, un thread concurrent pouvait observer le joueur déjà marqué en prison MAIS encore en possession réelle de ses cartes - la fenêtre que corrige recordEventAndResolveCannotPaySeizure"); //$NON-NLS-1$
	}
}
