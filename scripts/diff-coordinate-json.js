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
  const baseByCoordinate = new Map(baseItems.map(item => [item.coordinate, JSON.stringify(item)]));
  const changed = headItems.filter(item => baseByCoordinate.get(item.coordinate) !== JSON.stringify(item));

  fs.writeFileSync(outputPath, JSON.stringify(changed));
}

main();
