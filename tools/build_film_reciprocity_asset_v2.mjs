import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const defaultOverridesInput = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "film_reciprocity_audited_overrides_2026_09_01.json",
);
const [pointsInput, databaseInput, filmCatalogInput, output, overridesInput = defaultOverridesInput] =
  process.argv.slice(2);
if (!pointsInput || !databaseInput || !filmCatalogInput || !output) {
  throw new Error(
    "Usage: node tools/build_film_reciprocity_asset_v2.mjs " +
      "<reciprocity-points.csv> <reciprocity-database.md> <film-catalog.json> <output.json>",
  );
}

const csvText = fs.readFileSync(pointsInput, "utf8");
const databaseText = fs.readFileSync(databaseInput, "utf8");
const filmCatalog = JSON.parse(fs.readFileSync(filmCatalogInput, "utf8"));
const auditedOverrides = JSON.parse(fs.readFileSync(overridesInput, "utf8"));

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
const validTypes = new Set(["NONE", "RANGE", "BOUNDED_UNCHANGED", "TABLE", "FIXED_EV", "POWER"]);
const methods = methodRows.map((row) => {
  const id = text(row["方法ID"]);
  const type = text(row["类型"]);
  if (!id || !validTypes.has(type)) throw new Error(`Invalid reciprocity method: ${id}/${type}`);
  const sourceRows = pointRowsByMethod.get(id) ?? [];
  if (type !== "NONE" && type !== "BOUNDED_UNCHANGED" && sourceRows.length === 0) {
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
      const x = Math.log2(Number(source["Tm(s)"]));
      const y = Math.log2(Number(source["Tc(s)"]));
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

const auditedMethodIds = new Set([
  ...auditedOverrides.methods.map((method) => method.id),
  ...Object.keys(auditedOverrides.methodPatches ?? {}),
]);
for (const override of auditedOverrides.methods) {
  const index = methods.findIndex((method) => method.id === override.id);
  if (index >= 0) methods[index] = override;
  else methods.push(override);
}
for (const [methodId, patch] of Object.entries(auditedOverrides.methodPatches ?? {})) {
  const method = methods.find((candidate) => candidate.id === methodId);
  if (!method) throw new Error(`Cannot patch missing reciprocity method ${methodId}`);
  Object.assign(method, patch);
}
for (const film of films) {
  film.methodId = auditedOverrides.filmMethodOverrides[String(film.filmId)] ?? film.methodId;
}
const finalMethodIds = new Set(methods.map((method) => method.id));
if (finalMethodIds.size !== methods.length) throw new Error("Duplicate final reciprocity method IDs");
for (const film of films) {
  if (!finalMethodIds.has(film.methodId)) {
    throw new Error(`Film ${film.filmId} references missing final method ${film.methodId}`);
  }
}
for (const method of methods) {
  if (method.type !== "TABLE") continue;
  for (const point of method.points) {
    if (point.correctedSeconds + 1e-9 < point.meteredSeconds) {
      throw new Error(
        `TABLE method ${method.id} shortens exposure at ${point.meteredSeconds}s: ${point.correctedSeconds}s`,
      );
    }
  }
  for (let index = 1; index < method.points.length; index += 1) {
    const before = method.points[index - 1];
    const after = method.points[index];
    const beforeStops = Math.log2(before.correctedSeconds / before.meteredSeconds);
    const afterStops = Math.log2(after.correctedSeconds / after.meteredSeconds);
    if (afterStops + 1e-9 < beforeStops) {
      throw new Error(
        `TABLE method ${method.id} has a dipping compensation node: ` +
          `${before.meteredSeconds}s (${beforeStops} EV) -> ${after.meteredSeconds}s (${afterStops} EV)`,
      );
    }
  }
  const lastPoint = method.points.at(-1);
  if (
    lastPoint && method.officialMaximumSeconds != null &&
    method.officialMaximumSeconds + 1e-9 < lastPoint.meteredSeconds
  ) {
    throw new Error(
      `TABLE method ${method.id} has a published node beyond its official maximum: ` +
        `${lastPoint.meteredSeconds}s > ${method.officialMaximumSeconds}s`,
    );
  }
}

function endpointTangent(adjacentWidth, nextWidth, adjacentSlope, nextSlope) {
  let tangent = (
    (2 * adjacentWidth + nextWidth) * adjacentSlope - adjacentWidth * nextSlope
  ) / (adjacentWidth + nextWidth);
  if (Math.sign(tangent) !== Math.sign(adjacentSlope)) tangent = 0;
  else if (
    Math.sign(adjacentSlope) !== Math.sign(nextSlope) &&
    Math.abs(tangent) > Math.abs(3 * adjacentSlope)
  ) tangent = 3 * adjacentSlope;
  return tangent;
}

/** Mirrors the app's shape-preserving cubic fit of correction EV in exposure-stop space. */
function fitTable(points, input) {
  const samples = points
    .filter((point) => point.meteredSeconds > 0 && point.correctedSeconds > 0)
    .sort((left, right) => left.meteredSeconds - right.meteredSeconds)
    .filter((point, index, all) => index === all.length - 1 || point.meteredSeconds !== all[index + 1].meteredSeconds);
  if (samples.length < 2 || input <= 0) return null;
  const exact = samples.find((point) => Math.abs(point.meteredSeconds - input) <= 1e-9);
  if (exact) return exact.correctedSeconds;

  const x = samples.map((point) => Math.log2(point.meteredSeconds));
  const correctionStops = samples.map((point) =>
    Math.log2(point.correctedSeconds / point.meteredSeconds));
  const widths = x.slice(1).map((value, index) => value - x[index]);
  const slopes = widths.map((width, index) =>
    (correctionStops[index + 1] - correctionStops[index]) / width);
  const tangents = Array(samples.length).fill(0);
  if (samples.length === 2) {
    tangents[0] = slopes[0];
    tangents[1] = slopes[0];
  } else {
    tangents[0] = endpointTangent(widths[0], widths[1], slopes[0], slopes[1]);
    for (let index = 1; index < samples.length - 1; index += 1) {
      const before = slopes[index - 1];
      const after = slopes[index];
      if (before !== 0 && after !== 0 && Math.sign(before) === Math.sign(after)) {
        const beforeWeight = 2 * widths[index] + widths[index - 1];
        const afterWeight = widths[index] + 2 * widths[index - 1];
        tangents[index] = (beforeWeight + afterWeight) /
          (beforeWeight / before + afterWeight / after);
      }
    }
    const last = samples.length - 1;
    tangents[last] = endpointTangent(
      widths[last - 1], widths[last - 2], slopes[last - 1], slopes[last - 2],
    );
  }
  // The preceding no-compensation section is a constant 0 EV.
  tangents[0] = 0;

  const target = Math.log2(input);
  if (target < x[0]) {
    return Math.max(input, input * 2 ** (correctionStops[0] + tangents[0] * (target - x[0])));
  }
  if (target > x.at(-1)) {
    return Math.max(input, input * 2 ** (correctionStops.at(-1) + tangents.at(-1) * (target - x.at(-1))));
  }
  const segment = x.findIndex((value) => value > target) - 1;
  const width = widths[segment];
  const ratio = (target - x[segment]) / width;
  const ratio2 = ratio * ratio;
  const ratio3 = ratio2 * ratio;
  const fittedStops = (2 * ratio3 - 3 * ratio2 + 1) * correctionStops[segment] +
    (ratio3 - 2 * ratio2 + ratio) * width * tangents[segment] +
    (-2 * ratio3 + 3 * ratio2) * correctionStops[segment + 1] +
    (ratio3 - ratio2) * width * tangents[segment + 1];
  return Math.max(input, input * 2 ** fittedStops);
}

/** Mirrors the app's runtime calculation so published nodes can be reconciled to the source CSV. */
function calculateCorrectedSeconds(method, meteredSeconds) {
  const input = Number(meteredSeconds);
  if (input <= method.noCompensationSeconds + 1e-9) return input;
  if (method.type === "POWER") return Math.max(input, input ** method.parameter);
  if (method.type === "FIXED_EV") return input * (2 ** method.parameter);
  if (method.type !== "TABLE") return null;

  const boundary = {
    meteredSeconds: method.noCompensationSeconds,
    correctedSeconds: method.noCompensationSeconds,
  };
  return fitTable([boundary, ...method.points], input);
}

const methodById = new Map(methods.map((method) => [method.id, method]));
let auditedRows = 0;
let maximumCsvEvError = 0;
let transitionBoundaryRows = 0;
for (const source of pointRows) {
  const method = methodById.get(text(source.MethodID));
  const meteredSeconds = optionalNumber(source["Tm(s)"], `Tm row ${source.__row}`);
  const sourceExpectedSeconds = optionalNumber(source["Tc(s)"], `Tc row ${source.__row}`);
  if (!method || meteredSeconds == null || sourceExpectedSeconds == null || auditedMethodIds.has(method.id)) continue;
  // A fitted reciprocity curve is never allowed to shorten the metered exposure. Some legacy
  // POWER grid rows below one second contain Tm^P < Tm; normalize those derived rows to the
  // runtime invariant rather than preserving a physically invalid negative compensation.
  const expectedSeconds = method.type === "POWER"
    ? Math.max(meteredSeconds, sourceExpectedSeconds)
    : sourceExpectedSeconds;
  // TABLE grid rows are derived from the previous piecewise interpolation. Only published nodes
  // are authoritative now that runtime uses a smooth, shape-preserving curve fit.
  if (method.type === "TABLE" && text(source.Source) !== "node") continue;
  // ACROS encodes the first corrected 120 s sample as 119.999 s so the preceding interval
  // can remain strictly "<120 s". The app exposes a nominal 120 s tick, not 119.999 s.
  if (
    Math.abs(meteredSeconds - method.noCompensationSeconds) <= 1e-9 &&
    expectedSeconds > meteredSeconds * (1 + 1e-9)
  ) {
    transitionBoundaryRows += 1;
    continue;
  }
  const calculatedSeconds = calculateCorrectedSeconds(method, meteredSeconds);
  // RANGE rows and bounded unchanged methods intentionally become unavailable after
  // their published no-compensation limit.
  if (calculatedSeconds == null) continue;
  const evError = Math.abs(Math.log2(calculatedSeconds / expectedSeconds));
  maximumCsvEvError = Math.max(maximumCsvEvError, evError);
  auditedRows += 1;
  if (evError > 0.0002) {
    throw new Error(
      `Runtime curve mismatch for ${method.id} at CSV row ${source.__row}: ` +
        `Tm=${meteredSeconds}, expected Tc=${expectedSeconds}, calculated Tc=${calculatedSeconds}, ` +
        `error=${evError} EV`,
    );
  }
}

const result = {
  schemaVersion: 2,
  sourceFiles: [path.basename(pointsInput), path.basename(databaseInput), path.basename(overridesInput)],
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
  `${JSON.stringify({
    methods: methods.length,
    films: films.length,
    points: pointCount,
    auditedRows,
    transitionBoundaryRows,
    maximumCsvEvError,
    typeCounts,
  })}\n`,
);
