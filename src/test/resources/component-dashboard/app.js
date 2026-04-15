const reportUrl = new URLSearchParams(window.location.search).get("report") || "report.json";
const tileBaseUrl = "https://maps.runescape.wiki/osrs/versions/2026-03-04_a/tiles/rendered";
const MAP_ID = -1;
const MIN_ZOOM = -4;
const MIN_NATIVE_ZOOM = -2;
const MAX_ZOOM = 4;
const MAX_NATIVE_ZOOM = 3;

const summaryEl = document.getElementById("summary");
const componentListEl = document.getElementById("component-list");
const componentDetailsEl = document.getElementById("component-details");
const componentSearchEl = document.getElementById("component-search");
const entriesToggleEl = document.getElementById("entries-toggle");
const exitsToggleEl = document.getElementById("exits-toggle");
const allBoundsToggleEl = document.getElementById("all-bounds-toggle");
const centerComponentEl = document.getElementById("center-component");

let currentPlane = 0;
let selectedComponent = null;
let allComponents = [];
let markerLayers = [];
let componentMarkerLayers = [];
let selectedOverlayLayers = [];
let boundsOverlayLayers = [];
let currentReport = null;

const WikiTileLayer = L.TileLayer.extend({
  getTileUrl(coords) {
    return `${tileBaseUrl}/${MAP_ID}/${coords.z}/${currentPlane}_${coords.x}_${-(1 + coords.y)}.png`;
  },

  createTile(coords, done) {
    const tile = L.TileLayer.prototype.createTile.call(this, coords, done);
    tile.onerror = () => {};
    return tile;
  }
});

const map = L.map("map", {
  crs: L.CRS.Simple,
  minZoom: MIN_ZOOM,
  maxZoom: MAX_ZOOM,
  zoomSnap: 1,
  center: [3200, 3200],
  zoom: 1,
  maxBounds: [[-1000, -1000], [13800, 13800]],
  maxBoundsViscosity: 0.5
});

map.attributionControl.addAttribution('&copy; <a href="https://runescape.wiki/">RuneScape Wiki</a>');

const baseLayer = new WikiTileLayer("", {
  minZoom: MIN_ZOOM,
  minNativeZoom: MIN_NATIVE_ZOOM,
  maxNativeZoom: MAX_NATIVE_ZOOM,
  maxZoom: MAX_ZOOM,
  noWrap: true
}).addTo(map);

function parsePoint(value) {
  const [x, y, plane] = value.split(",").map(Number);
  return { x, y, plane };
}

function worldToLatLng(point) {
  return [point.y + 0.5, point.x + 0.5];
}

function clearLayers() {
  markerLayers.forEach(layer => map.removeLayer(layer));
  componentMarkerLayers.forEach(layer => map.removeLayer(layer));
  selectedOverlayLayers.forEach(layer => map.removeLayer(layer));
  boundsOverlayLayers.forEach(layer => map.removeLayer(layer));
  markerLayers = [];
  componentMarkerLayers = [];
  selectedOverlayLayers = [];
  boundsOverlayLayers = [];
}

function addMarker(marker, color) {
  const point = parsePoint(marker.point);
  const layer = L.circleMarker(worldToLatLng(point), {
    radius: 6,
    color,
    fillColor: color,
    fillOpacity: 0.9,
    weight: 2
  }).bindTooltip(
    `${marker.direction}: ${marker.point}` +
    `<br>linked component: ${marker.connectedComponentId}` +
    `<br>type: ${marker.transportType}` +
    (marker.label ? `<br>${marker.label}` : "")
  );
  layer.addTo(map);
  markerLayers.push(layer);
}

function addBounds(component, options = {}) {
  const bounds = [
    [component.bounds.minY, component.bounds.minX],
    [component.bounds.maxY + 1, component.bounds.maxX + 1]
  ];
  const rect = L.rectangle(bounds, {
    color: options.color || "#9a3412",
    weight: options.weight ?? 2,
    fillColor: options.fillColor || "#fdba74",
    fillOpacity: options.fillOpacity ?? 0.16
  }).addTo(map);
  if (options.collection) {
    options.collection.push(rect);
  }
  return rect;
}

function addComponentMarker(component) {
  const point = parsePoint(component.samplePoint);
  if (point.plane !== currentPlane) {
    return;
  }

  const layer = L.marker(worldToLatLng(point), {
    keyboard: false,
    icon: L.divIcon({
      className: "component-marker",
      iconSize: [12, 12],
      iconAnchor: [6, 6]
    }),
    title: `Component ${component.id}`
  }).bindTooltip(
    `Component ${component.id}` +
    `<br>size: ${component.size}` +
    `<br>sample: ${component.samplePoint}` +
    `<br>in: ${component.incomingEdgeCount}, out: ${component.outgoingEdgeCount}`
  );
  layer.on("click", () => {
    renderComponent(component);
    renderComponentList();
  });
  layer.addTo(map);
  componentMarkerLayers.push(layer);
}

function formatPoint(point) {
  return `(${point.x}, ${point.y}, ${point.plane})`;
}

function buildTransportCountSummary(transportCounts) {
  if (!transportCounts) {
    return [];
  }

  const lines = [
    `Transport total: ${transportCounts.total}`,
    `Split: ${transportCounts.transports} transports, ${transportCounts.teleports} teleports`
  ];

  const byType = Object.entries(transportCounts.byType || {}).sort((a, b) =>
    b[1] - a[1] || a[0].localeCompare(b[0])
  );

  if (byType.length > 0) {
    lines.push("By category:");
    byType.forEach(([type, count]) => {
      lines.push(`- ${type}: ${count}`);
    });
  }

  return lines;
}

function buildDetails(component) {
  const sample = parsePoint(component.samplePoint);
  const lines = [
    `Component: ${component.id}`,
    `Size: ${component.size}`,
    `Sample: ${formatPoint(sample)}`,
    `Bounds: (${component.bounds.minX}, ${component.bounds.minY}, ${component.bounds.minPlane}) -> (${component.bounds.maxX}, ${component.bounds.maxY}, ${component.bounds.maxPlane})`,
    `Incoming edges: ${component.incomingEdgeCount}`,
    `Outgoing edges: ${component.outgoingEdgeCount}`,
    `Entry markers: ${component.entryPoints.length}`,
    `Exit markers: ${component.exitPoints.length}`
  ];

  const transportSummaryLines = buildTransportCountSummary(currentReport?.transportCounts);
  if (transportSummaryLines.length > 0) {
    lines.push("");
    lines.push(...transportSummaryLines);
  }

  if (component.entryPoints.length > 0) {
    lines.push("");
    lines.push("Entries:");
    component.entryPoints.slice(0, 12).forEach(marker => {
      lines.push(`- ${marker.point} from ${marker.connectedComponentId} via ${marker.transportType}${marker.label ? ` (${marker.label})` : ""}`);
    });
  }

  if (component.exitPoints.length > 0) {
    lines.push("");
    lines.push("Exits:");
    component.exitPoints.slice(0, 12).forEach(marker => {
      lines.push(`- ${marker.point} to ${marker.connectedComponentId} via ${marker.transportType}${marker.label ? ` (${marker.label})` : ""}`);
    });
  }

  return lines.join("\n");
}

function componentSearchText(component) {
  return [
    component.id,
    component.size,
    component.samplePoint,
    component.entryPoints.map(marker => `${marker.point} ${marker.transportType} ${marker.label || ""}`).join(" "),
    component.exitPoints.map(marker => `${marker.point} ${marker.transportType} ${marker.label || ""}`).join(" ")
  ].join(" ").toLowerCase();
}

function filteredComponents() {
  const query = componentSearchEl.value.trim().toLowerCase();
  const connectedComponents = allComponents.filter(component =>
    component.incomingEdgeCount > 0 || component.outgoingEdgeCount > 0
  );
  if (!query) {
    return connectedComponents;
  }
  return connectedComponents.filter(component => componentSearchText(component).includes(query));
}

function visibleComponentsOnPlane() {
  return filteredComponents().filter(component => parsePoint(component.samplePoint).plane === currentPlane);
}

function renderComponentList() {
  const components = filteredComponents();
  componentListEl.innerHTML = "";

  if (components.length === 0) {
    const item = document.createElement("li");
    item.textContent = "No matching components.";
    componentListEl.appendChild(item);
    return;
  }

  components.forEach(component => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.textContent = `#${component.id} · size ${component.size} · in ${component.incomingEdgeCount} · out ${component.outgoingEdgeCount}`;
    if (selectedComponent && selectedComponent.id === component.id) {
      button.classList.add("selected");
    }
    button.addEventListener("click", () => {
      renderComponent(component);
      renderComponentList();
    });
    item.appendChild(button);
    componentListEl.appendChild(item);
  });
}

function renderMarkers(component) {
  if (entriesToggleEl.checked) {
    component.entryPoints.forEach(marker => addMarker(marker, "#2563eb"));
  }
  if (exitsToggleEl.checked) {
    component.exitPoints.forEach(marker => addMarker(marker, "#dc2626"));
  }
}

function renderComponentMarkers() {
  componentMarkerLayers.forEach(layer => map.removeLayer(layer));
  componentMarkerLayers = [];
  visibleComponentsOnPlane().forEach(component => addComponentMarker(component));
}

function renderBoundsOverlays() {
  boundsOverlayLayers.forEach(layer => map.removeLayer(layer));
  boundsOverlayLayers = [];

  if (!allBoundsToggleEl.checked) {
    return;
  }

  visibleComponentsOnPlane().forEach(component => {
    if (selectedComponent && component.id === selectedComponent.id) {
      return;
    }
    addBounds(component, {
      color: "#b45309",
      weight: 1,
      fillColor: "#fdba74",
      fillOpacity: 0.08,
      collection: boundsOverlayLayers
    });
  });
}

function renderComponent(component) {
  selectedComponent = component;
  clearLayers();
  currentPlane = parsePoint(component.samplePoint).plane;
  baseLayer.redraw();

  renderComponentMarkers();
  renderBoundsOverlays();
  const rect = addBounds(component, { collection: selectedOverlayLayers });
  renderMarkers(component);
  componentDetailsEl.textContent = buildDetails(component);
  map.fitBounds(rect.getBounds().pad(0.35));
}

fetch(reportUrl)
  .then(response => {
    if (!response.ok) {
      throw new Error(`Failed to load ${reportUrl}`);
    }
    return response.json();
  })
  .then(report => {
    currentReport = report;
    allComponents = [...(report.components || [])].sort((a, b) => b.size - a.size || a.id - b.id);
    const connectedCount = allComponents.filter(component =>
      component.incomingEdgeCount > 0 || component.outgoingEdgeCount > 0
    ).length;
    const summaryParts = [
      `${connectedCount}/${report.componentCount} previewable components`,
      `${report.walkableTileCount} walkable tiles`,
      `${report.transportEdgeCount} transport edges`
    ];
    if (report.transportCounts) {
      summaryParts.push(
        `${report.transportCounts.total} total links`
        + ` (${report.transportCounts.transports} transport, ${report.transportCounts.teleports} teleport)`
      );
    }
    summaryEl.textContent = summaryParts.join(", ");

    const components = filteredComponents();
    if (components.length === 0) {
      componentDetailsEl.textContent = "No components in report.json";
      return;
    }

    renderComponent(components[0]);
    renderComponentList();
  })
  .catch(error => {
    summaryEl.textContent = error.message;
    componentDetailsEl.textContent = "Unable to render component viewer.";
  });

componentSearchEl.addEventListener("input", () => {
  const components = filteredComponents();
  renderComponentList();
  if (components.length === 0) {
    clearLayers();
    return;
  }
  if (!selectedComponent || !components.find(component => component.id === selectedComponent.id)) {
    renderComponent(components[0]);
    renderComponentList();
    return;
  }
  renderComponent(selectedComponent);
  renderComponentList();
});

entriesToggleEl.addEventListener("change", () => {
  if (selectedComponent) {
    renderComponent(selectedComponent);
  }
});

exitsToggleEl.addEventListener("change", () => {
  if (selectedComponent) {
    renderComponent(selectedComponent);
  }
});

allBoundsToggleEl.addEventListener("change", () => {
  if (selectedComponent) {
    renderComponent(selectedComponent);
  }
});

centerComponentEl.addEventListener("click", () => {
  if (selectedComponent) {
    renderComponent(selectedComponent);
  }
});
