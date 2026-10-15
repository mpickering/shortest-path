package shortestpath.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.presentation.PathAnnotator;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.TransportAvailability;
import shortestpath.transport.Transport;

/**
 * The "Transports" debug layer's model: every transport with an origin tile, grouped by origin
 * and by destination, with whether the player can use it. Rebuilt only when the plugin's
 * transport availability changes.
 */
final class TransportMarkers
{
	private static final int MAX_ROWS = 10;

	static final class Origin
	{
		final int point;
		final List<Transport> transports = new ArrayList<>(2);
		boolean anyAvailable;

		private Origin(int point)
		{
			this.point = point;
		}
	}

	private final PathAnnotator plugin;
	private PrimitiveIntHashMap<Transport[]> builtFrom;
	private PrimitiveIntHashMap<Transport[]> builtAvailable;
	private List<Origin> origins = List.of();
	private Map<Integer, Origin> byOrigin = Map.of();
	private Map<Integer, List<Transport>> byDestination = Map.of();
	private Set<Transport> available = Set.of();

	TransportMarkers(PathAnnotator plugin)
	{
		this.plugin = plugin;
	}

	/** The transports grouped by origin tile, refreshed first if availability changed. */
	List<Origin> origins()
	{
		PrimitiveIntHashMap<Transport[]> all = plugin.getAllDisplayTransports();
		PrimitiveIntHashMap<Transport[]> availableNow = plugin.getTransports();
		// The maps are rebuilt whenever availability is refreshed, so identity marks a change.
		if (all != builtFrom || availableNow != builtAvailable)
		{
			build(all, availableNow);
			builtFrom = all;
			builtAvailable = availableNow;
		}
		return origins;
	}

	boolean isAvailable(Transport transport)
	{
		return available.contains(transport);
	}

	/** The transports that start on {@code point}; call {@link #origins()} first in a frame. */
	List<Transport> from(int point)
	{
		Origin origin = byOrigin.get(point);
		return origin == null ? List.of() : origin.transports;
	}

	/** The transports that land on {@code point}; call {@link #origins()} first in a frame. */
	List<Transport> to(int point)
	{
		return byDestination.getOrDefault(point, List.of());
	}

	/** Tooltip rows for the transports leaving and arriving at {@code point}. */
	List<String> rows(int point)
	{
		List<String> rows = new ArrayList<>();
		List<Transport> leaving = from(point);
		if (!leaving.isEmpty())
		{
			rows.add("Transports from " + TargetMarkers.format(point));
			addRows(rows, leaving, true);
		}
		List<Transport> arriving = to(point);
		if (!arriving.isEmpty())
		{
			rows.add("Transports to " + TargetMarkers.format(point));
			addRows(rows, arriving, false);
		}
		return rows;
	}

	private void addRows(List<String> rows, List<Transport> transports, boolean leaving)
	{
		int shown = transports.size() > MAX_ROWS ? MAX_ROWS - 1 : transports.size();
		for (int i = 0; i < shown; i++)
		{
			Transport transport = transports.get(i);
			int other = leaving ? transport.getDestination() : transport.getOrigin();
			rows.add("  " + name(plugin, transport) + (leaving ? " -> " : " <- ") + TargetMarkers.format(other)
				+ (isAvailable(transport) ? "" : " (" + unavailable(plugin, transport) + ")"));
		}
		if (shown < transports.size())
		{
			rows.add("  ... and " + (transports.size() - shown) + " more");
		}
	}

	/** Why a transport is unavailable, for a tooltip. */
	static String unavailable(PathAnnotator plugin, Transport transport)
	{
		String reason = plugin.explainUnavailable(transport);
		return reason == null ? "unavailable" : "unavailable: " + reason;
	}

	/** A transport's name for a tooltip: its menu text, else its object, else its type. */
	static String name(PathAnnotator plugin, Transport transport)
	{
		String info = plugin.formatTransportDisplay(transport);
		if (info != null && !info.isEmpty())
		{
			return info;
		}
		if (transport.getObjectInfo() != null && !transport.getObjectInfo().isEmpty())
		{
			return transport.getObjectInfo();
		}
		return transport.getType() == null ? "Transport" : transport.getType().name();
	}

	private void build(PrimitiveIntHashMap<Transport[]> all, PrimitiveIntHashMap<Transport[]> availableNow)
	{
		Set<Transport> usable = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int origin : availableNow.keys())
		{
			Collections.addAll(usable, availableNow.getOrDefault(origin, TransportAvailability.EMPTY_TRANSPORTS));
		}

		List<Origin> builtOrigins = new ArrayList<>();
		Map<Integer, Origin> builtByOrigin = new HashMap<>();
		Map<Integer, List<Transport>> builtByDestination = new HashMap<>();
		for (int point : all.keys())
		{
			if (point == Transport.UNDEFINED_ORIGIN)
			{
				continue; // teleports have their own layer
			}
			Origin origin = new Origin(point);
			for (Transport transport : all.getOrDefault(point, TransportAvailability.EMPTY_TRANSPORTS))
			{
				if (transport == null || (transport.getType() != null && transport.getType().isTeleport())
					|| transport.getDestination() == Transport.UNDEFINED_DESTINATION)
				{
					continue; // teleports have their own layer
				}
				origin.transports.add(transport);
				origin.anyAvailable |= usable.contains(transport);
				builtByDestination.computeIfAbsent(transport.getDestination(), d -> new ArrayList<>(2)).add(transport);
			}
			if (!origin.transports.isEmpty())
			{
				builtOrigins.add(origin);
				builtByOrigin.put(point, origin);
			}
		}
		origins = builtOrigins;
		byOrigin = builtByOrigin;
		byDestination = builtByDestination;
		available = usable;
	}

	/**
	 * Whether a transport is a short local hop (stairs, a door, a shortcut) rather than a trip
	 * across the map: within {@code tiles} of its origin on both axes.
	 */
	static boolean isLocal(Transport transport, int tiles)
	{
		return WorldPointUtil.distanceBetween2D(transport.getOrigin(), transport.getDestination()) <= tiles;
	}
}
