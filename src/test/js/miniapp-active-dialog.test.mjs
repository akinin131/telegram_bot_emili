import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const appPath = new URL("../../main/resources/miniapp/app.js", import.meta.url);
const indexPath = new URL("../../main/resources/miniapp/index.html", import.meta.url);

class MockClassList {
  add() {}
  remove() {}
  toggle() {}
}

class MockElement {
  constructor() {
    this.classList = new MockClassList();
    this.style = { setProperty() {} };
    this.dataset = {};
    this.children = [];
    this.textContent = "";
    this.hidden = false;
    this.isConnected = true;
  }

  addEventListener() {}
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = [...children]; }
  setAttribute() {}
  removeAttribute() {}
  querySelector() { return null; }
  querySelectorAll() { return []; }
}

function createDocument() {
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, new MockElement());
    return elements.get(id);
  };
  const documentElement = element("documentElement");
  documentElement.attributes = new Map();
  documentElement.setAttribute = (name, value) => documentElement.attributes.set(name, value);

  return {
    body: element("body"),
    documentElement,
    createElement: () => new MockElement(),
    getElementById: element,
    querySelectorAll: () => [],
    elements,
  };
}

function bootstrapFixture() {
  return {
    ok: true,
    settings: {
      audiencePreference: "female",
      selectedCharacter: "alice",
      selectedStory: "alice_story",
      selectedStoryTitle: "История Алисы",
      activeDialogId: "dialog-alice",
    },
    characters: [
      { id: "alice", name: "Алиса", audience: "female", imageUrl: "alice.jpg", description: "" },
      { id: "bella", name: "Белла", audience: "female", imageUrl: "bella.jpg", description: "" },
    ],
    stories: [{ id: "alice_story", title: "История Алисы", description: "", setup: "" }],
    storiesByCharacter: {
      alice: [{ id: "alice_story", title: "История Алисы", description: "", setup: "" }],
      bella: [{ id: "bella_story", title: "История Беллы", description: "", setup: "" }],
    },
    dialogs: [{
      id: "dialog-alice",
      characterId: "alice",
      characterName: "Алиса",
      storyId: "alice_story",
      storyTitle: "История Алисы",
      recentMessages: [],
    }],
    balance: { textTokensLeft: 10, imageCreditsLeft: 2, gifCreditsLeft: 0 },
    payments: { plans: [], packs: [], gifPacks: [] },
    subscription: null,
    customStory: {},
    admin: { enabled: false },
  };
}

async function runMiniApp() {
  const source = await readFile(appPath, "utf8");
  const document = createDocument();
  const storage = new Map();
  const bootstrap = bootstrapFixture();
  const window = {
    Telegram: null,
    pageYOffset: 0,
    location: { href: "https://example.test/miniapp/" },
    open() {},
    scrollTo() {},
    requestAnimationFrame(callback) { callback(); },
    setTimeout,
    clearTimeout,
  };
  const context = vm.createContext({
    AbortController,
    Boolean,
    Date,
    Error,
    JSON,
    Map,
    Math,
    Number,
    Object,
    Promise,
    Set,
    String,
    URLSearchParams,
    console: { log() {}, warn() {}, error() {} },
    document,
    encodeURIComponent,
    fetch: async () => ({ ok: true, status: 200, text: async () => JSON.stringify(bootstrap) }),
    localStorage: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: (key) => storage.delete(key),
    },
    setTimeout,
    clearTimeout,
    window,
  });
  window.window = window;
  window.document = document;

  vm.runInContext(source, context, { filename: "app.js" });
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
  return { context, document };
}

test("opening another character does not change the active game shown in settings", async () => {
  const { context, document } = await runMiniApp();

  vm.runInContext('selectCharacter("bella"); renderSettings(); showScreen("settings");', context);

  assert.equal(document.getElementById("currentSelection").textContent, "Алиса");
  assert.equal(document.getElementById("currentStoryHint").textContent, "История Алисы");
});

test("the immutable app script URL changes whenever app.js changes", async () => {
  const [source, index] = await Promise.all([
    readFile(appPath),
    readFile(indexPath, "utf8"),
  ]);
  const expectedVersion = createHash("sha256").update(source).digest("hex").slice(0, 12);
  assert.match(index, new RegExp(`/miniapp/app\\.js\\?v=${expectedVersion}["']`));
});
