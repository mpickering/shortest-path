package shortestpath.overlay;

import com.google.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.ActiveSearch;
import shortestpath.pathfinder.PathStep;
import shortestpath.presentation.PathAnnotator;
import shortestpath.scheduler.PathScheduler;
import shortestpath.settings.Settings;
import shortestpath.transport.Transport;
import shortestpath.widgets.WidgetGeometry;

public class PathMapTooltipOverlay extends Overlay
{
	private final Client client;
	private final WorldMapCanvas canvas;
	private final Settings settings;
	private final PathScheduler scheduler;
	private final WidgetGeometry geometry;
	private final PathAnnotator annotator;

	@Inject
	private PathMapTooltipOverlay(Client client, Settings settings, PathScheduler scheduler,
		WidgetGeometry geometry, PathAnnotator annotator, WorldMapCanvas canvas)
	{
		this.client = client;
		this.canvas = canvas;
		this.settings = settings;
		this.scheduler = scheduler;
		this.geometry = geometry;
		this.annotator = annotator;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_HIGHEST);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!settings.display().drawMap() || !geometry.worldMapOpen())
		{
			return null;
		}

		ActiveSearch search = scheduler.getActiveSearch();
		if (search != null)
		{
			List<PathStep> path = search.getPath();
			Point cursorPos = client.getMouseCanvasPosition();
			for (int i = 0; i < path.size(); i++)
			{
				int nextPoint = WorldPointUtil.UNDEFINED;
				if (path.size() > i + 1)
				{
					nextPoint = path.get(i + 1).getPackedPosition();
				}
				if (drawTooltip(graphics, cursorPos, path.get(i).getPackedPosition(), nextPoint, i + 1, path, i))
				{
					return null;
				}
			}
			OverlayDrawing.forEachUnreachedTarget(search, path, target ->
				drawTooltip(graphics, cursorPos, target, WorldPointUtil.UNDEFINED, -1, path, -1));
		}

		return null;
	}

	private boolean drawTooltip(Graphics2D graphics, Point cursorPos, int point, int nextPoint, int n,
		List<PathStep> path, int pathIndex)
	{
		if (!canvas.isHovered(point, cursorPos))
		{
			return false;
		}

		List<String> rows = new ArrayList<>(Arrays.asList("Shortest path:",
			n < 0 ? "Unused target" : ("Step " + n + " of " + path.size())));
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

		canvas.drawTooltip(graphics, canvas.bounds(), canvas.x(point), canvas.y(point), rows);

		return true;
	}
}
