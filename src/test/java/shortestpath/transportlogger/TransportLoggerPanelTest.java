package shortestpath.transportlogger;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransportLoggerPanelTest
{
	@Test
	public void selectionCopyAndClearCommandsWorkOnTheEdt() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			List<String> commands = new ArrayList<>();
			TransportLoggerPanel panel = new TransportLoggerPanel(commands::add, target ->
			{
			});
			TransportLog.Entry entry = new TransportLog.Entry(new WorldPoint(3200, 3200, 0),
				new WorldPoint(3300, 3300, 0), "Climb-down Ladder [object 123]", "Automatic jump", 4, "[828]");
			panel.update(List.of(entry), "Ready");
			assertEquals(TransportLog.HEADER + "\n" + entry.tsv(), panel.selectedTsv());
			findButton(panel, "Start").doClick();
			findButton(panel, "Finish").doClick();
			findButton(panel, "Cancel").doClick();
			findButton(panel, "Clear").doClick();
			assertEquals(List.of("origin", "destination", "cancel", "clear"), commands);
			panel.update(List.of(), "Ready");
			assertEquals("", panel.selectedTsv());
		});
	}

	@Test
	public void renderPreview() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			TransportLoggerPanel panel = new TransportLoggerPanel(command ->
			{
			}, target ->
			{
			});
			panel.update(List.of(
				new TransportLog.Entry(new WorldPoint(2445, 3416, 1), new WorldPoint(2446, 3415, 0),
					"Climb-down Staircase [object 16677]", "Automatic jump", 4, "[828]"),
				new TransportLog.Entry(new WorldPoint(2791, 2937, 0), new WorldPoint(2791, 2935, 0),
					"Chop-down Jungle tree [object 2890]", "Manual", null, "")), "Ready");
			panel.setSize(225, 700);
			layout(panel);
			BufferedImage image = new BufferedImage(225, 700, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = image.createGraphics();
			panel.printAll(graphics);
			graphics.dispose();
			try
			{
				Path out = Path.of("build/transport-logger-preview.png");
				Files.createDirectories(out.getParent());
				assertTrue(ImageIO.write(image, "png", out.toFile()));
			}
			catch (java.io.IOException e)
			{
				throw new AssertionError(e);
			}
		});
	}

	@Test
	public void routeTargetAcceptsCoordinatesAndRejectsInvalidInput() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			List<WorldPoint> targets = new ArrayList<>();
			TransportLoggerPanel panel = new TransportLoggerPanel(command ->
			{
			}, targets::add);
			JTextField coordinates = findField(panel);
			coordinates.setText("4127 5508 0");
			findButton(panel, "Set target").doClick();
			coordinates.setText(" 4075, 4601, 0 ");
			coordinates.postActionEvent();
			assertEquals(List.of(new WorldPoint(4127, 5508, 0), new WorldPoint(4075, 4601, 0)), targets);
			for (String invalid : List.of("", "4127 5508", "x y z", "-1 5508 0", "16384 5508 0", "4127 5508 4", "4127 5508 -1", "999999999999 5508 0"))
			{
				coordinates.setText(invalid);
				findButton(panel, "Set target").doClick();
			}
			assertEquals(2, targets.size());
		});
	}

	private static JTextField findField(Container parent)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JTextField)
			{
				return (JTextField) child;
			}
			if (child instanceof Container)
			{
				JTextField found = findField((Container) child);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static JButton findButton(Container parent, String name)
	{
		for (Component child : parent.getComponents())
		{
			if (child instanceof JButton && name.equals(((JButton) child).getText()))
			{
				return (JButton) child;
			}
			if (child instanceof Container)
			{
				JButton found = findButton((Container) child, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void layout(Container parent)
	{
		parent.doLayout();
		for (Component child : parent.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}
}
