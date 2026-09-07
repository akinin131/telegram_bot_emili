import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "..");
const outputDir = path.join(repoRoot, "outputs", "prompt_export");

const stringsPath = path.join(repoRoot, "src/main/resources/strings.properties");
const catalogPath = path.join(repoRoot, "src/main/kotlin/emily/domain/BotCatalog.kt");
const botPath = path.join(repoRoot, "src/main/kotlin/emily/bot/EmilyVirtualGirlBot.kt");
const miniAppPath = path.join(repoRoot, "src/main/resources/miniapp/app.compat.js");

function decodeJavaProperties(value) {
  return value
    .replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t")
    .replace(/\\\\/g, "\\");
}

function parseProperties(content) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const result = [];
  let pending = "";

  for (const rawLine of lines) {
    if (!pending && (/^\s*[#!]/.test(rawLine) || rawLine.trim() === "")) continue;
    let line = pending + rawLine;
    if (line.endsWith("\\") && !line.endsWith("\\\\")) {
      pending = line.slice(0, -1);
      continue;
    }
    pending = "";
    const separatorIndex = line.search(/[:=]/);
    if (separatorIndex === -1) continue;
    const key = line.slice(0, separatorIndex).trim();
    const value = decodeJavaProperties(line.slice(separatorIndex + 1).trim());
    result.push({ key, value });
  }

  return result;
}

function findMatchingParen(source, startIndex) {
  let depth = 0;
  let i = startIndex;
  let inString = false;
  let inTripleString = false;
  let escaped = false;

  while (i < source.length) {
    const char = source[i];
    const next3 = source.slice(i, i + 3);

    if (inTripleString) {
      if (next3 === `"""`) {
        inTripleString = false;
        i += 3;
        continue;
      }
      i += 1;
      continue;
    }

    if (inString) {
      if (!escaped && char === `"`) inString = false;
      escaped = !escaped && char === "\\";
      i += 1;
      continue;
    }

    if (next3 === `"""`) {
      inTripleString = true;
      i += 3;
      continue;
    }

    if (char === `"`) {
      inString = true;
      escaped = false;
      i += 1;
      continue;
    }

    if (char === "(") depth += 1;
    if (char === ")") {
      depth -= 1;
      if (depth === 0) return i;
    }
    i += 1;
  }

  throw new Error(`No matching parenthesis found from ${startIndex}`);
}

function extractListBody(source, listName) {
  const marker = `val ${listName} = listOf(`;
  const start = source.indexOf(marker);
  if (start === -1) throw new Error(`List ${listName} not found`);
  const openParen = source.indexOf("(", start + marker.length - 1);
  const closeParen = findMatchingParen(source, openParen);
  return source.slice(openParen + 1, closeParen);
}

function splitTopLevelComma(text) {
  const parts = [];
  let current = "";
  let depthParen = 0;
  let depthBrace = 0;
  let inString = false;
  let inTripleString = false;
  let escaped = false;

  for (let i = 0; i < text.length; i += 1) {
    const char = text[i];
    const next3 = text.slice(i, i + 3);

    if (inTripleString) {
      current += char;
      if (next3 === `"""`) {
        current += text[i + 1] + text[i + 2];
        i += 2;
        inTripleString = false;
      }
      continue;
    }

    if (inString) {
      current += char;
      if (!escaped && char === `"`) inString = false;
      escaped = !escaped && char === "\\";
      continue;
    }

    if (next3 === `"""`) {
      current += next3;
      i += 2;
      inTripleString = true;
      continue;
    }

    if (char === `"`) {
      current += char;
      inString = true;
      escaped = false;
      continue;
    }

    if (char === "(") depthParen += 1;
    if (char === ")") depthParen -= 1;
    if (char === "{") depthBrace += 1;
    if (char === "}") depthBrace -= 1;

    if (char === "," && depthParen === 0 && depthBrace === 0) {
      if (current.trim()) parts.push(current.trim());
      current = "";
      continue;
    }

    current += char;
  }

  if (current.trim()) parts.push(current.trim());
  return parts;
}

function parseAssignments(text) {
  const assignments = {};
  for (const item of splitTopLevelComma(text)) {
    const eq = item.indexOf("=");
    if (eq === -1) continue;
    assignments[item.slice(0, eq).trim()] = item.slice(eq + 1).trim();
  }
  return assignments;
}

function extractConstructorBlocksFromList(source, listName, constructorName) {
  const listBody = extractListBody(source, listName);
  const results = [];
  let searchIndex = 0;
  while (true) {
    const ctorStart = listBody.indexOf(`${constructorName}(`, searchIndex);
    if (ctorStart === -1) break;
    const openParen = listBody.indexOf("(", ctorStart);
    const closeParen = findMatchingParen(listBody, openParen);
    results.push(parseAssignments(listBody.slice(openParen + 1, closeParen)));
    searchIndex = closeParen + 1;
  }
  return results;
}

function splitConcat(expr) {
  const parts = [];
  let current = "";
  let inString = false;
  let inTripleString = false;
  let escaped = false;

  for (let i = 0; i < expr.length; i += 1) {
    const char = expr[i];
    const next3 = expr.slice(i, i + 3);

    if (inTripleString) {
      current += char;
      if (next3 === `"""`) {
        current += expr[i + 1] + expr[i + 2];
        i += 2;
        inTripleString = false;
      }
      continue;
    }

    if (inString) {
      current += char;
      if (!escaped && char === `"`) inString = false;
      escaped = !escaped && char === "\\";
      continue;
    }

    if (next3 === `"""`) {
      current += next3;
      i += 2;
      inTripleString = true;
      continue;
    }

    if (char === `"`) {
      current += char;
      inString = true;
      escaped = false;
      continue;
    }

    if (char === "+") {
      if (current.trim()) parts.push(current.trim());
      current = "";
      continue;
    }

    current += char;
  }

  if (current.trim()) parts.push(current.trim());
  return parts;
}

function unquoteString(expr) {
  const trimmed = expr.trim();
  if (trimmed.startsWith(`"""`) && trimmed.includes(`"""`)) {
    return trimmed.slice(3, trimmed.lastIndexOf(`"""`)).trim();
  }
  if (trimmed.startsWith(`"`) && trimmed.endsWith(`"`)) {
    return JSON.parse(trimmed);
  }
  return null;
}

function evaluateExpression(expr, stringsMap) {
  if (!expr) return "";
  const trimmed = expr.trim();
  const concatParts = splitConcat(trimmed);
  if (concatParts.length > 1) {
    return concatParts.map((part) => evaluateExpression(part, stringsMap)).join("");
  }

  const literal = unquoteString(trimmed);
  if (literal !== null) return literal;

  const stringsMatch = trimmed.match(/^Strings\.get\("([^"]+)"\)$/);
  if (stringsMatch) return stringsMap.get(stringsMatch[1]) ?? "";

  const setMatch = trimmed.match(/^setOf\((.*)\)$/s);
  if (setMatch) {
    return setMatch[1]
      .split(",")
      .map((part) => part.trim().replace(/\.id$/, ""))
      .filter(Boolean)
      .join(", ");
  }

  if (/^[a-zA-Z_][a-zA-Z0-9_]*\.id$/.test(trimmed)) {
    return trimmed.replace(/\.id$/, "");
  }

  return trimmed;
}

function extractTripleQuote(source, name) {
  const match = source.match(new RegExp(`val\\s+${name}\\s*=\\s*"""([\\s\\S]*?)"""\\.trimIndent\\(\\)`));
  return match ? match[1].trim() : "";
}

function extractFunctionPromptTemplate(source, functionName) {
  const start = source.indexOf(`private fun ${functionName}(character: CharacterProfile): String =`);
  if (start === -1) return "";
  const tripleStart = source.indexOf(`"""`, start);
  const tripleEnd = source.indexOf(`"""`, tripleStart + 3);
  return source.slice(tripleStart + 3, tripleEnd).trim();
}

function extractLine(source, regex) {
  const match = source.match(regex);
  return match ? match[1] : "";
}

function styleSheet(sheet, title, subtitle, headers, rows, widths) {
  sheet.showGridLines = false;
  sheet.getRange(`A1:${String.fromCharCode(64 + headers.length)}1`).merge();
  sheet.getRange("A1").values = [[title]];
  sheet.getRange(`A2:${String.fromCharCode(64 + headers.length)}2`).merge();
  sheet.getRange("A2").values = [[subtitle]];
  sheet.getRange(`A1:${String.fromCharCode(64 + headers.length)}2`).format.fill = { color: "#F4E8E2" };
  sheet.getRange("A1").format.font = { bold: true, size: 16, color: "#6C4030" };
  sheet.getRange("A2").format.font = { italic: true, color: "#7E6257" };

  const allRows = [headers, ...rows];
  const endCol = String.fromCharCode(64 + headers.length);
  const endRow = allRows.length + 3;
  const range = sheet.getRange(`A4:${endCol}${endRow}`);
  range.values = allRows;
  sheet.getRange(`A4:${endCol}4`).format.fill = { color: "#A86052" };
  sheet.getRange(`A4:${endCol}4`).format.font = { bold: true, color: "#FFFFFF" };
  sheet.getRange(`A4:${endCol}${endRow}`).format.wrapText = true;
  sheet.getRange(`A4:${endCol}${endRow}`).format.verticalAlignment = "top";
  sheet.getRange(`A4:${endCol}${endRow}`).format.borders = { preset: "all", style: "thin", color: "#E7D5CE" };

  widths.forEach((width, index) => {
    const col = String.fromCharCode(65 + index);
    sheet.getRange(`${col}:${col}`).format.columnWidth = width;
  });

  const used = sheet.getUsedRange();
  if (used) used.format.autofitRows();
  sheet.freezePanes.freezeRows(4);
}

const stringsContent = await fs.readFile(stringsPath, "utf8");
const catalogSource = await fs.readFile(catalogPath, "utf8");
const botSource = await fs.readFile(botPath, "utf8");
const miniAppSource = await fs.readFile(miniAppPath, "utf8");

const stringEntries = parseProperties(stringsContent);
const stringsMap = new Map(stringEntries.map((item) => [item.key, item.value]));

const characterAssignments = extractConstructorBlocksFromList(catalogSource, "characters", "CharacterProfile");
const storyAssignments = extractConstructorBlocksFromList(catalogSource, "stories", "StoryScenario");

const rawPromptRows = [];

for (const item of characterAssignments) {
  const characterId = evaluateExpression(item.id, stringsMap);
  rawPromptRows.push(["character", characterId, "name", evaluateExpression(item.name, stringsMap), catalogPath]);
  rawPromptRows.push(["character", characterId, "shortDescription", evaluateExpression(item.shortDescription, stringsMap), catalogPath]);
  rawPromptRows.push(["character", characterId, "systemPrompt", evaluateExpression(item.systemPrompt, stringsMap), catalogPath]);
  rawPromptRows.push(["character", characterId, "imagePersona", evaluateExpression(item.imagePersona, stringsMap), catalogPath]);
  rawPromptRows.push(["character", characterId, "startDialogSeed", evaluateExpression(item.startDialogSeed, stringsMap), catalogPath]);
}

for (const item of storyAssignments) {
  const storyId = evaluateExpression(item.id, stringsMap);
  rawPromptRows.push(["story", storyId, "title", evaluateExpression(item.title, stringsMap), catalogPath]);
  rawPromptRows.push(["story", storyId, "shortDescription", evaluateExpression(item.shortDescription, stringsMap), catalogPath]);
  rawPromptRows.push(["story", storyId, "setup", evaluateExpression(item.setup, stringsMap), catalogPath]);
  rawPromptRows.push(["story", storyId, "systemInstructions", evaluateExpression(item.systemInstructions, stringsMap), catalogPath]);
  rawPromptRows.push(["story", storyId, "openingLine", evaluateExpression(item.openingLine, stringsMap), catalogPath]);
  rawPromptRows.push(["story", storyId, "characterIds", evaluateExpression(item.characterIds, stringsMap), catalogPath]);
}

rawPromptRows.push(["shared_block", "global", "chatFormattingRules", extractTripleQuote(catalogSource, "chatFormattingRules"), catalogPath]);
rawPromptRows.push(["shared_block", "global", "autoPhotoRules", extractTripleQuote(catalogSource, "autoPhotoRules"), catalogPath]);
rawPromptRows.push(["shared_block", "global", "system.prompt.default", stringsMap.get("system.prompt.default") ?? "", stringsPath]);
rawPromptRows.push(["shared_block", "global", "persona.default", stringsMap.get("persona.default") ?? "", stringsPath]);
rawPromptRows.push(["image_prompt", "global", "imagePromptSystem", extractFunctionPromptTemplate(botSource, "imagePromptSystem"), botPath]);
rawPromptRows.push(["image_prompt", "global", "scenePromptSystem", extractFunctionPromptTemplate(botSource, "scenePromptSystem"), botPath]);
rawPromptRows.push(["image_prompt", "male", "imagePromptExample", extractLine(botSource, /AudiencePreference\.MALE\s*->\s*\n?\s*"([^"]+)"/), botPath]);
rawPromptRows.push(["image_prompt", "female", "imagePromptExample", extractLine(botSource, /else\s*->\s*\n?\s*"([^"]+)"/), botPath]);
rawPromptRows.push(["miniapp_custom_story", "global", "title_placeholder", extractLine(miniAppSource, /name="title" maxlength="60" placeholder="([^"]+)"/), miniAppPath]);
rawPromptRows.push(["miniapp_custom_story", "global", "shortDescription_placeholder", extractLine(miniAppSource, /name="description" maxlength="160" placeholder="([^"]+)"/), miniAppPath]);
rawPromptRows.push(["miniapp_custom_story", "global", "setup_placeholder", extractLine(miniAppSource, /name="setup" maxlength="900" rows="5" placeholder="([^"]+)"/), miniAppPath]);
rawPromptRows.push(["miniapp_custom_story", "global", "openingLine_placeholder", extractLine(miniAppSource, /name="openingLine" maxlength="240" rows="3" placeholder="([^"]+)"/), miniAppPath]);

const workbook = Workbook.create();
const rawSheet = workbook.worksheets.add("Raw Prompts");
const stringsSheet = workbook.worksheets.add("All Strings");

styleSheet(
  rawSheet,
  "Raw Prompt Texts",
  "Прямой дамп всех найденных prompt/story текстов из кода.",
  ["group", "id", "field", "text", "source"],
  rawPromptRows,
  [18, 20, 24, 110, 50],
);

styleSheet(
  stringsSheet,
  "All strings.properties",
  "Полная выгрузка текстовых ключей из strings.properties.",
  ["key", "value", "source"],
  stringEntries.map((item) => [item.key, item.value, stringsPath]),
  [34, 110, 46],
);

await fs.mkdir(outputDir, { recursive: true });
const outputPath = path.join(outputDir, "raw_prompt_texts.xlsx");
const out = await SpreadsheetFile.exportXlsx(workbook);
await out.save(outputPath);

console.log(JSON.stringify({
  outputPath,
  rawPromptRows: rawPromptRows.length,
  stringRows: stringEntries.length,
}, null, 2));
