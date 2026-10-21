package shortestpath.transportlogger;

import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TransportLoggerPluginTest
{
	private final Client client = mock(Client.class);
	private final TransportLoggerPlugin plugin = new TransportLoggerPlugin();
	private TransportLog log;

	@Before
	public void setUp() throws Exception
	{
		Field clientField = TransportLoggerPlugin.class.getDeclaredField("client");
		clientField.setAccessible(true);
		clientField.set(plugin, client);
		Field logField = TransportLoggerPlugin.class.getDeclaredField("log");
		logField.setAccessible(true);
		log = (TransportLog) logField.get(plugin);
		log.sample(new WorldPoint(3200, 3200, 0), 100);
		when(client.getTickCount()).thenReturn(100);
	}

	@Test
	public void routeCoordinatesUseThePublicPathMessageOnlyWhileLoggedIn() throws Exception
	{
		EventBus eventBus = mock(EventBus.class);
		Field field = TransportLoggerPlugin.class.getDeclaredField("eventBus");
		field.setAccessible(true);
		field.set(plugin, eventBus);
		WorldPoint target = new WorldPoint(4127, 5508, 0);
		plugin.routeTo(target);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		plugin.routeTo(target);
		verifyNoInteractions(eventBus);
		when(client.getLocalPlayer()).thenReturn(mock(Player.class));
		plugin.routeTo(target);
		ArgumentCaptor<PluginMessage> request = ArgumentCaptor.forClass(PluginMessage.class);
		verify(eventBus).post(request.capture());
		assertEquals("shortestpath", request.getValue().getNamespace());
		assertEquals("path", request.getValue().getName());
		assertEquals(target, request.getValue().getData().get("target"));
		assertEquals(1, request.getValue().getData().size());
	}

	@Test
	public void clickedDialogueCapturesChosenTextAndNumberInsteadOfContinue()
	{
		MenuEntry boat = menu(MenuAction.GAME_OBJECT_FIRST_OPTION, "Travel", "<col=ffff00>Boat</col>", 21177);
		plugin.onMenuOptionClicked(new MenuOptionClicked(boat));
		MenuEntry choice = menu(MenuAction.WIDGET_CONTINUE, "Continue", "", 0);
		Widget selected = option(3, "<col=ffffff>Port<br>Khazard</col>");
		when(choice.getWidget()).thenReturn(selected);
		plugin.onMenuOptionClicked(new MenuOptionClicked(choice));
		String input = capturedInput();
		assertTrue(input, input.startsWith("Travel Boat [object 21177]"));
		assertTrue(input, input.contains("Dialogue option 3: Port Khazard [widget " + InterfaceID.Chatmenu.OPTIONS + ":3]"));
		assertFalse(input, input.contains("Continue"));
	}

	@Test
	public void numberKeyCapturesOnlyTheSelectedDialogueChoice()
	{
		Widget parent = mock(Widget.class);
		Widget[] options = {option(0, "Select an Option"), option(1, "Port Sarim"),
			option(2, "Rellekka"), option(3, "Port Khazard")};
		when(parent.getChildren()).thenReturn(options);
		when(client.getWidget(InterfaceID.Chatmenu.OPTIONS)).thenReturn(parent);
		when(client.getIntStack()).thenReturn(new int[]{InterfaceID.Chatmenu.OPTIONS, 2});
		when(client.getIntStackSize()).thenReturn(2);
		plugin.onScriptPreFired(new ScriptPreFired(ScriptID.CHATBOX_KEYINPUT_MATCHED));
		String input = capturedInput();
		assertEquals("Dialogue option 2: Rellekka [widget " + InterfaceID.Chatmenu.OPTIONS + ":2]", input);
		assertFalse(input.contains("Port Sarim"));
		assertFalse(input.contains("Port Khazard"));
	}

	@Test
	public void rightClickNpcOptionUsesNpcDefinitionIdNotSceneIndex()
	{
		MenuEntry entry = menu(MenuAction.NPC_THIRD_OPTION, "Rellekka", "<col=ffff00>Sailor</col>", 7);
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(4657);
		when(entry.getNpc()).thenReturn(npc);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		assertEquals("Rellekka Sailor [npc 4657] (NPC_THIRD_OPTION)", capturedInput());
	}

	@Test
	public void missingNpcIsLabelledAsAnIndexInsteadOfInventingAnId()
	{
		plugin.onMenuOptionClicked(new MenuOptionClicked(menu(MenuAction.NPC_SECOND_OPTION, "Travel", "Sailor", 7)));
		assertEquals("Travel Sailor [npc index 7] (NPC_SECOND_OPTION)", capturedInput());
	}

	@Test
	public void usingAnItemOnAnNpcKeepsBothIdentifiers()
	{
		MenuEntry entry = menu(MenuAction.ITEM_USE_ON_NPC, "Use", "Ticket -> Sailor", 7);
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(4657);
		when(entry.getNpc()).thenReturn(npc);
		when(entry.getItemId()).thenReturn(1234);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		assertEquals("Use Ticket -> Sailor [npc 4657] [item 1234] (ITEM_USE_ON_NPC)", capturedInput());
	}

	@Test
	public void rightClickObjectDestinationKeepsTheSelectedAction()
	{
		plugin.onMenuOptionClicked(new MenuOptionClicked(menu(MenuAction.GAME_OBJECT_SECOND_OPTION,
			"Weiss", "Boat", 21176)));
		assertEquals("Weiss Boat [object 21176] (GAME_OBJECT_SECOND_OPTION)", capturedInput());
	}

	@Test
	public void destinationWidgetAddsItsLabelWithoutLosingTheMenuOperation()
	{
		Widget widget = mock(Widget.class);
		when(widget.getId()).thenReturn(123 << 16 | 4);
		when(widget.getIndex()).thenReturn(6);
		when(widget.getText()).thenReturn("<col=ffffff>Port<br>Khazard</col>");
		MenuEntry entry = menu(MenuAction.CC_OP, "Select", "", 2);
		when(entry.getWidget()).thenReturn(widget);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
		assertEquals("Select [widget text: Port Khazard] [widget " + widget.getId() + ":6; op 2] (CC_OP)", capturedInput());
	}

	@Test
	public void consumedActionsAndUnrelatedKeyboardScriptsAreIgnored()
	{
		MenuOptionClicked consumed = new MenuOptionClicked(menu(MenuAction.GAME_OBJECT_FIRST_OPTION, "Travel", "Boat", 123));
		consumed.consume();
		plugin.onMenuOptionClicked(consumed);
		plugin.onMenuOptionClicked(new MenuOptionClicked(menu(MenuAction.WALK, "Walk here", "", 0)));
		plugin.onScriptPreFired(new ScriptPreFired(-1));
		assertEquals("", capturedInput());
		verify(client, never()).getIntStack();
	}

	@Test
	public void missingOrInvalidDialogueWidgetsAndStacksAreIgnored()
	{
		ScriptPreFired key = new ScriptPreFired(ScriptID.CHATBOX_KEYINPUT_MATCHED);
		plugin.onScriptPreFired(key);
		when(client.getIntStackSize()).thenReturn(2);
		plugin.onScriptPreFired(key);
		when(client.getIntStack()).thenReturn(new int[]{InterfaceID.Chatmenu.OPTIONS});
		plugin.onScriptPreFired(key);
		when(client.getIntStack()).thenReturn(new int[]{InterfaceID.Chatmenu.OPTIONS, 0});
		plugin.onScriptPreFired(key);
		when(client.getIntStack()).thenReturn(new int[]{123, 1});
		plugin.onScriptPreFired(key);
		when(client.getIntStack()).thenReturn(new int[]{InterfaceID.Chatmenu.OPTIONS, 1});
		plugin.onScriptPreFired(key);
		Widget parent = mock(Widget.class);
		when(client.getWidget(InterfaceID.Chatmenu.OPTIONS)).thenReturn(parent);
		plugin.onScriptPreFired(key);
		Widget title = option(0, "Select an Option");
		when(parent.getChildren()).thenReturn(new Widget[]{title});
		plugin.onScriptPreFired(key);
		Widget hidden = option(1, "Rellekka");
		when(hidden.isHidden()).thenReturn(true);
		when(parent.getChildren()).thenReturn(new Widget[]{null, hidden});
		plugin.onScriptPreFired(key);
		assertEquals("", capturedInput());
	}

	private String capturedInput()
	{
		log.sample(new WorldPoint(3300, 3300, 0), 101);
		assertEquals(1, log.entries().size());
		return log.entries().get(0).interaction;
	}

	private static MenuEntry menu(MenuAction type, String option, String target, int identifier)
	{
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.getType()).thenReturn(type);
		when(entry.getOption()).thenReturn(option);
		when(entry.getTarget()).thenReturn(target);
		when(entry.getIdentifier()).thenReturn(identifier);
		when(entry.getItemId()).thenReturn(-1);
		return entry;
	}

	private static Widget option(int index, String text)
	{
		Widget option = mock(Widget.class);
		when(option.getId()).thenReturn(InterfaceID.Chatmenu.OPTIONS);
		when(option.getIndex()).thenReturn(index);
		when(option.getText()).thenReturn(text);
		return option;
	}
}
