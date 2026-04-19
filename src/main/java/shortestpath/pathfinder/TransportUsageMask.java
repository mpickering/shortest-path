package shortestpath.pathfinder;

import shortestpath.transport.TransportType;

public final class TransportUsageMask {
    public static final int FAIRY_RING = 1 << 0;
    public static final int SPIRIT_TREE = 1 << 1;
    public static final int ALL_AVAILABLE = FAIRY_RING | SPIRIT_TREE;
    public static final int MASK_VARIANTS = ALL_AVAILABLE + 1;

    private TransportUsageMask() {
    }

    public static int consume(int remainingMask, TransportType transportType) {
        if (TransportType.FAIRY_RING.equals(transportType)) {
            return remainingMask & ~FAIRY_RING;
        }
        if (TransportType.SPIRIT_TREE.equals(transportType)) {
            return remainingMask & ~SPIRIT_TREE;
        }
        return remainingMask;
    }

    public static boolean canUse(int remainingMask, TransportType transportType) {
        if (TransportType.FAIRY_RING.equals(transportType)) {
            return (remainingMask & FAIRY_RING) != 0;
        }
        if (TransportType.SPIRIT_TREE.equals(transportType)) {
            return (remainingMask & SPIRIT_TREE) != 0;
        }
        return true;
    }

    public static boolean consumes(TransportType transportType) {
        return TransportType.FAIRY_RING.equals(transportType)
            || TransportType.SPIRIT_TREE.equals(transportType);
    }
}
