const reportUrl = new URLSearchParams(window.location.search).get("report") || "report.json";
const tileBaseUrl = "https://maps.runescape.wiki/osrs/versions/2026-03-04_a/tiles/rendered";
const MAP_ID = -1;
const MIN_ZOOM = -4;
const MIN_NATIVE_ZOOM = -2;
const MAX_ZOOM = 4;
const MAX_NATIVE_ZOOM = 3;
const summaryEl = document.getElementById("summary");
const runListEl = document.getElementById("run-list");
const runDetailsEl = document.getElementById("run-details");
const runSearchEl = document.getElementById("run-search");
const collisionToggleEl = document.getElementById("collision-toggle");
const centerTargetEl = document.getElementById("center-target");

let currentPlane = 0;
let selectedRun = null;
let allRuns = [];

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

let markerLayers = [];
let collisionLayers = [];

function worldToLatLng(point) {
  return [point.y + 0.5, point.x + 0.5];
}

function clearLayers() {
  markerLayers.forEach(layer => map.removeLayer(layer));
  collisionLayers.forEach(layer => map.removeLayer(layer));
  markerLayers = [];
  collisionLayers = [];
}

function addMarker(point, label, color, radius = 6) {
  const marker = L.circleMarker(worldToLatLng(point), {
    radius,
    color,
    fillColor: color,
    fillOpacity: 0.9
  }).bindTooltip(label);
  marker.addTo(map);
  markerLayers.push(marker);
}

function addCollisionWindow(collisionWindow) {
  collisionWindow.tiles.forEach(tile => {
    const bounds = [
      [tile.y, tile.x],
      [tile.y + 1, tile.x + 1]
    ];

    if (tile.blocked) {
      const blockedRect = L.rectangle(bounds, {
        color: "#0f172a",
        weight: 0,
        fillColor: "#0f172a",
        fillOpacity: 0.45
      }).addTo(map);
      collisionLayers.push(blockedRect);
    } else {
      const freeRect = L.rectangle(bounds, {
        color: "#94a3b8",
        weight: 0.5,
        fillOpacity: 0
      }).addTo(map);
      collisionLayers.push(freeRect);
    }

    const wallColor = "#38bdf8";
    const wallWeight = 2;
    if (!tile.north) {
      collisionLayers.push(L.polyline([[tile.y + 1, tile.x], [tile.y + 1, tile.x + 1]], { color: wallColor, weight: wallWeight, interactive: false }).addTo(map));
    }
    if (!tile.south) {
      collisionLayers.push(L.polyline([[tile.y, tile.x], [tile.y, tile.x + 1]], { color: wallColor, weight: wallWeight, interactive: false }).addTo(map));
    }
    if (!tile.east) {
      collisionLayers.push(L.polyline([[tile.y, tile.x + 1], [tile.y + 1, tile.x + 1]], { color: wallColor, weight: wallWeight, interactive: false }).addTo(map));
    }
    if (!tile.west) {
      collisionLayers.push(L.polyline([[tile.y, tile.x], [tile.y + 1, tile.x]], { color: wallColor, weight: wallWeight, interactive: false }).addTo(map));
    }
  });
}

function renderCollisionOverlay() {
  collisionLayers.forEach(layer => map.removeLayer(layer));
  collisionLayers = [];

  if (!selectedRun || !selectedRun.collisionWindow || !collisionToggleEl.checked) {
    return;
  }

  addCollisionWindow(selectedRun.collisionWindow);
}

function statusLabel(run) {
  const route = run.reached ? "reached" : "unreached";
  if (run.assertionPassed === true) return `pass, ${route}`;
  if (run.assertionPassed === false) return `fail, ${route}`;
  return route;
}

function formatPoint(point) {
  return `(${point.x}, ${point.y}, ${point.plane})`;
}

function markerColor(kind) {
  if (kind === "start") return "#1d4ed8";
  if (kind === "target") return "#2d6a4f";
  if (kind === "closest") return "#d97706";
  if (kind === "bank") return "#7c3aed";
  return "#475569";
}

function buildDetails(run) {
  const details = [
    `Name: ${run.name}`,
    `Category: ${run.category || "default"}`,
    `Assertion: ${run.assertionPassed === true ? "passed" : run.assertionPassed === false ? "failed" : "n/a"}`,
    `Reached: ${run.reached}`,
    `Termination: ${run.terminationReason}`,
    `Path length: ${run.path.length}`,
    `Nodes checked: ${run.stats.nodesChecked}`,
    `Transports checked: ${run.stats.transportsChecked}`,
    `Elapsed: ${(run.stats.elapsedNanos / 1_000_000).toFixed(2)} ms`,
    `Start: ${formatPoint(run.start)}`,
    `Target: ${formatPoint(run.target)}`,
    `Closest reached: ${formatPoint(run.closestReachedPoint)}`
  ];

  if (run.assertionMessage) {
    details.splice(3, 0, `Assertion message: ${run.assertionMessage}`);
  }

  if (run.details && run.details.length > 0) {
    details.push("");
    details.push("Scenario details:");
    run.details.forEach(detail => details.push(`- ${detail}`));
  }

  if (run.markers && run.markers.length > 0) {
    details.push("");
    details.push("Markers:");
    run.markers.forEach(marker => details.push(`- ${marker.label}: ${formatPoint(marker.point)}`));
  }

  if (run.transports && run.transports.length > 0) {
    details.push("");
    details.push("Transports used:");
    run.transports.forEach(step => {
      const label = step.displayInfo || step.objectInfo || step.type;
      details.push(`- step ${step.stepIndex}: ${step.type} -> ${label}`);
    });
  }

  if (run.collisionWindow) {
    details.push("");
    details.push(
      `Collision window: ${run.collisionWindow.width}x${run.collisionWindow.height}` +
      ` @ (${run.collisionWindow.originX}, ${run.collisionWindow.originY}, ${run.collisionWindow.plane})`
    );
  }

  return details.join("\n");
}

function normalizeSearchText(text) {
  return (text || "").toLowerCase().replace(/\s+/g, " ").trim();
}

function buildRunSearchText(run) {
  const parts = [
    run.name,
    run.category,
    run.terminationReason,
    ...(run.details || []),
    ...(run.transports || []).map(step => `${step.type} ${step.displayInfo || step.objectInfo || ""}`)
  ];
  return normalizeSearchText(parts.join(" "));
}

function fuzzyScore(query, text) {
  if (!query) {
    return 0;
  }

  if (text.includes(query)) {
    return query.length * 1000 - text.indexOf(query);
  }

  let score = 0;
  let textIndex = 0;
  let consecutive = 0;
  for (let i = 0; i < query.length; i++) {
    const ch = query[i];
    const foundIndex = text.indexOf(ch, textIndex);
    if (foundIndex === -1) {
      return Number.NEGATIVE_INFINITY;
    }

    if (foundIndex === textIndex) {
      consecutive += 1;
      score += 20 + consecutive * 5;
    } else {
      consecutive = 0;
      score += 5;
    }

    textIndex = foundIndex + 1;
  }

  return score - (text.length - query.length);
}

function filteredRuns() {
  const query = normalizeSearchText(runSearchEl.value);
  if (!query) {
    return allRuns;
  }

  return allRuns
    .map(run => ({ run, score: fuzzyScore(query, buildRunSearchText(run)) }))
    .filter(entry => entry.score > Number.NEGATIVE_INFINITY)
    .sort((a, b) => b.score - a.score || a.run.name.localeCompare(b.run.name))
    .map(entry => entry.run);
}

function renderRunList() {
  const runs = filteredRuns();
  runListEl.innerHTML = "";

  if (runs.length === 0) {
    const item = document.createElement("li");
    item.textContent = "No matching scenarios.";
    runListEl.appendChild(item);
    return;
  }

  runs.forEach((run, index) => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.textContent = `${index + 1}. [${statusLabel(run)}] ${run.name}`;
    if (selectedRun === run) {
      button.classList.add("selected");
    }
    button.addEventListener("click", () => {
      renderRun(run);
      renderRunList();
    });
    item.appendChild(button);
    runListEl.appendChild(item);
  });
}

function renderRun(run) {
  selectedRun = run;
  clearLayers();
  currentPlane = run.target.plane;
  baseLayer.redraw();

  const path = run.path.map(worldToLatLng);
  if (path.length > 0) {
    const polyline = L.polyline(path, { color: run.reached ? "#2d6a4f" : "#d1495b", weight: 3 }).addTo(map);
    markerLayers.push(polyline);
    map.fitBounds(polyline.getBounds().pad(0.25));
  } else {
    map.setView(worldToLatLng(run.target), 2);
  }

  (run.markers || []).forEach(marker => {
    const radius = marker.kind === "bank" ? 5 : 6;
    addMarker(marker.point, marker.label, markerColor(marker.kind), radius);
  });

  runDetailsEl.textContent = buildDetails(run);
  renderCollisionOverlay();
}

fetch(reportUrl)
  .then(response => {
    if (!response.ok) {
      throw new Error(`Failed to load ${reportUrl}`);
    }
    return response.json();
  })
  .then(report => {
    const runs = report.runs || [];
    allRuns = runs;
    summaryEl.textContent =
      `${report.summary.successfulRuns}/${report.summary.totalRuns} reached, ` +
      `${report.summary.failedRuns} unreached` +
      (report.subtitle ? `\n${report.subtitle}` : "");

    if (runs.length === 0) {
      runDetailsEl.textContent = "No runs in report.json";
      return;
    }

    currentPlane = runs[0].target.plane;
    baseLayer.redraw();

    renderRun(runs[0]);
    renderRunList();
  })
  .catch(error => {
    summaryEl.textContent = error.message;
    runDetailsEl.textContent = "Unable to render dashboard.";
  });

runSearchEl.addEventListener("input", () => {
  const runs = filteredRuns();
  renderRunList();
  if (runs.length === 0) {
    return;
  }

  if (!selectedRun || !runs.includes(selectedRun)) {
    renderRun(runs[0]);
    renderRunList();
  }
});

collisionToggleEl.addEventListener("change", () => {
  renderCollisionOverlay();
});

centerTargetEl.addEventListener("click", () => {
  if (!selectedRun) {
    return;
  }

  map.setView(worldToLatLng(selectedRun.target), Math.max(map.getZoom(), 2));
});
