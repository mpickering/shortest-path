package shortestpath;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.transport.Transport;

public final class TransportSnapshotWriter {
    private static final String HEADER = String.join("\t",
        "state",
        "origin_x",
        "origin_y",
        "origin_plane",
        "destination_x",
        "destination_y",
        "destination_plane",
        "type",
        "duration",
        "display_info",
        "object_info",
        "consumable",
        "max_wilderness_level");

    private TransportSnapshotWriter() {
    }

    public static Path defaultOutputPath() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.ZonedDateTime.now());
        return Path.of(System.getProperty("user.home"),
            ".runelite",
            "shortestpath",
            "transport-snapshots",
            "transport-snapshot-" + timestamp + ".tsv");
    }

    public static void writeSnapshot(Path output, PathfinderConfig config, int currentLocation) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("# shortest-path transport snapshot v1\n");
            writer.write("# generated_at\t" + Instant.now() + "\n");
            writer.write("# current_location\t" + formatPoint(currentLocation) + "\n");
            writer.write(HEADER);
            writer.write('\n');
            writeState(writer, "WITHOUT_BANK", config, false);
            writeState(writer, "WITH_BANK", config, true);
        }
    }

    private static void writeState(Writer writer, String state, PathfinderConfig config, boolean bankVisited) throws IOException {
        Set<TransportKey> written = new HashSet<>();
        for (int origin : config.getTransportsPacked(bankVisited).keys()) {
            for (Transport transport : config.getTransportsPacked(bankVisited).getOrDefault(origin, Set.of())) {
                writeTransport(writer, state, transport, written);
            }
        }
        for (Transport transport : config.getUsableTeleports(bankVisited)) {
            writeTransport(writer, state, transport, written);
        }
    }

    private static void writeTransport(Writer writer, String state, Transport transport, Set<TransportKey> written) throws IOException {
        TransportKey key = new TransportKey(transport);
        if (!written.add(key)) {
            return;
        }

        writer.write(state);
        writer.write('\t');
        writePoint(writer, transport.getOrigin());
        writer.write('\t');
        writePoint(writer, transport.getDestination());
        writer.write('\t');
        writer.write(transport.getType() != null ? transport.getType().name() : "");
        writer.write('\t');
        writer.write(Integer.toString(transport.getDuration()));
        writer.write('\t');
        writer.write(escape(transport.getDisplayInfo()));
        writer.write('\t');
        writer.write(escape(transport.getObjectInfo()));
        writer.write('\t');
        writer.write(Boolean.toString(transport.isConsumable()));
        writer.write('\t');
        writer.write(Integer.toString(transport.getMaxWildernessLevel()));
        writer.write('\n');
    }

    private static void writePoint(Writer writer, int packedPoint) throws IOException {
        if (packedPoint == WorldPointUtil.UNDEFINED) {
            writer.write("\t\t");
            return;
        }
        writer.write(Integer.toString(WorldPointUtil.unpackWorldX(packedPoint)));
        writer.write('\t');
        writer.write(Integer.toString(WorldPointUtil.unpackWorldY(packedPoint)));
        writer.write('\t');
        writer.write(Integer.toString(WorldPointUtil.unpackWorldPlane(packedPoint)));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').trim();
    }

    private static String formatPoint(int packedPoint) {
        if (packedPoint == WorldPointUtil.UNDEFINED) {
            return "undefined";
        }
        return WorldPointUtil.unpackWorldX(packedPoint)
            + "," + WorldPointUtil.unpackWorldY(packedPoint)
            + "," + WorldPointUtil.unpackWorldPlane(packedPoint);
    }

    private static final class TransportKey {
        private final int origin;
        private final int destination;
        private final String type;
        private final String displayInfo;
        private final String objectInfo;

        private TransportKey(Transport transport) {
            this.origin = transport.getOrigin();
            this.destination = transport.getDestination();
            this.type = transport.getType() != null ? transport.getType().name() : "";
            this.displayInfo = transport.getDisplayInfo();
            this.objectInfo = transport.getObjectInfo();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TransportKey)) {
                return false;
            }
            TransportKey other = (TransportKey) obj;
            return origin == other.origin
                && destination == other.destination
                && java.util.Objects.equals(type, other.type)
                && java.util.Objects.equals(displayInfo, other.displayInfo)
                && java.util.Objects.equals(objectInfo, other.objectInfo);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(origin, destination, type, displayInfo, objectInfo);
        }
    }
}
