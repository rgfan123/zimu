import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const source = "/Users/jerry/Documents/子牧/京东商品编号.xlsx";
const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(source));
const sheets = [0, 1, 2, 3].map((index) => workbook.worksheets.getItemAt(index).getUsedRange().values);

const library = new Map();
const text = (value) => value == null ? "" : String(value).normalize("NFKC").trim();
const number = (value) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
};
const ensure = (code) => {
  if (!library.has(code)) library.set(code, { code, names: new Set(), candidates: new Map(), bundleReferences: new Set() });
  return library.get(code);
};
const addName = (code, name) => {
  if (!/^EMG\d+$/.test(code) || !name) return;
  ensure(code).names.add(name);
};
const addCandidate = (code, context, name, multiplier) => {
  if (!/^EMG\d+$/.test(code) || !name || multiplier == null) return;
  const item = ensure(code);
  item.names.add(name);
  item.candidates.set(`${context}\u001f${name}\u001f${multiplier}`, { context, name, multiplier });
};

for (const row of sheets[0].slice(1)) {
  const code = text(row[5]);
  addName(code, text(row[4]));
  addCandidate(code, "CAISHIXIAN", text(row[0]), number(row[1]));
  addCandidate(code, "JUFUBAO", text(row[2]), number(row[3]));
}
for (const row of sheets[2]) {
  const code = text(row[0]);
  addName(code, text(row[1]));
  addCandidate(code, text(row[3]) || "SUPPLIER_REFERENCE", text(row[1]), number(row[2]));
}
for (const row of sheets[3]) {
  const code = text(row[0]);
  addName(code, text(row[1]));
  addCandidate(code, text(row[3]) || "JUFUBAO", text(row[1]), number(row[2]));
}
for (const row of sheets[1]) {
  for (const [nameColumn, codeColumn] of [[0, 1], [4, 5], [7, 8]]) {
    const code = text(row[codeColumn]);
    if (!/^EMG\d+$/.test(code)) continue;
    const item = ensure(code);
    const bundle = text(row[nameColumn]);
    if (bundle && !bundle.startsWith("EMG")) item.bundleReferences.add(bundle);
  }
}

const output = [...library.values()].sort((a, b) => a.code.localeCompare(b.code)).map((item) => ({
  code: item.code,
  primaryName: [...item.names][0] || `京东商品 ${item.code}`,
  aliases: [...item.names].sort(),
  candidates: [...item.candidates.values()].sort((a, b) => `${a.context}${a.name}`.localeCompare(`${b.context}${b.name}`)),
  bundleReferences: [...item.bundleReferences].sort(),
}));
console.log(JSON.stringify({ count: output.length, unnamed: output.filter((item) => item.aliases.length === 0).length, items: output }, null, 2));
