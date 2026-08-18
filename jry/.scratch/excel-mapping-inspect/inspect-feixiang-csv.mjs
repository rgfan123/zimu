import { readFileSync } from "node:fs";
import { Workbook } from "@oai/artifact-tool";

const source = process.argv[2];
const bytes = readFileSync(source);
const text = new TextDecoder("gb18030", { fatal: true }).decode(bytes);
const workbook = await Workbook.fromCSV(text, { sheetName: "飞象原始导出" });
const inspected = await workbook.inspect({
  kind: "workbook,sheet,region",
  range: "A1:AN3",
  maxChars: 12000,
});
console.log(inspected.ndjson);
