#!/usr/bin/env node

const fs = require('fs');

function readJson(path) {
  return JSON.parse(fs.readFileSync(path, 'utf8'));
}

function main() {
  const [basePath, headPath, outputPath] = process.argv.slice(2);
  if (!basePath || !headPath || !outputPath) {
    console.error('Usage: diff-coordinate-json.js <base.json> <head.json> <output.json>');
    process.exit(1);
  }

  const baseItems = readJson(basePath);
  const headItems = readJson(headPath);
  const baseCoordinates = new Set(baseItems.map(item => item.coordinate));
  const changed = headItems.filter(item => !baseCoordinates.has(item.coordinate));

  fs.writeFileSync(outputPath, JSON.stringify(changed));
}

main();
