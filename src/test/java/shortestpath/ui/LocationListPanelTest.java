package shortestpath.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import static org.junit.Assert.*;

public class LocationListPanelTest
{
	@Test
	public void readsDescriptionsAndSearchesAllTerms() throws Exception
	{
		List<LocationListPanel.Entry> entries = LocationListPanel.readEntries(new StringReader(
			"# coordinates\tdescription\n\n3200 3201 2\tClimb-down ladder — upstairs\n"));
		assertEquals(1, entries.size());
		LocationListPanel.Entry entry = entries.get(0);
		assertEquals(WorldPointUtil.packWorldPoint(3200, 3201, 2), entry.point);
		assertTrue(entry.label.contains("Climb-down ladder — upstairs"));
		assertTrue(entry.matches(new String[]{"ladder", "3201"}));
		assertFalse(entry.matches(new String[]{"ladder", "boat"}));
	}

	@Test
	public void rejectsBadRowsWithTheirLineNumber() throws Exception
	{
		for (String row : List.of("3200 3201 4\tBad plane", "-1 3201 0\tNegative",
			"32768 3201 0\tToo large", "x y z\tInvalid", "3200 3201\tMissing plane",
			"3200 3201 0", "3200 3201 0\t "))
		{
			IOException error = assertThrows(IOException.class,
				() -> LocationListPanel.readEntries(new StringReader("# header\n" + row)));
			assertTrue(error.getMessage(), error.getMessage().startsWith("Line 2:"));
		}
	}

	@Test
	public void debouncedSearchFiltersWithoutRoutingAndEnterShowsLocation() throws Exception
	{
		CountDownLatch filtered = new CountDownLatch(1);
		AtomicInteger routed = new AtomicInteger();
		AtomicInteger point = new AtomicInteger();
		LocationListPanel[] panel = new LocationListPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			panel[0] = new LocationListPanel(value ->
			{
				routed.incrementAndGet();
				point.set(value);
			});
			JList<?> list = find(panel[0], JList.class);
			list.getModel().addListDataListener(new ListDataListener()
			{
				@Override
				public void intervalAdded(ListDataEvent event)
				{
					filtered.countDown();
				}

				@Override
				public void intervalRemoved(ListDataEvent event)
				{
				}

				@Override
				public void contentsChanged(ListDataEvent event)
				{
				}
			});
			JTextField search = find(panel[0], JTextField.class);
			search.setText("ship");
			search.setText("SHIP 3019");
			// Typing leaves the list untouched until the debounce fires.
			assertTrue(list.getModel().getSize() > 1);
		});
		assertTrue("Search timer did not update the list", filtered.await(5, TimeUnit.SECONDS));
		SwingUtilities.invokeAndWait(() ->
		{
			JList<?> list = find(panel[0], JList.class);
			assertEquals(1, list.getModel().getSize());
			assertEquals(0, routed.get());
			list.setSelectedIndex(0);
			for (KeyListener listener : list.getKeyListeners())
			{
				listener.keyPressed(new KeyEvent(list, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'));
			}
			assertEquals(1, routed.get());
			assertEquals(WorldPointUtil.packWorldPoint(3019, 3960, 2), point.get());
		});
	}

	private static <T extends Component> T find(Container container, Class<T> type)
	{
		for (Component child : container.getComponents())
		{
			if (type.isInstance(child))
			{
				return type.cast(child);
			}
			if (child instanceof Container)
			{
				T result = find((Container) child, type);
				if (result != null)
				{
					return result;
				}
			}
		}
		return null;
	}
}
