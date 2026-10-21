package shortestpath.transportlogger;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Transport Logger (development)",
	description = "Capture transport coordinates and observed animation ticks for data gathering",
	enabledByDefault = false
)
public class TransportLoggerPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ClientToolbar toolbar;
	@Inject private EventBus eventBus;
	private final TransportLog log = new TransportLog();
	private TransportLoggerPanel panel;
	private NavigationButton navigation;

	@Override
	protected void startUp() throws Exception
	{
		Runnable create = () -> panel = new TransportLoggerPanel(
			command -> clientThread.invokeLater(() -> command(command)),
			target -> clientThread.invokeLater(() -> routeTo(target)));
		if (SwingUtilities.isEventDispatchThread())
		{
			create.run();
		}
		else
		{
			SwingUtilities.invokeAndWait(create);
		}
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = icon.createGraphics();
		graphics.setColor(Color.CYAN);
		graphics.drawString("T", 4, 13);
		graphics.dispose();
		navigation = NavigationButton.builder().tooltip("Transport logger").icon(icon)
			.panel(panel).priority(101).build();
		toolbar.addNavigation(navigation);
		log.resetSession();
		publish("Ready");
	}

	@Override
	protected void shutDown()
	{
		toolbar.removeNavigation(navigation);
		navigation = null;
		panel = null;
		log.resetSession();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		WorldPoint point = position();
		if (client.getGameState() == GameState.LOGGED_IN && point != null
			&& log.sample(point, client.getTickCount()))
		{
			publish("Ready");
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Player player = client.getLocalPlayer();
		WorldPoint point = position();
		if ((client.getGameState() == GameState.LOGGED_IN || client.getGameState() == GameState.LOADING)
			&& player != null && event.getActor() == player && point != null
			&& log.animation(point, player.getAnimation(), client.getTickCount()))
		{
			publish("Ready");
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (event.isConsumed() || action == MenuAction.WALK || action == MenuAction.CANCEL
			|| action.name().startsWith("EXAMINE") || action.name().startsWith("RUNELITE"))
		{
			return;
		}
		Widget widget = event.getWidget();
		if (action == MenuAction.WIDGET_CONTINUE && recordDialogue(widget))
		{
			return;
		}
		String input = (plain(event.getMenuOption()) + " " + plain(event.getMenuTarget())).trim();
		if (action.name().contains("GAME_OBJECT"))
		{
			input += " [object " + event.getId() + "]";
		}
		else if (action.name().contains("NPC"))
		{
			NPC npc = event.getMenuEntry().getNpc();
			input += npc == null ? " [npc index " + event.getId() + "]" : " [npc " + npc.getId() + "]";
		}
		if (event.getItemId() >= 0)
		{
			input += " [item " + event.getItemId() + "]";
		}
		if (widget != null)
		{
			String label = plain(widget.getText());
			if (label.isEmpty())
			{
				label = plain(widget.getName());
			}
			if (!label.isEmpty() && !input.contains(label))
			{
				input += " [widget text: " + label + "]";
			}
			String operation = action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY
				? "; op " + event.getId() : "";
			input += " [widget " + widget.getId() + ":" + widget.getIndex() + operation + "]";
		}
		log.interaction(input + " (" + action + ")", client.getTickCount());
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		// RuneLite's PaymentTracker uses this script to observe keyboard dialogue selections.
		if (event.getScriptId() != ScriptID.CHATBOX_KEYINPUT_MATCHED || client.getIntStackSize() < 2)
		{
			return;
		}
		int[] stack = client.getIntStack();
		if (stack == null || stack.length < 2 || stack[0] != InterfaceID.Chatmenu.OPTIONS || stack[1] <= 0)
		{
			return;
		}
		Widget options = client.getWidget(stack[0]);
		if (options != null && !options.isHidden())
		{
			Widget[] children = options.getChildren();
			if (children != null && stack[1] < children.length)
			{
				recordDialogue(children[stack[1]]);
			}
		}
	}

	private boolean recordDialogue(Widget option)
	{
		if (option == null || option.getId() != InterfaceID.Chatmenu.OPTIONS
			|| option.getIndex() <= 0 || option.isHidden() || plain(option.getText()).isEmpty())
		{
			return false;
		}
		log.interaction("Dialogue option " + option.getIndex() + ": " + plain(option.getText())
			+ " [widget " + option.getId() + ":" + option.getIndex() + "]", client.getTickCount());
		return true;
	}

	private static String plain(String text)
	{
		return text == null ? "" : Text.removeTags(text.replace("<br>", " ")).replace('\u00a0', ' ').trim();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN && event.getGameState() != GameState.LOADING)
		{
			log.resetSession();
			publish("Session reset");
		}
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		log.resetSession();
		publish("World changed");
	}

	private WorldPoint position()
	{
		Player player = client.getLocalPlayer();
		return player == null || player.getLocalLocation() == null ? null
			: WorldPoint.fromLocalInstance(client, player.getLocalLocation());
	}

	private void command(String command)
	{
		if (panel == null)
		{
			return;
		}
		if (command.equals("clear"))
		{
			log.clear();
		}
		else if (command.equals("cancel"))
		{
			log.cancelManual();
		}
		else
		{
			WorldPoint point = position();
			if (client.getGameState() != GameState.LOGGED_IN || point == null)
			{
				publish("Log in first");
				return;
			}
			if (command.equals("origin"))
			{
				log.markOrigin(point, client.getTickCount());
			}
			else if (!log.markDestination(point, client.getTickCount()))
			{
				publish("Mark origin, then travel first");
				return;
			}
		}
		publish(log.manualOrigin() == null ? "Ready" : "Origin: " + TransportLog.coordinate(log.manualOrigin()));
	}

	void routeTo(WorldPoint target)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			publish("Log in first");
			return;
		}
		eventBus.post(new PluginMessage("shortestpath", "path", Map.of("target", target)));
		publish("Route requested: " + TransportLog.coordinate(target));
	}

	private void publish(String message)
	{
		TransportLoggerPanel target = panel;
		List<TransportLog.Entry> entries = log.entries();
		if (target != null)
		{
			SwingUtilities.invokeLater(() -> target.update(entries, message));
		}
	}
}
