package jyt.geconomicus.helper.server;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jyt.geconomicus.helper.Event;
import jyt.geconomicus.helper.Event.EventType;
import jyt.geconomicus.helper.Game;
import jyt.geconomicus.helper.Player;

/**
 * Objets de transfert JSON. On ne sérialise jamais directement les entités JPA :
 * ça évite les soucis de lazy-loading / références cycliques (Game -> Player -> Game)
 * et ça découple le contrat de l'API du schéma de persistance interne.
 *
 * Pourquoi c'est important ici précisément : Game a une collection @OneToMany de Player,
 * et chaque Player a un @ManyToOne vers Game (voir Player.java) : sérialiser l'entité Game
 * telle quelle produirait une boucle infinie Game -> players -> Player -> game -> ... .
 * Les DTO définis ci-dessous (des "record" Java, immuables) ne gardent que les champs
 * utiles à l'affichage, dans un seul sens (Game contient ses Players, pas l'inverse).
 */
public class Dtos
{
	public record PlayerDto(Integer id, String name, boolean active, int curDebt, int curInterest,
			boolean visitedBank, int age, Integer declaredAge, String favoriteColor, String avatarConfigJson,
			int goodsCount, String accessToken, int weakGoods, int mediumGoods, int strongGoods,
			boolean hasStartingAllocation)
	{
		static PlayerDto from(final Player p, final int pAge)
		{
			return new PlayerDto(p.getId(), p.getName(), p.isActive(), p.getCurDebt(), p.getCurInterest(),
					p.isVisitedBank(), pAge, p.getDeclaredAge(), p.getFavoriteColor(), p.getAvatarConfigJson(),
					// weakGoods/mediumGoods/strongGoods : ajoutés le 28/08/2026 (voir
					// Player.java) - manquaient jusqu'ici côté DTO, alors que le moteur
					// les tient déjà à jour en direct à chaque GOODS_TRADE. Nécessaires
					// pour pré-remplir l'inventaire de mort troc dans l'assistant (voir
					// renderStepDeathTroc dans app.js), sur le même principe que le
					// pré-remplissage déjà en place pour la monnaie libre.
					p.getGoodsCount(), p.getAccessToken(), p.getWeakGoods(), p.getMediumGoods(), p.getStrongGoods(),
					// hasStartingAllocation : ajouté le même jour - vrai dès que ce
					// joueur a reçu sa mise en place (4 cartes + 1/1/1 jetons, voir
					// GameService.dealStartingHandsForLibreIfNeeded). Sans lui,
					// computeLibrePrefill (app.js) ne saurait jamais que le tout
					// premier tour d'un joueur libre part d'un solde de 7, pas de 0.
					p.getStartingCardsJson() != null);
		}
	}

	// Réponse de GET /api/games/{id}/players/by-token/{token} (écran joueur,
	// player-view.html) - PlayerDto + le solde de jetons dérivé (voir
	// GameService.computeTradeBalance). Volontairement séparé de PlayerDto
	// (utilisé ailleurs pour des LISTES de joueurs - y ajouter une requête
	// supplémentaire par joueur y créerait un problème de performance N+1) :
	// cette route ne renvoie jamais qu'UN seul joueur, le coût est négligeable.
	public record PlayerSelfViewDto(Integer id, String name, boolean active, int curDebt, int curInterest,
			boolean visitedBank, Integer declaredAge, String favoriteColor, String avatarConfigJson,
			int goodsCount, String accessToken, int tradeBalance, int weakGoods, int mediumGoods, int strongGoods,
			int moneySystem, boolean tradingAllowed, double weakCoinValue)
	{
		static PlayerSelfViewDto from(final Player p, final int pTradeBalance, final int pMoneySystem,
				final boolean pTradingAllowed, final double pWeakCoinValue)
		{
			return new PlayerSelfViewDto(p.getId(), p.getName(), p.isActive(), p.getCurDebt(), p.getCurInterest(),
					p.isVisitedBank(), p.getDeclaredAge(), p.getFavoriteColor(), p.getAvatarConfigJson(),
					p.getGoodsCount(), p.getAccessToken(), pTradeBalance, p.getWeakGoods(), p.getMediumGoods(),
					p.getStrongGoods(), pMoneySystem, pTradingAllowed, pWeakCoinValue);
		}
	}

	public record EventDto(Integer id, String type, String typeLabel, Integer playerId, String playerName,
			long timestamp, int principal, int interest, Integer counterpartyPlayerId, String counterpartyPlayerName,
			int goodsFromPlayer, int goodsFromCounterparty, int weakCoins, int mediumCoins, int strongCoins,
			int weakCards, int mediumCards, int strongCards)
	{
		static EventDto from(final Event e)
		{
			return new EventDto(e.getId(), e.getEvt().name(), e.getEvt().getDescription(),
					e.getPlayer() == null ? null : e.getPlayer().getId(),
					e.getPlayer() == null ? null : e.getPlayer().getName(),
					e.getTstamp() == null ? 0 : e.getTstamp().getTime(), e.getPrincipal(), e.getInterest(),
					e.getCounterpartyPlayer() == null ? null : e.getCounterpartyPlayer().getId(),
					e.getCounterpartyPlayer() == null ? null : e.getCounterpartyPlayer().getName(),
					e.getGoodsFromPlayer(), e.getGoodsFromCounterparty(),
					// Étape 3, monnaie libre : ces trois champs manquaient jusqu'ici -
					// jamais nécessaires avant (le client ne faisait qu'ÉCRIRE ces
					// valeurs, ex. l'inventaire saisi à une mort, jamais les RELIRE
					// après coup). Nécessaires maintenant pour retrouver le dernier
					// solde connu d'un joueur (voir renderStepAllPlayersMoney côté
					// front, qui pré-remplit désormais l'assistant de fin de tour à
					// partir du dernier WEALTH_CHECKPOINT/DEATH/QUIT enregistré).
					e.getWeakCoins(), e.getMediumCoins(), e.getStrongCoins(),
					e.getWeakCards(), e.getMediumCards(), e.getStrongCards());
		}
	}

	// Étape 3, mode smartphone : une transaction individuelle carte-contre-
	// jetons entre deux joueurs - voir Transaction.java (geco-engine) pour le
	// raisonnement complet et sa portée volontairement limitée à ce stade.
	public record TransactionDto(Integer id, String uuid, Integer sellerPlayerId, String sellerPlayerName,
			Integer buyerPlayerId, String buyerPlayerName, int turnNumber, long timestamp, String cardTypeId,
			String cardLevel, int weakCoins, int mediumCoins, int strongCoins, int totalCoinsValue,
			int buyerWeakGoods, int buyerMediumGoods, int buyerStrongGoods, boolean isGoodsTrade, int totalGoodsValue)
	{
		static TransactionDto from(final jyt.geconomicus.helper.Transaction t)
		{
			return new TransactionDto(t.getId(), t.getUuid(), t.getSeller().getId(), t.getSeller().getName(),
					t.getBuyer().getId(), t.getBuyer().getName(), t.getTurnNumber(),
					t.getTstamp() == null ? 0 : t.getTstamp().getTime(), t.getCardTypeId(), t.getCardLevel(),
					t.getWeakCoins(), t.getMediumCoins(), t.getStrongCoins(), t.totalCoinsValue(),
					t.getBuyerWeakGoods(), t.getBuyerMediumGoods(), t.getBuyerStrongGoods(), t.isGoodsTrade(),
					t.totalGoodsValue());
		}
	}

	/**
	 * Encaissement d'un carré (voir CardSquareEvent) - remonté par
	 * l'utilisateur (31/08/2026) : "toutes ces opérations sont enregistrées
	 * dans l'historique du tour... et seront transmises à l'application de
	 * l'animateur".
	 */
	public record CardSquareEventDto(Integer id, Integer playerId, String playerName, int turnNumber, long timestamp,
			String cashedCardTypeId, String cashedLevel, String promotedCardTypeId, String promotedLevel,
			java.util.List<String> replenishedCardIds, boolean triggeredBreakthrough)
	{
		static CardSquareEventDto from(final jyt.geconomicus.helper.CardSquareEvent s)
		{
			java.util.List<String> replenished;
			try
			{
				replenished = new com.fasterxml.jackson.databind.ObjectMapper().readValue(s.getReplenishedCardIdsJson(),
						new com.fasterxml.jackson.core.type.TypeReference<java.util.ArrayList<String>>()
						{
						});
			}
			catch (final com.fasterxml.jackson.core.JsonProcessingException e)
			{
				replenished = java.util.List.of(); // donnée corrompue (ne devrait jamais arriver) : liste vide plutôt qu'une erreur
			}
			return new CardSquareEventDto(s.getId(), s.getPlayer().getId(), s.getPlayer().getName(), s.getTurnNumber(),
					s.getTstamp() == null ? 0 : s.getTstamp().getTime(), s.getCashedCardTypeId(), s.getCashedLevel(),
					s.getPromotedCardTypeId(), s.getPromotedLevel(), replenished, s.isTriggeredBreakthrough());
		}
	}

	public record GameSummaryDto(Integer id, String description, int moneySystem, int turnNumber,
			int nbTurnsPlanned, String location, String curdate)
	{
		static GameSummaryDto from(final Game g)
		{
			return new GameSummaryDto(g.getId(), g.getDescription(), g.getMoneySystem(), g.getTurnNumber(),
					g.getNbTurnsPlanned(), g.getLocation(), g.getCurdate());
		}
	}

	public record GameDetailDto(Integer id, String description, int moneySystem, int turnNumber,
			int nbTurnsPlanned, int moneyMass, int interestGained, int activePlayersCount, double avgAge,
			int totalCreditsOutstanding, int turnDurationSeconds, long turnStartedAtEpochMs, List<PlayerDto> players,
			List<EventDto> events, int moneyCardsFactor, double weakCoinValue, String animatorPseudo,
			int seizedValues, int moneyInvestBank, int cardsInvestBank, Integer pausedRemainingSeconds,
			int startingGoods, boolean strictTrm, String pin)
	{
		static GameDetailDto from(final Game g)
		{
			// Calcul de "l'âge" (en tours) de chaque joueur : nombre de tours écoulés
			// depuis sa dernière naissance/renaissance (événement JOIN ou DEATH). On
			// rejoue la liste d'événements triée chronologiquement, comme le fait déjà
			// StatsFrame côté Swing pour ses propres calculs, plutôt que d'inventer un
			// chiffre : un tour ne compte que lorsqu'un événement TURN est rencontré.
			final List<Event> sortedEvents = g.getEvents().stream()
					.sorted(Comparator.comparing(Event::getTstamp, Comparator.nullsLast(Comparator.naturalOrder())))
					.collect(Collectors.toList());
			final Map<Integer, Integer> birthTurnByPlayerId = new HashMap<>();
			int turnCounterMutable = 0;
			for (final Event e : sortedEvents)
			{
				if (e.getEvt() == EventType.TURN)
					turnCounterMutable++;
				else if ((e.getEvt() == EventType.JOIN || e.getEvt() == EventType.DEATH) && e.getPlayer() != null)
					birthTurnByPlayerId.put(e.getPlayer().getId(), turnCounterMutable);
			}
			final int turnCounter = turnCounterMutable;

			final List<Player> activePlayers = g.getPlayers().stream().filter(Player::isActive)
					.collect(Collectors.toList());
			final List<PlayerDto> players = g.getPlayers().stream()
					.map(p -> PlayerDto.from(p, turnCounter - birthTurnByPlayerId.getOrDefault(p.getId(), 0)))
					.collect(Collectors.toList());
			final List<EventDto> events = g.getEvents().stream().map(EventDto::from).collect(Collectors.toList());

			final double avgAge = activePlayers.isEmpty() ? 0
					: activePlayers.stream()
							.mapToInt(p -> turnCounter - birthTurnByPlayerId.getOrDefault(p.getId(), 0)).average()
							.orElse(0);
			// Remonté par un utilisateur : le libellé promet "crédits + intérêts" -
			// le total doit donc bien inclure les deux, pas seulement le principal
			// comme c'était le cas jusqu'ici.
			final int totalCredits = activePlayers.stream().mapToInt(p -> p.getCurDebt() + p.getCurInterest()).sum();

			return new GameDetailDto(g.getId(), g.getDescription(), g.getMoneySystem(), g.getTurnNumber(),
					g.getNbTurnsPlanned(), g.getMoneyMass(), g.getInterestGained(), activePlayers.size(), avgAge,
					totalCredits, g.getTurnDurationSeconds(),
					g.getTurnStartedAt() == null ? 0 : g.getTurnStartedAt().getTime(), players, events,
					g.getMoneyCardsFactor(), g.getWeakCoinValue(), g.getAnimatorPseudo(),
					g.getSeizedValues(), g.getMoneyInvestBank(), g.getCardsInvestBank(), g.getPausedRemainingSeconds(),
					g.getStartingGoods(), g.isStrictTrm(), g.getPin());
		}
	}

	// Corps de requête pour la création de partie
	public record CreateGameRequest(int moneySystem, int nbTurnsPlanned, String animatorPseudo, String animatorEmail,
			String description, String curDate, String location, int moneyCardsFactor, int turnDurationSeconds,
			double weakCoinValue, boolean tokenPenalty, int startingGoods, boolean strictTrm)
	{
	}

	public record AddPlayerRequest(String name)
	{
	}

	// Corps de requête pour PUT /api/plugins/{id}/enabled (écran Paramètres).
	public record EnabledRequest(boolean enabled)
	{
	}

	// Corps de requête pour PUT /api/languages/{code}/display (écran Paramètres) -
	// réglage manuel de drapeau/libellé. flag/label vides ou absents = efface le
	// réglage manuel (retour à la déduction automatique, voir LanguageService).
	public record LanguageDisplayRequest(String flag, String label)
	{
	}

	// Corps de requête pour POST /api/games/{id}/unlock (vérification du PIN).
	public record UnlockRequest(String pin)
	{
	}

	// Corps de requête pour PUT /api/settings (écran Paramètres). gameMode :
	// "classique" ou "smartphone" (voir AppSettings.GAME_MODE_*) - absent/null
	// laisse la valeur actuelle inchangée, comme defaultLanguage/updateCheckUrl.
	public record UpdateSettingsRequest(String defaultLanguage, boolean soundMuted, int soundVolume,
			String updateCheckUrl, boolean protectionEnabled, String gameMode)
	{
	}

	// Corps de requête pour PUT /api/catalogs/{kind}/{id} (écran Paramètres,
	// mode smartphone - tableaux Cartes/Visuels/Avatars) : patch partiel des
	// métadonnées d'une entrée de catalogue, jamais l'image elle-même (voir
	// CatalogService). Les clés absentes du patch laissent le champ existant
	// inchangé.
	public record CatalogEntryPatch(java.util.Map<String, Object> fields)
	{
	}

	/** Requête d'auto-inscription depuis le smartphone du joueur (étape 3, Phase B). */
	public record JoinRequest(String name, Integer declaredAge, String favoriteColor, String avatarConfigJson)
	{
	}

	public record RecordEventRequest(String type, Integer playerId, int principal, int interest, int weakCards,
			int mediumCards, int strongCards, Integer counterpartyPlayerId, int goodsFromPlayer,
			int goodsFromCounterparty, int weakCoins, int mediumCoins, int strongCoins,
			// Troc uniquement : détail par niveau donné par la CONTREPARTIE d'un
			// GOODS_TRADE - manquait ici jusqu'au 28/08/2026, malgré leur présence
			// déjà effective dans GameService.recordEvent (jamais vraiment
			// exploitable côté client faute d'un moyen de les transmettre).
			int weakGoodsFromCounterparty, int mediumGoodsFromCounterparty, int strongGoodsFromCounterparty)
	{
	}

	// Corps de requête pour POST /api/games/{id}/transactions (étape 3, mode
	// smartphone). buyerAccessToken : jeton individuel de l'acheteur (voir
	// Player.accessToken) - preuve que c'est bien lui qui initie l'achat,
	// vérifié uniquement quand la protection par code est activée (voir
	// AppSettings.protectionEnabled), exactement comme pour le PIN de partie.
	public record RecordTransactionRequest(String buyerAccessToken, Integer sellerPlayerId, Integer buyerPlayerId,
			String cardTypeId, String cardLevel, int weakCoins, int mediumCoins, int strongCoins, String nonce,
			long expiresAt, int buyerWeakGoods, int buyerMediumGoods, int buyerStrongGoods)
	{
	}

	/** Requête d'édition d'un événement existant (annuler/supprimer/éditer). tstamp
	 * est optionnel (format ISO-8601, ex. "2026-08-16T10:30:00Z") - null ou absent
	 * pour ne pas changer la date. */
	public record EditEventRequest(int principal, int interest, String tstamp, String name)
	{
	}

	// Corps de requête pour POST /api/games/{id}/trade-offers/{code}/redeem
	// (étape 3, achat de carte). buyerAccessToken : jeton individuel de
	// l'acheteur, mêmes règles que RecordTransactionRequest ci-dessus.
	public record RedeemTradeOfferRequest(Integer buyerPlayerId, String buyerAccessToken)
	{
	}

	// Corps de requête pour POST /api/games/{id}/trade-offers (étape 3, vente
	// de carte). sellerAccessToken : mêmes règles que buyerAccessToken plus
	// haut, côté vendeur cette fois. cardName : la table {code langue -> texte}
	// du catalogue "Cartes" (voir CatalogService), embarquée telle quelle pour
	// que l'acheteur affiche le nom dans SA PROPRE langue (voir player-view.js).
	public record CreateTradeOfferRequest(Integer sellerPlayerId, String sellerAccessToken, String cardTypeId,
			String cardLevel, java.util.Map<String, Object> cardName, int weakCoins, int mediumCoins, int strongCoins,
			int weakGoodsWanted, int mediumGoodsWanted, int strongGoodsWanted)
	{
	}

	// Réponse de POST /api/games/{id}/trade-offers et de
	// GET /api/games/{id}/trade-offers/{code} (avant confirmation d'achat).
	public record TradeOfferDto(String code, Integer sellerPlayerId, String sellerPlayerName, String cardTypeId,
			String cardLevel, java.util.Map<String, Object> cardName, int weakCoins, int mediumCoins, int strongCoins,
			int weakGoodsWanted, int mediumGoodsWanted, int strongGoodsWanted, long expiresAt)
	{
		static TradeOfferDto from(final String pCode, final TradeOfferService.Offer pOffer)
		{
			return new TradeOfferDto(pCode, pOffer.sellerPlayerId(), pOffer.sellerPlayerName(), pOffer.cardTypeId(),
					pOffer.cardLevel(), pOffer.cardName(), pOffer.weakCoins(), pOffer.mediumCoins(),
					pOffer.strongCoins(), pOffer.weakGoodsWanted(), pOffer.mediumGoodsWanted(),
					pOffer.strongGoodsWanted(), pOffer.expiresAtEpochMs());
		}
	}

	// Réponse de GET /api/network-info (étape 3, écran "Connexion joueurs" +
	// génération du lien personnel d'un joueur, voir app.js) : les adresses
	// locales détectées, et le port HTTPS s'il est disponible (null sinon -
	// voir SelfSignedCertService/GecoServer.mHttpsPort). Sans ce port, le
	// frontend n'a aucun moyen de savoir sur quel port se trouve le
	// connecteur HTTPS (il diffère du port HTTP normal).
	public record NetworkInfoDto(java.util.List<NetworkUtils.NetworkAddress> addresses, Integer httpsPort)
	{
	}

	// Réponse de GET /api/games/{id}/public-info (écran d'inscription joueur,
	// join.html) - remonté par un utilisateur (28/08/2026) : GET /api/games/{id}
	// (utilisée par join.html pour vérifier que la partie existe et afficher
	// son nom) est protégée par le PIN dès que la partie en a un - un joueur,
	// qui ne le connaît jamais, se voyait donc refuser jusqu'à la simple
	// CONSULTATION de l'écran d'inscription, avec un message trompeur
	// ("partie introuvable" au lieu de la vraie cause). Cette route est donc
	// volontairement PUBLIQUE (exemptée du PIN, voir checkGamePin) et ne
	// renvoie QUE l'information déjà destinée à être publique (le nom de la
	// partie affiché sur l'écran d'inscription) - jamais les joueurs, les
	// finances, ni quoi que ce soit qui justifierait la protection par PIN.
	public record PublicGameInfoDto(Integer id, String description)
	{
	}

	// Réponse de GET /api/games/{id}/leaderboard - voir GameService.computeLeaderboard.
	public record LeaderboardEntryDto(Integer playerId, String playerName, int value, int rank)
	{
	}

	// ============ Étape 3, monnaie dette : demandes de crédit smartphone ============
	// (voir CreditRequestService pour le raisonnement complet)

	public record CreateCreditRequestRequest(Integer playerId, String playerAccessToken, int requestedPrincipal)
	{
	}

	public record ApproveCreditRequestRequest(int principal, int interest)
	{
	}

	public record CreditRequestDto(Integer id, Integer playerId, String playerName, int requestedPrincipal,
			long createdAt, String status)
	{
		static CreditRequestDto from(final CreditRequestService.Request r)
		{
			return new CreditRequestDto(r.id(), r.playerId(), r.playerName(), r.requestedPrincipal(),
					r.createdAtEpochMs(), r.status());
		}
	}
}
