package shortestpath.overlay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.ShortestPathPlugin;
import shortestpath.transport.Transport;

/**
 * The "Teleport destinations" debug layer's markers: one per tile that a teleport usable from
 * anywhere lands on, coloured by the best availability among them, with tooltip rows naming each
 * teleport. Rebuilt only when the plugin's transport availability changes.
 */
final class TeleportMarkers
{
	static final Color COLOUR_USABLE = Color.WHITE;
	static final Color COLOUR_AFTER_BANK = new Color(120, 180, 255);
	static final Color COLOUR_UNAVAILABLE = Color.ORANGE;
	private static final int MAX_ROWS = 12;

	static final class Marker
	{
		final int point;
		final Color colour;
		final List<String> rows;

		private Marker(int point, Color colour, List<String> rows)
		{
			this.point = point;
			this.colour = colour;
			this.rows = rows;
		}
	}

	private enum Availability
	{
		USABLE("usable"),
		AFTER_BANK("after banking"),
		UNAVAILABLE(null);

		private final String label;

		Availability(String label)
		{
			this.label = label;
		}
	}

	private final ShortestPathPlugin plugin;
	private Transport[] builtFrom;
	private Transport[] builtUsable;
	private Transport[] builtUsableAfterBank;
	private List<Marker> markers = List.of();

	TeleportMarkers(ShortestPathPlugin plugin)
	{
		this.plugin = plugin;
	}

	List<Marker> markers()
	{
		Transport[] all = plugin.getAllTeleports();
		Transport[] usable = plugin.getUsableTeleports(false);
		Transport[] usableAfterBank = plugin.getUsableTeleports(true);
		// The arrays are rebuilt whenever availability is refreshed, so identity marks a change.
		if (all != builtFrom || usable != builtUsable || usableAfterBank != builtUsableAfterBank)
		{
			markers = build(all, usable, usableAfterBank);
			builtFrom = all;
			builtUsable = usable;
			builtUsableAfterBank = usableAfterBank;
		}
		return markers;
	}

	private List<Marker> build(Transport[] all, Transport[] usable, Transport[] usableAfterBank)
	{
		Set<Transport> usableNow = identitySet(usable);
		Set<Transport> usableBanked = identitySet(usableAfterBank);
		Map<Integer, List<Transport>> byDestination = new LinkedHashMap<>();
		for (Transport teleport : all)
		{
			if (teleport.getDestination() != Transport.UNDEFINED_DESTINATION)
			{
				byDestination.computeIfAbsent(teleport.getDestination(), d -> new ArrayList<>()).add(teleport);
			}
		}

		List<Marker> built = new ArrayList<>(byDestination.size());
		for (Map.Entry<Integer, List<Transport>> entry : byDestination.entrySet())
		{
			Availability best = Availability.UNAVAILABLE;
			List<String> lines = new ArrayList<>();
			for (Transport teleport : entry.getValue())
			{
				Availability availability = usableNow.contains(teleport) ? Availability.USABLE
					: usableBanked.contains(teleport) ? Availability.AFTER_BANK : Availability.UNAVAILABLE;
				if (availability.ordinal() < best.ordinal())
				{
					best = availability;
				}
				lines.add(TransportMarkers.name(plugin, teleport) + ": " + (availability == Availability.UNAVAILABLE
					? TransportMarkers.unavailable(plugin, teleport) : availability.label));
			}
			Collections.sort(lines);
			List<String> rows = new ArrayList<>();
			rows.add("Teleports to " + TargetMarkers.format(entry.getKey()));
			if (lines.size() > MAX_ROWS)
			{
				int more = lines.size() - (MAX_ROWS - 1);
				lines = new ArrayList<>(lines.subList(0, MAX_ROWS - 1));
				lines.add("... and " + more + " more");
			}
			rows.addAll(lines);
			built.add(new Marker(entry.getKey(), colour(best), rows));
		}
		return built;
	}

	private static Color colour(Availability availability)
	{
		switch (availability)
		{
			case USABLE:
				return COLOUR_USABLE;
			case AFTER_BANK:
				return COLOUR_AFTER_BANK;
			default:
				return COLOUR_UNAVAILABLE;
		}
	}

	private static Set<Transport> identitySet(Transport[] transports)
	{
		Set<Transport> set = Collections.newSetFromMap(new IdentityHashMap<>(transports.length * 2));
		Collections.addAll(set, transports);
		return set;
	}
}
