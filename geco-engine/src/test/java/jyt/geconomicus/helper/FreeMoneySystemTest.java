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

	@Test
	public void testStrictTrmQuitPlayerKeepsReceivingDuAndStaysCountedInMass()
	{
		// Reproduction du bug corrigé le 04/10/2026 (voir docs/03-architecture-
		// technique.md, entrée "Seconde relecture indépendante...") : 2 joueurs,
		// 7+7=14, un QUIT (7 jetons) suivi d'un TURN sans mort faisait AVANT
		// retomber moneyMass à 7 - la masse monétaire ne doit JAMAIS diminuer en
		// strict TRM. Vérifie aussi la décision utilisateur qui a suivi (même
		// jour, "vaut-il mieux continuer à lui verser le dividende ou arrêter ?"
		// - réponse : continuer) : le joueur sorti continue de toucher le DU à
		// chaque tour, et reste compté dans Game.computeCurrentDU()/
		// computeMoneyMassFromActivePlayersJetons (voir Player.quit).
		mGame.setStrictTrm(true);
		final Player p1 = new Player(mGame, "Alice");
		final Player p2 = new Player(mGame, "Bob");
		// Simule une partie suivie par smartphone (voir isSmartphoneTrackedGame)
		// - sans quoi la masse resterait sur l'ancien mécanisme "DU simple",
		// jamais concerné ni par le bug ni par ce correctif.
		p1.setStartingCardsJson("{}");
		p2.setStartingCardsJson("{}");
		new Event(mGame, EventType.JOIN, p1).applyEvent();
		new Event(mGame, EventType.JOIN, p2).applyEvent();
		// Simule la dotation de départ réellement distribuée par GameService en
		// direct (7 jetons faibles chacun) - hors périmètre du moteur pur testé
		// ici isolément.
		p1.setJetonWeak(7);
		p2.setJetonWeak(7);
		assertEquals(14, mGame.getMoneyMass(), "Masse de départ : 7 + 7.");

		// Alice quitte avec ses 7 jetons.
		final Event quit = new Event(mGame, EventType.QUIT, p1);
		quit.setWeakCoins(7);
		quit.applyEvent();
		assertEquals(14, mGame.getMoneyMass(),
				"Juste après le QUIT, la masse doit rester inchangée (déjà correct avant ce correctif).");
		assertEquals(true, p1.isQuit(),
				"Alice doit être marquée Player.quit=true après un QUIT en libre+strict TRM+smartphone.");
		assertEquals(false, p1.isActive(), "Alice ne doit en revanche plus être active (ne peut plus échanger/mourir).");

		// Bob reçoit un DU simulé, puis un TURN sans mort survient.
		p2.setJetonWeak(p2.getJetonWeak() + 1);
		new Event(mGame, EventType.TURN, null).applyEvent();

		// AVANT LE CORRECTIF : retombait à 7 (perte des jetons d'Alice). Désormais
		// : doit rester la somme réelle des jetons, Alice (sortie) incluse -
		// Alice (7) + Bob (8) = 15.
		assertEquals(15, mGame.getMoneyMass(),
				"La masse ne doit jamais diminuer : elle doit refléter la somme réelle des jetons, Alice (sortie) incluse.");

		// Alice continue de toucher le DU à chaque tour (décision utilisateur du
		// 04/10/2026) : un nouveau WEALTH_CHECKPOINT pour elle doit faire
		// grandir la masse en conséquence, exactement comme pour un joueur actif.
		final Event checkpoint = new Event(mGame, EventType.WEALTH_CHECKPOINT, p1);
		checkpoint.setWeakCoins(9); // 7 + 2 de DU, par exemple
		checkpoint.applyEvent();
		assertEquals(9, p1.getJetonWeak(),
				"Le WEALTH_CHECKPOINT doit mettre à jour le solde physique d'Alice, comme pour un joueur actif.");

		new Event(mGame, EventType.TURN, null).applyEvent();
		assertEquals(17, mGame.getMoneyMass(), "La masse doit refléter la croissance continue d'Alice (9) + Bob (8) = 17.");

		// computeCurrentDU() doit aussi compter Alice dans N (nombre de joueurs
		// vivants), pour ne pas gonfler indûment le DU de Bob - vérifié en
		// comparant au calcul attendu avec N=2, pas N=1.
		final double expectedDu = mGame.computeDuGrowthRatePerTurn() * mGame.getMoneyMass() / 2;
		assertEquals(Math.round(expectedDu), mGame.computeCurrentDU(),
				"Alice doit rester comptée dans N malgré son départ (DU calculé avec N=2, pas N=1).");
	}
}
