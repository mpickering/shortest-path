package shortestpath.pathfinder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;

public final class TransportSnapshotLoader {
    private TransportSnapshotLoader() {
    }

    public static Snapshot load(Path path) throws IOException {
        TransportAvailability.Builder withoutBank = new TransportAvailability.Builder(2048);
        TransportAvailability.Builder withBank = new TransportAvailability.Builder(2048);

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            if (columns.length < 13 || "state".equals(columns[0])) {
                continue;
            }

            Transport transport = new Transport.TransportBuilder()
                .origin(parsePoint(columns[1], columns[2], columns[3]))
                .destination(parsePoint(columns[4], columns[5], columns[6]))
                .type(parseTransportType(columns[7]))
                .duration(parseInt(columns[8], 0))
                .displayInfo(emptyToNull(columns[9]))
                .objectInfo(emptyToNull(columns[10]))
                .isConsumable(Boolean.parseBoolean(columns[11]))
                .maxWildernessLevel(parseInt(columns[12], -1))
                .build();

            if ("WITHOUT_BANK".equals(columns[0])) {
                withoutBank.add(transport);
            } else if ("WITH_BANK".equals(columns[0])) {
                withBank.add(transport);
            }
        }

        withoutBank.remapPohTransports();
        withBank.remapPohTransports();
        return new Snapshot(withoutBank.build(), withBank.build());
    }

    private static int parsePoint(String x, String y, String plane) {
        if (x.isBlank() || y.isBlank() || plane.isBlank()) {
            return WorldPointUtil.UNDEFINED;
        }
        return WorldPointUtil.packWorldPoint(
            Integer.parseInt(x.trim()),
            Integer.parseInt(y.trim()),
            Integer.parseInt(plane.trim()));
    }

    private static TransportType parseTransportType(String value) {
        return value == null || value.isBlank() ? null : TransportType.valueOf(value.trim());
    }

    private static int parseInt(String value, int defaultValue) {
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static final class Snapshot {
        private final TransportAvailability withoutBank;
        private final TransportAvailability withBank;

        private Snapshot(TransportAvailability withoutBank, TransportAvailability withBank) {
            this.withoutBank = withoutBank;
            this.withBank = withBank;
        }

        public TransportAvailability getWithoutBank() {
            return withoutBank;
        }

        public TransportAvailability getWithBank() {
            return withBank;
        }
    }
}
