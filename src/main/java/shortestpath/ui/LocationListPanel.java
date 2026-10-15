package shortestpath.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.PluginPanel;
import shortestpath.WorldPointUtil;

/** A searchable list of described world locations, adapted from Shortest Clue's picker. */
public class LocationListPanel extends PluginPanel
{
	static final class Entry
	{
		final int point;
		final String label;
		final String searchText;

		Entry(int x, int y, int plane, String description)
		{
			point = WorldPointUtil.packWorldPoint(x, y, plane);
			label = x + " " + y + " " + plane + "\n" + description;
			searchText = label.toLowerCase(Locale.ROOT);
		}

		boolean matches(String[] tokens)
		{
			for (String token : tokens)
			{
				if (!searchText.contains(token))
				{
					return false;
				}
			}
			return true;
		}
	}

	private final DefaultListModel<Entry> model = new DefaultListModel<>();
	private final JList<Entry> locations = new JList<>(model);
	private final JTextField search = new JTextField();
	private final JLabel count = new JLabel();
	private final JLabel source = new JLabel("Saved blocked origins");
	private final JButton load = new JButton("Load TSV…");
	private final Timer searchTimer = new Timer(150, event -> applyFilter());
	private List<Entry> entries;

	public LocationListPanel(IntConsumer onSelect)
	{
		try (Reader reader = new InputStreamReader(
			LocationListPanel.class.getResourceAsStream("/debug/locations.tsv"), StandardCharsets.UTF_8))
		{
			entries = readEntries(reader);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Cannot load bundled locations", e);
		}
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel header = new JPanel(new BorderLayout(0, 8));
		header.add(new JLabel("Locations"), BorderLayout.NORTH);
		search.setToolTipText("Search descriptions or coordinates; spaces separate terms");
		search.getAccessibleContext().setAccessibleName("Search locations");
		header.add(search, BorderLayout.CENTER);
		load.addActionListener(event -> loadFile());
		header.add(load, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		searchTimer.setRepeats(false);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				searchTimer.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				searchTimer.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				searchTimer.restart();
			}
		});
		locations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		locations.setCellRenderer(new LocationRenderer());
		locations.getAccessibleContext().setAccessibleName("Locations");
		locations.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				int row = locations.locationToIndex(event.getPoint());
				if (javax.swing.SwingUtilities.isLeftMouseButton(event) && row >= 0
					&& locations.getCellBounds(row, row).contains(event.getPoint()))
				{
					onSelect.accept(model.get(row).point);
				}
			}
		});
		locations.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent event)
			{
				if (event.getKeyCode() == KeyEvent.VK_ENTER && locations.getSelectedValue() != null)
				{
					onSelect.accept(locations.getSelectedValue().point);
				}
			}
		});
		add(new JScrollPane(locations), BorderLayout.CENTER);
		JPanel footer = new JPanel(new BorderLayout());
		footer.add(source, BorderLayout.NORTH);
		footer.add(count, BorderLayout.SOUTH);
		add(footer, BorderLayout.SOUTH);
		applyFilter();
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension size = super.getPreferredSize();
		size.height = Math.max(size.height, getScrollPane().getHeight());
		return size;
	}

	private void applyFilter()
	{
		searchTimer.stop();
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");
		List<Entry> visible = new ArrayList<>();
		for (Entry entry : entries)
		{
			if (entry.matches(tokens))
			{
				visible.add(entry);
			}
		}
		// As in Shortest Clue: debounce typing and notify Swing once for all added rows.
		model.clear();
		model.addAll(visible);
		count.setText(visible.size() + " of " + entries.size() + " locations");
	}

	private void loadFile()
	{
		JFileChooser chooser = new JFileChooser();
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		load.setEnabled(false);
		new SwingWorker<List<Entry>, Void>()
		{
			@Override
			protected List<Entry> doInBackground() throws IOException
			{
				try (Reader reader = Files.newBufferedReader(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8))
				{
					return readEntries(reader);
				}
			}

			@Override
			protected void done()
			{
				load.setEnabled(true);
				try
				{
					entries = get();
					source.setText(chooser.getSelectedFile().getName());
					source.setToolTipText(chooser.getSelectedFile().getAbsolutePath());
					applyFilter();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
				catch (ExecutionException e)
				{
					javax.swing.JOptionPane.showMessageDialog(LocationListPanel.this,
						e.getCause().getMessage(), "Cannot load locations", javax.swing.JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	static List<Entry> readEntries(Reader reader) throws IOException
	{
		List<Entry> result = new ArrayList<>();
		BufferedReader lines = new BufferedReader(reader);
		String line;
		int number = 0;
		while ((line = lines.readLine()) != null)
		{
			number++;
			if (line.isBlank() || line.stripLeading().startsWith("#"))
			{
				continue;
			}
			String[] fields = line.split("\t", 2);
			String[] coordinates = fields[0].trim().split("\\s+");
			try
			{
				if (fields.length != 2 || fields[1].isBlank() || coordinates.length != 3)
				{
					throw new IllegalArgumentException();
				}
				int x = Integer.parseInt(coordinates[0]);
				int y = Integer.parseInt(coordinates[1]);
				int plane = Integer.parseInt(coordinates[2]);
				if (x < 0 || x > 32767 || y < 0 || y > 32767 || plane < 0 || plane > 3)
				{
					throw new IllegalArgumentException();
				}
				result.add(new Entry(x, y, plane, fields[1].trim()));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("Line " + number + ": expected x y plane<TAB>description (x/y 0–32767, plane 0–3)", e);
			}
		}
		return result;
	}

	private static class LocationRenderer extends JTextArea implements ListCellRenderer<Entry>
	{
		LocationRenderer()
		{
			setLineWrap(true);
			setWrapStyleWord(true);
			setEditable(false);
			setFocusable(false);
			setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends Entry> list, Entry value,
			int index, boolean selected, boolean focused)
		{
			setText(value.label);
			setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			setForeground(selected ? list.getSelectionForeground() : list.getForeground());
			setFont(list.getFont());
			setSize(list.getWidth() > 8 ? list.getWidth() - 8 : 190, Short.MAX_VALUE);
			return this;
		}
	}
}
