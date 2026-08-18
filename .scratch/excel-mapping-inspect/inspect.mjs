import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const source = "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/京东商品编号.xlsx";
const outputDir = "/Users/jerry/Documents/子牧/.scratch/excel-mapping-inspect/rendered";

await fs.mkdir(outputDir, { recursive: true });
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));

const overview = await workbook.inspect({
  kind: "workbook,sheet,table",
  maxChars: 12000,
  tableMaxRows: 12,
  tableMaxCols: 16,
  tableMaxCellChars: 120,
});
console.log("OVERVIEW\n" + overview.ndjson);

const sheetInfo = (await workbook.inspect({ kind: "sheet", include: "id,name", maxChars: 12000 })).ndjson;
const sheets = sheetInfo.split("\n").filter(Boolean).map((line) => JSON.parse(line));
for (const entry of sheets) {
  const sheetName = entry.name ?? entry.sheetName;
  if (!sheetName) continue;
  const sheet = workbook.worksheets.getItem(sheetName);
  const used = sheet.getUsedRange();
  console.log(`SHEET ${sheetName} USED ${used?.address ?? "unknown"}`);
  const detail = await workbook.inspect({
    kind: "region,computedStyle,formula",
    sheetId: sheetName,
    range: used?.address,
    maxChars: 30000,
    tableMaxRows: 200,
    tableMaxCols: 24,
    tableMaxCellChars: 200,
    options: { maxResults: 300 },
  });
  console.log(detail.ndjson);
  const preview = await workbook.render({ sheetName, autoCrop: "all", scale: 1.5, format: "png" });
  const safeName = sheetName.replaceAll(/[^\p{L}\p{N}._-]+/gu, "_");
  await fs.writeFile(path.join(outputDir, `${safeName}.png`), new Uint8Array(await preview.arrayBuffer()));
}
