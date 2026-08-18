import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const sources = [
  "/Users/jerry/Documents/子牧/彩食鲜待发货订单.xlsx",
  "/Users/jerry/Documents/子牧/聚福宝待发货订单.xlsx",
  "/Users/jerry/Documents/子牧/飞象待发货订单.csv",
];

for (const source of sources) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));
  const inspected = await workbook.inspect({
    kind: "workbook,sheet,table,region",
    maxChars: 50000,
    tableMaxRows: 20,
    tableMaxCols: 80,
    tableMaxCellChars: 160,
  });
  console.log(`SOURCE ${source}`);
  console.log(inspected.ndjson);
}
