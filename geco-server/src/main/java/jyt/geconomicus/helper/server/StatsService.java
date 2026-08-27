package jyt.geconomicus.helper.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import jyt.geconomicus.helper.Event;
import jyt.geconomicus.helper.Event.EventType;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Calcule les statistiques de partie utilisées par les graphiques du tableau de bord
 * web : évolution de la masse monétaire dans le temps, et répartition des richesses
 * entre joueurs.
 * <p>
 * Ce n'est pas un nouvel algorithme : c'est un portage fidèle de la logique déjà
 * utilisée par {@code StatsFrame.java} côté Swing (classes internes {@code HistoryStats}
 * et la paire {@code computeValues}/{@code addFromEvent}), qui rejoue l'historique
 * complet des événements pour reconstituer l'état du jeu à chaque instant. On ne
 * réinvente donc pas le calcul : on le rend simplement consommable en JSON par le
 * front web plutôt que dessiné directement sur un {@code Graphics2D} Swing.
 */
public class StatsService
{
	/** Un point de la courbe "masse monétaire" : masse monétaire à la fin du tour donné. */
	public record MoneyMassPoint(int turn, int moneyMass)
	{
	}

	/** La richesse accumulée par un joueur au cours de la partie (voir {@link #computeWealthByPlayer}). */
	public record PlayerWealth(String playerName, int wealth)
	{
	}

	public record WealthDistribution(double top20Pct, double middle60Pct, double bottom20Pct,
			List<PlayerWealth> playerWealths)
	{
	}

	public record GameStats(List<MoneyMassPoint> moneyMassHistory, WealthDistribution wealthDistribution)
	{
	}

	/** Une tranche de l'histogramme de répartition finale des richesses. */
	public record WealthBucket(String label, int count)
	{
	}

	public record FinalReport(int totalPlayers, int finalizedPlayers, int notYetFinalizedPlayers, int nbTurnsPlanned,
			int yearsSimulated, int finalMoneyMass, int totalProduction, double average, double median,
			double stdDev, double giniIndex, double povertyThreshold, int playersUnderThreshold,
			double modestThreshold, int playersModest, List<WealthBucket> histogram,
			List<PlayerWealth> playerWealths, List<MoneyMassPoint> moneyMassHistory, boolean includesBank,
			List<TrocPlayerStat> trocStats, BankProfitBreakdown bankProfitBreakdown)
	{
	}

	/**
	 * Statistiques spécifiques au troc (voir plugins/troc/manifest.json,
	 * extraStats, et docs/10-etape-plugins-troc.md) : nombre d'échanges
	 * bien-contre-bien réalisés par chaque joueur. Liste vide pour tout autre
	 * système d'échange (dette, libre). Retour utilisateur : les échanges de
	 * service et le temps de vie ont été retirés après un premier essai -
	 * uniquement des transactions d'échange, jamais de don sans contrepartie.
	 */
	public record TrocPlayerStat(String playerName, int tradeCount)
	{
	}

	/**
	 * Activité individuelle d'un joueur au cours de la partie : nombre d'échanges
	 * réalisés, volume total de monnaie ayant transité par lui (crédits + intérêts +
	 * remboursements confondus), et montant total emprunté (pertinent surtout en
	 * monnaie dette). Répond à la demande : "qui a fait des crédits, qui a fait le
	 * plus de transactions, qui a brassé le plus grand volume".
	 */
	public record PlayerActivity(String playerName, int transactionCount, int creditsTaken, int volumeMoved)
	{
	}

	/** Statistiques globales d'activité de la partie (toutes couleurs de monnaie confondues). */
	public record ActivityReport(int globalTransactionCount, int globalVolumeMoved,
			List<PlayerActivity> byPlayer)
	{
	}

	/**
	 * Un point de la courbe de richesse d'un joueur : sa valeur accumulée à la fin
	 * du tour donné, et cette même valeur exprimée relativement à la moyenne de la
	 * masse monétaire par joueur actif à cet instant (M(t)/N(t)) - c'est cette
	 * seconde grandeur qui, selon la Théorie Relative de la Monnaie (module
	 * Galilée), converge vers 1.0 pour tout joueur au fil du temps en monnaie
	 * libre : un compte qui démarre à 0 rejoint la moyenne aux alentours de la
	 * moitié de l'espérance de vie simulée.
	 */
	public record PlayerWealthPoint(int turn, int value, double relativeToAverage)
	{
	}

	public record PlayerWealthSeries(String playerName, List<PlayerWealthPoint> points)
	{
	}

	public record WealthOverTimeReport(List<PlayerWealthSeries> series)
	{
	}

	/**
	 * Une des parties incluses dans une comparaison multi-parties (voir
	 * {@link #computeComparison}). {@code isDebt} évite au front de refaire le test
	 * {@code moneySystem == Game.MONEY_DEBT} lui-même.
	 */
	public record ComparisonGameInfo(Integer gameId, String label, boolean isDebt, int moneyCardsFactor)
	{
	}

	/**
	 * Une ligne du tableau de comparaison : un joueur (ou "Banque"), et sa richesse
	 * dans chacune des parties comparées, dans le même ordre que
	 * {@link ComparisonReport#games}. {@code null} quand ce joueur n'a pas participé
	 * à cette partie-là (peut arriver si les noms diffèrent d'une partie à l'autre,
	 * ou si un joueur n'a pas terminé la partie).
	 */
	public record ComparisonPlayerRow(String playerName, List<Integer> valuesPerGame)
	{
	}

	/**
	 * Portage web de {@code StatsFrame(List<Game>)} côté Swing (onglets "Aggrégés
	 * standards" / "Aggrégés corrigés") : compare plusieurs parties joueur par
	 * joueur, en les recoupant par nom.
	 */
	public record ComparisonReport(List<ComparisonGameInfo> games, List<ComparisonPlayerRow> standard,
			List<ComparisonPlayerRow> corrected)
	{
	}

	public GameStats computeStats(final Game pGame)
	{
		return new GameStats(computeMoneyMassHistory(pGame), computeWealthDistribution(pGame));
	}

	/**
	 * Calcule le rapport de fin de partie (écran "Partie terminée", Phase D) :
	 * moyenne, médiane, écart-type et indice de Gini de la production de valeurs par
	 * joueur, comme demandé par la notice officielle du jeu (section "Compte rendu" :
	 * "*Le nombre total de valeurs produites par joueur*", "*La moyenne globale des
	 * valeurs produites*", "*L'écart type de production des valeurs*").
	 * <p>
	 * Point d'attention important : la richesse d'un joueur n'est comptabilisée dans
	 * {@link #computeWealthByPlayer} qu'au moment de sa "mort" (événement DEATH/QUIT) -
	 * c'est le même principe que le tableur original ("*tous les joueurs sont appelés
	 * un par un devant l'animateur*" en fin de partie). Un joueur encore actif au
	 * moment de la génération du rapport n'est donc pas encore comptabilisé : plutôt
	 * que d'estimer une valeur approximative, on le signale explicitement via
	 * {@code notYetFinalizedPlayers} pour que l'animateur sache qu'il reste des
	 * décomptes à faire avant que le rapport soit complet.
	 */
	/**
	 * Remonté par un utilisateur : deux vues possibles sur la répartition finale
	 * des richesses - "sans banque" (uniquement les joueurs, chacun ayant les
	 * mêmes chances de départ) et "avec banque" (la banque comptée comme un
	 * "joueur" de plus, ce qui montre sa part réelle dans la richesse produite -
	 * portage du concept déjà présent dans l'app Swing originale, StatsFrame.java,
	 * qui proposait déjà ces deux onglets). Le total de la banque est celui déjà
	 * suivi en continu par le moteur (intérêts perçus + valeurs saisies + argent/
	 * cartes investis), pas recalculé depuis l'historique des événements.
	 */
	public FinalReport computeFinalReport(final Game pGame, final boolean pIncludeBank)
	{
		final Map<String, Integer> wealthByPlayer = computeWealthByPlayer(pGame);
		if (pIncludeBank)
		{
			final int bankTotal = computeBankWealth(pGame);
			wealthByPlayer.put("Banque", bankTotal); //$NON-NLS-1$
		}
		final List<Integer> wealths = new ArrayList<>(wealthByPlayer.values());
		wealths.sort(Comparator.naturalOrder());

		final int totalPlayers = pGame.getPlayers().size();
		final int finalizedPlayers = wealths.size();
		final int notYetFinalized = (int) pGame.getPlayers().stream().filter(p -> p.isActive()).count();

		final int totalProduction = wealths.stream().mapToInt(Integer::intValue).sum();
		final double average = finalizedPlayers == 0 ? 0 : (double) totalProduction / finalizedPlayers;
		final double median = computeMedian(wealths);
		final double stdDev = computeStdDev(wealths, average);
		final double gini = computeGini(wealths);
		// Remonté par un utilisateur, avec les sources officielles à l'appui
		// (Eurostat, INSEE, étude DREES "Personnes pauvres et modestes en Europe" -
		// n°1349, septembre 2025) : le seuil de pauvreté monétaire est fixé à 60%
		// du niveau de vie médian - PAS 50% comme précédemment supposé en
		// l'absence de source précise. Le même document introduit un second seuil
		// utile : la "condition modeste", entre 60% et 75% de la médiane -
		// suffisamment proche du seuil de pauvreté pour partager des conditions de
		// vie similaires, sans être comptée dans le taux de pauvreté strict.
		final double povertyThreshold = median * 0.6;
		final double modestThreshold = median * 0.75;
		final int playersUnderThreshold = (int) wealths.stream().filter(w -> w < povertyThreshold).count();
		final int playersModest = (int) wealths.stream()
				.filter(w -> w >= povertyThreshold && w < modestThreshold).count();
		// Remonté par un utilisateur, avec une capture d'écran de l'app Swing
		// originale à l'appui (StatsFrame.AggregatedStats) : "l'histogramme" doit
		// montrer une barre par JOUEUR (nommé), pas des tranches groupées - trié
		// par ordre alphabétique comme dans l'original, avec des lignes de
		// référence pour la moyenne, l'écart-type et le seuil de pauvreté (mêmes
		// valeurs déjà calculées ci-dessus, sur la même échelle que les barres).
		final List<PlayerWealth> playerWealths = wealthByPlayer.entrySet().stream()
				.map(e -> new PlayerWealth(e.getKey(), e.getValue()))
				.sorted(Comparator.comparing(PlayerWealth::playerName)).toList();

		return new FinalReport(totalPlayers, finalizedPlayers, notYetFinalized, pGame.getNbTurnsPlanned(),
				pGame.getNbTurnsPlanned() * 8, // convention officielle du jeu : 1 tour = 8 années simulées
				pGame.getMoneyMass(), totalProduction, round1(average), round1(median), round1(stdDev),
				round1(gini * 100), round1(povertyThreshold), playersUnderThreshold, round1(modestThreshold),
				playersModest, computeWealthHistogram(wealths), playerWealths, computeMoneyMassHistory(pGame),
				pIncludeBank, computeTrocStats(pGame),
				pGame.getMoneySystem() == Game.MONEY_DEBT ? computeBankProfitBreakdown(pGame) : null);
	}

	/**
	 * Calcule les statistiques spécifiques au troc (voir plugins/troc/manifest.json,
	 * extraStats) : rejoue l'historique des événements GOODS_TRADE pour cumuler,
	 * par joueur, le nombre d'échanges réalisés. Liste vide pour tout autre
	 * système d'échange.
	 */
	private List<TrocPlayerStat> computeTrocStats(final Game pGame)
	{
		if (pGame.getMoneySystem() != Game.MONEY_TROC)
			return List.of();

		final Map<String, Integer> tradeCountByPlayer = new TreeMap<>();
		final java.util.Set<String> allPlayerNames = new java.util.TreeSet<>();

		for (final Player player : pGame.getPlayers())
			allPlayerNames.add(player.getName());

		for (final Event event : pGame.getEvents())
		{
			if ((event.getEvt() != EventType.GOODS_TRADE) || (event.getPlayer() == null)
					|| (event.getCounterpartyPlayer() == null))
				continue;
			tradeCountByPlayer.merge(event.getPlayer().getName(), 1, Integer::sum);
			tradeCountByPlayer.merge(event.getCounterpartyPlayer().getName(), 1, Integer::sum);
		}

		final List<TrocPlayerStat> stats = new ArrayList<>();
		for (final String name : allPlayerNames)
			stats.add(new TrocPlayerStat(name, tradeCountByPlayer.getOrDefault(name, 0)));
		return stats;
	}

	/**
	 * Compare plusieurs parties (typiquement une en monnaie dette et une en monnaie
	 * libre, jouées avec le même groupe de joueurs) joueur par joueur - portage web
	 * de {@code ChooseGamesDialog}/{@code StatsFrame(List<Game>)} côté Swing (onglets
	 * "Aggrégés standards" et "Aggrégés corrigés").
	 * <p>
	 * Les joueurs sont recoupés d'une partie à l'autre <b>par leur nom exact</b> (même
	 * logique que l'original, qui utilise le nom comme clé dans une
	 * {@code SortedMap<String, List<Integer>>}) : utilisez donc les mêmes prénoms
	 * dans vos deux parties pour que la comparaison ait un sens.
	 * <p>
	 * La banque n'apparaît que pour les parties en monnaie dette (il n'y a pas de
	 * banque en monnaie libre) et sa valeur (déjà nette, voir
	 * {@link #computeBankWealth}) n'est jamais "corrigée" - seule la richesse des
	 * joueurs l'est.
	 * <p>
	 * <b>Vue "corrigée"</b> : reproduit exactement l'ajustement de l'original
	 * ({@code StatsFrame}, commentaire "take away the 8 cards that the player got in
	 * his hands for free") - on retranche un forfait de 8 à chaque joueur dans
	 * chaque partie (la dotation initiale de cartes, qui n'est pas de la richesse
	 * "produite"), et, en monnaie libre uniquement, encore 4 × le facteur
	 * carte/monnaie de cette partie (le DU moyen reçu une fois à la naissance et une
	 * fois à l'évaluation finale, compté pour moitié - cf. le commentaire original :
	 * "-2x2=4"). Comme l'original, ce même ajustement n'est PAS encore appliqué côté
	 * monnaie dette (l'original laisse un TODO à ce sujet : il faudrait, pour chaque
	 * évaluation d'un joueur, retrancher la masse monétaire moyenne par joueur au
	 * tour courant - non implémenté ici non plus, pour rester fidèle à l'original).
	 */
	public ComparisonReport computeComparison(final List<Game> pGames)
	{
		// Monnaie dette en premier, comme dans l'original (StatsFrame trie pGames de
		// la même façon avant de construire les colonnes).
		final List<Game> games = new ArrayList<>(pGames);
		games.sort(Comparator.comparingInt(Game::getMoneySystem).reversed());
		final int nbGames = games.size();

		final List<ComparisonGameInfo> gameInfos = new ArrayList<>();
		final SortedMap<String, List<Integer>> standardByPlayer = new TreeMap<>();
		final List<Integer> bankRow = new ArrayList<>(Collections.nCopies(nbGames, null));
		boolean anyBank = false;

		for (int i = 0; i < nbGames; i++)
		{
			final Game game = games.get(i);
			final boolean isDebt = game.getMoneySystem() == Game.MONEY_DEBT;
			gameInfos.add(new ComparisonGameInfo(game.getId(), gameLabel(game), isDebt, game.getMoneyCardsFactor()));

			for (final Map.Entry<String, Integer> e : computeWealthByPlayer(game).entrySet())
			{
				final List<Integer> row = standardByPlayer.computeIfAbsent(e.getKey(),
						k -> new ArrayList<>(Collections.nCopies(nbGames, null)));
				row.set(i, e.getValue());
			}
			if (isDebt)
			{
				bankRow.set(i, computeBankWealth(game));
				anyBank = true;
			}
		}

		final List<ComparisonPlayerRow> standard = new ArrayList<>();
		for (final Map.Entry<String, List<Integer>> e : standardByPlayer.entrySet())
			standard.add(new ComparisonPlayerRow(e.getKey(), e.getValue()));
		if (anyBank)
			standard.add(new ComparisonPlayerRow("Banque", bankRow)); //$NON-NLS-1$

		final List<ComparisonPlayerRow> corrected = new ArrayList<>();
		for (final ComparisonPlayerRow row : standard)
		{
			if ("Banque".equals(row.playerName())) //$NON-NLS-1$
			// La banque n'est jamais "corrigée" dans l'original.
			{
				corrected.add(row);
				continue;
			}
			final List<Integer> adjustedValues = new ArrayList<>(nbGames);
			for (int i = 0; i < nbGames; i++)
			{
				final Integer value = row.valuesPerGame().get(i);
				if (value == null)
				{
					adjustedValues.add(null);
					continue;
				}
				int adjustment = 8;
				if (games.get(i).getMoneySystem() == Game.MONEY_LIBRE)
					adjustment += 4 * games.get(i).getMoneyCardsFactor();
				adjustedValues.add(value - adjustment);
			}
			corrected.add(new ComparisonPlayerRow(row.playerName(), adjustedValues));
		}

		return new ComparisonReport(gameInfos, standard, corrected);
	}

	private String gameLabel(final Game pGame)
	{
		final String system = pGame.getMoneySystem() == Game.MONEY_DEBT ? "Dette" : "Libre"; //$NON-NLS-1$ //$NON-NLS-2$
		final String date = pGame.getCurdate() == null ? "" : pGame.getCurdate(); //$NON-NLS-1$
		final String location = (pGame.getLocation() == null) || pGame.getLocation().isBlank() ? ""
				: " (" + pGame.getLocation() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		return system + " – " + date + location; //$NON-NLS-1$
	}

	/**
	 * Reconstitue, pour chaque joueur, sa richesse à la fin de chaque tour (valeur
	 * absolue et valeur relative à la moyenne M(t)/N(t)) - la donnée de base pour
	 * reproduire la démonstration du "module Galilée" de la TRM
	 * (https://rml.creationmonetaire.info/modules/) : la convergence des comptes
	 * individuels vers la moyenne au fil du temps.
	 * <p>
	 * <b>Limite assumée, importante</b> : le moteur ne connaît la richesse réelle
	 * d'un joueur qu'aux moments où elle est explicitement évaluée - c'est-à-dire
	 * uniquement aux événements Mort/Fin de partie (`DEATH`/`QUIT`), où l'animateur
	 * saisit ce que le joueur possède physiquement à cet instant (cartes/jetons en
	 * main). Les échanges directs entre joueurs (achat/vente de cartes valeur) ne
	 * sont aujourd'hui pas enregistrés comme des événements individuels - ils se
	 * déroulent physiquement, hors logiciel. Entre deux évaluations, on ne peut
	 * donc pas savoir avec certitude ce qu'un joueur possède : cette méthode
	 * **maintient la dernière valeur connue** plutôt que d'inventer une évolution
	 * continue qui ne reposerait sur aucune donnée réelle.
	 * <p>
	 * Cette limite disparaîtra avec l'étape 3 : le système de cartes numériques
	 * prévu enregistrera chaque échange individuellement, rendant alors possible
	 * une courbe de richesse réellement continue et précise.
	 */
	public WealthOverTimeReport computeWealthOverTime(final Game pGame)
	{
		final Map<String, Integer> lastKnownValue = new java.util.LinkedHashMap<>();
		final Map<String, List<PlayerWealthPoint>> pointsByPlayer = new java.util.LinkedHashMap<>();
		for (final Player p : pGame.getPlayers())
		{
			lastKnownValue.put(p.getName(), 0);
			final List<PlayerWealthPoint> points = new ArrayList<>();
			points.add(new PlayerWealthPoint(0, 0, 0.0));
			pointsByPlayer.put(p.getName(), points);
		}

		final int[] turnCounter = { 0 };
		final int[] currentFactor = { 1 };
		pGame.recomputeAll(event -> {
			if (event.getEvt() == EventType.TURN)
			{
				turnCounter[0]++;
				final int mass = pGame.getMoneyMass();
				final long activeCount = pGame.getPlayers().stream().filter(Player::isActive).count();
				final double average = activeCount == 0 ? 0 : (double) mass / activeCount;
				for (final Player p : pGame.getPlayers())
				{
					final int value = lastKnownValue.getOrDefault(p.getName(), 0);
					final double relative = average == 0 ? 0 : value / average;
					pointsByPlayer.get(p.getName()).add(new PlayerWealthPoint(turnCounter[0], value, round2(relative)));
				}
			}
			else if (event.getEvt() == EventType.XTECHNOLOGICAL_BREAKTHROUGH)
			{
				currentFactor[0] *= 2;
			}
			else if ((event.getEvt() == EventType.DEATH || event.getEvt() == EventType.QUIT) && event.getPlayer() != null
					&& lastKnownValue.containsKey(event.getPlayer().getName()))
			{
				final String name = event.getPlayer().getName();
				// Bilan réel saisi par l'animateur à cet instant (voir limite documentée
				// ci-dessus). On l'enregistre comme point immédiatement (le tour courant
				// n'a pas forcément encore de point TURN à ce stade), puis on renaît à 0
				// pour la suite - conformément à la règle du jeu.
				final int assessedValue = computeGain(pGame, event, currentFactor[0]);
				final int mass = pGame.getMoneyMass();
				final long activeCount = pGame.getPlayers().stream().filter(Player::isActive).count();
				final double average = activeCount == 0 ? 0 : (double) mass / activeCount;
				final double relative = average == 0 ? 0 : assessedValue / average;
				pointsByPlayer.get(name)
						.add(new PlayerWealthPoint(turnCounter[0], assessedValue, round2(relative)));
				lastKnownValue.put(name, 0);
			}
		});

		final List<PlayerWealthSeries> series = pointsByPlayer.entrySet().stream()
				.map(e -> new PlayerWealthSeries(e.getKey(), e.getValue())).toList();
		return new WealthOverTimeReport(series);
	}

	/**
	 * Statistiques d'activité : qui a fait le plus de transactions, qui a le plus
	 * emprunté, qui a fait circuler le plus de monnaie. Ne compte que les
	 * événements "transactionnels" avec un joueur associé et un mouvement de
	 * monnaie réel (crédit, remboursement, intérêt, saisie) - on exclut
	 * volontairement JOIN/TURN/DEATH/MM_CHANGE, qui sont des événements de cycle
	 * de vie plutôt que des échanges à proprement parler.
	 */
	public ActivityReport computeActivityReport(final Game pGame)
	{
		final java.util.Set<EventType> transactional = java.util.EnumSet.of(EventType.NEW_CREDIT,
				EventType.INTEREST_ONLY, EventType.REIMB_CREDIT, EventType.CANNOT_PAY, EventType.BANKRUPT,
				EventType.PRISON);

		final Map<String, Integer> txCountByPlayer = new java.util.LinkedHashMap<>();
		final Map<String, Integer> creditsByPlayer = new java.util.LinkedHashMap<>();
		final Map<String, Integer> volumeByPlayer = new java.util.LinkedHashMap<>();
		int globalCount = 0;
		int globalVolume = 0;

		for (final Event event : pGame.getEvents())
		{
			if (!transactional.contains(event.getEvt()) || event.getPlayer() == null)
				continue;
			final String name = event.getPlayer().getName();
			final int volume = event.getPrincipal() + event.getInterest();

			txCountByPlayer.merge(name, 1, Integer::sum);
			volumeByPlayer.merge(name, volume, Integer::sum);
			if (event.getEvt() == EventType.NEW_CREDIT)
				creditsByPlayer.merge(name, event.getPrincipal(), Integer::sum);

			globalCount++;
			globalVolume += volume;
		}

		final List<PlayerActivity> byPlayer = pGame.getPlayers().stream()
				.map(p -> new PlayerActivity(p.getName(), txCountByPlayer.getOrDefault(p.getName(), 0),
						creditsByPlayer.getOrDefault(p.getName(), 0), volumeByPlayer.getOrDefault(p.getName(), 0)))
				.sorted(Comparator.comparingInt(PlayerActivity::volumeMoved).reversed()).toList();

		return new ActivityReport(globalCount, globalVolume, byPlayer);
	}

	private double computeMedian(final List<Integer> pSortedValues)
	{
		if (pSortedValues.isEmpty())
			return 0;
		final int n = pSortedValues.size();
		return n % 2 == 1 ? pSortedValues.get(n / 2)
				: (pSortedValues.get(n / 2 - 1) + pSortedValues.get(n / 2)) / 2.0;
	}

	private double computeStdDev(final List<Integer> pValues, final double pAverage)
	{
		if (pValues.isEmpty())
			return 0;
		final double variance = pValues.stream().mapToDouble(v -> Math.pow(v - pAverage, 2)).sum() / pValues.size();
		return Math.sqrt(variance);
	}

	/**
	 * Indice de Gini (mesure standard d'inégalité, entre 0 = parfaite égalité et 1 =
	 * inégalité maximale), calculé sur la liste déjà triée par ordre croissant :
	 * G = (2 * somme(rang * valeur) / (n * somme des valeurs)) - (n + 1) / n.
	 */
	private double computeGini(final List<Integer> pSortedValues)
	{
		final int n = pSortedValues.size();
		final int total = pSortedValues.stream().mapToInt(Integer::intValue).sum();
		if (n == 0 || total == 0)
			return 0;
		double weightedSum = 0;
		for (int i = 0; i < n; i++)
			weightedSum += (i + 1) * pSortedValues.get(i);
		return (2.0 * weightedSum) / (n * (double) total) - (n + 1.0) / n;
	}

	/**
	 * Histogramme de répartition finale des richesses, en tranches adaptées à
	 * l'étendue réelle des valeurs de la partie (plutôt que des seuils fixes qui
	 * n'auraient de sens que pour une échelle de jeu particulière).
	 */
	private List<WealthBucket> computeWealthHistogram(final List<Integer> pSortedValues)
	{
		if (pSortedValues.isEmpty())
			return List.of();
		final int min = pSortedValues.get(0);
		final int max = pSortedValues.get(pSortedValues.size() - 1);
		final int bucketCount = 6;
		final double bucketWidth = Math.max(1, (max - min) / (double) bucketCount);

		final List<WealthBucket> buckets = new ArrayList<>();
		for (int b = 0; b < bucketCount; b++)
		{
			final double lower = min + b * bucketWidth;
			final double upper = b == bucketCount - 1 ? max : min + (b + 1) * bucketWidth;
			final int count = (int) pSortedValues.stream().filter(v -> v >= lower && (v <= upper)).count();
			buckets.add(new WealthBucket(Math.round(lower) + "-" + Math.round(upper), count));
		}
		return buckets;
	}

	/**
	 * Rejoue l'historique de la partie et capture la masse monétaire à la fin de
	 * chaque tour. Repose sur {@link Game#recomputeAll}, qui existe déjà dans le
	 * moteur précisément pour cet usage ("Very useful to make historical graphs",
	 * cf. sa Javadoc) - c'est le même mécanisme que celui utilisé par
	 * {@code HistoryStats} côté Swing.
	 * <p>
	 * Note : {@code recomputeAll} réinitialise puis rejoue TOUS les événements de la
	 * partie ; il restaure donc exactement le même état final qu'avant l'appel (aucun
	 * effet de bord côté données), tout en corrigeant au passage d'éventuelles
	 * incohérences - c'est le comportement documenté de cette méthode.
	 */
	private List<MoneyMassPoint> computeMoneyMassHistory(final Game pGame)
	{
		final List<MoneyMassPoint> history = new ArrayList<>();
		pGame.recomputeAll(event -> {
			if (event.getEvt() == EventType.TURN)
				history.add(new MoneyMassPoint(pGame.getTurnNumber(), pGame.getMoneyMass()));
		});
		// Point de départ (tour 0, avant le premier "nouveau tour") pour que la courbe
		// parte bien de zéro plutôt que de sembler débuter avec de la monnaie déjà en circulation.
		history.add(0, new MoneyMassPoint(0, 0));
		return history;
	}

	/**
	 * Répartition des richesses entre joueurs (Top 20% / 20-80% / Bottom 20%), pour le
	 * graphique en anneau du tableau de bord. Porté de {@code StatsFrame.computeValues}
	 * + {@code addFromEvent}, en se limitant aux joueurs (sans la banque - à la
	 * différence de la version Swing qui peut l'inclure) : c'est ce qui correspond au
	 * graphique "Répartition des richesses" de la maquette, centré sur les joueurs.
	 */
	private WealthDistribution computeWealthDistribution(final Game pGame)
	{
		final Map<String, Integer> wealthByPlayer = computeWealthByPlayer(pGame);
		final List<Integer> sortedWealths = new ArrayList<>(wealthByPlayer.values());
		sortedWealths.sort(Comparator.naturalOrder());

		final int total = sortedWealths.stream().mapToInt(Integer::intValue).sum();
		double top20Pct = 0, middle60Pct = 0, bottom20Pct = 0;
		if (total > 0 && !sortedWealths.isEmpty())
		{
			final int n = sortedWealths.size();
			// Les 20% de joueurs les plus riches / les plus pauvres (au moins 1 joueur de
			// chaque côté dès que n > 1, pour que la répartition reste lisible sur de petits
			// effectifs comme c'est le cas typique d'une partie de Ğeconomicus).
			final int bottomCount = Math.max(1, (int) Math.round(n * 0.2));
			final int topCount = Math.max(1, (int) Math.round(n * 0.2));
			int bottomSum = 0, topSum = 0;
			for (int i = 0; i < Math.min(bottomCount, n); i++)
				bottomSum += sortedWealths.get(i);
			for (int i = Math.max(0, n - topCount); i < n; i++)
				topSum += sortedWealths.get(i);
			final int middleSum = total - bottomSum - topSum;
			top20Pct = 100.0 * topSum / total;
			bottom20Pct = 100.0 * bottomSum / total;
			middle60Pct = 100.0 - top20Pct - bottom20Pct;
		}

		final List<PlayerWealth> playerWealths = wealthByPlayer.entrySet().stream()
				.map(e -> new PlayerWealth(e.getKey(), e.getValue()))
				.sorted(Comparator.comparingInt(PlayerWealth::wealth).reversed()).toList();

		return new WealthDistribution(round1(top20Pct), round1(middle60Pct), round1(bottom20Pct), playerWealths);
	}

	/**
	 * Calcule la richesse de la banque en rejouant chronologiquement les événements -
	 * portage fidèle de {@code StatsFrame.computeValues}/{@code addFromEvent} côté
	 * banque (paramètre {@code pAddBank=true} dans l'original).
	 * <p>
	 * <b>Correctif (retour utilisateur, écart énorme constaté entre nos courbes et
	 * celles de l'app Swing d'origine)</b> : la version précédente lisait directement
	 * {@code pGame.getInterestGained() + getSeizedValues() + getMoneyInvestBank() +
	 * getCardsInvestBank()} - des compteurs bruts accumulés sur l'objet {@code Game}
	 * au fil de la partie, sans jamais soustraire le principal détruit lors d'un
	 * défaut de paiement. Résultat : chaque saisie (CANNOT_PAY/BANKRUPT/PRISON)
	 * gonflait artificiellement la richesse de la banque, contrairement à l'original
	 * qui ne crédite la banque que du <i>surplus</i> au-dessus du principal dû (voir
	 * {@code addFromEvent} : {@code if (gained > pOwedByPlayer) gained -= pOwedByPlayer;
	 * else gained = 0;}) - le principal saisi ne fait que compenser/détruire la dette,
	 * ce n'est pas un profit pour la banque. Cette méthode rejoue donc l'historique
	 * exactement comme l'original, plutôt que de s'appuyer sur les compteurs de
	 * {@code Game} (qui, de surcroît, ne sont jamais remis à zéro entre deux rejeux
	 * de {@code Game#recomputeAll} pour {@code cardsInvestBank}/{@code moneyInvestBank} -
	 * un second bug indépendant, corrigé séparément dans {@code Game#recomputeAll}).
	 */
	private int computeBankWealth(final Game pGame)
	{
		final List<Event> events = new ArrayList<>(pGame.getEvents());
		events.sort(Comparator.comparing(Event::getTstamp, Comparator.nullsLast(Comparator.naturalOrder())));

		int bankWealth = 0;
		final Map<String, Integer> playerDebts = new HashMap<>();

		for (final Event event : events)
		{
			final String playerName = event.getPlayer() == null ? null : event.getPlayer().getName();
			switch (event.getEvt())
			{
				case NEW_CREDIT:
					playerDebts.merge(playerName, event.getPrincipal(), Integer::sum);
					break;
				case INTEREST_ONLY:
					bankWealth += computeBankTransactionValue(event);
					break;
				case CANNOT_PAY:
				case BANKRUPT:
				case PRISON:
				case REIMB_CREDIT:
				{
					// "Tout ceci va à la banque, sauf le principal du crédit" (commentaire de
					// l'original) : mais seuls les défauts (pas les remboursements volontaires)
					// voient ce principal soustrait - portage exact de la condition originale.
					int gained = computeBankTransactionValue(event);
					final Integer owed = playerDebts.get(playerName);
					if ((owed != null) && ((event.getEvt() == EventType.CANNOT_PAY)
							|| (event.getEvt() == EventType.BANKRUPT) || (event.getEvt() == EventType.PRISON)))
						gained = gained > owed ? gained - owed : 0;
					bankWealth += gained;
					playerDebts.remove(playerName);
					break;
				}
				case SIDE_INVESTMENT:
					// La banque investit : on retire ce montant de ses gains (elle le
					// récupérera plus tard via ASSESSMENT_FINAL).
					bankWealth -= computeBankTransactionValue(event);
					break;
				case ASSESSMENT_FINAL:
					bankWealth += computeBankTransactionValue(event);
					break;
				default:
					break;
			}
		}
		return bankWealth;
	}

	/**
	 * Calcule la richesse accumulée par chaque joueur au cours de la partie, en
	 * rejouant chronologiquement les événements - portage direct de
	 * {@code StatsFrame.addFromEvent} : chaque événement crédite (ou parfois débite)
	 * un montant au joueur concerné, selon son type.
	 */
	private Map<String, Integer> computeWealthByPlayer(final Game pGame)
	{
		final List<Event> events = new ArrayList<>(pGame.getEvents());
		events.sort(Comparator.comparing(Event::getTstamp, Comparator.nullsLast(Comparator.naturalOrder())));

		final Map<String, Integer> achievements = new TreeMap<>();
		final Map<String, Integer> playerDebts = new HashMap<>();
		int currentFactor = 1;

		for (final Event event : events)
		{
			final String playerName = event.getPlayer() == null ? null : event.getPlayer().getName();
			switch (event.getEvt())
			{
				case NEW_CREDIT:
					playerDebts.merge(playerName, event.getPrincipal(), Integer::sum);
					break;
				case JOIN:
				case TURN:
				case MM_CHANGE:
				case END:
					break;
				case INTEREST_ONLY:
					// Va à la banque (non suivie ici) : n'affecte pas la richesse du joueur.
					break;
				case CANNOT_PAY:
				case BANKRUPT:
				case PRISON:
				case REIMB_CREDIT:
					// Le remboursement retire de la dette suivie, mais ne modifie pas la
					// richesse "gagnée" du joueur lui-même (elle va à la banque, non suivie ici).
					playerDebts.remove(playerName);
					break;
				case DEATH:
				case QUIT:
					if (playerName != null)
						addGain(pGame, event, playerName, achievements, currentFactor, false);
					break;
				case XTECHNOLOGICAL_BREAKTHROUGH:
					currentFactor *= 2;
					break;
				case SIDE_INVESTMENT:
				case ASSESSMENT_FINAL:
					// Concernent la banque (non suivie dans cette vue centrée joueurs).
					break;
				default:
					break;
			}
		}
		return achievements;
	}

	/** Portage de {@code StatsFrame.addFromEvent} : calcule le gain apporté par un événement. */
	private void addGain(final Game pGame, final Event pEvent, final String pPlayerName,
			final Map<String, Integer> pAchievements, final int pCurrentFactor, final boolean pSubtract)
	{
		final int currentValue = pAchievements.getOrDefault(pPlayerName, 0);
		final int gained = computeGain(pGame, pEvent, pCurrentFactor);
		pAchievements.put(pPlayerName, currentValue + (pSubtract ? -gained : gained));
	}

	/**
	 * Calcul du gain apporté par un événement pour le joueur concerné (portage de
	 * {@code StatsFrame.addFromEvent}), extrait en fonction pure indépendante de tout
	 * état accumulé - réutilisée à la fois par {@link #addGain} (calcul "à la mort",
	 * pour la répartition des richesses) et par {@link #computeWealthOverTime}
	 * (accumulation continue, tour par tour, pour le graphique de convergence façon
	 * module Galilée).
	 */
	/**
	 * Richesse d'un JOUEUR à sa mort/sortie (voir computeWealthByPlayer /
	 * computeWealthOverTime).
	 * <p>
	 * ⚠️ Historique (24/08/2026) : une tentative d'alignement sur un tableur
	 * transmis par l'utilisateur (geconomicus_money.ods) avait fait passer la
	 * monnaie dette sur des jetons faibles/moyens/forts (comme la monnaie
	 * libre) et retiré le facteur carte/monnaie de la valeur des cartes. Sur
	 * vérification directe auprès de l'utilisateur ET du code de l'application
	 * Swing d'origine ({@code StatsFrame.addFromEvent}, jamais modifié depuis),
	 * ces deux changements étaient erronés - le tableur ne reflétait pas
	 * fidèlement les règles réelles. Revenu à la formule d'origine, exacte :
	 * <pre>
	 * dette : principal + intérêts + TECH × facteur carte/monnaie × (cartes faibles + 2×moyennes + 4×fortes)
	 * libre : (jetons faibles + 2×moyens + 4×forts) / 3 + TECH × facteur carte/monnaie × (cartes faibles + 2×moyennes + 4×fortes)
	 * </pre>
	 * La monnaie dette n'a jamais eu qu'un seul type de jeton (la "monnaie
	 * restante"), jamais de jetons faible/moyen/fort - contrairement aux
	 * cartes valeurs, qui existent bien à trois niveaux dans les deux systèmes.
	 */
	private int computeGain(final Game pGame, final Event pEvent, final int pCurrentFactor)
	{
		// Troc (voir plugins/troc/manifest.json, wealthFormula) : aucune valeur
		// imposée sur les objets - une carte compte pour 1, quel que soit son
		// niveau (règle 7 de docs/10-etape-plugins-troc.md), donc pas de
		// multiplication par un facteur ni de pondération faible/moyen/fort comme
		// pour les deux autres systèmes. La valeur vient directement de
		// l'inventaire saisi à la mort/sortie (goodsFromPlayer), pas d'un calcul à
		// partir de cartes typées.
		if (pGame.getMoneySystem() == Game.MONEY_TROC)
			return pEvent.getGoodsFromPlayer();

		int gained;
		if (pGame.getMoneySystem() == Game.MONEY_DEBT)
			gained = pEvent.getPrincipal() + pEvent.getInterest();
		else
			gained = (pEvent.getWeakCoins() + 2 * pEvent.getMediumCoins() + 4 * pEvent.getStrongCoins()) / 3;
		gained += (pEvent.getWeakCards() + 2 * pEvent.getMediumCards() + 4 * pEvent.getStrongCards()) * pCurrentFactor
				* pGame.getMoneyCardsFactor();
		return gained;
	}

	/**
	 * Ancien calcul de richesse (principal + intérêts d'un crédit), gardé tel
	 * quel - à la demande de l'utilisateur, pas supprimé - car il reste
	 * pertinent pour suivre les transactions de la BANQUE (voir
	 * {@link #computeBankWealth}) : un remboursement, un défaut de paiement, un
	 * bilan final s'expriment naturellement en principal/intérêts, jamais en
	 * jetons/cartes détenus (ce ne sont pas des événements d'inventaire d'un
	 * joueur). N'existe qu'en monnaie dette - la banque n'existe pas dans les
	 * deux autres systèmes.
	 */
	private int computeBankTransactionValue(final Event pEvent)
	{
		return pEvent.getPrincipal() + pEvent.getInterest();
	}

	/**
	 * Part des bénéfices de la banque (intérêts perçus + valeurs saisies aux
	 * joueurs en défaut) dans sa richesse totale (bénéfices + ce qu'elle a déjà
	 * réinvesti) - demandé par l'utilisateur, pour un histogramme dédié
	 * uniquement à la banque sur l'écran Statistiques. Lit directement les
	 * compteurs déjà tenus à jour sur {@code Game} au fil des événements (voir
	 * {@code Game.gainInterest}/{@code seizeValues}/{@code investMoney}/
	 * {@code investCards}) plutôt que de rejouer l'historique - ces compteurs
	 * existaient déjà avant cette fonctionnalité, pour d'autres besoins.
	 */
	public record BankProfitBreakdown(int profit, int reinvested, int total)
	{
	}

	public BankProfitBreakdown computeBankProfitBreakdown(final Game pGame)
	{
		final int profit = pGame.getInterestGained() + pGame.getSeizedValues();
		final int reinvested = pGame.getMoneyInvestBank() + pGame.getCardsInvestBank();
		return new BankProfitBreakdown(profit, reinvested, profit + reinvested);
	}

	private static double round1(final double pValue)
	{
		return Math.round(pValue * 10) / 10.0;
	}

	private static double round2(final double pValue)
	{
		return Math.round(pValue * 100) / 100.0;
	}
}
