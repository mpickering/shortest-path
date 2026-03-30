package shortestpath.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import shortestpath.Util;
import shortestpath.WorldPointUtil;

public class TransportCoordinateExporter {
    public List<TransportCoordinateItem> exportAllFromResources() {
        LinkedHashMap<String, TransportCoordinateItem> items = new LinkedHashMap<>();
        for (TransportResourceSpec spec : TransportLoader.resourceSpecs()) {
            String resourceContents = readResource(spec.getPath());
            for (TransportSourceRow row : parseRows(spec.getPath().substring(1), resourceContents, spec.getTransportType())) {
                addCoordinate(items, row, row.getConcreteOrigin(), "origin");
                addCoordinate(items, row, row.getConcreteDestination(), "destination");
            }
        }
        return new ArrayList<>(items.values());
    }

    List<TransportCoordinateItem> exportFromContents(String sourcePath, String contents, TransportType transportType) {
        LinkedHashMap<String, TransportCoordinateItem> items = new LinkedHashMap<>();
        for (TransportSourceRow row : parseRows(sourcePath, contents, transportType)) {
            addCoordinate(items, row, row.getConcreteOrigin(), "origin");
            addCoordinate(items, row, row.getConcreteDestination(), "destination");
        }
        return new ArrayList<>(items.values());
    }

    static String toJson(List<TransportCoordinateItem> items, boolean pretty) {
        String indent = pretty ? "  " : "";
        String newline = pretty ? "\n" : "";
        StringBuilder json = new StringBuilder();
        json.append("[").append(newline);
        for (int i = 0; i < items.size(); i++) {
            TransportCoordinateItem item = items.get(i);
            if (pretty) {
                json.append(indent);
            }
            json.append("{");
            if (pretty) {
                json.append(newline).append(indent).append(indent);
            }
            json.append("\"id\":\"").append(escapeJson(item.getId())).append("\",");
            if (pretty) {
                json.append(newline).append(indent).append(indent);
            }
            json.append("\"label\":\"").append(escapeJson(item.getLabel())).append("\",");
            if (pretty) {
                json.append(newline).append(indent).append(indent);
            }
            json.append("\"coordinate\":\"").append(escapeJson(item.getCoordinate())).append("\",");
            if (pretty) {
                json.append(newline).append(indent).append(indent);
            }
            json.append("\"source\":\"").append(escapeJson(item.getSource())).append("\"");
            if (pretty) {
                json.append(newline).append(indent);
            }
            json.append("}");
            if (i < items.size() - 1) {
                json.append(",");
            }
            json.append(newline);
        }
        json.append("]");
        return json.toString();
    }

    static List<TransportSourceRow> parseRows(String sourcePath, String contents, TransportType transportType) {
        List<TransportSourceRow> rows = new ArrayList<>();
        new TransportTsvParser().parse(contents,
            (lineNumber, fieldMap) -> rows.add(new TransportSourceRow(sourcePath, lineNumber, fieldMap, transportType)));
        return rows;
    }

    private static void addCoordinate(Map<String, TransportCoordinateItem> items, TransportSourceRow row, Integer packedCoordinate, String role) {
        if (packedCoordinate == null) {
            return;
        }
        String coordinate = toCoordinateString(packedCoordinate);
        if (items.containsKey(coordinate)) {
            return;
        }
        items.put(coordinate, new TransportCoordinateItem(
            buildId(packedCoordinate),
            buildLabel(row, role),
            coordinate,
            row.getSourceReference()
        ));
    }

    private static String buildId(int packedCoordinate) {
        return "coord-" + WorldPointUtil.unpackWorldX(packedCoordinate)
            + "-" + WorldPointUtil.unpackWorldY(packedCoordinate)
            + "-" + WorldPointUtil.unpackWorldPlane(packedCoordinate);
    }

    private static String buildLabel(TransportSourceRow row, String role) {
        List<String> parts = new ArrayList<>();
        parts.add(toDisplayName(row.getTransportType()));
        parts.add(role);

        String displayInfo = row.getFieldMap().get("Display info");
        String objectInfo = row.getFieldMap().get("menuOption menuTarget objectID");
        LinkedHashSet<String> contextValues = new LinkedHashSet<>();
        if (displayInfo != null && !displayInfo.isBlank()) {
            contextValues.add(displayInfo.trim());
        }
        if (objectInfo != null && !objectInfo.isBlank()) {
            contextValues.add(objectInfo.trim());
        }
        if (!contextValues.isEmpty()) {
            parts.add(String.join(" / ", contextValues));
        }

        return String.join(": ", parts.subList(0, 2))
            + (parts.size() > 2 ? " - " + parts.get(2) : "");
    }

    private static String toDisplayName(TransportType transportType) {
        String lower = transportType.name().toLowerCase();
        String[] words = lower.split("_");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(words[i].charAt(0)));
            builder.append(words[i].substring(1));
        }
        return builder.toString();
    }

    private static String toCoordinateString(int packedCoordinate) {
        return WorldPointUtil.unpackWorldX(packedCoordinate)
            + "/" + WorldPointUtil.unpackWorldY(packedCoordinate)
            + "/" + WorldPointUtil.unpackWorldPlane(packedCoordinate);
    }

    private static String readResource(String path) {
        try {
            return new String(Util.readAllBytes(TransportCoordinateExporter.class.getResourceAsStream(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read transport resource " + path, e);
        }
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
