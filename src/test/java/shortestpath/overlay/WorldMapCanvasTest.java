package shortestpath.overlay;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import net.runelite.api.Client;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.widgets.WidgetGeometry;

public class WorldMapCanvasTest
{
	@Test
	public void undergroundTileUsesDrawnCoordinatesForItsCentreAndEdges()
	{
		WidgetGeometry geometry = mock(WidgetGeometry.class);
		WorldMapCanvas canvas = new WorldMapCanvas(mock(Client.class), geometry);
		int real = WorldPointUtil.packWorldPoint(1457, 10075, 0);
		int drawn = WorldPointUtil.packWorldPoint(1329, 9883, 0);
		int next = WorldPointUtil.dxdy(drawn, 1, -1);
		when(geometry.drawnMapPoint(real)).thenReturn(drawn);
		when(geometry.mapWorldPointToGraphicsPointX(drawn)).thenReturn(100);
		when(geometry.mapWorldPointToGraphicsPointY(drawn)).thenReturn(200);
		when(geometry.mapWorldPointToGraphicsPointX(next)).thenReturn(104);
		when(geometry.mapWorldPointToGraphicsPointY(next)).thenReturn(204);

		assertEquals(100, canvas.x(real));
		assertEquals(200, canvas.y(real));
		assertEquals(new Rectangle(98, 198, 4, 4), canvas.tileRect(real));
	}
}
