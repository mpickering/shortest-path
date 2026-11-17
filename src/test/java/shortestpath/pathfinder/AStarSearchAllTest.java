package shortestpath.pathfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import org.junit.BeforeClass;
import org.junit.Test;
import shortestpath.TestShortestPathConfig;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.astar.AStarForwardSearch;
import shortestpath.pathfinder.astar.GoalTest;
import shortestpath.pathfinder.astar.PreparedTarget;
import shortestpath.pathfinder.astar.SearchRestrictions;
import shortestpath.pathfinder.astar.SiteGraph;
import shortestpath.settings.TeleportationItem;

/**
 * {@link PreparedTarget#searchAll} gives every target the cost a search towards that target alone
 * finds, on the real world with a maxed account, from starts across the map.
 */
public class AStarSearchAllTest
{
	private static final GoalTest ANY_LAYER = (tile, banked) -> true;
	private static final int[] STARTS = {
		WorldPointUtil.packWorldPoint(3222, 3218, 0), // Lumbridge
		WorldPointUtil.packWorldPoint(2662, 3305, 0), // East Ardougne
		WorldPointUtil.packWorldPoint(1640, 3944, 0), // Wintertodt camp
	};

	private static SiteGraph graph;
	private static CollisionMap collision;
	private static int[] targets;

	@BeforeClass
	public static void prepare() throws Exception
	{
		PathfinderConfig config = maxedConfig();
		collision = config.getMap();
		graph = new SiteGraph(TestWorldData.routingStatic(), config.prepareAStarRoutingAccount(true));
		targets = hardClueTiles();
	}

	@Test
	public void everyTargetCostsWhatItsOwnSearchFinds()
	{
		PreparedTarget all = PreparedTarget.prepare(graph, collision, targets);
		int[] packed = all.packedTargets();
		for (int start : STARTS)
		{
			long started = System.nanoTime();
			int[] costs = all.searchAll(start, () -> false, SearchRestrictions.none(), ANY_LAYER);
			long allMillis = (System.nanoTime() - started) / 1_000_000;
			assertNotNull(costs);
			assertEquals(packed.length, costs.length);
			started = System.nanoTime();
			for (int i = 0; i < packed.length; i++)
			{
				AStarForwardSearch.Result single = PreparedTarget.prepare(graph, collision, packed[i])
					.search(start, () -> false, 1, SearchRestrictions.none(), AStarForwardSearch.NO_PROGRESS, ANY_LAYER);
				assertEquals("from " + WorldPointUtil.unpackWorldPoint(start) + " to "
					+ WorldPointUtil.unpackWorldPoint(packed[i]), single.cost(), costs[i]);
			}
			System.out.println("searchAll from " + WorldPointUtil.unpackWorldPoint(start) + ": " + packed.length
				+ " targets in " + allMillis + " ms; separate searches " + (System.nanoTime() - started) / 1_000_000
				+ " ms");
		}
	}

	private static int[] hardClueTiles() throws Exception
	{
		List<Integer> tiles = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			AStarSearchAllTest.class.getResourceAsStream("/clue-locations.tsv"), StandardCharsets.UTF_8)))
		{
			for (String line; (line = reader.readLine()) != null; )
			{
				String[] fields = line.split("\t");
				if (line.startsWith("#") || !fields[0].equals("HARD")) continue;
				String[] xyz = fields[1].split(" ");
				tiles.add(WorldPointUtil.packWorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
					Integer.parseInt(xyz[2])));
			}
		}
		return tiles.stream().mapToInt(Integer::intValue).toArray();
	}

	/** A logged-in account with every quest done, 99 in every skill and no items. */
	private static PathfinderConfig maxedConfig()
	{
		Client client = mock(Client.class);
		TestShortestPathConfig settings = new TestShortestPathConfig();
		ItemContainer empty = mock(ItemContainer.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getClientThread()).thenReturn(Thread.currentThread());
		when(client.getDBTableRows(DBTableID.Quest.ID)).thenReturn(List.of());
		when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);
		doReturn(new Item[0]).when(empty).getItems();
		doReturn(empty).when(client).getItemContainer(InventoryID.INV);
		doReturn(empty).when(client).getItemContainer(InventoryID.WORN);
		settings.set("calculationCutoff", 500);
		settings.set("useTeleportationItems", TeleportationItem.ALL);

		PathfinderConfig config = new TestPathfinderConfig(client, settings, QuestState.FINISHED, true, true);
		config.setItemStateService(shortestpath.items.ItemStateService.forTesting());
		config.refresh();
		return config;
	}
}
