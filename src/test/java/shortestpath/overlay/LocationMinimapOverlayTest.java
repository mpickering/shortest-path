package shortestpath.overlay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import net.runelite.api.CameraFocusableEntity;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import org.junit.Test;
import shortestpath.ShortestPathPlugin;
import shortestpath.WorldPointUtil;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LocationMinimapOverlayTest
{
	@Test
	public void markersWorkWithoutARouteAndRespectFloorClipAndToggle() throws Exception
	{
		Client client = mock(Client.class);
		ShortestPathPlugin plugin = mock(ShortestPathPlugin.class);
		WorldView view = mock(WorldView.class);
		when(client.getTopLevelWorldView()).thenReturn(view);
		when(view.getBaseX()).thenReturn(3200);
		when(view.getBaseY()).thenReturn(3200);
		when(view.getSizeX()).thenReturn(104);
		when(view.getSizeY()).thenReturn(104);
		when(client.getMinimapZoom()).thenReturn(4.0);
		CameraFocusableEntity focus = mock(CameraFocusableEntity.class);
		when(client.getCameraFocusEntity()).thenReturn(focus);
		when(focus.getCameraFocus()).thenReturn(new LocalPoint((10 << 7) + 64, (10 << 7) + 64, 0));
		Widget widget = mock(Widget.class);
		when(client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP_DRAW_AREA)).thenReturn(widget);
		when(widget.getCanvasLocation()).thenReturn(new Point(10, 10));
		when(widget.getWidth()).thenReturn(100);
		when(widget.getHeight()).thenReturn(100);
		shortestpath.settings.Settings settings = new shortestpath.settings.Settings(mock(net.runelite.client.config.ConfigManager.class), mock(shortestpath.ShortestPathConfig.class));
		shortestpath.widgets.WidgetGeometry geometry = mock(shortestpath.widgets.WidgetGeometry.class);
		when(geometry.getMinimapClipArea()).thenReturn(new Rectangle(0, 0, 90, 90));
		when(plugin.getLocationPoints()).thenReturn(new int[]{
			WorldPointUtil.packWorldPoint(3210, 3210, 0),
			WorldPointUtil.packWorldPoint(3213, 3210, 1),
			WorldPointUtil.packWorldPoint(3220, 3210, 0)
		});
		Constructor<PathMinimapOverlay> constructor = PathMinimapOverlay.class
			.getDeclaredConstructor(Client.class, shortestpath.settings.Settings.class, shortestpath.scheduler.PathRenderState.class, shortestpath.scheduler.PathScheduler.class, shortestpath.widgets.WidgetGeometry.class, ShortestPathPlugin.class);
		constructor.setAccessible(true);
		PathMinimapOverlay overlay = constructor.newInstance(client, settings, new shortestpath.scheduler.PathRenderState(null, null, settings), mock(shortestpath.scheduler.PathScheduler.class), geometry, plugin);
		BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		when(settings.configured().debugLayers()).thenReturn(java.util.Set.of(shortestpath.DebugLayer.LOCATIONS));
		overlay.render(graphics);
		graphics.dispose();
		assertEquals(Color.CYAN.getRGB(), image.getRGB(60, 60));
		assertEquals("Other floors stay hidden", 0, image.getRGB(72, 60));
		assertEquals("Markers are clipped to the minimap", 0, image.getRGB(100, 60));

		when(settings.configured().debugLayers()).thenReturn(java.util.Set.of());
		BufferedImage hidden = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
		graphics = hidden.createGraphics();
		overlay.render(graphics);
		graphics.dispose();
		assertEquals(0, hidden.getRGB(60, 60));
	}
}
