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
const customStoryRepoPath = path.join(repoRoot, "src/main/kotlin/emily/data/CustomStoryRepository.kt");

function decodeJavaProperties(value) {
  return value
    .replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t")
    .replace(/\\\\/g, "\\");
}

function parseProperties(content) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const result = {};
  let pending = "";

  for (const rawLine of lines) {
    if (!pending && (/^\s*[#!]/.test(rawLine) || rawLine.trim() === "")) {
      continue;
    }

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
    result[key] = value;
  }

  return result;
}

function extractTripleQuote(source, name) {
  const match = source.match(new RegExp(`val\\s+${name}\\s*=\\s*"""([\\s\\S]*?)"""\\.trimIndent\\(\\)`));
  return match ? match[1].trim() : "";
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
      if (!escaped && char === `"`) {
        inString = false;
      }
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

  throw new Error(`No matching parenthesis found from index ${startIndex}`);
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
      if (!escaped && char === `"`) {
        inString = false;
      }
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
    const key = item.slice(0, eq).trim();
    const value = item.slice(eq + 1).trim();
    assignments[key] = value;
  }
  return assignments;
}

function extractConstructorBlocks(source, variableRegex, constructorName) {
  const results = [];
  let match;
  const regex = new RegExp(variableRegex, "g");

  while ((match = regex.exec(source)) !== null) {
    const variableName = match[1];
    const ctorStart = source.indexOf(`${constructorName}(`, match.index);
    const openParen = source.indexOf("(", ctorStart);
    const closeParen = findMatchingParen(source, openParen);
    const body = source.slice(openParen + 1, closeParen);
    results.push({
      variableName,
      body,
      assignments: parseAssignments(body),
    });
    regex.lastIndex = closeParen;
  }

  return results;
}

function extractListBody(source, listName) {
  const marker = `val ${listName} = listOf(`;
  const start = source.indexOf(marker);
  if (start === -1) throw new Error(`List ${listName} not found`);
  const openParen = source.indexOf("(", start + marker.length - 1);
  const closeParen = findMatchingParen(source, openParen);
  return source.slice(openParen + 1, closeParen);
}

function unquoteString(expr) {
  const trimmed = expr.trim();
  if (trimmed.startsWith(`"""`) && trimmed.includes(`"""`)) {
    const inner = trimmed.slice(3, trimmed.lastIndexOf(`"""`));
    return inner.replace(/\r\n/g, "\n").trim();
  }
  if (trimmed.startsWith(`"`) && trimmed.endsWith(`"`)) {
    return JSON.parse(trimmed);
  }
  return null;
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

function evaluateExpression(expr, context) {
  const trimmed = expr.trim();
  if (splitConcat(trimmed).length > 1) {
    return splitConcat(trimmed).map((part) => evaluateExpression(part, context)).join("");
  }

  const literal = unquoteString(trimmed);
  if (literal !== null) return literal;

  const stringsMatch = trimmed.match(/^Strings\.get\("([^"]+)"\)$/);
  if (stringsMatch) return context.properties[stringsMatch[1]] ?? "";

  if (trimmed === "AudiencePreference.FEMALE") return "female";
  if (trimmed === "AudiencePreference.MALE") return "male";

  const setMatch = trimmed.match(/^setOf\((.*)\)$/s);
  if (setMatch) {
    return setMatch[1]
      .split(",")
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => part.replace(/\.id$/, ""));
  }

  if (/^[a-zA-Z_][a-zA-Z0-9_]*\.id$/.test(trimmed)) {
    return trimmed.replace(/\.id$/, "");
  }

  return trimmed;
}

function extractFunctionReturnTemplate(source, signature) {
  const start = source.indexOf(signature);
  if (start === -1) return "";
  const tripleStart = source.indexOf(`"""`, start);
  const tripleEnd = source.indexOf(`"""`, tripleStart + 3);
  return source.slice(tripleStart + 3, tripleEnd).trim();
}

function extractSingleLineString(source, regex) {
  const match = source.match(regex);
  return match ? match[1] : "";
}

function composeFinalPrompt(character, story, commonBlocks) {
  const segments = [
    character.systemPrompt.trim(),
    commonBlocks.chatFormattingRules,
    commonBlocks.autoPhotoRules,
  ];

  if (story) {
    segments.push(
      `АКТИВНАЯ ИСТОРИЯ: ${story.title}\n${story.setup}\n\n${story.systemInstructions}\n\nПравила режима истории:\n- Разыгрывай сюжет от лица ${character.name}.\n- Двигай сцену маленькими шагами: детали, события, выборы, реакции.\n- Не пересказывай всё сразу, не прыгай во времени, не действуй за пользователя.\n- Если пользователь уводит тему, мягко возвращай её в сцену.\n- Сохраняй стиль персонажа и формат живого чата.`,
    );
  }

  return segments.join("\n\n");
}

function makeSheetFrame(sheet, title, subtitle) {
  sheet.showGridLines = false;
  sheet.getRange("A1:H1").merge();
  sheet.getRange("A1").values = [[title]];
  sheet.getRange("A2:H2").merge();
  sheet.getRange("A2").values = [[subtitle]];

  sheet.getRange("A1:H2").format.fill = { color: "#F6E7E1" };
  sheet.getRange("A1").format.font = { bold: true, size: 16, color: "#6B3E2E" };
  sheet.getRange("A2").format.font = { italic: true, color: "#7A5C4F" };
}

function writeTable(sheet, startCell, rows) {
  const colCount = rows[0].length;
  const rowCount = rows.length;
  const startCol = startCell.match(/[A-Z]+/)[0];
  const startRow = Number(startCell.match(/\d+/)[0]);
  const endColIndex = columnToIndex(startCol) + colCount - 1;
  const endCol = indexToColumn(endColIndex);
  const endRow = startRow + rowCount - 1;
  const range = sheet.getRange(`${startCell}:${endCol}${endRow}`);
  range.values = rows;
  return range;
}

function columnToIndex(column) {
  return column.split("").reduce((acc, char) => acc * 26 + char.charCodeAt(0) - 64, 0);
}

function indexToColumn(index) {
  let n = index;
  let result = "";
  while (n > 0) {
    const rem = (n - 1) % 26;
    result = String.fromCharCode(65 + rem) + result;
    n = Math.floor((n - 1) / 26);
  }
  return result;
}

function styleHeader(range) {
  range.format.font = { bold: true, color: "#FFFFFF" };
  range.format.fill = { color: "#A85C4D" };
  range.format.wrapText = true;
  range.format.borders = { preset: "all", style: "thin", color: "#E7D3CD" };
}

function styleBody(range) {
  range.format.verticalAlignment = "top";
  range.format.wrapText = true;
  range.format.borders = { preset: "all", style: "thin", color: "#E7D3CD" };
}

const properties = parseProperties(await fs.readFile(stringsPath, "utf8"));
const catalogSource = await fs.readFile(catalogPath, "utf8");
const botSource = await fs.readFile(botPath, "utf8");
const customStorySource = await fs.readFile(customStoryRepoPath, "utf8");

const context = { properties };
const commonBlocks = {
  chatFormattingRules: extractTripleQuote(catalogSource, "chatFormattingRules"),
  autoPhotoRules: extractTripleQuote(catalogSource, "autoPhotoRules"),
  composeStoryRules: `Правила режима истории:
- Разыгрывай сюжет от лица {characterName}.
- Двигай сцену маленькими шагами: детали, события, выборы, реакции.
- Не пересказывай всё сразу, не прыгай во времени, не действуй за пользователя.
- Если пользователь уводит тему, мягко возвращай её в сцену.
- Сохраняй стиль персонажа и формат живого чата.`,
};

const characters = extractConstructorBlocks(
  catalogSource,
  String.raw`val\s+(\w+)\s*=\s*CharacterProfile\(`,
  "CharacterProfile",
).map(({ variableName, assignments }) => ({
  variableName,
  id: evaluateExpression(assignments.id, context),
  audience: evaluateExpression(assignments.audience, context),
  name: evaluateExpression(assignments.name, context),
  shortDescription: evaluateExpression(assignments.shortDescription, context),
  selectionPhotoUrl: evaluateExpression(assignments.selectionPhotoUrl, context),
  welcomePhotoUrl: evaluateExpression(assignments.welcomePhotoUrl, context),
  welcomePhotoFileId: assignments.welcomePhotoFileId ? evaluateExpression(assignments.welcomePhotoFileId, context) : "",
  systemPrompt: evaluateExpression(assignments.systemPrompt, context),
  imagePersona: evaluateExpression(assignments.imagePersona, context),
  startDialogSeed: evaluateExpression(assignments.startDialogSeed, context),
}));

const stories = extractConstructorBlocks(
  extractListBody(catalogSource, "stories"),
  String.raw`(StoryScenario)\(`,
  "StoryScenario",
).map(({ assignments }, index) => ({
  order: index + 1,
  id: evaluateExpression(assignments.id, context),
  title: evaluateExpression(assignments.title, context),
  shortDescription: evaluateExpression(assignments.shortDescription, context),
  setup: evaluateExpression(assignments.setup, context),
  systemInstructions: evaluateExpression(assignments.systemInstructions, context),
  openingLine: evaluateExpression(assignments.openingLine, context),
  characterIds: evaluateExpression(assignments.characterIds, context),
}));

const imagePrompts = {
  femaleSubjectDirective: `Subject: exactly one adult female character, {character.name}; keep identity/persona.
Required tags: 1girl, female focus, adult woman, feminine face, feminine body.
Never output male/minor tags: 1boy, boy, man, male, beard, stubble, suit, child, teen.`,
  maleSubjectDirective: `Subject: exactly one adult male character, {character.name}; keep identity/persona.
Required tags: 1boy, male focus, adult man, mature male, masculine face, masculine body.
Never output female/minor tags: 1girl, girl, woman, female, breasts, dress, skirt, lingerie, child, teen.`,
  imagePromptSystemTemplate: extractFunctionReturnTemplate(botSource, "private fun imagePromptSystem(character: CharacterProfile): String ="),
  scenePromptSystemTemplate: extractFunctionReturnTemplate(botSource, "private fun scenePromptSystem(character: CharacterProfile): String ="),
  femaleExample: extractSingleLineString(botSource, /else\s*->\s*\n?\s*"([^"]+)"/),
  maleExample: extractSingleLineString(botSource, /AudiencePreference\.MALE\s*->\s*\n?\s*"([^"]+)"/),
};

const customStoryNotes = [
  {
    field: "title",
    source: "Mini App custom story form",
    details: "Название пользовательской истории, max 60.",
  },
  {
    field: "shortDescription",
    source: "Mini App custom story form",
    details: "Короткое описание для карточки, max 160.",
  },
  {
    field: "setup",
    source: "Mini App custom story form / repository",
    details: "Сцена и правила истории, max 900. В коде для custom story это же поле уходит и в setup, и в systemInstructions.",
  },
  {
    field: "openingLine",
    source: "Mini App custom story form / repository",
    details: "Первое сообщение персонажа, max 240.",
  },
];

const workbook = Workbook.create();
const summarySheet = workbook.worksheets.add("Summary");
const charactersSheet = workbook.worksheets.add("Characters");
const storiesSheet = workbook.worksheets.add("Stories");
const blocksSheet = workbook.worksheets.add("Prompt Blocks");
const imageSheet = workbook.worksheets.add("Image Prompts");
const finalSheet = workbook.worksheets.add("Final Prompts");
const customSheet = workbook.worksheets.add("Custom Stories");

makeSheetFrame(
  summarySheet,
  "Emily Bot Prompts Export",
  "Сводный Excel по персонажам, историям и итоговым system prompts из текущего репозитория.",
);

const summaryRows = [
  ["Раздел", "Количество / значение", "Комментарий"],
  ["Персонажи", characters.length, "Профили и их базовые системные промты"],
  ["Истории", stories.length, "Сюжеты с setup, systemInstructions и openingLine"],
  ["Prompt-блоки", 3, "Общие правила чата/фото и story-mode блок"],
  ["Итоговые prompt-комбинации", characters.length + stories.length, "Свободный чат для каждого персонажа + сюжетный режим для каждой истории"],
  ["Источник 1", catalogPath, "Основной каталог персонажей и историй"],
  ["Источник 2", botPath, "Промты для генерации изображений и сцены"],
  ["Источник 3", stringsPath, "Строковые значения system.prompt.default и persona.default"],
  ["Источник 4", customStoryRepoPath, "Логика пользовательских историй"],
];
styleHeader(writeTable(summarySheet, "A4", [summaryRows[0]]));
const summaryBody = writeTable(summarySheet, "A5", summaryRows.slice(1));
styleBody(summaryBody);
summarySheet.getRange("A:A").format.columnWidth = 24;
summarySheet.getRange("B:B").format.columnWidth = 42;
summarySheet.getRange("C:C").format.columnWidth = 56;
summarySheet.freezePanes.freezeRows(4);

makeSheetFrame(charactersSheet, "Characters", "Базовые персонажи и все их prompt-related поля.");
const characterHeader = [[
  "id",
  "audience",
  "name",
  "shortDescription",
  "systemPrompt",
  "imagePersona",
  "startDialogSeed",
  "selectionPhotoUrl",
  "welcomePhotoUrl",
  "welcomePhotoFileId",
  "source",
]];
const characterRows = characters.map((item) => [
  item.id,
  item.audience,
  item.name,
  item.shortDescription,
  item.systemPrompt,
  item.imagePersona,
  item.startDialogSeed,
  item.selectionPhotoUrl,
  item.welcomePhotoUrl,
  item.welcomePhotoFileId,
  "BotCatalog.kt / strings.properties",
]);
styleHeader(writeTable(charactersSheet, "A4", characterHeader));
const characterBody = writeTable(charactersSheet, "A5", characterRows);
styleBody(characterBody);
["A","B","C","D","E","F","G","H","I","J","K"].forEach((col, index) => {
  const widths = [14,12,14,30,55,55,18,34,34,24,28];
  charactersSheet.getRange(`${col}:${col}`).format.columnWidth = widths[index];
});
charactersSheet.freezePanes.freezeRows(4);

makeSheetFrame(storiesSheet, "Stories", "Все предустановленные сюжетные истории и их prompt-поля.");
const storiesHeader = [[
  "id",
  "title",
  "characterIds",
  "shortDescription",
  "setup",
  "systemInstructions",
  "openingLine",
  "source",
]];
const storiesRows = stories.map((item) => [
  item.id,
  item.title,
  Array.isArray(item.characterIds) ? item.characterIds.join(", ") : String(item.characterIds || ""),
  item.shortDescription,
  item.setup,
  item.systemInstructions,
  item.openingLine,
  "BotCatalog.kt",
]);
styleHeader(writeTable(storiesSheet, "A4", storiesHeader));
const storiesBody = writeTable(storiesSheet, "A5", storiesRows);
styleBody(storiesBody);
["A","B","C","D","E","F","G","H"].forEach((col, index) => {
  const widths = [18,28,18,32,56,62,48,20];
  storiesSheet.getRange(`${col}:${col}`).format.columnWidth = widths[index];
});
storiesSheet.freezePanes.freezeRows(4);

makeSheetFrame(blocksSheet, "Prompt Blocks", "Общие reusable-блоки, которые подмешиваются в system prompt.");
const blockRows = [
  ["blockName", "usedIn", "text", "source"],
  ["chatFormattingRules", "composeSystemPrompt()", commonBlocks.chatFormattingRules, "BotCatalog.kt"],
  ["autoPhotoRules", "composeSystemPrompt()", commonBlocks.autoPhotoRules, "BotCatalog.kt"],
  ["storyModeRulesTemplate", "composeSystemPrompt() when story != null", commonBlocks.composeStoryRules, "BotCatalog.kt"],
  ["system.prompt.default", "Emily base systemPrompt", properties["system.prompt.default"] || "", "strings.properties"],
  ["persona.default", "Emily imagePersona", properties["persona.default"] || "", "strings.properties"],
];
styleHeader(writeTable(blocksSheet, "A4", [blockRows[0]]));
const blocksBody = writeTable(blocksSheet, "A5", blockRows.slice(1));
styleBody(blocksBody);
["A","B","C","D"].forEach((col, index) => {
  const widths = [24,34,88,24];
  blocksSheet.getRange(`${col}:${col}`).format.columnWidth = widths[index];
});
blocksSheet.freezePanes.freezeRows(4);

makeSheetFrame(imageSheet, "Image Prompts", "Шаблоны для prompt-генерации изображений и сцен из диалога.");
const imageRows = [
  ["name", "audience", "template", "source"],
  ["imageSubjectDirective", "female", imagePrompts.femaleSubjectDirective, "EmilyVirtualGirlBot.kt"],
  ["imageSubjectDirective", "male", imagePrompts.maleSubjectDirective, "EmilyVirtualGirlBot.kt"],
  ["imagePromptSystem", "all", imagePrompts.imagePromptSystemTemplate, "EmilyVirtualGirlBot.kt"],
  ["scenePromptSystem", "all", imagePrompts.scenePromptSystemTemplate, "EmilyVirtualGirlBot.kt"],
  ["imagePromptExample", "female", imagePrompts.femaleExample, "EmilyVirtualGirlBot.kt"],
  ["imagePromptExample", "male", imagePrompts.maleExample, "EmilyVirtualGirlBot.kt"],
];
styleHeader(writeTable(imageSheet, "A4", [imageRows[0]]));
const imageBody = writeTable(imageSheet, "A5", imageRows.slice(1));
styleBody(imageBody);
["A","B","C","D"].forEach((col, index) => {
  const widths = [24,14,90,24];
  imageSheet.getRange(`${col}:${col}`).format.columnWidth = widths[index];
});
imageSheet.freezePanes.freezeRows(4);

makeSheetFrame(finalSheet, "Final Prompts", "Готовые собранные system prompt'ы: отдельно свободный чат и отдельно режим истории.");
const finalRows = [["mode", "characterId", "characterName", "storyId", "storyTitle", "openingLine", "finalSystemPrompt"]];
for (const character of characters) {
  finalRows.push([
    "free_chat",
    character.id,
    character.name,
    "",
    "",
    "",
    composeFinalPrompt(character, null, commonBlocks),
  ]);
}
for (const story of stories) {
  const character = characters.find((item) =>
    Array.isArray(story.characterIds) ? story.characterIds.includes(item.id) : false,
  );
  if (!character) continue;
  finalRows.push([
    "story_mode",
    character.id,
    character.name,
    story.id,
    story.title,
    story.openingLine.replaceAll("{character}", character.name),
    composeFinalPrompt(character, story, commonBlocks),
  ]);
}
styleHeader(writeTable(finalSheet, "A4", [finalRows[0]]));
const finalBody = writeTable(finalSheet, "A5", finalRows.slice(1));
styleBody(finalBody);
["A","B","C","D","E","F","G"].forEach((col, index) => {
  const widths = [14,14,18,22,28,46,96];
  finalSheet.getRange(`${col}:${col}`).format.columnWidth = widths[index];
});
finalSheet.freezePanes.freezeRows(4);

makeSheetFrame(customSheet, "Custom Stories", "Как пользовательские истории задаются в Mini App и как превращаются в StoryScenario.");
const customRows = [
  ["field", "source", "details"],
  ...customStoryNotes.map((item) => [item.field, item.source, item.details]),
  ["toScenario.systemInstructions", "CustomStoryRepository.kt", "Для custom story поле systemInstructions заполняется тем же текстом, что и setup."],
  ["fallback openingLine", "CustomStoryRepository.kt", "Если openingLine пустой, подставляется: Начнём твою историю?"],
  ["form placeholders", "miniapp/app.compat.js", "title: Ночная поездка; shortDescription: описание карточки; setup: где вы, что происходит, какая роль у персонажа; openingLine: фраза, с которой начнётся чат."],
];
styleHeader(writeTable(customSheet, "A4", [customRows[0]]));
const customBody = writeTable(customSheet, "A5", customRows.slice(1));
styleBody(customBody);
["A","B","C"].forEach((col, index) => {
  const widths = [26,34,92];
  customSheet.getRange(`${col}:${col}`).format.columnWidth = widths[index];
});
customSheet.freezePanes.freezeRows(4);

for (const sheet of workbook.worksheets.items) {
  const used = sheet.getUsedRange();
  if (used) {
    used.format.autofitRows();
  }
}

await fs.mkdir(outputDir, { recursive: true });
const outputPath = path.join(outputDir, "emily_bot_prompts.xlsx");
const exported = await SpreadsheetFile.exportXlsx(workbook);
await exported.save(outputPath);

const inspect = await workbook.inspect({
  kind: "sheet,table",
  maxChars: 4000,
  tableMaxRows: 8,
  tableMaxCols: 8,
});

const previewSheetNames = ["Summary", "Characters", "Stories", "Final Prompts"];
for (const name of previewSheetNames) {
  const preview = await workbook.render({ sheetName: name, autoCrop: "all", scale: 1.6, format: "png" });
  const previewPath = path.join(outputDir, `${name.toLowerCase().replace(/\s+/g, "_")}.png`);
  await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
}

console.log(JSON.stringify({
  outputPath,
  inspect: inspect.ndjson,
  characters: characters.length,
  stories: stories.length,
  finalPrompts: finalRows.length - 1,
  customSourceLoaded: customStorySource.includes("toScenario"),
}, null, 2));
