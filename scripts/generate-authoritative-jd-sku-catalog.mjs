#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { XMLParser } from "fast-xml-parser";
import JSZip from "jszip";

const EXPECTED_JD_SHA = "85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873";
const EXPECTED_PRICE_SHA = "7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160";

const [jdPath, pricePath, mode = "--stdout", outputPath] = process.argv.slice(2);
if (!jdPath || !pricePath || !["--stdout", "--write", "--check"].includes(mode)
    || (mode !== "--stdout" && !outputPath)) {
  throw new Error("usage: generate-authoritative-jd-sku-catalog.mjs <jd.xlsx> <price.xlsx> [--stdout | --write <manifest.json> | --check <manifest.json>]");
}

const sha256 = async (path) => createHash("sha256").update(await fs.readFile(path)).digest("hex");
const jdSha = await sha256(jdPath);
const priceSha = await sha256(pricePath);
if (jdSha !== EXPECTED_JD_SHA || priceSha !== EXPECTED_PRICE_SHA) {
  throw new Error(`source drift: jd=${jdSha}, price=${priceSha}`);
}

const xmlParser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "",
  removeNSPrefix: true,
  parseAttributeValue: false,
  parseTagValue: false,
  trimValues: false,
});
const asArray = (value) => value == null ? [] : Array.isArray(value) ? value : [value];
const xmlText = (value) => {
  if (value == null) return "";
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (Array.isArray(value)) return value.map(xmlText).join("");
  if (typeof value === "object") {
    if ("#text" in value) return xmlText(value["#text"]);
    if ("t" in value) return xmlText(value.t);
    if ("r" in value) return xmlText(value.r);
  }
  throw new Error("unsupported spreadsheet text value");
};
const archiveText = async (archive, entryPath) => {
  const entry = archive.file(entryPath);
  if (!entry) throw new Error(`missing XLSX entry: ${entryPath}`);
  return entry.async("string");
};
const columnIndex = (reference) => {
  const match = /^([A-Z]+)[1-9]\d*$/.exec(reference);
  if (!match) throw new Error(`invalid cell reference: ${reference}`);
  return [...match[1]].reduce((result, character) => result * 26 + character.charCodeAt(0) - 64, 0) - 1;
};
const cellValue = (cell, sharedStrings, sheetName) => {
  if (cell.v == null && cell.is == null) return null;
  if (cell.t === "s") {
    const index = Number(cell.v);
    if (!Number.isSafeInteger(index) || sharedStrings[index] == null) {
      throw new Error(`invalid shared string at ${sheetName}!${cell.r}`);
    }
    return sharedStrings[index];
  }
  if (cell.t === "inlineStr") return xmlText(cell.is);
  if (cell.t === "str") return xmlText(cell.v);
  if (cell.t === "b") return cell.v === "1";
  const raw = xmlText(cell.v);
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) throw new Error(`invalid numeric cell at ${sheetName}!${cell.r}`);
  return numeric;
};
const parseWorksheet = (xml, sharedStrings, sheetName) => {
  const worksheet = xmlParser.parse(xml).worksheet;
  const cells = new Map();
  let maxRow = 0;
  let maxColumn = 0;
  for (const row of asArray(worksheet?.sheetData?.row)) {
    for (const cell of asArray(row.c)) {
      const reference = cell.r;
      const rowMatch = /([1-9]\d*)$/.exec(reference ?? "");
      if (!rowMatch) throw new Error(`invalid cell reference in ${sheetName}: ${reference}`);
      const rowIndex = Number(rowMatch[1]) - 1;
      const currentColumn = columnIndex(reference);
      const value = cellValue(cell, sharedStrings, sheetName);
      if (value == null || String(value).trim() === "") continue;
      cells.set(`${rowIndex}:${currentColumn}`, value);
      maxRow = Math.max(maxRow, rowIndex + 1);
      maxColumn = Math.max(maxColumn, currentColumn + 1);
    }
  }
  return Array.from({ length: maxRow }, (_, rowIndex) =>
    Array.from({ length: maxColumn }, (_, currentColumn) =>
      cells.get(`${rowIndex}:${currentColumn}`) ?? null));
};
const loadWorkbook = async (filePath) => {
  const archive = await JSZip.loadAsync(await fs.readFile(filePath));
  const workbook = xmlParser.parse(await archiveText(archive, "xl/workbook.xml")).workbook;
  const relationships = xmlParser.parse(
    await archiveText(archive, "xl/_rels/workbook.xml.rels"),
  ).Relationships;
  const targets = new Map(asArray(relationships?.Relationship)
    .map((relationship) => [relationship.Id, relationship.Target]));
  const sharedStringsEntry = archive.file("xl/sharedStrings.xml");
  const sharedStrings = sharedStringsEntry == null
    ? []
    : asArray(xmlParser.parse(await sharedStringsEntry.async("string")).sst?.si).map(xmlText);
  const worksheets = new Map();
  for (const sheet of asArray(workbook?.sheets?.sheet)) {
    const target = targets.get(sheet.id);
    if (!target) throw new Error(`missing worksheet relationship: ${sheet.name}`);
    const entryPath = target.startsWith("/")
      ? target.slice(1)
      : path.posix.normalize(path.posix.join("xl", target));
    worksheets.set(
      sheet.name,
      parseWorksheet(await archiveText(archive, entryPath), sharedStrings, sheet.name),
    );
  }
  return worksheets;
};
const worksheetRows = (workbook, sheetName) => {
  const worksheet = workbook.get(sheetName);
  if (!worksheet) throw new Error(`missing worksheet: ${sheetName}`);
  return worksheet;
};

const jdWorkbook = await loadWorkbook(jdPath);
const priceWorkbook = await loadWorkbook(pricePath);
const jdRows = worksheetRows(jdWorkbook, "Sheet1");
const priceRows = worksheetRows(priceWorkbook, "0");
const expectedJdHeader = ["彩食鲜商品", "数量", "聚福宝商品", "数量", "JD", "编码"];
const expectedPriceHeader = ["序号", "商品名称", "规格", "件装数", "一件代发价格", "建议售价"];
if (JSON.stringify(jdRows[0]) !== JSON.stringify(expectedJdHeader)
    || JSON.stringify(priceRows[0]) !== JSON.stringify(expectedPriceHeader)) {
  throw new Error("source columns drifted");
}

const text = (value) => value == null || String(value).trim() === "" ? null : String(value).trim();
const canonical = (value) => text(value)?.replaceAll("（", "(").replaceAll("）", ")") ?? null;
const money = (value) => {
  const raw = text(value);
  if (raw == null || !/^\d+(?:\.\d{1,2})?$/.test(raw)) throw new Error(`invalid money: ${raw}`);
  const [whole, fraction = ""] = raw.split(".");
  return `${whole}.${fraction.padEnd(2, "0")}`;
};
const nonemptyRows = (sheetName) => worksheetRows(jdWorkbook, sheetName)
  .filter((row) => row.some((value) => text(value) != null)).length;

const priceByExactName = new Map();
for (const [index, row] of priceRows.slice(1).entries()) {
  const name = text(row[1]);
  if (!name || priceByExactName.has(name)) throw new Error(`invalid/duplicate price name at row ${index + 2}`);
  priceByExactName.set(name, {
    row: index + 2,
    purchase: money(row[4]),
    retail: money(row[5]),
  });
}

const groups = new Map();
for (const [index, row] of jdRows.slice(1).entries()) {
  const jdCode = text(row[5]);
  if (!jdCode) continue;
  if (!/^EMG\d+$/.test(jdCode)) throw new Error(`invalid JD code at row ${index + 2}`);
  const sourceRow = {
    row: index + 2,
    caishixian_name: text(row[0]),
    caishixian_quantity: text(row[1]),
    jufubao_name: text(row[2]),
    jufubao_quantity: text(row[3]),
    jd_name: text(row[4]),
  };
  if (!sourceRow.jd_name) throw new Error(`missing JD name at row ${index + 2}`);
  const validChannelPair = (name, quantity) => (name == null && quantity == null)
    || (name != null && quantity != null && /^[1-9]\d*$/.test(quantity));
  if (!validChannelPair(sourceRow.caishixian_name, sourceRow.caishixian_quantity)
      || !validChannelPair(sourceRow.jufubao_name, sourceRow.jufubao_quantity)) {
    throw new Error(`invalid channel name/quantity pair at row ${index + 2}`);
  }
  const rows = groups.get(jdCode) ?? [];
  rows.push(sourceRow);
  groups.set(jdCode, rows);
}

const items = [...groups].map(([jdCode, sourceRows]) => {
  // Intentionally strict: only the Sheet1 彩食鲜商品 cell may match a price-row 商品名称 byte-for-byte.
  const exactNames = [...new Set(sourceRows
    .map((row) => row.caishixian_name)
    .filter((name) => priceByExactName.has(name)))];
  if (exactNames.length > 1) throw new Error(`ambiguous exact price match for ${jdCode}`);
  const matchedName = exactNames[0] ?? null;
  const price = matchedName == null ? null : priceByExactName.get(matchedName);
  const canonicalName = canonical(sourceRows[0].jd_name);
  const aliases = [...new Set(sourceRows
    .flatMap((row) => [row.caishixian_name, row.jufubao_name, row.jd_name])
    .map(canonical)
    .filter((name) => name && name !== canonicalName))];
  const differences = [];
  if (sourceRows.length > 1) differences.push("DUPLICATE_JD_CODE");
  if (sourceRows.some((row) => !row.caishixian_name)) differences.push("CAISHIXIAN_MAPPING_MISSING");
  if (sourceRows.some((row) => !row.jufubao_name)) differences.push("JUFUBAO_MAPPING_MISSING");
  if (sourceRows.some((row) => row.caishixian_quantity && row.jufubao_quantity
      && row.caishixian_quantity !== row.jufubao_quantity)) differences.push("CHANNEL_QUANTITY_DIFFERS");
  return {
    jd_code: jdCode,
    canonical_name: canonicalName,
    aliases,
    source_rows: sourceRows,
    price_match_name: matchedName,
    price_source_row: price?.row ?? null,
    purchase_price: price?.purchase ?? null,
    retail_price: price?.retail ?? null,
    mapping_difference_codes: differences,
  };
});

const matchedCount = items.filter((item) => item.purchase_price != null).length;
const duplicateCount = items.filter((item) => item.source_rows.length > 1).length;
if (jdRows.length !== 64 || priceRows.length !== 42 || items.length !== 61
    || duplicateCount !== 2 || matchedCount !== 27 || items.length - matchedCount !== 34) {
  throw new Error("authoritative coverage changed; review before regenerating");
}

const manifest = {
  schema_version: 1,
  jd_source: {
    file_name: "京东商品编号.xlsx",
    sheet_name: "Sheet1",
    sha256: jdSha,
    data_rows: jdRows.length - 1,
  },
  price_source: {
    file_name: "合作商品价格查询导出_按商品名称去重.xlsx",
    sheet_name: "0",
    sha256: priceSha,
    data_rows: priceRows.length - 1,
  },
  expected: {
    unique_jd_codes: items.length,
    duplicate_code_count: duplicateCount,
    price_matched_count: matchedCount,
    unpriced_count: items.length - matchedCount,
  },
  excluded_sheets: [
    { sheet_name: "Sheet2", nonempty_rows: nonemptyRows("Sheet2"), reason: "BUNDLE_MAPPING_OUT_OF_SCOPE" },
    { sheet_name: "Sheet3", nonempty_rows: nonemptyRows("Sheet3"), reason: "CAISHIXIAN_REFERENCE_OUT_OF_SCOPE" },
    { sheet_name: "Sheet4", nonempty_rows: nonemptyRows("Sheet4"), reason: "JUFUBAO_REFERENCE_OUT_OF_SCOPE" },
  ],
  items,
};
const output = `${JSON.stringify(manifest, null, 2)}\n`;

if (mode === "--write") {
  await fs.writeFile(outputPath, output, "utf8");
} else if (mode === "--check") {
  const current = await fs.readFile(outputPath, "utf8");
  if (current !== output) throw new Error(`${outputPath} is not reproducible from the pinned sources`);
  process.stdout.write(`OK ${createHash("sha256").update(output).digest("hex")}\n`);
} else {
  process.stdout.write(output);
}
