package shortestpath.analysis.visualizer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportLoader;
import shortestpath.transport.TransportType;

public class TransportMarkerIndex {
    private final Set<Integer> teleportEntries;
    private final Set<Integer> teleportExits;

    public TransportMarkerIndex(Set<Integer> teleportEntries, Set<Integer> teleportExits) {
        this.teleportEntries = teleportEntries;
        this.teleportExits = teleportExits;
    }

    public static TransportMarkerIndex fromResources() {
        Map<Integer, Set<Transport>> transportsByOrigin = TransportLoader.loadAllFromResources();
        Set<Integer> entries = new HashSet<>();
        Set<Integer> exits = new HashSet<>();
        for (Set<Transport> transports : transportsByOrigin.values()) {
            for (Transport transport : transports) {
                TransportType type = transport.getType();
                if (type == null || !type.isTeleport()) {
                    continue;
                }
                if (transport.getOrigin() != Transport.UNDEFINED_ORIGIN) {
                    entries.add(transport.getOrigin());
                }
                if (transport.getDestination() != Transport.UNDEFINED_DESTINATION) {
                    exits.add(transport.getDestination());
                }
            }
        }
        return new TransportMarkerIndex(entries, exits);
    }

    public boolean isTeleportEntry(int packedPoint) {
        return teleportEntries.contains(packedPoint);
    }

    public boolean isTeleportExit(int packedPoint) {
        return teleportExits.contains(packedPoint);
    }
}
