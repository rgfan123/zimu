import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const [source, output] = process.argv.slice(2);
if (!source || !output) throw new Error("source and output paths are required");

const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));
const sheets = await workbook.inspect({ kind: "sheet", include: "id,name", maxChars: 4000 });
const dataSheetRecord = sheets.ndjson.split("\n")
  .filter(Boolean)
  .map((line) => JSON.parse(line))
  .find((record) => record.kind === "sheet" && record.name === "导入数据");
if (!dataSheetRecord) throw new Error("missing 导入数据 sheet");
const dataSheet = workbook.resolve(dataSheetRecord.id);
dataSheet.getRange("A2:BZ150").clear({ applyTo: "contents" });
// Preserve one non-sensitive style row. ProviderFileService clears this marker before writing.
dataSheet.getRange("A2").values = [["__STYLE_ROW__"]];
await SpreadsheetFile.exportXlsx(workbook).then((blob) => blob.save(output));
