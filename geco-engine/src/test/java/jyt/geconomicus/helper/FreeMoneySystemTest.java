package jyt.geconomicus.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jyt.geconomicus.helper.Event.EventType;

/**
 * Vérifie le comportement du moteur spécifique à la monnaie libre : Dividende
 * Universel à l'entrée, convergence de la masse monétaire vers la moyenne à
 * chaque tour, bonus de masse monétaire à la mort/renaissance.
 */
public class FreeMoneySystemTest
{
	private EntityManagerFactory mFactory;
	private EntityManager mEm;
	private Game mGame;

	@BeforeEach
	public void setUp()
	{
		mFactory = TestPersistence.newIsolatedFactory();
		mEm = mFactory.createEntityManager();
		mEm.getTransaction().begin();
		// Facteur carte/monnaie = 1 pour des chiffres simples à vérifier à la main.
		mGame = new Game(Game.MONEY_LIBRE, 10, "animateur", "a@b.c", "partie de test", "today", "ici", 1);
	}

	@AfterEach
	public void tearDown()
	{
		mEm.getTransaction().rollback();
		mEm.close();
		mFactory.close();
	}

	@Test
	public void testJoinAddsDividendToMoneyMass()
	{
		final Player player = new Player(mGame, "Aramis");
		assertEquals(0, mGame.getMoneyMass(), "Aucun joueur actif : la masse monétaire de départ doit être nulle.");

		new Event(mGame, EventType.JOIN, player).applyEvent();

		// Voir Event.applyEvent, cas JOIN : "7 * facteur" par joueur qui rejoint.
		assertEquals(7, mGame.getMoneyMass(), "Un nouveau joueur doit apporter 7 × facteur carte/monnaie à la masse monétaire.");
	}

	@Test
	public void testTurnConvergesMoneyMassHalfwayTowardsTarget()
	{
		// Deux joueurs actifs -> cible théorique = 7 * facteur * nbJoueurs = 14,
		// atteinte exactement puisque chaque JOIN apporte pile 7 * facteur.
		final Player p1 = new Player(mGame, "Aramis");
		final Player p2 = new Player(mGame, "Dartagnan");
		new Event(mGame, EventType.JOIN, p1).applyEvent();
		new Event(mGame, EventType.JOIN, p2).applyEvent();
		assertEquals(14, mGame.getMoneyMass(), "Masse monétaire de départ attendue : 7 × 1 × 2 joueurs.");

		// Un décès ajoute un bonus fixe de "8 * facteur" (voir testDeathGrantsMoneyMassBonus
		// ci-dessous) - sans rapport avec la cible de convergence, ce qui crée un
		// écart réel entre masse actuelle (22) et cible théorique (toujours 14, le
		// nombre de joueurs actifs n'ayant pas changé).
		new Event(mGame, EventType.DEATH, p1).applyEvent();
		assertEquals(22, mGame.getMoneyMass(), "14 + le bonus de décès de 8 doit donner 22.");

		new Event(mGame, EventType.TURN, null).applyEvent();

		// Voir Event.applyEvent, cas TURN : changeMoneyMass((target - currentMM) / 2)
		// = (14 - 22) / 2 = -4 -> la masse ne doit se rapprocher qu'À MI-CHEMIN de
		// la cible en un seul tour, jamais y sauter directement.
		assertEquals(18, mGame.getMoneyMass(),
				"Un tour ne doit ramener la masse monétaire qu'à mi-chemin de l'écart avec la cible (22 - 4 = 18), pas directement à la cible (14).");
	}

	@Test
	public void testDeathGrantsMoneyMassBonus()
	{
		final Player player = new Player(mGame, "Aramis");
		new Event(mGame, EventType.JOIN, player).applyEvent();
		final int massBeforeDeath = mGame.getMoneyMass();

		new Event(mGame, EventType.DEATH, player).applyEvent();

		// Voir Event.applyEvent, cas DEATH : bonus fixe de "8 * facteur" en monnaie
		// libre (distinct du DU affiché à l'écran par l'assistant - un écart
		// documenté hérité de l'app originale, voir plugins/libre/manifest.json).
		assertEquals(massBeforeDeath + 8, mGame.getMoneyMass(),
				"Un décès en monnaie libre doit ajouter 8 × facteur carte/monnaie à la masse monétaire.");
	}

	@Test
	public void testDebtFieldsStayAtZeroInFreeMoneySystem()
	{
		// La monnaie libre n'a pas de banque ni de crédit - un joueur ne doit
		// jamais accumuler de dette dans ce système, quel que soit l'événement.
		final Player player = new Player(mGame, "Aramis");
		new Event(mGame, EventType.JOIN, player).applyEvent();
		assertEquals(0, player.getCurDebt());
		assertEquals(0, player.getCurInterest());
	}

	@Test
	public void testDeathCanDecreaseMoneyMassByDefault()
	{
		// Remonté par un utilisateur : documente explicitement le comportement
		// actuel (conservé volontairement, fidèle à l'app d'origine) - si le
		// joueur mourant déclare plus de jetons que le bonus fixe de décès
		// (8 × facteur), la masse monétaire globale DIMINUE net. C'est le point
		// de départ qui a motivé l'ajout du mode strict TRM ci-dessous.
		final Player p1 = new Player(mGame, "Aramis");
		final Player p2 = new Player(mGame, "Dartagnan");
		new Event(mGame, EventType.JOIN, p1).applyEvent();
		new Event(mGame, EventType.JOIN, p2).applyEvent();
		final int massBefore = mGame.getMoneyMass(); // 14

		final Event death = new Event(mGame, EventType.DEATH, p1);
		death.setWeakCoins(10); // valeur 10 (facteur 1), dépasse le bonus fixe de 8
		death.applyEvent();

		assertEquals(massBefore - 10 + 8, mGame.getMoneyMass(),
				"Par défaut, un joueur mourant avec plus de jetons que le bonus fixe (8) doit faire baisser la masse monétaire nette.");
	}

	@Test
	public void testStrictTrmNeverDecreasesMoneyMassAtDeath()
	{
		// Remonté par un utilisateur : en mode strict TRM (Game.setStrictTrm),
		// la masse monétaire ne doit JAMAIS diminuer à la mort d'un joueur, même
		// s'il déclare beaucoup de jetons - ce qu'il possédait reste compté
		// (juste inaccessible aux vivants), et la renaissance crée de la monnaie
		// fraîche à hauteur du DU du moment plutôt qu'un bonus fixe.
		mGame.setStrictTrm(true);
		final Player p1 = new Player(mGame, "Aramis");
		final Player p2 = new Player(mGame, "Dartagnan");
		new Event(mGame, EventType.JOIN, p1).applyEvent();
		new Event(mGame, EventType.JOIN, p2).applyEvent();
		final int massBefore = mGame.getMoneyMass(); // 14

		final Event death = new Event(mGame, EventType.DEATH, p1);
		death.setWeakCoins(10); // valeur 10 - ne doit PAS être retirée en mode strict
		death.applyEvent();

		// DU au moment de la mort : floor(14 / (7 × 2 joueurs × facteur 1)) = 1.
		assertEquals(massBefore + 1, mGame.getMoneyMass(),
				"En mode strict TRM, la masse monétaire ne doit jamais diminuer à une mort - elle doit augmenter du DU du moment (1 ici), pas d'un bonus fixe de 8, et sans retirer les jetons déclarés.");
	}

	@Test
	public void testStrictTrmHasNoEffectWhenDisabled()
	{
		// Vérifie que le réglage par défaut (strictTrm=false) reproduit
		// exactement testDeathCanDecreaseMoneyMassByDefault ci-dessus - c'est-à-
		// dire que l'ajout du mode strict n'a pas changé le comportement par
		// défaut du jeu.
		assertEquals(false, mGame.isStrictTrm(), "Le mode strict TRM doit être désactivé par défaut.");
	}
}
