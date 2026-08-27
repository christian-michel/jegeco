package jyt.geconomicus.helper.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * Détecte les adresses IPv4 locales de la machine, pour construire les URLs et QR
 * codes que les smartphones utiliseront pour rejoindre la partie (étape 3, Phase A
 * — fondations réseau).
 * <p>
 * Choix volontaire : on liste TOUTES les adresses IPv4 non-loopback trouvées,
 * plutôt que de deviner laquelle correspond au partage de connexion ou au Wifi du
 * lieu (l'IP exacte d'un point d'accès mobile varie selon l'OS et l'outil - voir
 * {@code docs/05-etape3-connectivite.md}). C'est à l'animateur de choisir, dans
 * l'interface, celle qui correspond au réseau utilisé - plus fiable que de
 * deviner automatiquement, et ça fonctionne quel que soit l'OS sans configuration
 * particulière.
 */
public class NetworkUtils
{
	public record NetworkAddress(String interfaceName, String address, boolean likelyHotspotOrLan)
	{
	}

	/**
	 * Heuristique simple pour trier en premier les adresses les plus probables
	 * (plages privées standard 192.168.x.x, 10.x.x.x, 172.16-31.x.x) : ce sont
	 * celles utilisées par le partage de connexion et les réseaux locaux
	 * domestiques/associatifs dans l'immense majorité des cas.
	 */
	private static boolean isLikelyLanOrHotspot(final String pAddress)
	{
		return pAddress.startsWith("192.168.") || pAddress.startsWith("10.")
				|| pAddress.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
	}

	public static List<NetworkAddress> listLocalAddresses()
	{
		final List<NetworkAddress> result = new ArrayList<>();
		try
		{
			final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces.hasMoreElements())
			{
				final NetworkInterface ni = interfaces.nextElement();
				// On exclut les interfaces inactives, loopback (127.0.0.1) et virtuelles
				// (souvent créées par des logiciels de virtualisation/VPN, rarement
				// pertinentes pour joindre le serveur depuis un smartphone).
				if (!isSafeToQuery(ni))
					continue;
				final Enumeration<InetAddress> addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements())
				{
					final InetAddress addr = addrs.nextElement();
					if (addr instanceof Inet4Address)
					{
						final String ip = addr.getHostAddress();
						result.add(new NetworkAddress(ni.getDisplayName(), ip, isLikelyLanOrHotspot(ip)));
					}
				}
			}
		}
		catch (final Exception e)
		{
			// On ne fait jamais planter le serveur pour un souci de détection réseau :
			// au pire, la liste est vide et l'animateur voit un message l'invitant à
			// vérifier sa configuration réseau manuellement.
			e.printStackTrace();
		}
		// Les adresses "probables" (LAN/hotspot) en premier, pour guider l'animateur.
		result.sort(Comparator.comparing(NetworkAddress::likelyHotspotOrLan).reversed());
		return result;
	}

	private static boolean isSafeToQuery(final NetworkInterface pNi)
	{
		try
		{
			return pNi.isUp() && !pNi.isLoopback() && !pNi.isVirtual();
		}
		catch (final Exception e)
		{
			return false;
		}
	}
}
