import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const sources = [
  "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/JD冷链导单.xlsx",
  "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/JD冷链导单(1).xlsx",
  "/Users/jerry/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_e1jsbf77g5db12_8d29/msg/file/2026-08/发货清单.xlsx",
];

for (const source of sources) {
  const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));
  const inspected = await workbook.inspect({
    kind: "workbook,sheet,table,region",
    maxChars: 80000,
    tableMaxRows: 15,
    tableMaxCols: 90,
    tableMaxCellChars: 180,
  });
  console.log(`SOURCE ${source}`);
  console.log(inspected.ndjson);
}
