package shortestpath.reachability;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReachabilityTargetLoaderTest {
    @Test
    public void loadsTargetsFromTsv() throws IOException {
        ReachabilityTargetLoader loader = new ReachabilityTargetLoader();
        List<ReachabilityTarget> targets = loader.loadFromResource("/reachability/targets.tsv");

        assertEquals(6, targets.size());
        assertEquals("Varrock west bank south tile", targets.get(0).getDescription());
    }

    @Test
    public void loadsClueTargetsFromCsvAndDedupesCoordinates() throws IOException {
        ReachabilityTargetLoader loader = new ReachabilityTargetLoader();
        List<ReachabilityTarget> targets = loader.loadFromCsv(Paths.get("/home/matt/shortest-path-haskell/clue_locations_full.csv"));

        assertEquals(865, targets.size());
        assertEquals("anagram (1212,3119,0)", targets.get(0).getDescription());
    }
}
