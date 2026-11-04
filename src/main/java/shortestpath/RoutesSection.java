package shortestpath;

import java.awt.Color;
import java.awt.Cursor;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import shortestpath.presentation.RouteChoices;

/** The sidebar's list of the current path's routes: click one to show it. Hidden with nothing to choose. */
final class RoutesSection extends JPanel
{
	private static final int TEXT_WIDTH = 170;

	private IntConsumer chosen = route -> { };

	RoutesSection()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 0, 0, 0));
		setVisible(false);
	}

	void onChosen(IntConsumer chosen)
	{
		this.chosen = chosen;
	}

	void show(List<RouteChoices.Choice> choices)
	{
		removeAll();
		setVisible(!choices.isEmpty());
		if (!choices.isEmpty())
		{
			JLabel heading = new JLabel("Routes");
			heading.setFont(FontManager.getRunescapeBoldFont());
			heading.setForeground(Color.WHITE);
			heading.setAlignmentX(LEFT_ALIGNMENT);
			heading.setBorder(new EmptyBorder(0, 0, 4, 0));
			add(heading);
			for (RouteChoices.Choice choice : choices)
			{
				add(row(choice));
			}
		}
		revalidate();
		repaint();
	}

	private JButton row(RouteChoices.Choice choice)
	{
		JButton row = new JButton(text(choice));
		row.setHorizontalAlignment(SwingConstants.LEFT);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setFocusPainted(false);
		row.setContentAreaFilled(true);
		row.setOpaque(true);
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setForeground(choice.isSelected() ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 1, 0,
				choice.isSelected() ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR),
			new EmptyBorder(4, 6, 4, 4)));
		row.getAccessibleContext().setAccessibleName(choice.getTitle());
		row.addActionListener(event -> chosen.accept(choice.getRoute()));
		return row;
	}

	private static String text(RouteChoices.Choice choice)
	{
		return "<html><div style=\"width:" + TEXT_WIDTH + "px\"><b>" + escape(choice.getTitle()) + "</b> &middot; "
			+ escape(choice.getTicks()) + "<br>" + escape(choice.getJourneys()) + "</div></html>";
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
