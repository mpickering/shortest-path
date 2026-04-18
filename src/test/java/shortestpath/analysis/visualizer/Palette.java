package shortestpath.analysis.visualizer;

import java.awt.Color;

public class Palette {
    private final Color walkable = new Color(235, 239, 242);
    private final Color blocked = new Color(42, 48, 56);
    private final Color missing = new Color(130, 0, 24);
    private final Color undefined = new Color(86, 59, 115);
    private final Color start = new Color(17, 138, 178);
    private final Color goal = new Color(239, 71, 111);
    private final Color teleportEntry = new Color(0, 168, 150);
    private final Color teleportExit = new Color(255, 159, 28);
    private final Color bank = new Color(255, 209, 102);
    private final Color visited = new Color(0, 109, 119);
    private final Color visitedWithBank = new Color(0, 78, 96);
    private final Color frontier = new Color(153, 102, 255);
    private final Color path = new Color(255, 255, 255);
    private final Color consistentSlack = new Color(239, 239, 239);
    private final Color inconsistentSlack = new Color(214, 40, 40);
    private final Color analysisWall = new Color(255, 0, 255);

    public int walkableRgb() {
        return walkable.getRGB();
    }

    public int blockedRgb() {
        return blocked.getRGB();
    }

    public int missingRgb() {
        return missing.getRGB();
    }

    public int undefinedRgb() {
        return undefined.getRGB();
    }

    public int startRgb() {
        return start.getRGB();
    }

    public int goalRgb() {
        return goal.getRGB();
    }

    public int teleportEntryRgb() {
        return teleportEntry.getRGB();
    }

    public int teleportExitRgb() {
        return teleportExit.getRGB();
    }

    public int bankRgb() {
        return bank.getRGB();
    }

    public int visitedRgb() {
        return visited.getRGB();
    }

    public int visitedWithBankRgb() {
        return visitedWithBank.getRGB();
    }

    public int frontierRgb() {
        return frontier.getRGB();
    }

    public int pathRgb() {
        return path.getRGB();
    }

    public int analysisWallRgb() {
        return analysisWall.getRGB();
    }

    public int baseValueRgb(double scaled) {
        scaled = Math.max(0.0d, Math.min(1.0d, scaled));
        int red = (int) Math.round(255.0d * scaled);
        int green = (int) Math.round(48.0d + 180.0d * (1.0d - Math.abs(0.5d - scaled) * 2.0d));
        int blue = (int) Math.round(255.0d * (1.0d - scaled));
        return new Color(red, green, blue).getRGB();
    }

    public int componentRgb(int componentId) {
        float hue = (float) ((componentId * 0.6180339887498949d) % 1.0d);
        return Color.getHSBColor(hue, 0.65f, 0.95f).getRGB();
    }

    public int slackRgb(double normalizedPositiveSlack) {
        if (normalizedPositiveSlack <= 0.0d) {
            return consistentSlack.getRGB();
        }
        double clamped = Math.max(0.0d, Math.min(1.0d, normalizedPositiveSlack));
        int red = blend(consistentSlack.getRed(), inconsistentSlack.getRed(), clamped);
        int green = blend(consistentSlack.getGreen(), inconsistentSlack.getGreen(), clamped);
        int blue = blend(consistentSlack.getBlue(), inconsistentSlack.getBlue(), clamped);
        return new Color(red, green, blue).getRGB();
    }

    private static int blend(int from, int to, double amount) {
        return (int) Math.round(from + (to - from) * amount);
    }
}
