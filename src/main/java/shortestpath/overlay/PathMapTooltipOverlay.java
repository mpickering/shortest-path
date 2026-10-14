package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;
import shortestpath.transport.Transport;

public class PathMapTooltipOverlay extends Overlay
{
	private final Client client;
	private final shortestpath.scheduler.PathScheduler scheduler, shortestpath.settings.Settings settings, shortestpath.presentation.PathAnnotator annotator;
	private final WorldMapCanvas canvas;
	private final DebugMapOverlay debugMapOverlay;

	@Inject
	private PathMapTooltipOverlay(Client client, shortestpath.scheduler.PathScheduler scheduler, shortestpath.settings.Settings settings, shortestpath.presentation.PathAnnotator annotator, WorldMapCanvas canvas,
		DebugMapOverlay debugMapOverlay)
	{
		this.client = client;
		this.scheduler = scheduler;
		this.settings = settings;
		this.annotator = annotator;
		this.canvas = canvas;
		this.debugMapOverlay = debugMapOverlay;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_HIGHEST);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Rectangle bounds = canvas.bounds();
		if (!settings.display().drawMap() || bounds == null)
		{
			return null;
		}

		Point cursorPos = client.getMouseCanvasPosition();
		int point = WorldPointUtil.UNDEFINED;
		List<String> rows = new ArrayList<>();
		if (scheduler.getActiveSearch() != null)
		{
			List<PathStep> path = scheduler.getActiveSearch().getPath();
			for (int i = 0; i < path.size() && point == WorldPointUtil.UNDEFINED; i++)
			{
				int nextPoint = WorldPointUtil.UNDEFINED;
				if (path.size() > i + 1)
				{
					nextPoint = path.get(i + 1).getPackedPosition();
				}
				if (canvas.isHovered(path.get(i).getPackedPosition(), cursorPos))
				{
					point = path.get(i).getPackedPosition();
					rows.addAll(pathRows(nextPoint, i + 1, path, i));
				}
			}
			for (int target : scheduler.getActiveSearch().getTargets().points())
			{
				if (point == WorldPointUtil.UNDEFINED && !path.isEmpty()
					&& target != path.get(path.size() - 1).getPackedPosition() && canvas.isHovered(target, cursorPos))
				{
					point = target;
					rows.addAll(pathRows(WorldPointUtil.UNDEFINED, -1, path, -1));
				}
			}
		}

		// The debug layers' hovered marker adds its own rows, below the path's.
		List<String> debugRows = debugMapOverlay.getHoverRows();
		if (!debugRows.isEmpty())
		{
			if (point == WorldPointUtil.UNDEFINED)
			{
				point = debugMapOverlay.getHoverPoint();
			}
			rows.addAll(debugRows);
		}

		if (!rows.isEmpty())
		{
			canvas.drawTooltip(graphics, bounds, canvas.x(point), canvas.y(point), rows);
		}

		return null;
	}

	private List<String> pathRows(int nextPoint, int n, List<PathStep> path, int pathIndex)
	{
		List<String> rows = new ArrayList<>(Arrays.asList("Shortest path:",
			n < 0 ? "Unused target" : ("Step " + n + " of " + scheduler.getActiveSearch().getPath().size())));
		if (nextPoint != WorldPointUtil.UNDEFINED)
		{
			PathStep currentStep = path.get(pathIndex);
			PathStep nextStep = annotator.nextPathStep(path, pathIndex);
			for (Transport transport : annotator.transportsForEdge(currentStep, nextStep))
			{
				String displayInfo = annotator.formatTransportDisplay(transport);
				if (displayInfo != null && !displayInfo.isEmpty())
				{
					// Check if this transport goes to POH - if so, look ahead to find the exit
					// transport
					String pohExitSuffix = OverlayDrawing.pohExitSuffix(annotator, nextPoint, path, pathIndex);
					if (pohExitSuffix != null)
					{
						displayInfo = displayInfo + pohExitSuffix;
					}
					rows.add(displayInfo);
					break;
				}
			}
		}

		return rows;
	}
}
