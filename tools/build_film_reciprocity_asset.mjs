import fs from "node:fs";
import path from "node:path";

const [methodInput, filmInput, output] = process.argv.slice(2);
if (!methodInput || !filmInput || !output) {
  throw new Error(
    "Usage: node tools/build_film_reciprocity_asset.mjs <method-rows.json> <film-rows.json> <output.json>",
  );
}

const methodRows = JSON.parse(fs.readFileSync(methodInput, "utf8"));
const filmRows = JSON.parse(fs.readFileSync(filmInput, "utf8"));

const text = (value) => (value == null ? "" : String(value).trim());
const number = (value) => {
  if (value == null || value === "") return null;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid numeric value: ${value}`);
  return parsed;
};

const methods = methodRows.slice(3).filter((row) => text(row[0])).map((row) => {
  const points = [];
  for (let column = 11; column <= 29; column += 3) {
    const meteredSeconds = number(row[column]);
    const correctedSeconds = number(row[column + 1]);
    if (meteredSeconds == null && correctedSeconds == null) continue;
    if (meteredSeconds == null || correctedSeconds == null || meteredSeconds <= 0 || correctedSeconds <= 0) {
      throw new Error(`Invalid reciprocity node in method ${row[0]} at column ${column + 1}`);
    }
    points.push({
      meteredSeconds,
      correctedSeconds,
      filter: text(row[column + 2]),
    });
  }
  for (let index = 1; index < points.length; index += 1) {
    if (points[index].meteredSeconds <= points[index - 1].meteredSeconds) {
      throw new Error(`Non-increasing reciprocity nodes in method ${row[0]}`);
    }
  }
  return {
    id: text(row[0]),
    type: text(row[1]),
    parameter: number(row[2]),
    noCompensationSeconds: number(row[3]),
    officialMaximumSeconds: number(row[4]),
    evidence: text(row[5]),
    longExposureFilter: text(row[6]),
    filterRule: text(row[7]),
    algorithm: text(row[8]),
    warning: text(row[9]),
    sourceUrl: text(row[10]),
    points,
  };
});

const methodIds = new Set(methods.map((method) => method.id));
if (methodIds.size !== methods.length) throw new Error("Duplicate reciprocity method IDs");

const films = filmRows.slice(1).filter((row) => number(row[0]) != null).map((row) => {
  const methodId = text(row[18]) || "NONE";
  if (!methodIds.has(methodId)) {
    throw new Error(`Film ${row[0]} references missing method ${methodId}`);
  }
  return {
    filmId: Number(row[0]),
    methodId,
  };
});

const filmIds = new Set(films.map((film) => film.filmId));
if (filmIds.size !== films.length) throw new Error("Duplicate film IDs");

const result = {
  schemaVersion: 1,
  sourceWorkbook: "胶片宽容度数据库_公开资料深挖版_2026-08-23.xlsx",
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
