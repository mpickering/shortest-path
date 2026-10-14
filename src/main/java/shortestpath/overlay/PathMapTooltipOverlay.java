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
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.PathStep;
import shortestpath.transport.Transport;

public class PathMapTooltipOverlay extends Overlay
{
	private final Client client;
	private final ShortestPathPlugin plugin;
	private final WorldMapCanvas canvas;

	@Inject
	private PathMapTooltipOverlay(Client client, ShortestPathPlugin plugin, WorldMapCanvas canvas)
	{
		this.client = client;
		this.plugin = plugin;
		this.canvas = canvas;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_HIGHEST);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.drawMap || canvas.bounds() == null)
		{
			return null;
		}

		if (plugin.getActiveSearch() != null)
		{
			List<PathStep> path = plugin.getActiveSearch().getPath();
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
			for (int target : plugin.getActiveSearch().getTargets().points())
			{
				if (!path.isEmpty() && target != path.get(path.size() - 1).getPackedPosition())
				{
					drawTooltip(graphics, cursorPos, target, WorldPointUtil.UNDEFINED, -1, path, -1);
				}
			}
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
			n < 0 ? "Unused target" : ("Step " + n + " of " + plugin.getActiveSearch().getPath().size())));
		if (nextPoint != WorldPointUtil.UNDEFINED)
		{
			PathStep currentStep = path.get(pathIndex);
			PathStep nextStep = plugin.nextPathStep(path, pathIndex);
			for (Transport transport : plugin.transportsForEdge(currentStep, nextStep))
			{
				String displayInfo = plugin.formatTransportDisplay(transport);
				if (displayInfo != null && !displayInfo.isEmpty())
				{
					// Check if this transport goes to POH - if so, look ahead to find the exit
					// transport
					String pohExitInfo = plugin.getPohExitInfo(nextPoint, path, pathIndex);
					if (pohExitInfo != null)
					{
						displayInfo = displayInfo + " (Exit: " + pohExitInfo + ")";
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
