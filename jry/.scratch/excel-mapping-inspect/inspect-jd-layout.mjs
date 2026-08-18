import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const sources = process.argv.slice(2);
for (const source of sources) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));
  const sheets = await workbook.inspect({ kind: "sheet", include: "id,name", maxChars: 4000 });
  console.log(`SOURCE ${source}`);
  console.log(sheets.ndjson);
  for (const record of sheets.ndjson.split("\n").filter(Boolean).map((line) => JSON.parse(line))) {
    if (record.kind !== "sheet") continue;
    const region = await workbook.inspect({
      kind: "region",
      sheetId: record.id,
      range: record.index === 0 ? "A1:BZ8" : "A1:Z40",
      maxChars: 30000,
    });
    console.log(region.ndjson);
  }
}
