package shortestpath;

import lombok.RequiredArgsConstructor;

/**
 * The debug views the plugin can draw, picked together in the "Debug layers" setting.
 */
@RequiredArgsConstructor
public enum DebugLayer
{
	PANEL("Stats panel"),
	TARGETS("Targets and goals"),
	LOCATIONS("Location list (minimap)"),
	TELEPORTS("Teleport destinations"),
	TRANSPORTS("Transports"),
	BANK_APPROACHES("Bank approach tiles"),
	COLLISION_MAP("Collision map"),
	CLICK_POINTS("Click points");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
