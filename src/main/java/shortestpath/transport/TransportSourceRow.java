package shortestpath.transport;

import java.util.Map;
import lombok.Getter;

@Getter
class TransportSourceRow {
    private final String sourcePath;
    private final int lineNumber;
    private final Map<String, String> fieldMap;
    private final TransportType transportType;
    private final Transport transport;

    TransportSourceRow(String sourcePath, int lineNumber, Map<String, String> fieldMap, TransportType transportType) {
        this.sourcePath = sourcePath;
        this.lineNumber = lineNumber;
        this.fieldMap = fieldMap;
        this.transportType = transportType;
        this.transport = new Transport(fieldMap, transportType);
    }

    Integer getConcreteOrigin() {
        return concreteCoordinate(transport.getOrigin(), Transport.UNDEFINED_ORIGIN);
    }

    Integer getConcreteDestination() {
        return concreteCoordinate(transport.getDestination(), Transport.UNDEFINED_DESTINATION);
    }

    String getSourceReference() {
        return sourcePath + ":" + lineNumber;
    }

    private Integer concreteCoordinate(int coordinate, int undefinedValue) {
        if (coordinate == undefinedValue || coordinate == Transport.LOCATION_PERMUTATION) {
            return null;
        }
        return coordinate;
    }
}
