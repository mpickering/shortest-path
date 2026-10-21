package shortestpath.transportlogger;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.PluginPanel;

final class TransportLoggerPanel extends PluginPanel
{
	private final DefaultListModel<String> model = new DefaultListModel<>();
	private final JList<String> history = new JList<>(model);
	private final JTextArea details = new JTextArea();
	private final JLabel status = new JLabel("Ready");
	private List<TransportLog.Entry> entries = List.of();

	TransportLoggerPanel(Consumer<String> command, Consumer<WorldPoint> routeTarget)
	{
		super(false);
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel header = new JPanel(new BorderLayout(0, 8));
		header.add(new JLabel("Transport logger"), BorderLayout.NORTH);
		JTextArea help = new JTextArea("Jumps are logged automatically. For short crossings: Start, take the transport, then Finish on its landing tile.");
		help.setEditable(false);
		help.setOpaque(false);
		help.setLineWrap(true);
		help.setWrapStyleWord(true);
		help.setRows(4);
		header.add(help, BorderLayout.CENTER);
		JPanel controls = new JPanel(new GridLayout(0, 2, 4, 4));
		button(controls, "Start", () -> command.accept("origin"));
		button(controls, "Finish", () -> command.accept("destination"));
		button(controls, "Cancel", () -> command.accept("cancel"));
		button(controls, "Clear", () -> command.accept("clear"));
		button(controls, "Copy row", () -> copy(selectedTsv()));
		button(controls, "Copy all", () -> copy(TransportLog.HEADER + "\n" + entries.stream()
			.map(TransportLog.Entry::tsv).collect(Collectors.joining("\n"))));
		JPanel actions = new JPanel(new BorderLayout(0, 8));
		actions.add(controls, BorderLayout.NORTH);
		JPanel target = new JPanel(new BorderLayout(0, 4));
		JTextField coordinates = new JTextField();
		JLabel label = new JLabel("Route target (x y plane)");
		label.setLabelFor(coordinates);
		target.add(label, BorderLayout.NORTH);
		target.add(coordinates, BorderLayout.CENTER);
		Runnable setTarget = () ->
		{
			try
			{
				String[] parts = coordinates.getText().trim().split("[\\s,]+");
				if (parts.length != 3)
				{
					throw new IllegalArgumentException();
				}
				int x = Integer.parseInt(parts[0]);
				int y = Integer.parseInt(parts[1]);
				int plane = Integer.parseInt(parts[2]);
				if (x < 0 || x > 16383 || y < 0 || y > 16383 || plane < 0 || plane > 3)
				{
					throw new IllegalArgumentException();
				}
				routeTarget.accept(new WorldPoint(x, y, plane));
			}
			catch (IllegalArgumentException e)
			{
				status.setText("Use x y plane; plane must be 0–3");
			}
		};
		coordinates.addActionListener(event -> setTarget.run());
		JButton targetButton = new JButton("Set target");
		targetButton.addActionListener(event -> setTarget.run());
		target.add(targetButton, BorderLayout.SOUTH);
		actions.add(target, BorderLayout.SOUTH);
		header.add(actions, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);
		history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		history.getAccessibleContext().setAccessibleName("Recorded transports, oldest first");
		history.addListSelectionListener(event -> showSelected());
		JPanel body = new JPanel(new BorderLayout(0, 8));
		JScrollPane listScroll = new JScrollPane(history);
		listScroll.setPreferredSize(new Dimension(210, 190));
		body.add(listScroll, BorderLayout.CENTER);
		details.setEditable(false);
		details.setLineWrap(true);
		details.setWrapStyleWord(true);
		details.getAccessibleContext().setAccessibleName("Selected transport details");
		JScrollPane detailScroll = new JScrollPane(details);
		detailScroll.setPreferredSize(new Dimension(210, 220));
		body.add(detailScroll, BorderLayout.SOUTH);
		add(body, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	void update(List<TransportLog.Entry> snapshot, String message)
	{
		int selected = history.getSelectedIndex();
		boolean follow = selected < 0 || selected == entries.size() - 1;
		entries = snapshot;
		model.clear();
		for (int i = 0; i < entries.size(); i++)
		{
			TransportLog.Entry entry = entries.get(i);
			String label = entry.interaction.isEmpty() ? "Unknown interaction" : entry.interaction;
			label = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
			model.addElement("<html><div style='width:130px'>#" + (i + 1) + " " + label
				+ "<br>" + TransportLog.coordinate(entry.origin) + " →<br>"
				+ TransportLog.coordinate(entry.destination) + "</div></html>");
		}
		if (!entries.isEmpty())
		{
			history.setSelectedIndex(follow ? entries.size() - 1 : Math.min(selected, entries.size() - 1));
			history.ensureIndexIsVisible(history.getSelectedIndex());
		}
		showSelected();
		status.setText(entries.size() + " recorded · " + message);
		status.setToolTipText(message);
	}

	String selectedTsv()
	{
		int index = history.getSelectedIndex();
		return index < 0 ? "" : TransportLog.HEADER + "\n" + entries.get(index).tsv();
	}

	private void showSelected()
	{
		int index = history.getSelectedIndex();
		if (index < 0)
		{
			details.setText("");
			return;
		}
		TransportLog.Entry entry = entries.get(index);
		details.setText("#" + (index + 1) + " · " + entry.capture
			+ "\nFrom: " + TransportLog.coordinate(entry.origin)
			+ "\nTo: " + TransportLog.coordinate(entry.destination)
			+ "\nInput sequence: " + (entry.interaction.isEmpty() ? "Unknown" : entry.interaction)
			+ "\nAnimation ticks: " + (entry.animationTicks == null ? "Unknown / still running" : entry.animationTicks)
			+ "\nAnimation IDs: " + entry.animationIds
			+ "\n\nAnimation time only; not the movement lock. Empty timing fields are intentional.");
		details.setCaretPosition(0);
	}

	private void copy(String text)
	{
		if (text.isEmpty())
		{
			status.setText("Select a transport first");
			return;
		}
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
			status.setText("Copied");
		}
		catch (IllegalStateException e)
		{
			status.setText("Clipboard busy; try again");
		}
	}

	private static void button(JPanel parent, String label, Runnable action)
	{
		JButton button = new JButton(label);
		button.addActionListener(event -> action.run());
		parent.add(button);
	}
}
