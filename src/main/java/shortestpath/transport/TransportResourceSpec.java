package shortestpath.transport;

import lombok.Value;

@Value
class TransportResourceSpec {
    String path;
    TransportType transportType;
    int radiusThreshold;
}
