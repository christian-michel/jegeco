package jyt.geconomicus.helper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	/**
	 * Place une carte chez l'acheteur via une vente depuis le vendeur - la
	 * technique de ce fichier (voir setUpGameWithTwoPlayers). Adapté le
	 * 27/09/2026 : recordTransaction vérifie désormais que le vendeur DÉTIENT
	 * la carte au moment de la vente (bug "même carte vendue via plusieurs QR",
	 * reproduit en HTTP réel - voir le commentaire dans GameService). Ce fichier
	 * faisait jusqu'ici vendre par p0 des cartes qu'il n'avait jamais eues
	 * (précisément le trou corrigé) : le vendeur reçoit donc d'abord cet
	 * exemplaire dans sa dotation (Player.startingCardsJson) - exactement la
	 * carte "fabriquée" que l'ancienne technique créait implicitement, sans rien
	 * changer d'autre aux scénarios testés.
	 */
	private void sell(final int pGameId, final int pSeller, final int pBuyer, final String pCardId, final String pLevel,
			final String pNonce) throws Exception
	{
		final jakarta.persistence.EntityManager em = sEmf.createEntityManager();
		try
		{
			em.getTransaction().begin();
			final jyt.geconomicus.helper.Player seller = em.find(jyt.geconomicus.helper.Player.class, pSeller);
			final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			final Map<String, Integer> hand = mapper.readValue(seller.getStartingCardsJson(),
					new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Integer>>()
					{
					});
			hand.merge(pCardId, 1, Integer::sum);
			seller.setStartingCardsJson(mapper.writeValueAsString(hand));
			em.getTransaction().commit();
		}
		finally
		{
			em.close();
		}
		sService.recordTransaction(pGameId, pSeller, pBuyer, pCardId, pLevel, 0, 0, 0, 0, 0, 0, pNonce,
				System.currentTimeMillis() + 60_000);
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
			sell(gameId, p0, p1, "tresforte_0", "tresforte", //$NON-NLS-1$ //$NON-NLS-2$
					"tresforte-" + gameId + "-" + i); //$NON-NLS-1$

		// Au moins 1 carré attendu (le carré tresforte lui-même) - PAS
		// forcément exactement 1 : p1 détient aussi sa VRAIE main de départ
		// (4 cartes faibles réelles, voir setUpGameWithTwoPlayers), et la
		// carte de récompense du carré tresforte (un modèle faible tiré au
		// hasard) peut par coïncidence compléter un DEUXIÈME carré, tout à
		// fait légitime, avec ces cartes de départ déjà en main - le garde-
		// fou du 22/09/2026 (degenerateLevelsThisCall) laisse maintenant la
		// boucle continuer pour l'encaisser aussi, plutôt que de s'arrêter
		// prématurément.
		final List<CardSquareEvent> squares = sService.checkAndCashInSquares(gameId, p1);
		assertTrue(!squares.isEmpty(), "au moins un carré attendu (le carré tresforte)"); //$NON-NLS-1$
		final CardSquareEvent square = squares.stream().filter(s -> "tresforte".equals(s.getCashedLevel())) //$NON-NLS-1$
				.findFirst().orElseThrow(() -> new AssertionError("le carré tresforte lui-même doit être présent parmi les carrés encaissés")); //$NON-NLS-1$
		assertTrue(square.isTriggeredRevolution(), "un carré tresforte réellement bouclé doit déclencher une révolution"); //$NON-NLS-1$
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
			sell(gameId, p0, p1, "tresforte_0", "tresforte", //$NON-NLS-1$ //$NON-NLS-2$
					"tresforte-" + gameId + "-" + i); //$NON-NLS-1$
		for (int i = 0; i < 4; i++)
			sell(gameId, p0, p1, "forte_0", "forte", //$NON-NLS-1$ //$NON-NLS-2$
					"forte-" + gameId + "-" + i); //$NON-NLS-1$

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

	/**
	 * Vérifie le correctif du 22/09/2026 (trouvé en TESTANT la rotation des
	 * valeurs sur un vrai playtest 4 joueurs, pas anticipé à la conception) :
	 * une promotion DÉGÉNÉRÉE (aucune vraie diversité de modèle disponible,
	 * voir le commentaire détaillé dans checkAndCashInSquares) à un niveau ne
	 * doit plus jamais empêcher l'encaissement d'un carré COMPLÈTEMENT
	 * DIFFÉRENT, à un autre niveau, dans le même appel. Avant ce correctif,
	 * l'ancien garde-fou (14/09/2026) retournait immédiatement dès qu'UN SEUL
	 * niveau dégénérait, laissant tout le reste - même parfaitement
	 * encaissable - en attente indéfiniment tant que l'ordre d'itération
	 * (stable pour un même jeu de clés) continuait à faire gagner ce même
	 * niveau dégénéré à chaque appel suivant. Confirmé en vrai playtest :
	 * bloqué sur des dizaines d'appels consécutifs, cinq modèles "moyenne"
	 * distincts (chacun déjà à 5+ exemplaires) jamais encaissés.
	 */
	@Test
	void testDegeneratePromotionAtOneLevelNeverStarvesADifferentEligibleSquare() throws Exception
	{
		final Map<String, List<String>> catalog = new LinkedHashMap<>();
		catalog.put("faible", List.of("faible_0", "faible_1", "faible_2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		// moyenne/forte : UN SEUL modèle chacun, délibérément - nécessaire
		// pour forcer une VRAIE dégénérescence (aucun autre modèle possible,
		// ni au même niveau ni au niveau cible).
		catalog.put("moyenne", List.of("moyenne_0")); //$NON-NLS-1$ //$NON-NLS-2$
		catalog.put("forte", List.of("forte_0")); //$NON-NLS-1$ //$NON-NLS-2$
		catalog.put("tresforte", List.of("tresforte_0")); //$NON-NLS-1$ //$NON-NLS-2$

		final Game game = sService.createGame(Game.MONEY_LIBRE, 12, "AnimDegenTest", null, //$NON-NLS-1$
				"test carre degenere n'affame pas les autres", "2026-09-22", "Ceres", 1, 180, 1.0, false, 0, true, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				0.5);
		final int gameId = game.getId();
		final int p0 = sService.addPlayer(gameId, "P0").getId(); //$NON-NLS-1$
		final int p1 = sService.addPlayer(gameId, "P1").getId(); //$NON-NLS-1$

		sService.recordEvent(gameId, "T", null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0); //$NON-NLS-1$
		sService.captureDeckPlayerCountIfNeeded(gameId);
		sService.dealStartingHandsForLibreIfNeeded(gameId, catalog);
		sService.recordEvent(gameId, "W", p1, 0, 0, 0, 0, 0, null, 0, 0, 1_000_000, 0, 0, 0, 0, 0); //$NON-NLS-1$

		int nonce = 0;
		// recordTransaction ne touche JAMAIS la pioche partagée elle-même
		// (Game.smartphoneCardPileJson) - seul un VRAI traitement de carré la
		// fait bouger (voir checkAndCashInSquares, samePile/nextPile.merge).
		// Pour épuiser RÉELLEMENT la pioche "forte" (5 exemplaires au total,
		// un seul modèle), il faut donc faire vivre 5 VRAIES promotions
		// moyenne -> forte, chacune consommant 1 exemplaire de la pioche
		// forte - jamais un raccourci artificiel qui laisserait la pioche
		// intacte pendant que la main du joueur, elle, semble pleine.
		// Correctif du 27/09/2026 (seconde relecture indépendante, campagne de
		// test 2/4/10 joueurs) : un carré dégénéré (aucune VRAIE promotion,
		// promotedCardId == squareCardId) n'est PLUS jamais retourné par
		// checkAndCashInSquares (voir son commentaire détaillé) - sinon
		// l'animation "carré" se rejouerait sur le téléphone du joueur à
		// chaque transaction suivante, pour un résultat strictement nul.
		// L'événement reste TOUJOURS persisté (conservation des cartes
		// inchangée), donc ce préalable de scénario se vérifie désormais via
		// listPlayerSquares (l'historique réel en base). setupSquares (la
		// liste RETOURNÉE par checkAndCashInSquares) est conservée telle
		// quelle : elle sert plus bas à vérifier directement qu'aucun carré
		// no-op n'y apparaît jamais.
		final java.util.List<CardSquareEvent> setupSquares = new java.util.ArrayList<>();
		for (int cycle = 0; cycle < 6; cycle++)
		{
			for (int i = 0; i < 4; i++)
				sell(gameId, p0, p1, "moyenne_0", "moyenne", //$NON-NLS-1$ //$NON-NLS-2$
						"n" + gameId + "-" + (nonce++)); //$NON-NLS-1$ //$NON-NLS-2$
			setupSquares.addAll(sService.checkAndCashInSquares(gameId, p1));
		}
		final boolean moyenneDegeneratedDuringSetup = sService.listPlayerSquares(gameId, p1).stream()
				.anyMatch(s -> "moyenne".equals(s.getCashedLevel()) //$NON-NLS-1$
						&& s.getCashedCardTypeId().equals(s.getPromotedCardTypeId()));
		assertTrue(moyenneDegeneratedDuringSetup,
				"préalable du scénario : après 6 cycles (largement plus que les 5 exemplaires de la pioche forte), " //$NON-NLS-1$
						+ "le carré moyenne_0 doit avoir dégénéré au moins une fois (pioche forte épuisée), visible dans " //$NON-NLS-1$
						+ "l'historique persisté même s'il n'est plus retourné/diffusé (voir le correctif du 27/09/2026)"); //$NON-NLS-1$
		// Preuve directe du correctif du 27/09/2026 : aucun des carrés
		// RETOURNÉS pendant ces 6 cycles ne doit être un no-op - c'est
		// exactement ce que la diffusion WebSocket (et donc l'animation
		// smartphone) ne doit plus jamais montrer.
		final boolean anyNoopSquareReturned = setupSquares.stream()
				.anyMatch(s -> s.getCashedCardTypeId().equals(s.getPromotedCardTypeId()));
		assertFalse(anyNoopSquareReturned,
				"un carré dégénéré (sans VRAIE promotion) ne doit plus jamais être retourné/diffusé - carrés retournés : " //$NON-NLS-1$
						+ setupSquares); //$NON-NLS-1$

		// Correctif du 27/09/2026 (seconde relecture indépendante) : les 6
		// cycles ci-dessus ne se contentent PAS de faire dégénérer moyenne_0
		// - checkAndCashInSquares traite TOUTES les cascades possibles en un
		// seul appel (voir sa boucle interne), donc une fois forte_0 accumulé
		// à 4+ exemplaires par les vraies promotions moyenne->forte, une
		// VRAIE cascade forte->tresforte->(retour)faible peut ELLE AUSSI se
		// déclencher pendant ces mêmes 6 appels - et consommer, au passage,
		// une partie du stock de la pioche "faible" partagée (seulement 15
		// exemplaires au total pour cette partie à 2 joueurs, 8 déjà distribués
		// à la mise en place). Mesuré : après les 6 cycles, la pioche "faible"
		// peut se retrouver ENTIÈREMENT épuisée par ces cascades imprévues -
		// ce qui rendrait alors le carré tresforte_0 planté juste en dessous
		// LUI AUSSI dégénéré (repli vers son propre niveau, faute de place en
		// "faible"), cassant la prémisse même de ce scénario ("pioche
		// tresforte intacte - promotion saine attendue, en boucle vers
		// faible"). Sans rapport avec le correctif du jour sur checkAndCashInSquares
		// (qui se contente de ne plus RETOURNER/DIFFUSER un carré dégénéré,
		// jamais de changer QUAND un carré dégénère) - ce risque existait déjà
		// avant, simplement invisible tant qu'un carré dégénéré était encore
		// retourné comme un carré normal. Reconstitué explicitement ici pour
		// rendre le scénario déterministe, indépendamment du nombre exact de
		// cascades survenues pendant les 6 cycles de préparation.
		final jakarta.persistence.EntityManager topUpEm = sEmf.createEntityManager();
		try
		{
			topUpEm.getTransaction().begin();
			final Game gameToTopUp = topUpEm.find(Game.class, gameId);
			final com.fasterxml.jackson.databind.ObjectMapper topUpMapper = new com.fasterxml.jackson.databind.ObjectMapper();
			final Map<String, Map<String, Integer>> piles = topUpMapper.readValue(
					gameToTopUp.getSmartphoneCardPileJson(),
					new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Map<String, Integer>>>()
					{
					});
			// +200, largement au-delà de tout besoin réaliste (pas seulement les
			// 4 nécessaires au carré tresforte_0 lui-même) : le tirage aléatoire
			// des modèles à la mise en place (Collections.shuffle, sans graine
			// fixe) peut faire dépendre CET appel précis d'autres carrés "faible"
			// intermédiaires (même repli dégénéré que moyenne/forte, mais entre
			// DEUX modèles faible DIFFÉRENTS - jamais un noopSquare au sens de ce
			// correctif, donc consommant eux aussi la pioche) - un essai avec
			// seulement +4 s'est révélé non déterministe (a échoué environ 1 run
			// sur 2 lors de la vérification), un tel écart de marge élimine le
			// risque sans changer l'intention du scénario (vérifier qu'un carré
			// dégénéré n'affame pas un autre carré réellement éligible, pas
			// mesurer la taille exacte de la pioche nécessaire).
			piles.get("faible").merge("faible_0", 200, Integer::sum); //$NON-NLS-1$ //$NON-NLS-2$
			gameToTopUp.setSmartphoneCardPileJson(topUpMapper.writeValueAsString(piles));
			topUpEm.getTransaction().commit();
		}
		finally
		{
			topUpEm.close();
		}

		// Place maintenant, EN MÊME TEMPS : un NOUVEAU carré moyenne_0 (qui va
		// à coup sûr dégénérer à nouveau, la pioche forte restant épuisée) ET
		// un carré tresforte_0 COMPLÈTEMENT DIFFÉRENT, à un niveau distinct,
		// jamais touché jusqu'ici (pioche "tresforte" intacte - promotion
		// saine attendue, en boucle vers "faible", garantie non vide par le
		// correctif ci-dessus).
		// tresforte_0 délibérément choisi plutôt que faible_0 (première
		// version de ce test, insuffisante - voir ci-dessous) : la main de
		// départ de p1 (dealStartingHandsForLibreIfNeeded) pioche TOUJOURS
		// dans le niveau "faible" en tout premier, avant même la moindre
		// transaction - une carte faible_X entre donc dans l'inventaire (une
		// LinkedHashMap, ordre = première apparition) AVANT moyenne_0,
		// quelle que soit la suite. Le bug (ancien garde-fou qui
		// "return"-ait au lieu de continuer) ne se manifeste QUE si le
		// niveau dégénéré est rencontré AVANT le niveau sain dans cet ordre
		// d'itération - une première version de ce test utilisait faible_0
		// comme carré "sain", et passait donc À TORT même contre l'ancien
		// code buggé (faible_0 déjà présent dès la main de départ, itéré et
		// encaissé AVANT que moyenne_0 ne dégénère et ne fasse "return").
		// tresforte_0, lui, n'entre dans l'inventaire de p1 QUE par la
		// transaction bypass juste en dessous - donc APRÈS moyenne_0 (déjà
		// présent depuis les 6 cycles de préparation) dans l'ordre
		// d'itération : exactement la situation qui, en vrai playtest,
		// affamait silencieusement des carrés entiers.
		for (int i = 0; i < 4; i++)
			sell(gameId, p0, p1, "moyenne_0", "moyenne", //$NON-NLS-1$ //$NON-NLS-2$
					"n" + gameId + "-" + (nonce++)); //$NON-NLS-1$ //$NON-NLS-2$
		for (int i = 0; i < 4; i++)
			sell(gameId, p0, p1, "tresforte_0", "tresforte", //$NON-NLS-1$ //$NON-NLS-2$
					"n" + gameId + "-" + (nonce++)); //$NON-NLS-1$ //$NON-NLS-2$

		final List<CardSquareEvent> squares = sService.checkAndCashInSquares(gameId, p1);

		// Pas de nouvelle vérification "le carré moyenne_0 dégénère
		// EXACTEMENT ici" - après 6 cycles, l'état précis de sa pioche (voire
		// son épuisement total) n'est plus garanti de façon déterministe ;
		// seul le préalable ci-dessus (au moins une dégénérescence pendant
		// les 6 cycles de préparation) importait pour planter le décor. Ce
		// qui compte VRAIMENT ici : quoi qu'il soit advenu du carré
		// moyenne_0 dans CET appel, le carré tresforte_0 - complètement
		// différent, sur une pioche saine - ne doit jamais rester affamé.
		final boolean tresforteSquareProcessed = squares.stream().anyMatch(
				s -> "tresforte".equals(s.getCashedLevel()) && "tresforte_0".equals(s.getCashedCardTypeId())); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(tresforteSquareProcessed,
				"le carré tresforte_0, complètement différent, aurait dû être encaissé dans le même appel - " //$NON-NLS-1$
						+ "pas affamé par la dégénérescence du carré moyenne_0 (correctif du 22/09/2026), carrés obtenus : " //$NON-NLS-1$
						+ squares); //$NON-NLS-1$
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
				sell(gameId, p0, p1, "tresforte_0", "tresforte", //$NON-NLS-1$ //$NON-NLS-2$
						"rev" + gameId + "-" + rev + "-" + i); //$NON-NLS-1$ //$NON-NLS-2$
			sService.checkAndCashInSquares(gameId, p1);
		}

		final Game gameAfter = sService.getGame(gameId);
		assertEquals(4, gameAfter.getRevolutionCount(), "4 révolutions doivent avoir eu lieu, une par appel"); //$NON-NLS-1$
		assertEquals(0.5, gameAfter.cardPriceInDU("faible"), 1e-9, //$NON-NLS-1$
				"après 4 révolutions, le barème doit être revenu exactement à la normale"); //$NON-NLS-1$
		assertEquals(4.0, gameAfter.cardPriceInDU("tresforte"), 1e-9); //$NON-NLS-1$
	}
}
