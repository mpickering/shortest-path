package shortestpath.benchmark;

import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import shortestpath.ShortestPathConfig;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.transport.Transport;

public class BenchmarkPathfinderConfig extends PathfinderConfig {
    public BenchmarkPathfinderConfig(Client client, ShortestPathConfig config) {
        super(client, config);
    }

    @Override
    public QuestState getQuestState(Quest quest) {
        return QuestState.FINISHED;
    }

    @Override
    public boolean varbitChecks(Transport transport) {
        return true;
    }

    @Override
    public boolean varPlayerChecks(Transport transport) {
        return true;
    }
}
