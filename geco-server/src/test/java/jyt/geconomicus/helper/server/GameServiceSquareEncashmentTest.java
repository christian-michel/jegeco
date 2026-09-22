package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Game;

/**
 * Vérifie le correctif du 22/09/2026 (remonté par l'utilisateur sur une
 * vraie partie libre + smartphone, 2 joueurs, 8 tours de 3 min, "à partir du
 * 5ème tour... les carrés s'emballent et ne se comptent pas, le joueur peut
 * arriver à 5 cartes sans avoir déclenché de carré" - capture d'écran jointe
 * montrant TROIS modèles distincts bloqués à 5 exemplaires chacun, sur DEUX
 * niveaux différents, forte ET tresforte, EN MÊME TEMPS).
 * <p>
 * Cause racine (voir le commentaire détaillé dans
 * {@link GameService#checkAndCashInSquares}) : la boucle de détection
 * s'arrêtait sur le PREMIER modèle à 4+ exemplaires rencontré dans
 * l'inventaire, quel que soit son niveau - y compris un modèle déjà au
 * niveau "tresforte" (le plus haut, sans promotion possible par conception,
 * voir le garde-fou qui retournait alors IMMÉDIATEMENT sans rien encaisser).
 * Une fois un joueur bloqué avec 4+ cartes tresforte identiques (jamais
 * réduit à 0, puisque jamais encaissable), ce modèle gagnait la course
 * d'itération à CHAQUE appel suivant, empêchant alors SILENCIEUSEMENT tout
 * encaissement de carré à un niveau inférieur pour ce joueur, pour le reste
 * de la partie.
 * <p>
 * Construction du scénario : {@link GameService#recordTransaction} ne
 * revérifie jamais que le VENDEUR possède réellement la carte au moment de
 * l'appel (contrairement à {@code recordCardSwap}, voir son commentaire du
 * 18/09/2026 - une différence assumée, propre au troc) - exploité ici pour
 * placer directement dans la main de p1 exactement les cartes voulues (4
 * "tresforte_0" bloquées PUIS 4 "forte_0" distinctes), sans dépendre d'une
 * cascade aléatoire coûteuse à orchestrer et à faire converger de façon
 * fiable. Le catalogue synthétique reste nécessaire pour que
 * {@code findLevelOfCard} reconnaisse chaque modèle utilisé ici comme
 * appartenant à la bonne pioche de la partie.
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

	@Test
	void testStuckTopLevelSquareNeverBlocksALowerLevelSquare() throws Exception
	{
		final Map<String, List<String>> catalog = new LinkedHashMap<>();
		catalog.put("faible", List.of("faible_0", "faible_1", "faible_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("moyenne", List.of("moyenne_0", "moyenne_1", "moyenne_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("forte", List.of("forte_0", "forte_1", "forte_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		catalog.put("tresforte", List.of("tresforte_0")); //$NON-NLS-1$ //$NON-NLS-2$

		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimSquareTest", null, //$NON-NLS-1$
				"test carre bloque au niveau max", "2026-09-22", "Ceres", 1, 180, 1.0, false, 0, true, 0.5); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final int gameId = game.getId();
		final int p0 = sService.addPlayer(gameId, "P0").getId(); //$NON-NLS-1$
		final int p1 = sService.addPlayer(gameId, "P1").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		// Établit game.smartphoneCardPileJson (avec les modèles du catalogue
		// ci-dessus, tous sélectionnés puisque exactement nbPlayers+1=3 par
		// niveau intermédiaire) - c'est cette pioche que findLevelOfCard
		// consulte pour reconnaître le niveau de chaque carte utilisée
		// ci-dessous. Les mains de départ elles-mêmes (cartes faibles) ne
		// servent pas dans ce scénario - seul recordTransaction sert à placer
		// des cartes, voir plus bas.
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog);

		// Solde de jetons volontairement énorme pour p1 (WEALTH_CHECKPOINT) :
		// en monnaie libre, recordTransaction recalcule TOUJOURS le vrai prix
		// requis d'après le niveau de la carte (levelValue), quels que soient
		// les weakCoins/mediumCoins/strongCoins passés en paramètre - ce test
		// porte sur le mécanisme du carré, pas sur les soldes.
		sService.recordEvent(gameId, "W", p1, 0, 0, 0, 0, 0, null, 0, 0, 1_000_000, 0, 0, 0, 0, 0); //$NON-NLS-1$

		// Place directement 4 "tresforte_0" dans la main de p1 - recordTransaction
		// (contrairement à recordCardSwap, voir le commentaire de tête de
		// classe) ne revérifie jamais que p0 possède réellement la carte
		// vendue, ce qui permet de construire ce scénario déterministe sans
		// dépendre d'une cascade aléatoire faible -> moyenne -> forte ->
		// tresforte.
		for (int i = 0; i < 4; i++)
			sService.recordTransaction(gameId, p0, p1, "tresforte_0", "tresforte", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$ //$NON-NLS-2$
					"tresforte-" + i, System.currentTimeMillis() + 60_000); //$NON-NLS-1$

		final Map<String, Integer> beforeCheck = sService.computePlayerCardInventory(gameId, p1);
		assertTrue(beforeCheck.getOrDefault("tresforte_0", 0) >= 4, //$NON-NLS-1$
				"préalable du scénario : p1 doit détenir 4 cartes tresforte_0 avant toute vérification de carré"); //$NON-NLS-1$

		// Un premier appel à checkAndCashInSquares à ce stade ne doit RIEN
		// changer pour tresforte_0 (jamais encaissable au niveau maximum, par
		// conception) - conforme AVANT et APRÈS le correctif du 22/09/2026,
		// ce n'est pas ce qui est sous test ici.
		sService.checkAndCashInSquares(gameId, p1);
		final Map<String, Integer> afterTresforteOnlyCheck = sService.computePlayerCardInventory(gameId, p1);
		assertTrue(afterTresforteOnlyCheck.getOrDefault("tresforte_0", 0) >= 4, //$NON-NLS-1$
				"le carré tresforte doit rester bloqué (jamais encaissé), avec ou sans le correctif"); //$NON-NLS-1$

		// Place maintenant 4 "forte_0" DISTINCTES dans la main de p1, PENDANT
		// que le carré tresforte reste bloqué - exactement la configuration
		// observée dans la vraie partie (plusieurs modèles bloqués à la fois,
		// sur des niveaux différents).
		for (int i = 0; i < 4; i++)
			sService.recordTransaction(gameId, p0, p1, "forte_0", "forte", 0, 0, 0, 0, 0, 0, //$NON-NLS-1$ //$NON-NLS-2$
					"forte-" + i, System.currentTimeMillis() + 60_000); //$NON-NLS-1$

		final Map<String, Integer> beforeFinalCheck = sService.computePlayerCardInventory(gameId, p1);
		assertTrue(beforeFinalCheck.getOrDefault("tresforte_0", 0) >= 4, //$NON-NLS-1$
				"le carré tresforte doit toujours être présent juste avant la vérification finale"); //$NON-NLS-1$
		assertTrue(beforeFinalCheck.getOrDefault("forte_0", 0) >= 4, //$NON-NLS-1$
				"p1 doit détenir 4 cartes forte_0 juste avant la vérification finale"); //$NON-NLS-1$

		// LE correctif sous test. Avant le 22/09/2026, cet appel repartait
		// immédiatement sans rien faire dès que le premier modèle rencontré
		// dans l'inventaire était le tresforte_0 bloqué - le carré forte_0
		// (niveau inférieur, réellement encaissable) restait alors ignoré
		// JAMAIS traité. Désormais, la détection ignore les modèles déjà au
		// niveau maximum et continue à chercher un AUTRE modèle réellement
		// encaissable.
		sService.checkAndCashInSquares(gameId, p1);

		final Map<String, Integer> afterFinalCheck = sService.computePlayerCardInventory(gameId, p1);
		assertTrue(afterFinalCheck.getOrDefault("tresforte_0", 0) >= 4, //$NON-NLS-1$
				"le carré tresforte doit rester intact après la vérification finale (jamais encaissable, par conception)"); //$NON-NLS-1$
		assertTrue(afterFinalCheck.getOrDefault("forte_0", 0) < 4, //$NON-NLS-1$
				"le carré forte_0 aurait dû être encaissé (" + afterFinalCheck.getOrDefault("forte_0", 0) //$NON-NLS-1$ //$NON-NLS-2$
						+ " exemplaires restants) - resté bloqué par le carré tresforte coincé"); //$NON-NLS-1$
	}
}
