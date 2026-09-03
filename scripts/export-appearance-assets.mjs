#!/usr/bin/env node

/** Promote audited Skin Splice Lab layers into the built-in client resource pack. */

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const WIDTH = 64;
const HEIGHT = 64;
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.dirname(SCRIPT_DIR);
const LAB_ASSETS = path.join(REPO_ROOT, "tools", "skin-lab", "assets");
const PARTS_DIR = path.join(
  REPO_ROOT,
  "src",
  "main",
  "resources",
  "assets",
  "villagelife",
  "textures",
  "entity",
  "person",
  "parts",
);
const CATALOG_PATH = path.join(
  REPO_ROOT,
  "src",
  "main",
  "resources",
  "assets",
  "villagelife",
  "appearance",
  "catalog.json",
);
const LAYERS = Object.freeze({
  skin: "skin.png",
  clothing: "clothing.png",
  hair: "hair.png",
  eyeLeft: "eyes-left.png",
  eyeRight: "eyes-right.png",
});

const crcTable = new Uint32Array(256);
for (let value = 0; value < 256; value += 1) {
  let crc = value;
  for (let bit = 0; bit < 8; bit += 1) {
    crc = crc & 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
  }
  crcTable[value] = crc >>> 0;
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, "ascii");
  const length = Buffer.alloc(4);
  const checksum = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  checksum.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])));
  return Buffer.concat([length, typeBuffer, data, checksum]);
}

function encodePng(rgba) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(WIDTH, 0);
  header.writeUInt32BE(HEIGHT, 4);
  header[8] = 8;
  header[9] = 6;
  const scanlines = Buffer.alloc(HEIGHT * (1 + WIDTH * 4));
  for (let y = 0; y < HEIGHT; y += 1) {
    const target = y * (1 + WIDTH * 4);
    scanlines[target] = 0;
    rgba.copy(scanlines, target + 1, y * WIDTH * 4, (y + 1) * WIDTH * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", header),
    pngChunk("IDAT", zlib.deflateSync(scanlines, { level: 9 })),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

function decodePng(sourcePath) {
  const encoded = fs.readFileSync(sourcePath);
  const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!encoded.subarray(0, pngSignature.length).equals(pngSignature)
      || encoded.toString("ascii", 12, 16) !== "IHDR"
      || encoded.readUInt32BE(16) !== WIDTH
      || encoded.readUInt32BE(20) !== HEIGHT) {
    throw new Error(`${sourcePath} must be an exact 64x64 PNG`);
  }
  const result = spawnSync("ffmpeg", [
    "-v", "error", "-i", sourcePath, "-f", "rawvideo", "-pix_fmt", "rgba", "-",
  ], { encoding: null, maxBuffer: 16 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(result.stderr.toString("utf8"));
  if (result.stdout.length !== WIDTH * HEIGHT * 4) {
    throw new Error(`${sourcePath} is not an exact 64x64 image`);
  }
  return result.stdout;
}

function normalizeBinaryAlpha(rgba) {
  const normalized = Buffer.from(rgba);
  for (let index = 3; index < normalized.length; index += 4) {
    normalized[index] = normalized[index] >= 128 ? 255 : 0;
  }
  return normalized;
}

function runtimeManifest(manifest, exportedFiles) {
  return {
    slug: manifest.slug,
    label: manifest.label,
    model: manifest.model,
    disabledParts: manifest.disabledParts ?? [],
    genderCategories: manifest.genderCategories,
    clothingProfile: manifest.clothingProfile,
    faceProfile: manifest.faceProfile,
    frontHairOcclusion: manifest.frontHairOcclusion,
    eyes: manifest.eyes,
    pigmentColors: manifest.pigmentColors,
    headwearOccludesHair: manifest.headwearOccludesHair ?? false,
    files: Object.fromEntries(exportedFiles.map((fileName) => [fileName, {}])),
  };
}

const sourceCatalog = JSON.parse(fs.readFileSync(path.join(LAB_ASSETS, "index.json"), "utf8"));
const partsParent = path.dirname(PARTS_DIR);
const stagingDirectory = fs.mkdtempSync(path.join(partsParent, "parts-export-"));
fs.mkdirSync(path.dirname(CATALOG_PATH), { recursive: true });

const runtimeCatalog = [];
let exportedLayerCount = 0;
try {
  for (const manifest of sourceCatalog) {
    const disabled = new Set(manifest.disabledParts ?? []);
    const outputDirectory = path.join(stagingDirectory, manifest.slug);
    const exportedFiles = [];
    for (const [part, fileName] of Object.entries(LAYERS)) {
      const sourcePath = path.join(LAB_ASSETS, manifest.slug, fileName);
      if (disabled.has(part) || !fs.existsSync(sourcePath)) continue;
      fs.mkdirSync(outputDirectory, { recursive: true });
      const rgba = normalizeBinaryAlpha(decodePng(sourcePath));
      fs.writeFileSync(path.join(outputDirectory, fileName), encodePng(rgba));
      exportedFiles.push(fileName);
      exportedLayerCount += 1;
    }
    runtimeCatalog.push(runtimeManifest(manifest, exportedFiles));
  }

  fs.rmSync(PARTS_DIR, { recursive: true, force: true });
  fs.renameSync(stagingDirectory, PARTS_DIR);
  fs.writeFileSync(CATALOG_PATH, `${JSON.stringify(runtimeCatalog, null, 2)}\n`);
} finally {
  fs.rmSync(stagingDirectory, { recursive: true, force: true });
}
console.log(`Exported ${runtimeCatalog.length} appearance assets (${exportedLayerCount} layers)`);
