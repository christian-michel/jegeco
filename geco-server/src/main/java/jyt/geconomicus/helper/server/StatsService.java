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
import jyt.geconomicus.helper.Transaction;

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

	/**
	 * La richesse accumulée par un joueur au cours de la partie (voir
	 * {@link #computeWealthByPlayer}). {@code quitEarly} (04/10/2026, voir
	 * Player.quit) : vrai si ce joueur a quitté la partie en cours de route
	 * (libre+strict TRM+smartphone) - décision utilisateur : il continue de
	 * toucher le Dividende Universel et reste donc compté normalement dans
	 * cette richesse (jamais figé à sa valeur de sortie), mais ce champ
	 * permet au client de le distinguer visuellement (toujours visible,
	 * jamais masqué) d'un joueur qui a joué la partie jusqu'au bout.
	 */
	public record PlayerWealth(String playerName, int wealth, boolean quitEarly)
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
	 * Échanges carte-contre-jetons (dette/libre, mode smartphone - voir
	 * {@link Transaction}) survenus PENDANT le tour donné : nombre d'échanges
	 * et leur valeur totale en unités monétaires (voir
	 * {@code Transaction.totalCoinsValue()}, déjà net de tout rendu de
	 * monnaie, puis converti en unités monétaires - voir
	 * {@link StatsService#monetaryUnitsPerJeton}).
	 * <p>
	 * BUG TROUVÉ ET CORRIGÉ (27/09/2026, relecture indépendante + campagne de
	 * test en HTTP réel) : {@code totalValue} était un nombre de JETONS FAIBLES
	 * (ce que compte {@code totalCoinsValue()}), affiché pourtant sous l'axe
	 * "Unités monétaires" et comparé visuellement à la masse monétaire, elle
	 * toujours en unités monétaires (voir Game.computeMoneyMassFromActivePlayersJetons).
	 * Identique tant que "Valeur d'une pièce faible" vaut 1, faux dès qu'elle
	 * diffère - mesuré avec 0,5 : 48 échanges affichés pour 24612 "unités"
	 * au lieu de 12306, et au tour 2 44 "unités" échangées pour une masse
	 * monétaire totale de 26 unités. Devient un {@code double} : une valeur en
	 * jetons × 0,5 (ou 0,2...) n'est plus forcément entière.
	 */
	public record ExchangeTurnPoint(int turn, int count, double totalValue)
	{
	}

	/**
	 * Activité d'échange d'un joueur, tous rôles confondus (acheteur ET
	 * vendeur) : {@code count} est le nombre de transactions où ce joueur
	 * apparaît d'un côté ou de l'autre, {@code totalValue} la somme des
	 * valeurs de ces mêmes transactions - une transaction compte donc une
	 * fois pour l'acheteur ET une fois pour le vendeur (deux participants
	 * distincts), contrairement à {@code globalCount}/{@code globalValue} de
	 * {@link ExchangeStats} qui ne comptent chaque transaction qu'une seule
	 * fois. {@code totalValue} en unités monétaires (voir {@link ExchangeTurnPoint}).
	 */
	public record PlayerExchangeStat(String playerName, int count, double totalValue)
	{
	}

	/**
	 * Statistiques des échanges monétaires individuels carte-contre-jetons
	 * (dette/libre, mode smartphone uniquement - voir {@link Transaction}).
	 * Répond à la demande d'un utilisateur (27/09/2026) : "connaître le
	 * nombre global d'échanges au cours de la partie... et la répartition de
	 * ces échanges (dans le temps au cours des tours - et parmi les
	 * joueurs)... la valeur des échanges en unités monétaires à chaque
	 * tour". Exclut volontairement les échanges troc (carte contre carte,
	 * jamais de valeur monétaire - voir {@code Transaction.isCardSwap()}/
	 * {@code isGoodsTrade()}) : cette statistique n'a de sens qu'en monnaie
	 * dette/libre. Toutes les valeurs en unités monétaires (voir
	 * {@link ExchangeTurnPoint}, correctif du 27/09/2026).
	 */
	public record ExchangeStats(int globalCount, double globalValue, double averageValuePerExchange,
			double medianValuePerExchange, double averageCountPerTurn, double medianCountPerTurn,
			List<ExchangeTurnPoint> byTurn, List<PlayerExchangeStat> byPlayer)
	{
	}

	/**
	 * Masse monétaire à la fin d'un tour, complétée de sa VARIATION depuis le
	 * tour précédent ({@code massDelta}, positif = création nette, négatif =
	 * destruction nette - voir CLAUDE.md, "Le calcul du DU") et du ratio
	 * masse/joueurs actifs ({@code massPerPlayer}, "l'accès à la monnaie"
	 * demandé par un utilisateur). {@code massDelta} est dérivé
	 * OBSERVATIONNELLEMENT (différence entre deux {@link MoneyMassPoint}
	 * consécutifs) plutôt que recalculé depuis les multiples chemins de
	 * mutation de la masse dans {@code Event.applyEvent()} (TURN/DEATH/
	 * NEW_CREDIT/REIMB_CREDIT, qui diffèrent selon le système monétaire et le
	 * mode strict TRM) : la masse elle-même reste la seule source de vérité,
	 * jamais recalculée séparément par une seconde formule qui pourrait
	 * diverger.
	 * <p>
	 * Précision de sens (29/09/2026, décision utilisateur suite à la relecture
	 * indépendante du 27-28/09/2026) : le point du "tour t" est la masse telle
	 * qu'elle se présente APRÈS TOUT ce qui s'est produit PENDANT le tour t
	 * (DU distribué, crédits accordés, morts/sorties - tout événement du tour
	 * t, quel que soit son type), pas seulement ce qui existait à son
	 * ouverture. Un point est donc mis à jour à CHAQUE événement rejoué
	 * (jamais seulement à l'événement TURN), en ne conservant que la DERNIÈRE
	 * valeur vue pour un numéro de tour donné - pour une partie EN COURS, le
	 * tour courant (pas encore terminé) apparaît donc déjà avec sa masse la
	 * plus à jour, affinée au fur et à mesure ("les stats affichent les
	 * données au fur et à mesure des tours", remonté par l'utilisateur).
	 * <p>
	 * BUG TROUVÉ ET CORRIGÉ (27-28/09/2026, relecture indépendante) : la
	 * version précédente ne capturait qu'à l'événement TURN qui OUVRE le tour
	 * - juste avant tout événement du tour lui-même. Sans effet en monnaie
	 * LIBRE (le DU y est toujours distribué à la FRONTIÈRE entre deux tours,
	 * via WEALTH_CHECKPOINT, donc déjà reflété dès l'ouverture du tour
	 * suivant), mais faux en monnaie DETTE : un crédit peut être accordé à
	 * N'IMPORTE QUEL moment du tour (voir {@code Event.applyEvent}, cas
	 * NEW_CREDIT, qui incrémente {@code Game.moneyMass} immédiatement, sans
	 * condition de moment) - mesuré : masse affichée aux tours 1-4 = 0/74/138/
	 * 162 au lieu de 74/138/162/206 (la valeur réelle de fin de tour), un
	 * décalage d'un tour complet, avec un "tour 1" à 0 alors que 6 échanges
	 * avaient déjà eu lieu avec les 74 unités créées ce même tour.
	 */
	public record MoneyMassDetailPoint(int turn, int moneyMass, int massDelta, int activePlayers,
			double massPerPlayer)
	{
	}

	/**
	 * Historique détaillé de la masse monétaire (voir {@link MoneyMassDetailPoint}),
	 * avec moyenne et médiane du ratio masse/joueurs et de la variation par
	 * tour - demandé explicitement par un utilisateur ("Montre les moyennes,
	 * et les médianes").
	 */
	public record MoneyMassDetailReport(List<MoneyMassDetailPoint> points, double averageMassPerPlayer,
			double medianMassPerPlayer, double averageMassDelta, double medianMassDelta)
	{
	}

	/**
	 * Rapport combiné échanges + masse monétaire détaillée, réservé aux
	 * parties en monnaie dette OU libre suivies par smartphone (seules à
	 * avoir de vraies {@link Transaction} individuelles - voir
	 * {@link #isSmartphoneTrackedGame}) : {@code applicable} vaut faux pour
	 * le troc (pas de valeur monétaire, voir docs/10-etape-plugins-troc.md,
	 * règle 6) et pour toute partie classique sans smartphone (aucune
	 * Transaction n'existe alors) - dans ces deux cas, {@code exchangeStats}
	 * et {@code moneyMassDetail} valent {@code null} plutôt que des rapports
	 * vides trompeurs.
	 */
	public record ExchangeAndMoneyReport(boolean applicable, ExchangeStats exchangeStats,
			MoneyMassDetailReport moneyMassDetail)
	{
	}

	/**
	 * Un point de la courbe de richesse d'un joueur au tour donné, désormais
	 * ÉCLATÉ en trois grandeurs distinctes (remonté par un utilisateur,
	 * 04/10/2026, PDF "Retours_-_20261004.pdf" : "l'affichage de la richesse
	 * sous forme de courbes semble comptabiliser les valeurs y compris les
	 * cartes converties en unités monétaires... je souhaiterais 3 graphiques
	 * à la place") :
	 * <ul>
	 * <li>{@code monetaryValue} - unités monétaires (jetons) SEULES, jamais
	 * les cartes. C'est cette seule grandeur, rapportée à la moyenne de la
	 * masse monétaire par joueur actif à cet instant (M(t)/N(t), voir
	 * {@code relativeToAverage}), qui correspond au "module Galilée" de la
	 * Théorie Relative de la Monnaie : elle seule converge vers 1.0 pour
	 * tout joueur au fil du temps en monnaie libre (un compte qui démarre à
	 * 0 rejoint la moyenne aux alentours de la moitié de l'espérance de vie
	 * simulée) - la théorie porte sur la MONNAIE, jamais sur la valeur des
	 * cartes détenues.</li>
	 * <li>{@code cardsValue} - valeur des cartes détenues SEULE, convertie en
	 * unités monétaires (même formule que {@code computeGain}, jamais les
	 * jetons/unités monétaires réels.</li>
	 * <li>{@code combinedValue} - les deux grandeurs ci-dessus additionnées
	 * (= l'ancienne et unique valeur de ce point avant ce correctif,
	 * toujours disponible pour qui veut la vue d'ensemble).</li>
	 * </ul>
	 */
	public record PlayerWealthPoint(int turn, int monetaryValue, int cardsValue, int combinedValue,
			double relativeToAverage)
	{
	}

	/**
	 * {@code quitEarly} (04/10/2026, voir Player.quit) : vrai si ce joueur a
	 * quitté la partie en cours de route - sa série continue malgré tout de
	 * recevoir un nouveau point à chaque tour (il continue de toucher le DU),
	 * ce champ sert uniquement à ce que le client distingue visuellement sa
	 * courbe (ex. ligne en pointillés) sans jamais la masquer.
	 */
	public record PlayerWealthSeries(String playerName, List<PlayerWealthPoint> points, boolean quitEarly)
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
				.map(e -> new PlayerWealth(e.getKey(), e.getValue(), isPlayerQuit(pGame, e.getKey())))
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
	 * <b>Limite assumée</b>, désormais partielle depuis l'étape 3 (voir
	 * EventType.WEALTH_CHECKPOINT) : en monnaie libre, si l'animateur utilise
	 * l'assistant de fin de tour en mode smartphone, la richesse de CHAQUE joueur
	 * actif (pas seulement ceux qui meurent) est désormais mesurée à chaque tour,
	 * à partir des transactions réellement enregistrées - la courbe est alors
	 * continue et précise, comme anticipé de longue date dans ce commentaire.
	 * Sans ce mécanisme (monnaie dette, partie classique sans smartphone, ou
	 * animateur qui saisit encore l'inventaire à la main), la limite d'origine
	 * s'applique toujours : le moteur ne connaît la richesse réelle d'un joueur
	 * qu'aux évaluations Mort/Fin de partie (`DEATH`/`QUIT`), et cette méthode
	 * **maintient la dernière valeur connue** entre deux évaluations plutôt que
	 * d'inventer une évolution qui ne reposerait sur aucune donnée réelle.
	 */
	public WealthOverTimeReport computeWealthOverTime(final Game pGame)
	{
		// [0]=monétaire, [1]=cartes - voir la Javadoc de PlayerWealthPoint
		// (éclatement des deux grandeurs demandé par un utilisateur le
		// 04/10/2026).
		final Map<String, int[]> lastKnownValue = new java.util.LinkedHashMap<>();
		final Map<String, List<PlayerWealthPoint>> pointsByPlayer = new java.util.LinkedHashMap<>();
		for (final Player p : pGame.getPlayers())
		{
			lastKnownValue.put(p.getName(), new int[] { 0, 0 });
			// Remonté par l'utilisateur (13/09/2026) : "la courbe ne doit pas inclure
			// le tour 0, elle doit démarrer au début du tour 1" - plus de point de
			// départ artificiel à (0, 0) ici, le premier point réel de chaque joueur
			// est désormais celui posé par le tout premier événement TURN (voir plus
			// bas), à moins que le joueur n'ait encore aucun tour joué (partie tout
			// juste créée), auquel cas sa série reste simplement vide plutôt que de
			// montrer un zéro qui n'a jamais correspondu à un tour réel.
			pointsByPlayer.put(p.getName(), new ArrayList<>());
		}

		// BUG TROUVÉ ET CORRIGÉ (04/10/2026, en écrivant les tests de la
		// fonctionnalité "joueur qui quitte en cours de partie continue de
		// toucher le DU", voir Player.quit) : le correctif du 13/09/2026
		// ci-dessous (`turn = QUIT ? nbTurnsPlanned : turnCounter[0]`)
		// supposait "QUIT ne survient JAMAIS qu'au tout dernier tour de la
		// partie" - vrai jusqu'ici (seul l'assistant de FIN DE PARTIE postait
		// des QUIT), mais plus du tout depuis qu'un joueur peut quitter
		// n'importe quand en cours de route (openPlayerQuitDialog). Forcer
		// SYSTÉMATIQUEMENT `turn = nbTurnsPlanned` pour un QUIT mid-partie
		// plaçait son point au MAUVAIS tour (le dernier de la partie, pas
		// celui où il a réellement quitté) - mesuré : une partie à 5 tours
		// où Alice quitte au tour 1 lui donnait un point supplémentaire
		// fantôme au tour 5, et son point légitime du tour 1 n'était jamais
		// dédupliqué avec l'ouverture de ce même tour (comparaison de tours
		// différents). Corrigé en précalculant ICI, une seule fois, l'ensemble
		// des QUIT réellement "de fin de partie" (ceux qu'AUCUN TURN ne suit
		// plus nulle part dans l'historique complet) - seuls CEUX-LÀ gardent
		// le correctif du 13/09/2026 ; tout autre QUIT (mid-partie) utilise
		// désormais le tour RÉEL où il survient, exactement comme DEATH.
		final List<Event> allEventsByTime = new ArrayList<>(pGame.getEvents());
		allEventsByTime.sort(Comparator.comparing(Event::getTstamp, Comparator.nullsLast(Comparator.naturalOrder())));
		final java.util.Set<Event> endOfGameQuitEvents = new java.util.HashSet<>();
		boolean turnSeenScanningFromEnd = false;
		for (int i = allEventsByTime.size() - 1; i >= 0; i--)
		{
			final Event e = allEventsByTime.get(i);
			if (e.getEvt() == EventType.TURN)
				turnSeenScanningFromEnd = true;
			else if ((e.getEvt() == EventType.QUIT) && !turnSeenScanningFromEnd)
				endOfGameQuitEvents.add(e);
		}

		final int[] turnCounter = { 0 };
		final int[] currentFactor = { 1 };
		pGame.recomputeAll(event -> {
			if (event.getEvt() == EventType.TURN)
			{
				turnCounter[0]++;
				final int mass = pGame.getMoneyMass();
				// Élargi à isQuit() (04/10/2026, voir Player.quit/Game.computeCurrentDU) :
				// un joueur qui a quitté continue de toucher le DU et doit donc rester
				// compté dans cette moyenne M(t)/N(t) - isQuit() reste toujours faux en
				// dehors de ce cas précis (libre+strict TRM+smartphone), élargissement
				// sans effet ailleurs (idem plus bas dans cette même méthode).
				final long activeCount = pGame.getPlayers().stream().filter(p2 -> p2.isActive() || p2.isQuit()).count();
				final double average = activeCount == 0 ? 0 : (double) mass / activeCount;
				for (final Player p : pGame.getPlayers())
				{
					final int[] known = lastKnownValue.getOrDefault(p.getName(), new int[] { 0, 0 });
					final double relative = average == 0 ? 0 : known[0] / average;
					pointsByPlayer.get(p.getName())
							.add(new PlayerWealthPoint(turnCounter[0], known[0], known[1], known[0] + known[1],
									round2(relative)));
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
				final int assessedMonetary = computeMonetaryGain(pGame, event);
				final int assessedCards = computeCardsGain(pGame, event, currentFactor[0]);
				final int mass = pGame.getMoneyMass();
				final long activeCount = pGame.getPlayers().stream().filter(p2 -> p2.isActive() || p2.isQuit()).count();
				final double average = activeCount == 0 ? 0 : (double) mass / activeCount;
				final double relative = average == 0 ? 0 : assessedMonetary / average;
				// BUG TROUVÉ ET CORRIGÉ (remonté par l'utilisateur, 13/09/2026, PDF avec
				// captures d'écran - "ne pas montrer le point où le compte revient à
				// zéro quand ils quittent la partie à la toute fin, la courbe doit
				// s'arrêter sur leur score final") : un QUIT de FIN DE PARTIE (voir
				// renderEndGameInventory dans app.js - "à la fin du dernier tour, il
				// n'y a jamais de mort" - QUIT y est posté pour TOUS les joueurs
				// actifs, sans événement TURN derrière puisque la partie s'arrête là)
				// laissait turnCounter[0] encore égal au tour PRÉCÉDENT (aucun TURN ne
				// l'avait incrémenté pour ce dernier tour) : ce point se retrouvait au
				// MÊME tour que celui déjà posé par le TURN d'entrée dans ce dernier
				// tour - deux points distincts au même x, un artefact visuel qui
				// pouvait ressembler à une chute/un retour à zéro juste avant la fin
				// de la courbe. DEATH, lui, ne survient jamais au dernier tour (mort/
				// renaissance en cours de partie uniquement) et un TURN event suit
				// toujours peu après pour incrémenter turnCounter normalement -
				// turnCounter[0] y reste donc correct, inchangé.
				// CORRECTIF ÉLARGI (04/10/2026, voir endOfGameQuitEvents précalculé
				// plus haut) : cette correction supposait à tort "QUIT ne survient
				// JAMAIS qu'au tout dernier tour" - plus vrai depuis qu'un joueur peut
				// quitter en cours de partie (Player.quit) tout en continuant d'être
				// rejoué ici. Seuls les QUIT vraiment "de fin de partie" (aucun TURN
				// ne les suit plus nulle part) gardent ce correctif ; un QUIT mid-
				// partie utilise désormais le tour RÉEL où il survient, comme DEATH.
				final int turn = endOfGameQuitEvents.contains(event) ? pGame.getNbTurnsPlanned() : turnCounter[0];
				// BUG TROUVÉ ET CORRIGÉ (04/10/2026, PDF "Retours_-_20261004.pdf") :
				// "sur les courbes... il faut afficher les valeurs en fin de tour,
				// juste avant qu'ils ne quittent la partie". Le correctif du
				// 13/09/2026 ci-dessus force bien le POINT DEATH/QUIT au bon tour,
				// mais un point TURN avait souvent déjà été posé pour CE MÊME tour
				// (à son ouverture, avec la valeur d'AVANT les échanges du tour -
				// voir le bloc TURN ci-dessus) : deux points distincts au même x,
				// reliés par une ligne, donnaient l'impression d'une chute/un pic
				// artificiel juste avant la fin de la courbe - exactement ce que
				// l'utilisateur décrivait comme "les comptes qui retombent à 0"
				// (aucun zéro n'était réellement tracé, mais l'effet visuel y
				// ressemblait). Corrigé en remplaçant ce point d'ouverture de tour,
				// désormais obsolète, par le bilan RÉEL DEATH/QUIT - un seul point
				// par joueur et par tour, toujours le plus à jour.
				final List<PlayerWealthPoint> playerPoints = pointsByPlayer.get(name);
				if (!playerPoints.isEmpty() && (playerPoints.get(playerPoints.size() - 1).turn() == turn))
					playerPoints.remove(playerPoints.size() - 1);
				playerPoints.add(new PlayerWealthPoint(turn, assessedMonetary, assessedCards,
						assessedMonetary + assessedCards, round2(relative)));
				lastKnownValue.put(name, new int[] { 0, 0 });
			}
			else if ((event.getEvt() == EventType.WEALTH_CHECKPOINT) && (event.getPlayer() != null)
					&& lastKnownValue.containsKey(event.getPlayer().getName()))
			{
				// Étape 3, monnaie libre, mode smartphone (voir Event.java) : un
				// survivant, contrairement à DEATH/QUIT ci-dessus - on met juste à
				// jour la dernière valeur connue, SANS ajouter de point immédiatement
				// (pas de renaissance à 0 : le joueur continue de jouer) et SANS
				// retirer quoi que ce soit de la masse monétaire (déjà garanti par
				// Event.applyEvent(), qui traite ce type comme un pur no-op). Posé
				// juste avant l'événement TURN dans le déroulé de l'assistant de fin
				// de tour (voir GecoServer/wizard côté web) : le point TURN
				// juste après lira cette valeur fraîchement mise à jour via
				// lastKnownValue.getOrDefault(...) ci-dessus, sans code
				// supplémentaire nécessaire à cet endroit précis.
				lastKnownValue.put(event.getPlayer().getName(), new int[] { computeMonetaryGain(pGame, event),
						computeCardsGain(pGame, event, currentFactor[0]) });
			}
		});

		final List<PlayerWealthSeries> series = pointsByPlayer.entrySet().stream()
				.map(e -> new PlayerWealthSeries(e.getKey(), e.getValue(), isPlayerQuit(pGame, e.getKey()))).toList();
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

	/**
	 * Vrai si au moins un joueur de la partie a une dotation de départ posée
	 * en jetons ({@code Player.startingCardsJson != null}, voir
	 * {@code GameService.dealStartingHandsForLibreIfNeeded}) - c'est-à-dire
	 * une partie dette ou libre jouée en mode smartphone, seule à produire de
	 * vraies {@link Transaction} individuelles exploitables ici.
	 * <p>
	 * Portage volontairement DISTINCT du {@code Event.isSmartphoneTrackedGame}
	 * privé du moteur : celui-ci ne regarde que les joueurs encore ACTIFS
	 * (pertinent en direct, pour savoir comment traiter l'événement courant),
	 * alors qu'un rapport de fin de partie n'a en général plus AUCUN joueur
	 * actif (tous morts/sortis) - on regarde donc ici TOUS les joueurs, actifs
	 * ou non, `startingCardsJson` n'étant jamais remis à `null` après coup
	 * (voir GameService, seul un JSON vide `"{}"` peut lui succéder, jamais
	 * `null`).
	 */
	private boolean isSmartphoneTrackedGame(final Game pGame)
	{
		for (final Player p : pGame.getPlayers())
			if (p.getStartingCardsJson() != null)
				return true;
		return false;
	}

	/**
	 * Vrai si le joueur de ce nom a quitté la partie en cours de route (voir
	 * Player.quit, 04/10/2026) - utilisé pour marquer {@code quitEarly} dans
	 * {@link PlayerWealth}/{@link PlayerWealthSeries}, afin que le client
	 * puisse le distinguer visuellement sans jamais le masquer des stats.
	 * Retombe sur faux pour un nom qui ne correspond à aucun joueur (ex. la
	 * pseudo-entrée "Banque" ajoutée par {@link #computeFinalReport}).
	 */
	private boolean isPlayerQuit(final Game pGame, final String pPlayerName)
	{
		for (final Player p : pGame.getPlayers())
			if (p.getName().equals(pPlayerName))
				return p.isQuit();
		return false;
	}

	/**
	 * Point d'entrée du rapport combiné échanges + masse monétaire détaillée
	 * (voir {@link ExchangeAndMoneyReport}) - {@code pTransactions} doit être
	 * la liste complète des transactions de la partie (voir
	 * {@code GameService.listTransactions}), fournie par l'appelant : ce
	 * service reste volontairement sans accès direct à l'EntityManager (voir
	 * la Javadoc de classe), {@link Game} n'ayant lui-même aucune relation
	 * JPA vers {@link Transaction}.
	 */
	public ExchangeAndMoneyReport computeExchangeAndMoneyReport(final Game pGame,
			final List<Transaction> pTransactions)
	{
		final boolean applicable = ((pGame.getMoneySystem() == Game.MONEY_DEBT)
				|| (pGame.getMoneySystem() == Game.MONEY_LIBRE)) && isSmartphoneTrackedGame(pGame);
		if (!applicable)
			return new ExchangeAndMoneyReport(false, null, null);
		return new ExchangeAndMoneyReport(true, computeExchangeStats(pGame, pTransactions),
				computeMoneyMassDetailHistory(pGame));
	}

	/**
	 * Calcule {@link ExchangeStats} en rejouant la liste des transactions
	 * individuelles (déjà horodatées par tour, voir
	 * {@code Transaction.turnNumber}, pas besoin de recorréler par
	 * timestamp). Exclut les échanges troc (voir {@link ExchangeStats}).
	 */
	private ExchangeStats computeExchangeStats(final Game pGame, final List<Transaction> pTransactions)
	{
		final List<Transaction> monetary = pTransactions.stream()
				.filter(t -> !t.isCardSwap() && !t.isGoodsTrade()).toList();
		// Correctif du 27/09/2026 (voir ExchangeTurnPoint) : les sommes restent
		// tenues en JETONS entiers (long, jamais de dépassement ni d'erreur
		// d'arrondi cumulée), converties en unités monétaires UNE SEULE FOIS à
		// la fin de chaque agrégat.
		final double unitsPerJeton = monetaryUnitsPerJeton(pGame);

		final int globalCount = monetary.size();
		final List<Integer> sortedValues = monetary.stream().map(Transaction::totalCoinsValue).sorted().toList();
		final long globalValueJetons = sortedValues.stream().mapToLong(Integer::longValue).sum();
		final double averageValuePerExchange = globalCount == 0 ? 0
				: ((double) globalValueJetons / globalCount) * unitsPerJeton;
		final double medianValuePerExchange = computeMedian(sortedValues) * unitsPerJeton;

		// Un point par tour DÉJÀ JOUÉ (1..tour courant), même sans aucun
		// échange (compte à 0) - pour que le graphique montre une vraie
		// continuité temporelle plutôt que de ne représenter que les tours
		// où quelque chose s'est produit.
		final Map<Integer, long[]> byTurnMap = new TreeMap<>();
		for (int turn = 1; turn <= Math.max(pGame.getTurnNumber(), 0); turn++)
			byTurnMap.put(turn, new long[2]);
		for (final Transaction t : monetary)
		{
			final long[] bucket = byTurnMap.computeIfAbsent(t.getTurnNumber(), k -> new long[2]);
			bucket[0]++;
			bucket[1] += t.totalCoinsValue();
		}
		final List<ExchangeTurnPoint> byTurn = byTurnMap.entrySet().stream()
				.map(e -> new ExchangeTurnPoint(e.getKey(), (int) e.getValue()[0],
						round2(e.getValue()[1] * unitsPerJeton)))
				.toList();
		final List<Integer> sortedCountsPerTurn = byTurn.stream().map(ExchangeTurnPoint::count).sorted().toList();
		final double averageCountPerTurn = sortedCountsPerTurn.isEmpty() ? 0
				: sortedCountsPerTurn.stream().mapToInt(Integer::intValue).average().orElse(0);
		final double medianCountPerTurn = computeMedian(sortedCountsPerTurn);

		// Chaque transaction implique deux participants (acheteur + vendeur) :
		// comptée une fois pour chacun (voir Javadoc de PlayerExchangeStat).
		final Map<String, long[]> byPlayerMap = new java.util.LinkedHashMap<>();
		for (final Player p : pGame.getPlayers())
			byPlayerMap.put(p.getName(), new long[2]);
		for (final Transaction t : monetary)
		{
			final int value = t.totalCoinsValue();
			if (t.getBuyer() != null)
			{
				final long[] b = byPlayerMap.computeIfAbsent(t.getBuyer().getName(), k -> new long[2]);
				b[0]++;
				b[1] += value;
			}
			if (t.getSeller() != null)
			{
				final long[] s = byPlayerMap.computeIfAbsent(t.getSeller().getName(), k -> new long[2]);
				s[0]++;
				s[1] += value;
			}
		}
		final List<PlayerExchangeStat> byPlayer = byPlayerMap.entrySet().stream()
				.map(e -> new PlayerExchangeStat(e.getKey(), (int) e.getValue()[0],
						round2(e.getValue()[1] * unitsPerJeton)))
				.sorted(Comparator.comparingInt(PlayerExchangeStat::count).reversed()).toList();

		return new ExchangeStats(globalCount, round2(globalValueJetons * unitsPerJeton), round1(averageValuePerExchange),
				round1(medianValuePerExchange), round1(averageCountPerTurn), round1(medianCountPerTurn), byTurn,
				byPlayer);
	}

	/**
	 * Calcule {@link MoneyMassDetailReport} en rejouant l'historique complet
	 * de la partie (même mécanisme que {@link #computeMoneyMassHistory}) :
	 * capture, pour chaque tour, la masse monétaire APRÈS tout ce qui s'y est
	 * produit, sa VARIATION depuis le tour précédent (création si positive,
	 * destruction si négative), le nombre de joueurs actifs et le ratio
	 * masse/joueurs actifs ("l'accès à la monnaie"). Voir la Javadoc de
	 * {@link MoneyMassDetailPoint} pour le raisonnement complet (correctif du
	 * 29/09/2026, décision utilisateur) : capture à CHAQUE événement rejoué,
	 * pas seulement à l'événement TURN, en ne gardant que la DERNIÈRE valeur
	 * vue pour chaque numéro de tour - un événement qui ne change ni la masse
	 * ni le nombre de joueurs actifs ne fait alors que réécrire la même
	 * valeur (sans incidence), jamais besoin de connaître à l'avance la
	 * liste exacte des types d'événements qui mutent la masse (TURN/DEATH
	 * pour la libre stricte TRM, NEW_CREDIT/REIMB_CREDIT/... pour la dette).
	 */
	private MoneyMassDetailReport computeMoneyMassDetailHistory(final Game pGame)
	{
		// turn -> [masse, joueurs actifs] au dernier événement rejoué de ce
		// tour (TreeMap : parcouru dans l'ordre croissant des tours ensuite).
		final Map<Integer, int[]> byTurn = new TreeMap<>();
		final Map<Player, int[]> jetonsBeforeReplay = resetJetonsToStartOfGameForReplay(pGame);
		// BUG TROUVÉ ET CORRIGÉ (04/10/2026, campagne de test HTTP réelle 4/8/20
		// joueurs, libre+smartphone strict TRM, 12 tours) : pour CHAQUE tour
		// comportant une mort, ce point affichait une masse monétaire TROP
		// BASSE (et le tour suivant une "création" artificiellement gonflée en
		// compensation) - mesuré sur les deux campagnes : écart de -48 à -131
		// unités sur une partie de 4 joueurs selon le tour, et un écart
		// significatif à QUASIMENT CHAQUE tour sur une partie de 8 joueurs (où
		// une mort est programmée presque à chaque tour, voir
		// docs/03-architecture-technique.md, entrée du 04/10/2026, pour le
		// relevé complet). Dans un cas extrême (un joueur très riche meurt
		// alors que la croissance naturelle du tour est encore faible), ce
		// mécanisme pourrait même afficher une barre ROUGE ("destruction
		// monétaire") alors que la masse stricte TRM ne doit JAMAIS diminuer -
		// contredisant visuellement la garantie pédagogique centrale de ce
		// mode (voir FreeMoneySystemTest.testStrictTrmNeverDecreasesMoneyMassAtDeath,
		// qui vérifie la masse elle-même, jamais ce graphique dérivé).
		// <p>
		// Cause : un DEATH, en libre strict TRM suivi par smartphone, recalcule
		// IMMÉDIATEMENT game.moneyMass à partir des jetons courants (voir
		// Event.applyEvent, cas DEATH) - mais à cet instant, le point de
		// contrôle WEALTH_CHECKPOINT qui distribue le DU du tour EN COURS à
		// tous les joueurs (y compris celui qui vient de renaître) n'a pas
		// encore eu lieu : app.js enregistre toujours D (pour les joueurs
		// mourants) PUIS W (pour tous les joueurs actifs, DU inclus) PUIS
		// seulement T (voir openEndOfTurnWizard/renderStep4) - exactement
		// l'ordre rejoué ici. Or WEALTH_CHECKPOINT ne touche JAMAIS
		// game.moneyMass (volontairement, voir son propre cas plus bas) : seul
		// l'événement TURN suivant recalcule la masse complète (jetons post-DU
		// inclus) - mais SOUS LE NUMÉRO DE TOUR SUIVANT, puisque TURN
		// incrémente le compteur de tour AVANT de recalculer. Un premier
		// correctif (ignorer seulement la capture du DEATH lui-même) s'est
		// révélé INSUFFISANT, vérifié en relançant la même campagne : les
		// WEALTH_CHECKPOINT qui suivent le DEATH, bien que ne modifiant jamais
		// la masse, restent capturés normalement et ré-écrivent quand même la
		// valeur incomplète (déjà physiquement appliquée par Event.applyEvent,
		// qu'on l'enregistre ou non dans ce rapport) sous le même numéro de
		// tour.
		// <p>
		// Corrigé en SUSPENDANT toute capture pour le numéro de tour courant
		// dès qu'un tel DEATH survient, jusqu'au prochain TURN (inclus - qui,
		// lui, capture toujours, et lève la suspension) : le tour de la mort
		// garde ainsi la valeur COMPLÈTE déjà posée par la transition TURN qui
		// l'a fait démarrer (identique à n'importe quel autre tour), au lieu
		// d'être écrasée par l'état intermédiaire du DEATH puis "confirmée"
		// telle quelle par les WEALTH_CHECKPOINT qui suivent. Portée
		// volontairement étroite (uniquement ce cas précis, jamais un filtre
		// par type d'événement général) pour ne pas réintroduire le besoin de
		// connaître à l'avance la liste des événements qui mutent la masse
		// pour les AUTRES systèmes monétaires (ex. NEW_CREDIT en dette, qui
		// doit lui rester capturé immédiatement - rien ne le "corrige" plus
		// tard comme le fait TURN ici).
		final boolean[] suppressUntilNextTurn = { false };
		try
		{
			pGame.recomputeAll(event -> {
				final int turn = pGame.getTurnNumber();
				// Rien à montrer avant le tout premier "nouveau tour" (même
				// convention que computeExchangeStats.byTurn, qui démarre à 1).
				if (turn <= 0)
					return;
				final boolean isDeathDeferringMass = (event.getEvt() == EventType.DEATH)
						&& (pGame.getMoneySystem() == Game.MONEY_LIBRE) && pGame.isStrictTrm()
						&& isSmartphoneTrackedGame(pGame);
				if (isDeathDeferringMass)
					suppressUntilNextTurn[0] = true;
				if (suppressUntilNextTurn[0] && (event.getEvt() != EventType.TURN))
					return; // en attente du TURN qui apportera l'état complet de ce tour
				if (event.getEvt() == EventType.TURN)
					suppressUntilNextTurn[0] = false;
				final int mass = pGame.getMoneyMass();
				// Élargi à isQuit() (04/10/2026) pour la même raison que
				// computeWealthOverTime ci-dessus : la masse somme désormais aussi un
				// joueur sorti (voir Game.computeMoneyMassFromActivePlayersJetons),
				// "massPerPlayer" doit donc diviser par la même population.
				final long activeCount = pGame.getPlayers().stream().filter(p2 -> p2.isActive() || p2.isQuit()).count();
				byTurn.put(turn, new int[] { mass, (int) activeCount });
			});
		}
		finally
		{
			// Hygiène : rend à l'objet (détaché, jamais persisté ici) les jetons
			// qu'il avait avant ce rejeu - voir resetJetonsToStartOfGameForReplay.
			for (final Map.Entry<Player, int[]> e : jetonsBeforeReplay.entrySet())
			{
				e.getKey().setJetonWeak(e.getValue()[0]);
				e.getKey().setJetonMedium(e.getValue()[1]);
				e.getKey().setJetonStrong(e.getValue()[2]);
			}
		}

		final List<MoneyMassDetailPoint> points = new ArrayList<>();
		int previousMass = 0;
		for (final Map.Entry<Integer, int[]> e : byTurn.entrySet())
		{
			final int mass = e.getValue()[0];
			final int activePlayers = e.getValue()[1];
			final double massPerPlayer = activePlayers == 0 ? 0 : (double) mass / activePlayers;
			points.add(new MoneyMassDetailPoint(e.getKey(), mass, mass - previousMass, activePlayers,
					round1(massPerPlayer)));
			previousMass = mass;
		}

		final List<Integer> sortedDeltas = points.stream().map(MoneyMassDetailPoint::massDelta).sorted().toList();
		final List<Double> sortedMassPerPlayer = points.stream().map(MoneyMassDetailPoint::massPerPlayer).sorted()
				.toList();
		final double averageMassDelta = sortedDeltas.isEmpty() ? 0
				: sortedDeltas.stream().mapToInt(Integer::intValue).average().orElse(0);
		final double averageMassPerPlayer = sortedMassPerPlayer.isEmpty() ? 0
				: sortedMassPerPlayer.stream().mapToDouble(Double::doubleValue).average().orElse(0);

		return new MoneyMassDetailReport(points, round1(averageMassPerPlayer), round1(computeMedianDouble(sortedMassPerPlayer)),
				round1(averageMassDelta), round1(computeMedian(sortedDeltas)));
	}

	private double computeMedian(final List<Integer> pSortedValues)
	{
		if (pSortedValues.isEmpty())
			return 0;
		final int n = pSortedValues.size();
		return n % 2 == 1 ? pSortedValues.get(n / 2)
				: (pSortedValues.get(n / 2 - 1) + pSortedValues.get(n / 2)) / 2.0;
	}

	/**
	 * Valeur, en unités monétaires, d'un jeton faible tel que compté par
	 * {@code Transaction.totalCoinsValue()} - voir le correctif du 27/09/2026
	 * sur {@link ExchangeTurnPoint}. Monnaie LIBRE : "Valeur d'une pièce
	 * faible" de la partie (le prix d'une carte y est converti en jetons
	 * PHYSIQUES par division par cette valeur, voir GameService.levelValue).
	 * Monnaie DETTE smartphone : toujours 1 - "1 jeton faible = 1 unité
	 * monétaire" (voir CLAUDE.md et Event.applyEvent, cas NEW_CREDIT, qui
	 * crédite jetonWeak du principal tel quel, sans aucune conversion).
	 */
	private static double monetaryUnitsPerJeton(final Game pGame)
	{
		if (pGame.getMoneySystem() != Game.MONEY_LIBRE)
			return 1;
		return (pGame.getWeakCoinValue() == 0) ? 1 : pGame.getWeakCoinValue();
	}

	/**
	 * BUG TROUVÉ ET CORRIGÉ (27/09/2026, relecture indépendante + campagne de
	 * test 2/4/10 joueurs en HTTP réel) : en monnaie LIBRE suivie par
	 * smartphone, le point "tour 1" de la masse détaillée valait la masse
	 * FINALE de la partie (mesuré : 14725 au lieu de 28 pour 4 joueurs, 28207
	 * au lieu de 70 pour 10 joueurs), et le tour 2 affichait en conséquence une
	 * "destruction" fictive de presque toute cette masse (-14673 au lieu de
	 * +24) - faussant aussi les moyennes/médianes de variation et de masse par
	 * joueur. Cause (même mécanisme que la limite "Tour 1" déjà documentée dans
	 * CLAUDE.md pour le graphique "masse monétaire") : en direct, le tout
	 * premier TURN est appliqué AVANT la mise en place (dotation de 7 unités),
	 * puis GameService.dealStartingHandsForLibreIfNeeded resynchronise la masse
	 * sur les jetons tout juste distribués ; en REJEU (Game.recomputeAll, qui ne
	 * remet jamais Player.jetonWeak à zéro), ce même TURN voit les joueurs déjà
	 * suivis par smartphone et recalcule la masse à partir de leurs jetons
	 * ACTUELS (ceux de fin de partie), jamais de ceux du départ.
	 * <p>
	 * Correctif volontairement LOCAL à ce rapport (lecture seule, objet détaché
	 * jamais persisté - aucun effet sur le rejeu utilisé par Annuler/éditer,
	 * dont la refonte reste une décision utilisateur en attente, voir
	 * CLAUDE.md) : avant le rejeu, chaque joueur reçoit les jetons qu'il avait
	 * RÉELLEMENT au début du tour 1 - la dotation de départ
	 * (Game.computeStartingJetonsPerPlayer, exactement ce que distribue
	 * dealStartingHandsForLibreIfNeeded) pour un joueur ayant rejoint avant le
	 * premier TURN, 0 pour un joueur arrivé plus tard (aucune dotation en
	 * direct avant son premier point de contrôle WEALTH_CHECKPOINT). Les
	 * tours suivants sont déjà exacts sans cela (chaque WEALTH_CHECKPOINT
	 * rejoué, posé pour TOUS les joueurs actifs juste avant chaque TURN,
	 * remet les jetons à leur valeur réelle). Sans objet hors libre+smartphone
	 * (la dette ne recalcule jamais la masse depuis les jetons) : renvoie alors
	 * une table vide, sans rien modifier. Renvoie les jetons d'avant la
	 * modification, pour que l'appelant les restaure après le rejeu.
	 */
	private Map<Player, int[]> resetJetonsToStartOfGameForReplay(final Game pGame)
	{
		final Map<Player, int[]> before = new java.util.LinkedHashMap<>();
		if ((pGame.getMoneySystem() != Game.MONEY_LIBRE) || !isSmartphoneTrackedGame(pGame))
			return before;
		// Par identifiant plutôt que par instance : ne dépend pas de l'identité
		// d'objet JPA entre Event.getPlayer() et Game.getPlayers().
		final java.util.Set<Integer> joinedBeforeFirstTurn = new java.util.HashSet<>();
		for (final Event event : pGame.getEvents())
		{
			if (event.getEvt() == EventType.TURN)
				break;
			if ((event.getEvt() == EventType.JOIN) && (event.getPlayer() != null))
				joinedBeforeFirstTurn.add(event.getPlayer().getId());
		}
		final int startingJetons = pGame.computeStartingJetonsPerPlayer();
		for (final Player p : pGame.getPlayers())
		{
			before.put(p, new int[] { p.getJetonWeak(), p.getJetonMedium(), p.getJetonStrong() });
			p.setJetonWeak(joinedBeforeFirstTurn.contains(p.getId()) ? startingJetons : 0);
			p.setJetonMedium(0);
			p.setJetonStrong(0);
		}
		return before;
	}

	/** Même principe que {@link #computeMedian(List)}, pour une liste de {@code double} déjà triée. */
	private double computeMedianDouble(final List<Double> pSortedValues)
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
				.map(e -> new PlayerWealth(e.getKey(), e.getValue(), isPlayerQuit(pGame, e.getKey())))
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
		// 04/10/2026 (voir Player.quit) : la valeur du DERNIER WEALTH_CHECKPOINT
		// traité pour un joueur qui a quitté, servant de référence pour calculer
		// un DELTA (voir le cas WEALTH_CHECKPOINT ci-dessous) - jamais addGain
		// directement, qui ACCUMULE plutôt que REMPLACE : un même solde ouvert
		// (celui du "dernier segment de vie", amorcé par l'événement QUIT lui-
		// même) ne doit être compté qu'une fois en net, même rafraîchi à chaque
		// tour.
		// BUG TROUVÉ ET CORRIGÉ (04/10/2026, en écrivant les tests de cette
		// fonctionnalité) : cette référence doit être UNIQUEMENT la composante
		// MONÉTAIRE (computeMonetaryGain), jamais computeGain au complet
		// (monnaie + cartes) - un WEALTH_CHECKPOINT ne représente JAMAIS de
		// mouvement de cartes (toujours 0, voir Event.java), donc comparer un
		// computeGain complet (avec les cartes réellement détenues au moment
		// du QUIT) à un computeGain ultérieur (cartes toujours à 0)
		// soustrairait à tort la valeur des cartes dès le premier
		// WEALTH_CHECKPOINT qui suit - alors qu'elle a déjà été comptée UNE
		// FOIS, correctement, par addGain ci-dessous à l'instant du QUIT. Les
		// cartes d'un joueur sorti ne bougent plus jamais après coup (son
		// inventaire est vidé, voir GameService) : seule la composante
		// monétaire a besoin d'un suivi par delta.
		final Map<String, Integer> openQuitSegmentMonetaryValue = new HashMap<>();
		// BUG TROUVÉ ET CORRIGÉ (04/10/2026, seconde relecture indépendante du
		// commit 8006d14) : la condition d'origine ci-dessous, pour le cas
		// WEALTH_CHECKPOINT, testait `event.getPlayer().isQuit()` - CE CHAMP
		// REFLÈTE L'ÉTAT FINAL (ACTUEL) DU JOUEUR, PAS SON ÉTAT AU MOMENT DE
		// CET ÉVÉNEMENT PRÉCIS. Puisque CHAQUE joueur actif en libre+smartphone
		// reçoit déjà un WEALTH_CHECKPOINT à CHAQUE tour (mécanisme pré-existant,
		// voir app.js/wizFinish - pas spécifique aux joueurs sortis), un joueur
		// qui finit par quitter APRÈS avoir joué plusieurs tours actifs se
		// retrouvait avec TOUS ses WEALTH_CHECKPOINT antérieurs à son QUIT
		// (reçus alors qu'il était encore parfaitement actif) traités à tort
		// comme faisant partie de son "segment de sortie" - ajoutant leur valeur
		// en PLUS de ce que addGain(QUIT) compte déjà intégralement (qui est une
		// valeur ABSOLUE incluant toute la croissance entre sa renaissance/son
		// arrivée et son QUIT, donc DÉJÀ ces mêmes WEALTH_CHECKPOINT antérieurs).
		// Mesuré par une campagne HTTP réelle (3 joueurs, l'un meurt puis rejoue
		// 5 tours actifs avant de quitter tardivement) : richesse finale gonflée
		// de 64 unités (1171 au lieu de 1107, +5,8%) rien que pour ce joueur -
		// un biais qui grandit avec le nombre de tours joués avant la sortie,
		// donc avec le cas le PLUS courant en usage réel (quitter après avoir
		// joué un moment, pas au tour 1). Corrigé en suivant la chronologie
		// RÉELLE du rejeu (les événements sont déjà triés par horodatage
		// juste au-dessus) plutôt que l'état final du joueur : un ensemble
		// dédié, rempli UNIQUEMENT au moment où l'on traite effectivement le
		// QUIT de ce joueur dans cette boucle (jamais avant), remplace désormais
		// `event.getPlayer().isQuit()` pour ce cas précis.
		final java.util.Set<String> quitSoFarInReplay = new java.util.HashSet<>();
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
					{
						addGain(pGame, event, playerName, achievements, currentFactor, false);
						// Amorce le suivi delta ci-dessous : un joueur qui quitte en
						// libre+strict TRM+smartphone (voir Player.quit) va continuer de
						// recevoir des WEALTH_CHECKPOINT - sans cette valeur de référence,
						// le premier d'entre eux recompterait depuis zéro une valeur déjà
						// ajoutée ci-dessus par addGain. MONÉTAIRE SEULE (voir le
						// commentaire détaillé sur openQuitSegmentMonetaryValue, plus haut) -
						// les cartes qu'elle détenait à l'instant du QUIT viennent d'être
						// comptées une fois pour toutes par addGain, jamais retouchées après.
						if ((event.getEvt() == EventType.QUIT) && (event.getPlayer() != null)
								&& event.getPlayer().isQuit())
						{
							openQuitSegmentMonetaryValue.put(playerName, computeMonetaryGain(pGame, event));
							// Marque CE joueur comme "sorti à partir de MAINTENANT" dans la
							// relecture chronologique (voir le commentaire détaillé sur
							// quitSoFarInReplay, plus haut) - jamais avant ce point précis,
							// même si event.getPlayer().isQuit() (l'état FINAL du joueur) est
							// déjà vrai pour tout événement antérieur relu ici.
							quitSoFarInReplay.add(playerName);
						}
					}
					break;
				case WEALTH_CHECKPOINT:
					// Uniquement pour un joueur qui a DÉJÀ quitté À CE POINT PRÉCIS DE LA
					// RELECTURE CHRONOLOGIQUE (quitSoFarInReplay, jamais event.getPlayer().
					// isQuit() - voir le commentaire détaillé plus haut sur ce correctif du
					// 04/10/2026) : son DU continue, ce "segment de vie" resté ouvert par
					// son événement QUIT doit donc rester à jour ici plutôt que figé à sa
					// valeur de sortie. Un joueur encore ACTIF (y compris un futur sortant
					// pas encore quitté à ce stade de la relecture) n'est volontairement
					// PAS traité ici (voir la Javadoc de cette méthode : "pas encore
					// comptabilisé tant qu'actif"). MONÉTAIRE SEULE (computeMonetaryGain,
					// jamais computeGain) : un WEALTH_CHECKPOINT ne représente jamais de
					// mouvement de cartes.
					if ((playerName != null) && quitSoFarInReplay.contains(playerName))
					{
						final int newValue = computeMonetaryGain(pGame, event);
						final int previousValue = openQuitSegmentMonetaryValue.getOrDefault(playerName, 0);
						achievements.merge(playerName, newValue - previousValue, Integer::sum);
						openQuitSegmentMonetaryValue.put(playerName, newValue);
					}
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
	 * Calcul du gain (richesse) apporté par un événement pour le joueur
	 * concerné (portage de {@code StatsFrame.addFromEvent}), extrait en
	 * fonction pure indépendante de tout état accumulé - réutilisée à la fois
	 * par {@link #addGain} (calcul "à la mort", pour la répartition des
	 * richesses de {@link #computeWealthByPlayer}/{@link #computeFinalReport})
	 * et par {@link #computeWealthOverTime} (accumulation continue, tour par
	 * tour, pour le graphique de convergence façon module Galilée). Depuis le
	 * 04/10/2026 (demande utilisateur, voir la Javadoc de
	 * {@link PlayerWealthPoint}), ce n'est plus qu'une simple SOMME de deux
	 * composantes désormais calculables séparément - {@link #computeMonetaryGain}
	 * (jetons/unités monétaires) et {@link #computeCardsGain} (cartes) - pour
	 * que {@code computeWealthOverTime} puisse les tracer sur des courbes
	 * distinctes sans jamais dupliquer la formule elle-même.
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
		return computeMonetaryGain(pGame, pEvent) + computeCardsGain(pGame, pEvent, pCurrentFactor);
	}

	/**
	 * Composante MONÉTAIRE (jetons) du gain ci-dessus - voir la Javadoc de
	 * {@link PlayerWealthPoint} (éclaté du 04/10/2026, demande utilisateur) :
	 * extraite de {@link #computeGain} en préservant EXACTEMENT le même calcul
	 * (aucune régression pour {@link #computeWealthByPlayer}/
	 * {@link #computeFinalReport}, qui continuent d'appeler {@link #computeGain}
	 * tel quel). Toujours nulle en troc (aucune monnaie dans ce système - voir
	 * {@link #computeCardsGain} pour sa seule grandeur de valeur, les objets
	 * échangés).
	 */
	private int computeMonetaryGain(final Game pGame, final Event pEvent)
	{
		if (pGame.getMoneySystem() == Game.MONEY_TROC)
			return 0;
		if (pGame.getMoneySystem() == Game.MONEY_DEBT)
			return pEvent.getPrincipal() + pEvent.getInterest();
		return (pEvent.getWeakCoins() + 2 * pEvent.getMediumCoins() + 4 * pEvent.getStrongCoins()) / 3;
	}

	/**
	 * Composante CARTES du gain ci-dessus - voir la Javadoc de
	 * {@link PlayerWealthPoint}. En troc (voir plugins/troc/manifest.json,
	 * wealthFormula) - changement de règle le 28/08/2026, remonté par
	 * l'utilisateur : "il faut reprendre le système de valeur 4x pour que les
	 * joueurs cherchent encore à faire des carrés, comme dans les autres
	 * parties des autres plugins". Avant cette date, un objet comptait pour 1
	 * quel que soit son niveau (règle 7 de docs/10-etape-plugins-troc.md,
	 * désormais périmée - voir la mise à jour du 28/08/2026 dans ce même
	 * document) : un carré (4 objets d'un niveau → 1 objet du niveau
	 * supérieur) n'avait alors aucun intérêt économique, seulement un intérêt
	 * de "rareté"/négociation. Désormais pondéré 1/4/16 (faible/moyen/fort) -
	 * le même rapport ×4 par niveau que celui déjà utilisé pour le taux
	 * d'échange smartphone (voir Transaction.java) - pour qu'un carré soit
	 * exactement neutre en richesse (4×1 = 1×4), cohérent avec ce que
	 * dette/libre offrent déjà à travers leurs propres cartes valeur (pondérées
	 * 1/2/4 × facteur technologique × facteur carte/monnaie, une formule
	 * DIFFÉRENTE du troc - jamais mélangées).
	 */
	private int computeCardsGain(final Game pGame, final Event pEvent, final int pCurrentFactor)
	{
		if (pGame.getMoneySystem() == Game.MONEY_TROC)
			return pEvent.getWeakCards() + (4 * pEvent.getMediumCards()) + (16 * pEvent.getStrongCards());
		return (pEvent.getWeakCards() + 2 * pEvent.getMediumCards() + 4 * pEvent.getStrongCards()) * pCurrentFactor
				* pGame.getMoneyCardsFactor();
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
