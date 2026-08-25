import fs from "node:fs";
import path from "node:path";

const [pointsInput, databaseInput, filmCatalogInput, output] = process.argv.slice(2);
if (!pointsInput || !databaseInput || !filmCatalogInput || !output) {
  throw new Error(
    "Usage: node tools/build_film_reciprocity_asset_v2.mjs " +
      "<reciprocity-points.csv> <reciprocity-database.md> <film-catalog.json> <output.json>",
  );
}

const csvText = fs.readFileSync(pointsInput, "utf8");
const databaseText = fs.readFileSync(databaseInput, "utf8");
const filmCatalog = JSON.parse(fs.readFileSync(filmCatalogInput, "utf8"));

const text = (value) => (value == null ? "" : String(value).trim());
const markdownText = (value) => text(value)
  .replace(/<br\s*\/?\s*>/gi, "\n")
  .replace(/\*\*/g, "")
  .trim();
const optionalNumber = (value, label) => {
  const normalized = text(value);
  if (!normalized || normalized === "—" || normalized === "-") return null;
  const parsed = Number(normalized);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid ${label}: ${value}`);
  return parsed;
};

function parseCsv(source) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    if (quoted) {
      if (character === '"' && source[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        field += character;
      }
    } else if (character === '"') {
      quoted = true;
    } else if (character === ",") {
      row.push(field);
      field = "";
    } else if (character === "\n") {
      row.push(field.replace(/\r$/, ""));
      if (row.some((value) => value !== "")) rows.push(row);
      row = [];
      field = "";
    } else {
      field += character;
    }
  }
  if (quoted) throw new Error("Unterminated quoted CSV field");
  if (field || row.length) {
    row.push(field.replace(/\r$/, ""));
    if (row.some((value) => value !== "")) rows.push(row);
  }
  if (!rows.length) throw new Error("Reciprocity CSV is empty");
  rows[0][0] = rows[0][0].replace(/^\uFEFF/, "");
  const headers = rows[0];
  const required = ["MethodID", "Type", "Tm(s)", "Tc(s)", "DeltaEV", "Source", "LPD_Filter", "Note"];
  required.forEach((header) => {
    if (!headers.includes(header)) throw new Error(`Missing CSV column: ${header}`);
  });
  return rows.slice(1).map((values, rowIndex) => ({
    ...Object.fromEntries(headers.map((header, column) => [header, values[column] ?? ""])),
    __row: rowIndex + 2,
  }));
}

function parseMarkdownRow(line) {
  return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map(markdownText);
}

function markdownTable(startPattern, endPattern, expectedFirstHeader) {
  const start = databaseText.search(startPattern);
  if (start < 0) throw new Error(`Missing Markdown section: ${startPattern}`);
  const afterStart = databaseText.slice(start);
  const relativeEnd = afterStart.search(endPattern);
  const section = relativeEnd < 0 ? afterStart : afterStart.slice(0, relativeEnd);
  const lines = section.split(/\r?\n/).filter((line) => line.trim().startsWith("|"));
  const headerIndex = lines.findIndex((line) => parseMarkdownRow(line)[0] === expectedFirstHeader);
  if (headerIndex < 0 || !lines[headerIndex + 1]?.includes("---")) {
    throw new Error(`Missing Markdown table header: ${expectedFirstHeader}`);
  }
  const headers = parseMarkdownRow(lines[headerIndex]);
  return lines.slice(headerIndex + 2).map(parseMarkdownRow).map((values) => Object.fromEntries(
    headers.map((header, column) => [header, values[column] ?? ""]),
  ));
}

const pointRows = parseCsv(csvText);
const pointRowsByMethod = Map.groupBy(pointRows, (row) => text(row.MethodID));

const methodRows = markdownTable(/^### 2\.1 总表/m, /^### 2\.2 /m, "方法ID");
const validTypes = new Set(["NONE", "RANGE", "TABLE", "FIXED_EV", "POWER"]);
const methods = methodRows.map((row) => {
  const id = text(row["方法ID"]);
  const type = text(row["类型"]);
  if (!id || !validTypes.has(type)) throw new Error(`Invalid reciprocity method: ${id}/${type}`);
  const sourceRows = pointRowsByMethod.get(id) ?? [];
  if (type !== "NONE" && sourceRows.length === 0) {
    throw new Error(`Method ${id} has no CSV rows`);
  }
  sourceRows.forEach((source) => {
    if (text(source.Type) !== type) {
      throw new Error(`CSV type mismatch for ${id} at row ${source.__row}`);
    }
  });

  const noCompensationSeconds = optionalNumber(row["无补偿上限(s)"], `${id} no-compensation limit`) ?? 1;
  const declaredMaximum = optionalNumber(row["官方上限(s)"], `${id} official maximum`);
  const validSourceRows = sourceRows.filter((source) => {
    const meteredSeconds = optionalNumber(source["Tm(s)"], `${id} Tm row ${source.__row}`);
    const correctedSeconds = optionalNumber(source["Tc(s)"], `${id} Tc row ${source.__row}`);
    return meteredSeconds != null && correctedSeconds != null && meteredSeconds > 0 && correctedSeconds > 0;
  });
  const fittedMaximum = validSourceRows.length
    ? Math.max(...validSourceRows.map((source) => Number(source["Tm(s)"])))
    : null;
  const officialMaximumSeconds = declaredMaximum ?? (
    type === "POWER" || type === "FIXED_EV" ? fittedMaximum : null
  );

  let parameter = optionalNumber(row["P/固定EV"], `${id} P/fixed EV`);
  if (type === "POWER") {
    const fitRows = validSourceRows.filter((source) => Number(source["Tm(s)"]) > noCompensationSeconds + 1e-9);
    const sums = fitRows.reduce((result, source) => {
      const x = Math.log(Number(source["Tm(s)"]));
      const y = Math.log(Number(source["Tc(s)"]));
      return { xx: result.xx + x * x, xy: result.xy + x * y };
    }, { xx: 0, xy: 0 });
    const fittedParameter = sums.xx > 0 ? sums.xy / sums.xx : null;
    if (parameter == null) parameter = fittedParameter;
    if (parameter == null || fittedParameter == null || Math.abs(parameter - fittedParameter) > 0.0025) {
      throw new Error(`POWER fit mismatch for ${id}: declared=${parameter}, fitted=${fittedParameter}`);
    }
  }
  if (type === "FIXED_EV" && parameter == null) {
    const deltas = validSourceRows
      .map((source) => optionalNumber(source.DeltaEV, `${id} DeltaEV row ${source.__row}`))
      .filter((value) => value != null && value > 0)
      .sort((left, right) => left - right);
    parameter = deltas.length ? deltas[Math.floor(deltas.length / 2)] : null;
  }

  const pointCandidates = type === "TABLE"
    ? validSourceRows.filter((source) => text(source.Source) === "node")
    : [];
  if (type === "TABLE" && pointCandidates.length === 0) {
    throw new Error(`TABLE method ${id} has no valid manufacturer/discrete nodes`);
  }
  const points = pointCandidates.map((source) => ({
    meteredSeconds: Number(source["Tm(s)"]),
    correctedSeconds: Number(source["Tc(s)"]),
    filter: text(source.LPD_Filter),
  })).sort((left, right) => left.meteredSeconds - right.meteredSeconds);
  for (let index = 1; index < points.length; index += 1) {
    if (points[index].meteredSeconds <= points[index - 1].meteredSeconds) {
      throw new Error(`Non-increasing reciprocity nodes in method ${id}`);
    }
  }

  return {
    id,
    type,
    parameter,
    noCompensationSeconds,
    officialMaximumSeconds,
    evidence: text(row["证据"]),
    longExposureFilter: markdownText(row["长曝专用滤镜"]),
    filterRule: markdownText(row["滤镜计入"]),
    algorithm: markdownText(row["计算公式"]),
    warning: markdownText(row["备注/警告"]),
    confidence: markdownText(row["分段可信度"]),
    sourceUrl: "",
    points,
  };
});

const methodIds = new Set(methods.map((method) => method.id));
if (methodIds.size !== methods.length) throw new Error("Duplicate reciprocity method IDs");
const csvMethodIds = new Set(pointRows.map((row) => text(row.MethodID)));
const csvOnly = [...csvMethodIds].filter((id) => !methodIds.has(id));
if (csvOnly.length) throw new Error(`CSV methods missing from Markdown: ${csvOnly.join(", ")}`);

const filmRows = markdownTable(/^## 1\. 胶片清单/m, /^## 2\. 方法库/m, "#");
const catalogById = new Map(filmCatalog.map((film) => [Number(film.id), film]));
const normalizeName = (value) => text(value)
  .replace(/⭐/g, "")
  .replace(/\s+/g, " ")
  .replace(/\s*\/\s*/g, "/")
  .toLocaleUpperCase("en-US");
const films = filmRows.map((row) => {
  const filmId = optionalNumber(row["#"], "film ID");
  if (!Number.isInteger(filmId)) throw new Error(`Invalid film ID: ${row["#"]}`);
  const methodId = text(row["方法ID"]) || "NONE";
  if (!methodIds.has(methodId)) throw new Error(`Film ${filmId} references missing method ${methodId}`);
  const catalogFilm = catalogById.get(filmId);
  if (!catalogFilm) throw new Error(`Film ${filmId} is missing from the app film catalog`);
  if (
    normalizeName(row["厂家"]) !== normalizeName(catalogFilm.manufacturer) ||
    normalizeName(row["型号"]) !== normalizeName(catalogFilm.model)
  ) {
    throw new Error(
      `Film ${filmId} identity mismatch: Markdown=${row["厂家"]} ${row["型号"]}; ` +
        `app=${catalogFilm.manufacturer} ${catalogFilm.model}`,
    );
  }
  return { filmId, methodId };
});
if (films.length !== filmCatalog.length) {
  throw new Error(`Film mapping count mismatch: Markdown=${films.length}, app=${filmCatalog.length}`);
}
const filmIds = new Set(films.map((film) => film.filmId));
if (filmIds.size !== films.length) throw new Error("Duplicate film IDs");

const result = {
  schemaVersion: 2,
  sourceFiles: [path.basename(pointsInput), path.basename(databaseInput)],
  methods,
  films,
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, "utf8");

const typeCounts = Object.fromEntries(
  [...new Set(methods.map((method) => method.type))]
    .sort()
    .map((type) => [type, methods.filter((method) => method.type === type).length]),
);
const pointCount = methods.reduce((sum, method) => sum + method.points.length, 0);
process.stdout.write(
  `${JSON.stringify({ methods: methods.length, films: films.length, points: pointCount, typeCounts })}\n`,
);
