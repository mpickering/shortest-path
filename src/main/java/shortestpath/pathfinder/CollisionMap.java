package shortestpath.pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;


public class CollisionMap {
    // Enum.values() makes copies every time which hurts performance in the hotpath
    private static final OrdinalDirection[] ORDINAL_VALUES = OrdinalDirection.values();

    private final SplitFlagMap collisionData;

    public byte[] getPlanes() {
        return collisionData.getRegionMapPlaneCounts();
    }

    public CollisionMap(SplitFlagMap collisionData) {
        this.collisionData = collisionData;
    }

    private boolean get(int x, int y, int z, int flag) {
        return collisionData.get(x, y, z, flag);
    }

    public boolean n(int x, int y, int z) {
        return get(x, y, z, 0);
    }

    public boolean s(int x, int y, int z) {
        return n(x, y - 1, z);
    }

    public boolean e(int x, int y, int z) {
        return get(x, y, z, 1);
    }

    public boolean w(int x, int y, int z) {
        return e(x - 1, y, z);
    }

    private boolean ne(int x, int y, int z) {
        return n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z);
    }

    private boolean nw(int x, int y, int z) {
        return n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z);
    }

    private boolean se(int x, int y, int z) {
        return s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z);
    }

    private boolean sw(int x, int y, int z) {
        return s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z);
    }

    public boolean isBlocked(int x, int y, int z) {
        return !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z);
    }

    private static int packedPointFromOrdinal(int startPacked, OrdinalDirection direction) {
        final int x = WorldPointUtil.unpackWorldX(startPacked);
        final int y = WorldPointUtil.unpackWorldY(startPacked);
        final int plane = WorldPointUtil.unpackWorldPlane(startPacked);
        return WorldPointUtil.packWorldPoint(x + direction.x, y + direction.y, plane);
    }

    // This is only safe if pathfinding is single-threaded
    private final List<Node> neighbors = new ArrayList<>(16);
    private final boolean[] traversable = new boolean[8];
    private final NeighborStats stats = new NeighborStats();

    public NeighborStats getStats() {
        return stats;
    }

    public void resetStats() {
        stats.reset();
    }

    public List<Node> getNeighbors(Node node, VisitedTiles visited, PathfinderConfig config, int wildernessLevel,
        boolean targetInWilderness) {
        if (node.isTile()) {
            return getTileNeighbors(node, visited, config, wildernessLevel);
        } else {
            return getAbstractNodeNeighbors(node, visited, config, targetInWilderness);
        }
    }

    // Get neighbours for a walkable tile: 
    //      * Neighbouring tiles we can walk to
    //      * A transition into banked state, if the current tile is a bank.
    //      * Transition into abstract global teleport nodes, if we haven't tried that yet.
    private List<Node> getTileNeighbors(Node node, VisitedTiles visited, PathfinderConfig config, int wildernessLevel) {
        stats.tileNeighborCalls++;
        final int x = WorldPointUtil.unpackWorldX(node.packedPosition);
        final int y = WorldPointUtil.unpackWorldY(node.packedPosition);
        final int z = WorldPointUtil.unpackWorldPlane(node.packedPosition);

        neighbors.clear();

        // Either we have already visited a bank, if the current tile is a bank switch into the bankVisited state for the
        // rest of the path.
        boolean pathBankVisited = node.bankVisited
            || (config.isBankPathEnabled() && config.getDestinations("bank").contains(node.packedPosition));

        // Firstly check if there are any transports or teleports which are applicable from the current tile.
        long tileTransportLookupStartNanos = System.nanoTime();
        Set<Transport> transports = config.getTransportsPacked(pathBankVisited).getOrDefault(node.packedPosition, Set.of());
        stats.tileTransportLookupCount++;
        stats.tileTransportLookupNanos += System.nanoTime() - tileTransportLookupStartNanos;
        for (Transport transport : transports) {
            stats.tileTransportCandidates++;
            if (!TransportUsageMask.canUse(node.remainingTransportMask, transport.getType())) {
                stats.tileTransportMaskRejected++;
                continue;
            }
            int nextTransportMask = TransportUsageMask.consume(node.remainingTransportMask, transport.getType());
            // Do not consider a transport if we have already visited its target tile.
            if (visited.get(transport.getDestination(), pathBankVisited, nextTransportMask)) {
                stats.tileTransportVisitedRejected++;
                continue;
            }
            // NB: Do not need to check for wilderness level for transports, since transports have specific origin tile.
            stats.tileTransportAccepted++;
            neighbors.add(new TransportNode(
                transport.getDestination(),
                node,
                transport.getDuration(),
                config.getAdditionalTransportCost(transport),
                pathBankVisited,
                nextTransportMask));
        }

        // Global teleports are considered once per abstract search state. That includes
        // the initial state and any later bank-state or wilderness-bucket transition.
        Node globalTeleports = Node.abstractNode(AbstractNodeKind.fromWildernessLevel(wildernessLevel), node, pathBankVisited);
        stats.globalAbstractNodeChecks++;
        if (!visited.get(globalTeleports)) {
            stats.globalAbstractNodeAccepted++;
            neighbors.add(globalTeleports);
        }

        // Then add tiles which we can walk to, which go into the FIFO boundary queue.
        long traversableStartNanos = System.nanoTime();
        if (isBlocked(x, y, z)) {
            stats.blockedTileNeighborCalls++;
            boolean westBlocked = isBlocked(x - 1, y, z);
            boolean eastBlocked = isBlocked(x + 1, y, z);
            boolean southBlocked = isBlocked(x, y - 1, z);
            boolean northBlocked = isBlocked(x, y + 1, z);
            boolean southWestBlocked = isBlocked(x - 1, y - 1, z);
            boolean southEastBlocked = isBlocked(x + 1, y - 1, z);
            boolean northWestBlocked = isBlocked(x - 1, y + 1, z);
            boolean northEastBlocked = isBlocked(x + 1, y + 1, z);
            traversable[0] = !westBlocked;
            traversable[1] = !eastBlocked;
            traversable[2] = !southBlocked;
            traversable[3] = !northBlocked;
            traversable[4] = !southWestBlocked && !westBlocked && !southBlocked;
            traversable[5] = !southEastBlocked && !eastBlocked && !southBlocked;
            traversable[6] = !northWestBlocked && !westBlocked && !northBlocked;
            traversable[7] = !northEastBlocked && !eastBlocked && !northBlocked;
        } else {
            stats.unblockedTileNeighborCalls++;
            traversable[0] = w(x, y, z);
            traversable[1] = e(x, y, z);
            traversable[2] = s(x, y, z);
            traversable[3] = n(x, y, z);
            traversable[4] = sw(x, y, z);
            traversable[5] = se(x, y, z);
            traversable[6] = nw(x, y, z);
            traversable[7] = ne(x, y, z);
        }
        stats.traversableComputationNanos += System.nanoTime() - traversableStartNanos;

        long walkNeighborLoopStartNanos = System.nanoTime();
        for (int i = 0; i < traversable.length; i++) {
            OrdinalDirection d = ORDINAL_VALUES[i];
            int neighborPacked = packedPointFromOrdinal(node.packedPosition, d);
            stats.walkNeighborCandidateChecks++;
            if (visited.get(neighborPacked, pathBankVisited, node.remainingTransportMask)) {
                stats.walkNeighborVisitedRejected++;
                continue;
            }

            if (traversable[i]) {
                stats.walkNeighborAccepted++;
                neighbors.add(new Node(
                    neighborPacked,
                    node,
                    Node.cost(neighborPacked, node),
                    pathBankVisited,
                    node.remainingTransportMask));
            } else if (Math.abs(d.x + d.y) == 1 && isBlocked(x + d.x, y + d.y, z)) {
                stats.blockedAdjacentTransportDirectionChecks++;
                long blockedAdjacentLookupStartNanos = System.nanoTime();
                Set<Transport> neighborTransports = config.getTransportsPacked(pathBankVisited).getOrDefault(neighborPacked, Set.of());
                stats.blockedAdjacentTransportLookupCount++;
                stats.blockedAdjacentTransportLookupNanos += System.nanoTime() - blockedAdjacentLookupStartNanos;
                for (Transport transport : neighborTransports) {
                    stats.blockedAdjacentTransportCandidates++;
                    if (transport.getOrigin() == Transport.UNDEFINED_ORIGIN
                        || !(transport.isUsableAtWildernessLevel(wildernessLevel))
                        || visited.get(transport.getOrigin(), pathBankVisited, node.remainingTransportMask)) {
                        stats.blockedAdjacentTransportRejected++;
                        continue;
                    }
                    stats.blockedAdjacentTransportAccepted++;
                    neighbors.add(new Node(
                        transport.getOrigin(),
                        node,
                        Node.cost(transport.getOrigin(), node),
                        pathBankVisited,
                        node.remainingTransportMask));
                }
            }
        }
        stats.walkNeighborLoopNanos += System.nanoTime() - walkNeighborLoopStartNanos;

        return neighbors;
    }

    // The only abstract nodes are currently for global teleports
    private List<Node> getAbstractNodeNeighbors(Node node, VisitedTiles visited, PathfinderConfig config,
        boolean targetInWilderness) {
        stats.abstractNeighborCalls++;
        neighbors.clear();
        int sourceTile = node.getClosestTilePosition();
        long usableTeleportsLookupStartNanos = System.nanoTime();
        Set<Transport> usableTeleports = config.getUsableTeleports(node.bankVisited);
        stats.abstractTeleportLookupCount++;
        stats.abstractTeleportLookupNanos += System.nanoTime() - usableTeleportsLookupStartNanos;
        for (Transport transport : usableTeleports) {
            stats.abstractTeleportCandidates++;
            if (visited.get(transport.getDestination(), node.bankVisited, node.remainingTransportMask)) {
                stats.abstractTeleportVisitedRejected++;
                continue;
            }
            if (!transport.isUsableAtWildernessLevel(node.abstractKind.maxWildernessLevel())) {
                stats.abstractTeleportWildernessRejected++;
                continue;
            }
            if (config.avoidWilderness(sourceTile, transport.getDestination(), targetInWilderness)) {
                stats.abstractTeleportAvoidWildernessRejected++;
                continue;
            }
            stats.abstractTeleportAccepted++;
            neighbors.add(new TransportNode(
                transport.getDestination(),
                node,
                transport.getDuration(),
                config.getAdditionalTransportCost(transport),
                node.bankVisited,
                node.remainingTransportMask));
        }
        return neighbors;
    }

    public static final class NeighborStats {
        private long tileNeighborCalls;
        private long abstractNeighborCalls;
        private long blockedTileNeighborCalls;
        private long unblockedTileNeighborCalls;
        private long traversableComputationNanos;
        private long walkNeighborLoopNanos;
        private long walkNeighborCandidateChecks;
        private long walkNeighborVisitedRejected;
        private long walkNeighborAccepted;
        private long tileTransportLookupCount;
        private long tileTransportLookupNanos;
        private long tileTransportCandidates;
        private long tileTransportMaskRejected;
        private long tileTransportVisitedRejected;
        private long tileTransportAccepted;
        private long globalAbstractNodeChecks;
        private long globalAbstractNodeAccepted;
        private long blockedAdjacentTransportDirectionChecks;
        private long blockedAdjacentTransportLookupCount;
        private long blockedAdjacentTransportLookupNanos;
        private long blockedAdjacentTransportCandidates;
        private long blockedAdjacentTransportRejected;
        private long blockedAdjacentTransportAccepted;
        private long abstractTeleportLookupCount;
        private long abstractTeleportLookupNanos;
        private long abstractTeleportCandidates;
        private long abstractTeleportVisitedRejected;
        private long abstractTeleportWildernessRejected;
        private long abstractTeleportAvoidWildernessRejected;
        private long abstractTeleportAccepted;

        public void reset() {
            tileNeighborCalls = 0;
            abstractNeighborCalls = 0;
            blockedTileNeighborCalls = 0;
            unblockedTileNeighborCalls = 0;
            traversableComputationNanos = 0;
            walkNeighborLoopNanos = 0;
            walkNeighborCandidateChecks = 0;
            walkNeighborVisitedRejected = 0;
            walkNeighborAccepted = 0;
            tileTransportLookupCount = 0;
            tileTransportLookupNanos = 0;
            tileTransportCandidates = 0;
            tileTransportMaskRejected = 0;
            tileTransportVisitedRejected = 0;
            tileTransportAccepted = 0;
            globalAbstractNodeChecks = 0;
            globalAbstractNodeAccepted = 0;
            blockedAdjacentTransportDirectionChecks = 0;
            blockedAdjacentTransportLookupCount = 0;
            blockedAdjacentTransportLookupNanos = 0;
            blockedAdjacentTransportCandidates = 0;
            blockedAdjacentTransportRejected = 0;
            blockedAdjacentTransportAccepted = 0;
            abstractTeleportLookupCount = 0;
            abstractTeleportLookupNanos = 0;
            abstractTeleportCandidates = 0;
            abstractTeleportVisitedRejected = 0;
            abstractTeleportWildernessRejected = 0;
            abstractTeleportAvoidWildernessRejected = 0;
            abstractTeleportAccepted = 0;
        }

        public long getTileNeighborCalls() { return tileNeighborCalls; }
        public long getAbstractNeighborCalls() { return abstractNeighborCalls; }
        public long getBlockedTileNeighborCalls() { return blockedTileNeighborCalls; }
        public long getUnblockedTileNeighborCalls() { return unblockedTileNeighborCalls; }
        public long getTraversableComputationNanos() { return traversableComputationNanos; }
        public long getWalkNeighborLoopNanos() { return walkNeighborLoopNanos; }
        public long getWalkNeighborCandidateChecks() { return walkNeighborCandidateChecks; }
        public long getWalkNeighborVisitedRejected() { return walkNeighborVisitedRejected; }
        public long getWalkNeighborAccepted() { return walkNeighborAccepted; }
        public long getTileTransportLookupCount() { return tileTransportLookupCount; }
        public long getTileTransportLookupNanos() { return tileTransportLookupNanos; }
        public long getTileTransportCandidates() { return tileTransportCandidates; }
        public long getTileTransportMaskRejected() { return tileTransportMaskRejected; }
        public long getTileTransportVisitedRejected() { return tileTransportVisitedRejected; }
        public long getTileTransportAccepted() { return tileTransportAccepted; }
        public long getGlobalAbstractNodeChecks() { return globalAbstractNodeChecks; }
        public long getGlobalAbstractNodeAccepted() { return globalAbstractNodeAccepted; }
        public long getBlockedAdjacentTransportDirectionChecks() { return blockedAdjacentTransportDirectionChecks; }
        public long getBlockedAdjacentTransportLookupCount() { return blockedAdjacentTransportLookupCount; }
        public long getBlockedAdjacentTransportLookupNanos() { return blockedAdjacentTransportLookupNanos; }
        public long getBlockedAdjacentTransportCandidates() { return blockedAdjacentTransportCandidates; }
        public long getBlockedAdjacentTransportRejected() { return blockedAdjacentTransportRejected; }
        public long getBlockedAdjacentTransportAccepted() { return blockedAdjacentTransportAccepted; }
        public long getAbstractTeleportLookupCount() { return abstractTeleportLookupCount; }
        public long getAbstractTeleportLookupNanos() { return abstractTeleportLookupNanos; }
        public long getAbstractTeleportCandidates() { return abstractTeleportCandidates; }
        public long getAbstractTeleportVisitedRejected() { return abstractTeleportVisitedRejected; }
        public long getAbstractTeleportWildernessRejected() { return abstractTeleportWildernessRejected; }
        public long getAbstractTeleportAvoidWildernessRejected() { return abstractTeleportAvoidWildernessRejected; }
        public long getAbstractTeleportAccepted() { return abstractTeleportAccepted; }
    }
}
