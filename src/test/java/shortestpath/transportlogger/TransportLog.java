package shortestpath.transportlogger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

/** Client-thread state; timings describe observed animations, never movement unlocks. */
final class TransportLog
{
	static final String HEADER = "Origin\tDestination\tInteraction\tAnimation ticks\tAnimation IDs\tCapture";

	static final class Entry
	{
		final WorldPoint origin;
		final WorldPoint destination;
		final String interaction;
		final String capture;
		final Integer animationTicks;
		final String animationIds;

		Entry(WorldPoint origin, WorldPoint destination, String interaction, String capture,
			Integer animationTicks, String animationIds)
		{
			this.origin = origin;
			this.destination = destination;
			this.interaction = interaction;
			this.capture = capture;
			this.animationTicks = animationTicks;
			this.animationIds = animationIds;
		}

		String tsv()
		{
			return coordinate(origin) + "\t" + coordinate(destination) + "\t" + interaction
				+ "\t" + (animationTicks == null ? "" : animationTicks) + "\t" + animationIds + "\t" + capture;
		}
	}

	private static final class Animation
	{
		final WorldPoint origin;
		final int start;
		final Set<Integer> ids = new LinkedHashSet<>();
		int end = -1;
		int entry = -1;

		Animation(WorldPoint origin, int start)
		{
			this.origin = origin;
			this.start = start;
		}
	}

	private static final class Input
	{
		final String description;
		final int tick;

		Input(String description, int tick)
		{
			this.description = description;
			this.tick = tick;
		}
	}

	private final List<Input> inputs = new ArrayList<>();
	private final List<Entry> entries = new ArrayList<>();
	private WorldPoint previous;
	private WorldPoint manualOrigin;
	private int manualTick;
	private Animation animation;

	void interaction(String description, int tick)
	{
		if (manualOrigin == null)
		{
			inputs.removeIf(input -> tick - input.tick > 50);
		}
		inputs.add(new Input(description.replaceAll("[\\t\\r\\n]+", " "), tick));
	}

	boolean animation(WorldPoint point, int id, int tick)
	{
		if (id != -1)
		{
			// Keep contiguous departure/arrival animations together, including same-tick switches.
			if (animation == null || (animation.end >= 0 && tick > animation.end + 1))
			{
				animation = new Animation(point, tick);
			}
			animation.ids.add(id);
			animation.end = -1;
		}
		else if (animation != null && animation.end < 0)
		{
			animation.end = tick;
		}
		return updateAnimation();
	}

	boolean sample(WorldPoint point, int tick)
	{
		boolean added = false;
		if (point != null)
		{
			if (previous != null && manualOrigin == null
				&& (point.getPlane() != previous.getPlane() || point.distanceTo2D(previous) > 2))
			{
				add(previous, point, tick, "Automatic jump");
				added = true;
			}
			else if (previous != null && !previous.equals(point) && manualOrigin == null && animation != null && animation.end >= 0)
			{
				animation = null;
			}
			previous = point;
		}
		if (manualOrigin == null && animation != null && animation.end >= 0 && tick > animation.end + 1)
		{
			animation = null;
		}
		return added;
	}

	void markOrigin(WorldPoint point, int tick)
	{
		manualOrigin = point;
		manualTick = tick;
		previous = point;
		inputs.clear();
	}

	boolean markDestination(WorldPoint point, int tick)
	{
		if (manualOrigin == null || point.equals(manualOrigin))
		{
			return false;
		}
		add(manualOrigin, point, tick, "Manual");
		manualOrigin = null;
		previous = point;
		return true;
	}

	void cancelManual()
	{
		manualOrigin = null;
	}

	WorldPoint manualOrigin()
	{
		return manualOrigin;
	}

	List<Entry> entries()
	{
		return List.copyOf(entries);
	}

	void resetSession()
	{
		previous = null;
		manualOrigin = null;
		inputs.clear();
		animation = null;
	}

	void clear()
	{
		entries.clear();
		resetSession();
	}

	private void add(WorldPoint origin, WorldPoint destination, int tick, String capture)
	{
		// A recent input is evidence to inspect, not proof that it caused this movement.
		String input = inputs.stream().filter(item -> manualOrigin != null || tick - item.tick <= 50)
			.map(item -> item.description).collect(Collectors.joining(" → "));
		entries.add(new Entry(origin, destination, input, capture, null, ""));
		inputs.clear();
		if (animation != null && animation.origin.equals(origin)
			&& (manualOrigin == null || animation.start >= manualTick)
			&& animation.entry < 0 && (manualOrigin != null
				|| animation.end < 0 || tick <= animation.end + 1))
		{
			animation.entry = entries.size() - 1;
			updateAnimation();
		}
	}

	private boolean updateAnimation()
	{
		if (animation == null || animation.entry < 0)
		{
			return false;
		}
		Entry old = entries.get(animation.entry);
		String ids = animation.ids.toString();
		Integer ticks = animation.end < 0 ? null : animation.end - animation.start;
		entries.set(animation.entry, new Entry(old.origin, old.destination, old.interaction,
			old.capture, ticks, ids));
		return true;
	}

	static String coordinate(WorldPoint point)
	{
		return point.getX() + " " + point.getY() + " " + point.getPlane();
	}
}
