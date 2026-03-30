package shortestpath.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import shortestpath.Util;
import shortestpath.WorldPointUtil;

@Slf4j
public class TransportLoader {
    private static final List<TransportResourceSpec> RESOURCE_SPECS = Arrays.asList(
        new TransportResourceSpec("/transports/transports.tsv", TransportType.TRANSPORT, 0),
        new TransportResourceSpec("/transports/agility_shortcuts.tsv", TransportType.AGILITY_SHORTCUT, 0),
        new TransportResourceSpec("/transports/boats.tsv", TransportType.BOAT, 0),
        new TransportResourceSpec("/transports/canoes.tsv", TransportType.CANOE, 0),
        new TransportResourceSpec("/transports/charter_ships.tsv", TransportType.CHARTER_SHIP, 0),
        new TransportResourceSpec("/transports/ships.tsv", TransportType.SHIP, 0),
        new TransportResourceSpec("/transports/fairy_rings.tsv", TransportType.FAIRY_RING, 0),
        new TransportResourceSpec("/transports/gnome_gliders.tsv", TransportType.GNOME_GLIDER, 6),
        new TransportResourceSpec("/transports/hot_air_balloons.tsv", TransportType.HOT_AIR_BALLOON, 7),
        new TransportResourceSpec("/transports/magic_carpets.tsv", TransportType.MAGIC_CARPET, 0),
        new TransportResourceSpec("/transports/magic_mushtrees.tsv", TransportType.MAGIC_MUSHTREE, 5),
        new TransportResourceSpec("/transports/minecarts.tsv", TransportType.MINECART, 0),
        new TransportResourceSpec("/transports/quetzals.tsv", TransportType.QUETZAL, 0),
        new TransportResourceSpec("/transports/seasonal_transports.tsv", TransportType.SEASONAL_TRANSPORTS, 0),
        new TransportResourceSpec("/transports/spirit_trees.tsv", TransportType.SPIRIT_TREE, 5),
        new TransportResourceSpec("/transports/teleportation_items.tsv", TransportType.TELEPORTATION_ITEM, 0),
        new TransportResourceSpec("/transports/teleportation_boxes.tsv", TransportType.TELEPORTATION_BOX, 0),
        new TransportResourceSpec("/transports/teleportation_levers.tsv", TransportType.TELEPORTATION_LEVER, 0),
        new TransportResourceSpec("/transports/teleportation_minigames.tsv", TransportType.TELEPORTATION_MINIGAME, 0),
        new TransportResourceSpec("/transports/teleportation_portals.tsv", TransportType.TELEPORTATION_PORTAL, 0),
        new TransportResourceSpec("/transports/teleportation_portals_poh.tsv", TransportType.TELEPORTATION_PORTAL_POH, 0),
        new TransportResourceSpec("/transports/teleportation_spells.tsv", TransportType.TELEPORTATION_SPELL, 0),
        new TransportResourceSpec("/transports/wilderness_obelisks.tsv", TransportType.WILDERNESS_OBELISK, 0)
    );

    private static void addTransports(Map<Integer, Set<Transport>> transports, String path, TransportType transportType) {
        addTransports(transports, path, transportType, 0);
    }

    private static void addTransports(Map<Integer, Set<Transport>> transports, String path, TransportType transportType, int radiusThreshold) {
        try {
            String s = new String(Util.readAllBytes(TransportLoader.class.getResourceAsStream(path)), StandardCharsets.UTF_8);
            addTransportsFromContents(transports, s, transportType, radiusThreshold);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void addTransportsFromContents(Map<Integer, Set<Transport>> transports, String contents, TransportType transportType, int radiusThreshold) {
        Set<Transport> newTransports = new HashSet<>();
        new TransportTsvParser().parse(contents, (lineNumber, fieldMap) -> {
            Transport transport = new Transport(fieldMap, transportType);
            newTransports.add(transport);
        });

        /*
        * A transport with origin A and destination B is one-way and must
        * be duplicated as origin B and destination A to become two-way.
        * Example: key-locked doors
        * 
        * A transport with origin A and a missing destination is one-way,
        * but can go from origin A to all destinations with a missing origin.
        * Example: fairy ring AIQ -> <blank>
        * 
        * A transport with a missing origin and destination B is one-way,
        * but can go from all origins with a missing destination to destination B.
        * Example: fairy ring <blank> -> AIQ
        * 
        * Identical transports from origin A to destination A are skipped, and
        * non-identical transports from origin A to destination A can be skipped
        * by specifying a radius threshold to ignore almost identical coordinates.
        * Example: fairy ring AIQ -> AIQ
        */
        Set<Transport> transportOrigins = new HashSet<>();
        Set<Transport> transportDestinations = new HashSet<>();
        for (Transport transport : newTransports) {
            int origin = transport.getOrigin();
            int destination = transport.getDestination();
            // Logic to determine ordinary transport vs teleport vs permutation (e.g. fairy ring)
            if (
                ( origin == Transport.UNDEFINED_ORIGIN && destination == Transport.UNDEFINED_DESTINATION)
                || (origin == Transport.LOCATION_PERMUTATION && destination == Transport.LOCATION_PERMUTATION)) {
                continue;
            } else if (origin != Transport.LOCATION_PERMUTATION && origin != Transport.UNDEFINED_ORIGIN
                && destination == Transport.LOCATION_PERMUTATION) {
                transportOrigins.add(transport);
            } else if (origin == Transport.LOCATION_PERMUTATION
                && destination != Transport.LOCATION_PERMUTATION && destination != Transport.UNDEFINED_DESTINATION) {
                transportDestinations.add(transport);
            }
            if (origin != Transport.LOCATION_PERMUTATION
                && destination != Transport.UNDEFINED_DESTINATION && destination != Transport.LOCATION_PERMUTATION
                && (origin == Transport.UNDEFINED_ORIGIN || origin != destination)) {
                transports.computeIfAbsent(origin, k -> new HashSet<>()).add(transport);
            }
        }
        for (Transport origin : transportOrigins) {
            for (Transport destination : transportDestinations) {
                // The radius threshold prevents transport permutations from including (almost) same origin and destination
                if (WorldPointUtil.distanceBetween2D(origin.getOrigin(), destination.getDestination()) > radiusThreshold) {
                    transports
                        .computeIfAbsent(origin.getOrigin(), k -> new HashSet<>())
                        .add(new Transport(origin, destination));
                }
            }
        }
    }

    static List<TransportResourceSpec> resourceSpecs() {
        return RESOURCE_SPECS;
    }

    public static HashMap<Integer, Set<Transport>> loadAllFromResources() {
        HashMap<Integer, Set<Transport>> transports = new HashMap<>();
        for (TransportResourceSpec spec : RESOURCE_SPECS) {
            addTransports(transports, spec.getPath(), spec.getTransportType(), spec.getRadiusThreshold());
        }
        return transports;
    }
}
