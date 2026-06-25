const tg = window.Telegram && window.Telegram.WebApp;
const DEFAULT_BOT_URL = "https://t.me/you_emily_bot";
const BOOTSTRAP_CACHE_KEY = "emily:miniappBootstrap:v2";
const GALLERY_CACHE_KEY = "emily:validatedGalleries:v1";
const PENDING_AUDIENCE_KEY = "emily:pendingAudience";

document.documentElement.setAttribute("data-miniapp-boot", "started");
window.__miniappBootState = "started";
console.log("[audit] app.js loaded");

const state = {
  bootstrap: null,
  currentScreen: "characters",
  settingsPaymentTab: "plans",
  selectedCharacterId: null,
  previewCharacterId: null,
  audiencePreference: null,
  galleryCharacterId: null,
  galleryImages: [],
  galleryIndex: 0,
  galleryByCharacter: {},
  galleryRequests: {},
  galleryValidationRequests: {},
  brokenGalleryImageIds: new Set(),
  lastNonSettingsScreen: "characters",
  finishTimer: null,
  pendingCharacterRequestId: 0,
  pendingAudienceSwitchRequestId: 0,
  pendingAudienceTarget: null,
  pendingStorySelectionKey: null,
  storiesByCharacter: {},
};

const els = {
  screenTitle: document.getElementById("screenTitle"),
  statusPanel: document.getElementById("statusPanel"),
  balanceText: document.getElementById("balanceText"),
  tokenBalanceText: document.getElementById("tokenBalanceText"),
  tokenPlanText: document.getElementById("tokenPlanText"),
  currentStoryHint: document.getElementById("currentStoryHint"),
  paymentOptions: document.getElementById("paymentOptions"),
  preferenceScreen: document.getElementById("preferenceScreen"),
  audienceSettings: document.getElementById("audienceSettings"),
  charactersScreen: document.getElementById("charactersScreen"),
  storiesScreen: document.getElementById("storiesScreen"),
  dialogsScreen: document.getElementById("dialogsScreen"),
  galleryScreen: document.getElementById("galleryScreen"),
  settingsScreen: document.getElementById("settingsScreen"),
  loadingScreen: document.getElementById("loadingScreen"),
  charactersGrid: document.getElementById("charactersGrid"),
  storiesList: document.getElementById("storiesList"),
  dialogsList: document.getElementById("dialogsList"),
  selectedCharacterPanel: document.getElementById("selectedCharacterPanel"),
  skipStoryButton: document.getElementById("skipStoryButton"),
  backToCharacters: document.getElementById("backToCharacters"),
  backFromGallery: document.getElementById("backFromGallery"),
  galleryHeader: document.getElementById("galleryHeader"),
  galleryGrid: document.getElementById("galleryGrid"),
  galleryViewer: document.getElementById("galleryViewer"),
  closeGalleryViewer: document.getElementById("closeGalleryViewer"),
  prevGalleryImage: document.getElementById("prevGalleryImage"),
  nextGalleryImage: document.getElementById("nextGalleryImage"),
  galleryViewerImage: document.getElementById("galleryViewerImage"),
  galleryViewerMeta: document.getElementById("galleryViewerMeta"),
  backFromSettings: document.getElementById("backFromSettings"),
  currentSelection: document.getElementById("currentSelection"),
  navPills: document.querySelectorAll("[data-target]"),
};

const screenTitles = {
  preference: "Выбор",
  characters: "Персонажи",
  stories: "Истории",
  dialogs: "Диалоги",
  gallery: "Галерея",
  settings: "Настройки",
};

try {
  document.documentElement.setAttribute("data-miniapp-stage", "init");
  initTelegram();
  document.documentElement.setAttribute("data-miniapp-stage", "bind");
  bindEvents();
  document.documentElement.setAttribute("data-miniapp-stage", "bootstrap");
  hydrateCachedGalleries();
  hydrateCachedBootstrap();
  loadBootstrap();
} catch (error) {
  document.documentElement.setAttribute("data-miniapp-stage", "crash");
  document.documentElement.setAttribute("data-miniapp-error", String(error && error.message || error));
  throw error;
}

function initTelegram() {
  if (!tg) return;

  safeTelegramCall(() => tg.ready());
  safeTelegramCall(() => tg.expand());
  safeTelegramCall(() => {
    if (typeof tg.setBackgroundColor === "function") tg.setBackgroundColor("#0b0b0f");
  });
  safeTelegramCall(() => {
    if (typeof tg.setHeaderColor === "function") tg.setHeaderColor("#0b0b0f");
  });

  const theme = tg.themeParams || {};
  if (theme.text_color) document.documentElement.style.setProperty("--text", theme.text_color);
  if (theme.hint_color) document.documentElement.style.setProperty("--muted", theme.hint_color);
  if (theme.button_color) document.documentElement.style.setProperty("--accent", theme.button_color);

  if (tg.BackButton && typeof tg.BackButton.onClick === "function") {
    safeTelegramCall(() => tg.BackButton.onClick(() => {
      if (state.currentScreen === "settings") {
        showScreen(state.lastNonSettingsScreen);
        return;
      }
      if (state.currentScreen === "gallery") {
        showScreen("characters");
        return;
      }
      if (state.currentScreen === "stories") {
        showScreen("characters");
      }
    }));
  }
}

function bindEvents() {
  els.navPills.forEach((button) => {
    button.addEventListener("click", () => {
      const target = button.dataset.target || "characters";
      if (!hasAudiencePreference()) {
        showScreen("preference");
        return;
      }
      if (target === "settings") {
        state.lastNonSettingsScreen = state.currentScreen === "settings" ? "characters" : state.currentScreen;
        renderSettings();
      }
      showScreen(target);
    });
  });

  document.querySelectorAll("[data-audience-choice]").forEach((button) => {
    button.addEventListener("click", () => selectAudience(button.dataset.audienceChoice));
  });
  on(els.backToCharacters, "click", () => showScreen("characters"));
  on(els.backFromGallery, "click", () => showScreen("characters"));
  on(els.backFromSettings, "click", () => showScreen(state.lastNonSettingsScreen));
  on(els.skipStoryButton, "click", skipStory);
  on(els.closeGalleryViewer, "click", closeGalleryViewer);
  on(els.prevGalleryImage, "click", () => showGalleryImage(state.galleryIndex - 1));
  on(els.nextGalleryImage, "click", () => showGalleryImage(state.galleryIndex + 1));
  on(els.galleryViewerImage, "error", handleGalleryViewerError);
  on(els.galleryViewer, "click", (event) => {
    if (event.target === els.galleryViewer) closeGalleryViewer();
  });
}

function on(element, eventName, handler) {
  if (element) {
    element.addEventListener(eventName, handler);
  }
}

async function loadBootstrap(nextScreen = null) {
  try {
    const data = await api("/miniapp/api/bootstrap");
    const targetScreen = nextScreen || state.currentScreen || "characters";
    cacheBootstrap(data);
    state.bootstrap = data;
    syncStoriesByCharacter(data.storiesByCharacter);
    applyPendingAudience();

    if (state.pendingAudienceTarget) {
      const pendingAudience = state.pendingAudienceTarget;
      applyAudienceSwitch(pendingAudience, {
        characters: data.characters || [],
        stories: data.stories,
        selectedCharacter:
          (data.settings && data.settings.selectedCharacter) ||
          state.selectedCharacterId ||
          firstId(data.characters || []),
      });
    } else {
      reconcileAudienceState({ persist: true });
    }

    const settings = state.bootstrap.settings || {};
    state.audiencePreference = resolveAudiencePreference();
    state.selectedCharacterId =
      settings.selectedCharacter ||
      firstId(state.bootstrap.characters) ||
      null;

    renderStatus();
    renderCharacters();
    renderDialogs();
    renderSettings();
    refreshStoriesScreenIfPending();
    showScreen(state.audiencePreference ? targetScreen : "preference");
    setLoading(false);
    prefetchGalleries(data.characters);
    document.documentElement.setAttribute("data-miniapp-stage", "ready");
  } catch (error) {
    document.documentElement.setAttribute("data-miniapp-stage", "load-error");
    document.documentElement.setAttribute("data-miniapp-error", String(error && error.message || error));
    if (state.bootstrap) {
      showToast(error.message || "Не удалось обновить данные");
    } else {
      showFatalError(error);
    }
  }
}

function hydrateCachedBootstrap() {
  const cached = readCachedBootstrap();
  if (!cached) return false;

  state.bootstrap = cached;
  syncStoriesByCharacter(cached.storiesByCharacter);
  applyPendingAudience();
  reconcileAudienceState({ persist: true });
  const settings = state.bootstrap.settings || {};
  state.audiencePreference = resolveAudiencePreference();
  state.selectedCharacterId =
    settings.selectedCharacter ||
    firstId(state.bootstrap.characters) ||
    null;

  renderStatus();
  renderCharacters();
  renderDialogs();
  renderSettings();
  showScreen(state.audiencePreference ? "characters" : "preference");
  setLoading(false);
  prefetchGalleries(cached.characters);
  document.documentElement.setAttribute("data-miniapp-stage", "cached");
  return true;
}

function readCachedBootstrap() {
  try {
    const raw = localStorage.getItem(BOOTSTRAP_CACHE_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    return data && Array.isArray(data.characters) ? data : null;
  } catch (_error) {
    localStorage.removeItem(BOOTSTRAP_CACHE_KEY);
    return null;
  }
}

function cacheBootstrap(data) {
  try {
    localStorage.setItem(BOOTSTRAP_CACHE_KEY, JSON.stringify(data));
  } catch (_error) {
    // Cache is only a speed boost; the app still works without it.
  }
}

function persistPendingAudience(audience) {
  try {
    localStorage.setItem(PENDING_AUDIENCE_KEY, audience);
  } catch (_e) {}
}

function clearPendingAudience() {
  try {
    localStorage.removeItem(PENDING_AUDIENCE_KEY);
  } catch (_e) {}
}

function applyPendingAudience() {
  try {
    const pending = localStorage.getItem(PENDING_AUDIENCE_KEY);
    if (pending && state.bootstrap && state.bootstrap.settings) {
      state.bootstrap.settings.audiencePreference = pending;
    }
  } catch (_e) {}
}

function hydrateCachedGalleries() {
  try {
    const cached = JSON.parse(localStorage.getItem(GALLERY_CACHE_KEY) || "null");
    if (!cached || Date.now() - Number(cached.savedAt || 0) > 86_400_000) return;
    Object.entries(cached.entries || {}).forEach(([characterId, entry]) => {
      if (entry && entry.character && Array.isArray(entry.images)) {
        state.galleryByCharacter[characterId] = { ...entry, validated: true };
      }
    });
  } catch (_error) {
    localStorage.removeItem(GALLERY_CACHE_KEY);
  }
}

function cacheValidatedGalleries() {
  try {
    const entries = {};
    Object.entries(state.galleryByCharacter).forEach(([characterId, entry]) => {
      if (entry && entry.validated) entries[characterId] = entry;
    });
    localStorage.setItem(GALLERY_CACHE_KEY, JSON.stringify({ savedAt: Date.now(), entries }));
  } catch (_error) {
    // Browser image cache remains the primary fast path.
  }
}


async function api(path, options = {}) {
  const timeoutMs = options.timeoutMs || 20_000;
  const response = typeof fetch === "function"
    ? await apiWithFetch(path, options, timeoutMs)
    : await apiWithXhr(path, options, timeoutMs);

  const raw = response.text;
  const data = raw ? JSON.parse(raw) : {};
  if (!response.ok || data.ok === false) {
    const telegramDescription = data.telegram && data.telegram.description;
    const message = telegramDescription
      ? `${data.error || "Ошибка Telegram"}: ${telegramDescription}`
      : data.error || `Ошибка API ${response.status}`;
    const error = new Error(message);
    error.status = response.status;
    error.data = data;
    throw error;
  }
  return data;
}

async function apiWithFetch(path, options, timeoutMs) {
  const controller = typeof AbortController === "function" ? new AbortController() : null;
  const timeoutId = controller
    ? window.setTimeout(() => controller.abort(), timeoutMs)
    : null;

  try {
    const response = await fetch(path, {
      method: options.method || "GET",
      signal: controller ? controller.signal : undefined,
      headers: {
        "Content-Type": "application/json",
        "X-Telegram-Init-Data": tg ? tg.initData || "" : "",
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
    }).catch((error) => {
      if (error.name === "AbortError") {
        throw new Error("Сервер бота отвечает слишком долго. Перезапусти бота и обнови Mini App.");
      }
      throw error;
    });

    return {
      ok: response.ok,
      status: response.status,
      text: await response.text(),
    };
  } finally {
    if (timeoutId != null) {
      window.clearTimeout(timeoutId);
    }
  }
}

function apiWithXhr(path, options, timeoutMs) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(options.method || "GET", path, true);
    xhr.timeout = timeoutMs;
    xhr.setRequestHeader("Content-Type", "application/json");
    xhr.setRequestHeader("X-Telegram-Init-Data", tg ? tg.initData || "" : "");
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      resolve({
        ok: xhr.status >= 200 && xhr.status < 300,
        status: xhr.status,
        text: xhr.responseText || "",
      });
    };
    xhr.onerror = function () {
      reject(new Error("Не удалось связаться с сервером Mini App."));
    };
    xhr.ontimeout = function () {
      reject(new Error("Сервер бота отвечает слишком долго. Перезапусти бота и обнови Mini App."));
    };
    xhr.send(options.body ? JSON.stringify(options.body) : null);
  });
}

function showScreen(name) {
  if (!hasAudiencePreference() && name !== "preference") {
    name = "preference";
  }

  if (name === "characters" && state.currentScreen === "stories") {
    state.previewCharacterId = null;
    const savedStories = storiesForCharacterFromCache(state.selectedCharacterId);
    if (savedStories) state.bootstrap.stories = savedStories;
    renderCharacters();
    renderSettings();
  }

  state.currentScreen = name;
  document.body.classList.toggle("preference-mode", name === "preference");
  document.body.classList.toggle("stories-mode", name === "stories");
  els.screenTitle.textContent = screenTitles[name] || "Emily";

  [
    els.charactersScreen,
    els.preferenceScreen,
    els.storiesScreen,
    els.dialogsScreen,
    els.galleryScreen,
    els.settingsScreen,
  ].forEach((screen) => screen.classList.remove("active"));

  if (name === "preference") els.preferenceScreen.classList.add("active");
  if (name === "characters") els.charactersScreen.classList.add("active");
  if (name === "stories") els.storiesScreen.classList.add("active");
  if (name === "dialogs") els.dialogsScreen.classList.add("active");
  if (name === "gallery") els.galleryScreen.classList.add("active");
  if (name === "settings") els.settingsScreen.classList.add("active");

  els.navPills.forEach((button) => {
    const target = button.dataset.target;
    const active = name === target || (name === "stories" && target === "characters");
    button.classList.toggle("active", active);
  });

  if (tg && tg.BackButton && typeof tg.BackButton.hide === "function" && typeof tg.BackButton.show === "function") {
    if (name === "stories") safeTelegramCall(() => tg.BackButton.show());
    else safeTelegramCall(() => tg.BackButton.hide());
  }
}

function safeTelegramCall(callback) {
  try {
    callback();
  } catch (error) {
    console.warn("[miniapp] Telegram WebApp method skipped", error);
  }
}

function firstId(items) {
  return items && items.length ? items[0].id : null;
}

function hasAudiencePreference() {
  return Boolean(resolveAudiencePreference());
}

function inferAudienceFromCharacters(characters) {
  if (!Array.isArray(characters) || characters.length === 0) return null;
  const audience = characters[0] && characters[0].audience;
  if (!audience) return null;
  return characters.every((item) => item.audience === audience) ? audience : null;
}

function resolveAudiencePreference() {
  if (state.pendingAudienceTarget) return state.pendingAudienceTarget;

  const settingsAudience =
    state.audiencePreference ||
    (state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference);
  const inferred = inferAudienceFromCharacters(state.bootstrap && state.bootstrap.characters);

  if (inferred && settingsAudience && inferred !== settingsAudience) {
    return inferred;
  }

  return settingsAudience || inferred || null;
}

function syncAudiencePreference(audience) {
  if (!audience || !state.bootstrap) return;
  state.audiencePreference = audience;
  if (!state.bootstrap.settings) state.bootstrap.settings = {};
  state.bootstrap.settings.audiencePreference = audience;
}

function reconcileAudienceState(options = {}) {
  if (!state.bootstrap) return null;

  const inferred = inferAudienceFromCharacters(state.bootstrap.characters);
  let audience = resolveAudiencePreference();

  if (inferred && audience && inferred !== audience && !state.pendingAudienceTarget) {
    audience = inferred;
  }

  if (!audience) return null;

  console.log("[audit] reconcileAudienceState audience=%s selectedCharacterId=%s",
    audience, state.selectedCharacterId);

  syncAudiencePreference(audience);

  if (options.persist) cacheBootstrap(state.bootstrap);
  return audience;
}

function renderStatus() {
  const balance = state.bootstrap && state.bootstrap.balance;

  if (els.statusPanel) els.statusPanel.hidden = true;
  els.balanceText.textContent = balance
    ? `${balance.textTokensLeft} токенов / ${balance.imageCreditsLeft} фото / ${balance.gifCreditsLeft || 0} GIF`
    : "Нет данных";
}

function renderCharacters() {
  const allCharacters = state.bootstrap && state.bootstrap.characters || [];
  const audience = resolveAudiencePreference();
  const characters = audience
    ? allCharacters.filter(function (c) { return c.audience === audience; })
    : allCharacters;
  els.charactersGrid.replaceChildren();

  characters.forEach((character) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "character-card";
    if (character.id === state.selectedCharacterId) card.classList.add("selected");
    card.addEventListener("click", () => selectCharacter(character.id));

    const image = document.createElement("img");
    image.src = character.imageUrl;
    image.alt = character.name;
    image.loading = "lazy";

    const info = document.createElement("div");
    info.className = "character-info";

    const title = document.createElement("h2");
    title.textContent = character.name;

    const description = document.createElement("p");
    description.textContent = character.description;

    const galleryButton = document.createElement("span");
    galleryButton.className = "character-gallery-button";
    galleryButton.textContent = "Фото";
    galleryButton.addEventListener("click", (event) => {
      event.stopPropagation();
      openGallery(character.id);
    });

    info.append(title, description);
    card.append(image, galleryButton, info);
    els.charactersGrid.append(card);
  });
}

function openGallery(characterId) {
  const character = (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === characterId);
  if (!character) return showToast("Персонаж не найден");

  const cached = state.galleryByCharacter[characterId];
  state.galleryCharacterId = characterId;
  state.galleryImages = cached && cached.validated ? cached.images.slice() : [];
  renderGallery(cached ? cached.character : character, { pending: !(cached && cached.validated) });
  showScreen("gallery");
  fetchGallery(characterId, character)
    .then((entry) => validateGalleryEntry(characterId, entry))
    .then((entry) => {
      if (state.currentScreen === "gallery" && state.galleryCharacterId === characterId) {
        state.galleryImages = entry.images.slice();
        renderGallery(entry.character);
      }
    })
    .catch((error) => {
      if (state.currentScreen === "gallery" && state.galleryCharacterId === characterId) {
        renderGallery(character);
        showToast(error.message || "Не удалось обновить галерею");
      }
    });
}

function prefetchGalleries(characters) {
  const orderedCharacters = [...(characters || [])].sort((left, right) => {
    if (left.id === state.selectedCharacterId) return -1;
    if (right.id === state.selectedCharacterId) return 1;
    return 0;
  });
  orderedCharacters.forEach((character) => {
    fetchGallery(character.id, character)
      .then((entry) => validateGalleryEntry(character.id, entry))
      .catch(() => {});
  });
}

function fetchGallery(characterId, fallbackCharacter) {
  if (state.galleryRequests[characterId]) return state.galleryRequests[characterId];
  const request = api(`/miniapp/api/gallery?characterId=${encodeURIComponent(characterId)}`)
    .then((data) => {
      const images = (data.images || []).filter((item) => !state.brokenGalleryImageIds.has(item.id));
      const entry = { character: data.character || fallbackCharacter, images, validated: false };
      state.galleryByCharacter[characterId] = entry;
      delete state.galleryRequests[characterId];
      return entry;
    })
    .catch((error) => {
      delete state.galleryRequests[characterId];
      throw error;
    });
  state.galleryRequests[characterId] = request;
  return request;
}

function validateGalleryEntry(characterId, entry) {
  if (entry.validated) return Promise.resolve(entry);
  if (state.galleryValidationRequests[characterId]) return state.galleryValidationRequests[characterId];
  const request = Promise.all(entry.images.map(preloadGalleryImage))
    .then((results) => {
      entry.images = results.filter(Boolean);
      entry.validated = true;
      state.galleryByCharacter[characterId] = entry;
      cacheValidatedGalleries();
      delete state.galleryValidationRequests[characterId];
      return entry;
    })
    .catch((error) => {
      delete state.galleryValidationRequests[characterId];
      throw error;
    });
  state.galleryValidationRequests[characterId] = request;
  return request;
}

function preloadGalleryImage(item) {
  if (state.brokenGalleryImageIds.has(item.id)) return Promise.resolve(null);
  return new Promise((resolve) => {
    const image = new Image();
    let settled = false;
    const finish = (result, broken = false) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timeoutId);
      image.onload = null;
      image.onerror = null;
      if (broken) state.brokenGalleryImageIds.add(item.id);
      resolve(result);
    };
    const timeoutId = window.setTimeout(() => finish(null), 20_000);
    image.onload = () => {
      if (typeof image.decode === "function") {
        image.decode().catch(() => {}).then(() => finish(item));
        return;
      }
      finish(item);
    };
    image.onerror = () => finish(null, true);
    image.src = item.imageUrl;
  });
}

function charactersForAudience(audience) {
  const byAudience = state.bootstrap && state.bootstrap.charactersByAudience;
  if (byAudience && Array.isArray(byAudience[audience])) {
    return byAudience[audience];
  }

  const currentAudience =
    (state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference) ||
    state.audiencePreference;
  if (currentAudience === audience && state.bootstrap && Array.isArray(state.bootstrap.characters)) {
    return state.bootstrap.characters;
  }

  return null;
}

function snapshotAudienceState() {
  return {
    audiencePreference: state.audiencePreference,
    selectedCharacterId: state.selectedCharacterId,
    settings: state.bootstrap && state.bootstrap.settings ? { ...state.bootstrap.settings } : null,
    characters: state.bootstrap && state.bootstrap.characters ? state.bootstrap.characters.slice() : [],
    stories: state.bootstrap && state.bootstrap.stories ? state.bootstrap.stories.slice() : [],
  };
}

function restoreAudienceSnapshot(snapshot) {
  if (!snapshot || !state.bootstrap) return;

  state.audiencePreference = snapshot.audiencePreference;
  state.selectedCharacterId = snapshot.selectedCharacterId;
  state.bootstrap.settings = snapshot.settings || state.bootstrap.settings;
  state.bootstrap.characters = snapshot.characters;
  state.bootstrap.stories = snapshot.stories;
  cacheBootstrap(state.bootstrap);
  renderCharacters();
  renderSelectedCharacter();
  renderStories();
  renderSettings();
}

function applyAudienceSwitch(audience, payload = {}) {
  if (!state.bootstrap) return;
  if (!state.bootstrap.settings) state.bootstrap.settings = {};
  const currentStory = state.bootstrap.settings.selectedStory || null;
  const currentStoryTitle = state.bootstrap.settings.selectedStoryTitle || null;

  const characters = payload.characters !== undefined
    ? payload.characters
    : (state.bootstrap.characters || charactersForAudience(audience) || []);
  const selectedCharacter = payload.selectedCharacter !== undefined
    ? payload.selectedCharacter
    : (state.selectedCharacterId || firstId(characters));
  const stories =
    payload.stories ||
    (selectedCharacter ? storiesForCharacterFromCache(selectedCharacter) : null) ||
    [];

  state.audiencePreference = audience;
  state.bootstrap.settings.audiencePreference = audience;
  state.bootstrap.settings.selectedCharacter = selectedCharacter;
  state.bootstrap.settings.selectedStory =
    payload.selectedStory !== undefined ? payload.selectedStory : currentStory;
  state.bootstrap.settings.selectedStoryTitle =
    payload.selectedStoryTitle !== undefined ? payload.selectedStoryTitle : currentStoryTitle;
  state.bootstrap.characters = characters;
  state.bootstrap.stories = stories;
  if (payload.storiesByCharacter) syncStoriesByCharacter(payload.storiesByCharacter);
  state.selectedCharacterId = selectedCharacter;
  cacheBootstrap(state.bootstrap);

  console.log("[audit] applyAudienceSwitch audience=%s selectedCharacterId=%s charactersCount=%s storiesCount=%s",
    audience, selectedCharacter, characters.length, stories.length);

  renderCharacters();
  renderSelectedCharacter();
  renderStories();
  renderSettings();
}

async function selectAudience(audience, options = {}) {
  if (!audience) return;
  if (resolveAudiencePreference() === audience) return;

  console.log("[audit] selectAudience audience=%s currentSelectedCharacterId=%s",
    audience, state.selectedCharacterId);

  state.pendingAudienceTarget = audience;
  const requestId = ++state.pendingAudienceSwitchRequestId;
  const previousSnapshot = snapshotAudienceState();
  let localCharacters = charactersForAudience(audience);
  if (!localCharacters && state.bootstrap) {
    localCharacters = (state.bootstrap.characters || []).filter(function (c) { return c.audience === audience; });
    if (localCharacters.length === 0) localCharacters = null;
  }
  const targetScreen = options.stayOnSettings ? "settings" : "characters";

  try {
    persistPendingAudience(audience);

    if (localCharacters) {
      state.audiencePreference = audience;
      if (state.bootstrap && state.bootstrap.settings) {
        state.bootstrap.settings.audiencePreference = audience;
      }
      cacheBootstrap(state.bootstrap);
      renderCharacters();
      renderSelectedCharacter();
      renderSettings();
      showScreen(targetScreen);
      prefetchGalleries(localCharacters);
    } else {
      syncAudiencePreference(audience);
      cacheBootstrap(state.bootstrap);
      renderAudienceSettings();
    }

    const data = await api("/miniapp/api/audience", {
      method: "POST",
      body: { audience },
    });
    if (requestId !== state.pendingAudienceSwitchRequestId) return;

    console.log("[audit] selectAudience API response audience=%s serverSelectedCharacter=%s", audience, data.selectedCharacter);
    console.log("[audit] selectAudience before API apply selectedCharacterId=%s", state.selectedCharacterId);

    clearPendingAudience();

    applyAudienceSwitch(data.audiencePreference, {
      characters: data.characters,
      stories: data.stories,
      storiesByCharacter: data.storiesByCharacter,
      selectedCharacter: data.selectedCharacter,
      selectedStory: data.selectedStory,
      selectedStoryTitle: data.selectedStoryTitle,
    });

    console.log("[audit] selectAudience after API apply selectedCharacterId=%s", state.selectedCharacterId);
    if (state.currentScreen === targetScreen) {
      showScreen(targetScreen);
    }
    prefetchGalleries(data.characters || []);
  } catch (error) {
    if (requestId !== state.pendingAudienceSwitchRequestId) return;

    clearPendingAudience();

    if (localCharacters) {
      restoreAudienceSnapshot(previousSnapshot);
      if (state.currentScreen === targetScreen) {
        showScreen(targetScreen);
      }
    } else {
      state.audiencePreference = previousSnapshot.audiencePreference;
      if (state.bootstrap && previousSnapshot.settings) {
        state.bootstrap.settings = { ...previousSnapshot.settings };
      }
      renderAudienceSettings();
    }

    showToast(error.message || "Не удалось сменить выбор");
  } finally {
    if (requestId === state.pendingAudienceSwitchRequestId) {
      state.pendingAudienceTarget = null;
    }
  }
}

function renderGallery(character, options = {}) {
  els.galleryHeader.replaceChildren();
  els.galleryGrid.replaceChildren();

  const title = document.createElement("h2");
  title.textContent = `Галерея ${character.name}`;
  const subtitle = document.createElement("p");
  subtitle.textContent = options.pending
    ? "Галерея открыта. Фотографии появятся через мгновение."
    : state.galleryImages.length
    ? `${state.galleryImages.length} фото. Нажми на любое, чтобы открыть просмотр.`
    : "У этого персонажа пока нет сгенерированных фото.";
  els.galleryHeader.append(title, subtitle);

  if (options.pending) {
    for (let index = 0; index < 6; index += 1) {
      const skeleton = document.createElement("div");
      skeleton.className = "gallery-tile gallery-tile-skeleton";
      els.galleryGrid.append(skeleton);
    }
    return;
  }

  if (state.galleryImages.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-dialogs";
    empty.textContent = "Сгенерируй картинку в чате, и она появится здесь.";
    els.galleryGrid.append(empty);
    return;
  }

  state.galleryImages.forEach((item, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "gallery-tile";
    button.addEventListener("click", () => {
      const currentIndex = state.galleryImages.findIndex((candidate) => candidate.id === item.id);
      if (currentIndex >= 0) openGalleryViewer(currentIndex);
    });

    const image = document.createElement("img");
    image.src = item.imageUrl;
    image.alt = item.prompt || "Сгенерированное фото";
    image.loading = "eager";
    image.decoding = "async";
    image.addEventListener("error", () => removeBrokenGalleryImage(character, item.id));

    const meta = document.createElement("span");
    meta.textContent = formatDialogTime(item.createdAt);

    button.append(image, meta);
    els.galleryGrid.append(button);
  });
}

function removeBrokenGalleryImage(character, imageId) {
  state.brokenGalleryImageIds.add(imageId);
  state.galleryImages = state.galleryImages.filter((item) => item.id !== imageId);
  const cached = state.galleryByCharacter[state.galleryCharacterId];
  if (cached) cached.images = cached.images.filter((item) => item.id !== imageId);
  cacheValidatedGalleries();
  renderGallery(character);
}

function openGalleryViewer(index) {
  if (!state.galleryImages.length) return;
  els.galleryViewer.hidden = false;
  showGalleryImage(index);
}

function closeGalleryViewer() {
  els.galleryViewer.hidden = true;
  els.galleryViewerImage.removeAttribute("src");
}

function showGalleryImage(index) {
  if (!state.galleryImages.length) return;
  state.galleryIndex = (index + state.galleryImages.length) % state.galleryImages.length;
  const item = state.galleryImages[state.galleryIndex];
  els.galleryViewerImage.src = item.imageUrl;
  els.galleryViewerImage.alt = item.prompt || "Сгенерированное фото";
  els.galleryViewerMeta.textContent = `${state.galleryIndex + 1} из ${state.galleryImages.length} · ${formatDialogTime(item.createdAt)}`;
}

function handleGalleryViewerError() {
  const item = state.galleryImages[state.galleryIndex];
  const character = (state.bootstrap && state.bootstrap.characters || [])
    .find((candidate) => candidate.id === state.galleryCharacterId);
  if (!item || !character) return closeGalleryViewer();
  removeBrokenGalleryImage(character, item.id);
  if (state.galleryImages.length === 0) {
    closeGalleryViewer();
    return;
  }
  state.galleryIndex %= state.galleryImages.length;
  showGalleryImage(state.galleryIndex);
}

function selectCharacter(characterId) {
  const character = (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === characterId);
  if (!character) {
    showToast("Персонаж не найден");
    return;
  }

  console.log("[audit] selectCharacter characterId=%s previousSelected=%s",
    characterId, state.selectedCharacterId);

  state.selectedCharacterId = characterId;
  state.previewCharacterId = characterId;
  if (state.bootstrap && state.bootstrap.settings) {
    state.bootstrap.settings.selectedCharacter = characterId;
  }
  cacheBootstrap(state.bootstrap);
  renderSelectedCharacter();
  showScreen("stories");
  renderStoriesPending();

  api("/miniapp/api/select-character", {
    method: "POST",
    body: { characterId },
  }).catch(function () {});

  const cachedStories = storiesForCharacterFromCache(characterId);
  if (cachedStories) {
    state.bootstrap.stories = cachedStories;
    renderStories();
    return;
  }

  loadStoriesForCharacter(characterId);
}

function refreshStoriesScreenIfPending() {
  if (state.currentScreen !== "stories" || !state.previewCharacterId || !state.bootstrap) return;

  const stories = storiesForCharacterFromCache(state.previewCharacterId);
  if (!stories) return;

  state.bootstrap.stories = stories;
  renderStories();
}

async function loadStoriesForCharacter(characterId) {
  const requestId = ++state.pendingCharacterRequestId;

  try {
    const stories = await fetchStoriesForCharacter(characterId);
    if (requestId !== state.pendingCharacterRequestId) return;
    if (state.currentScreen !== "stories" || state.previewCharacterId !== characterId) return;

    state.bootstrap.stories = stories;
    renderStories();
  } catch (error) {
    if (requestId !== state.pendingCharacterRequestId) return;
    if (state.currentScreen !== "stories" || state.previewCharacterId !== characterId) return;

    const character = (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === characterId);
    renderStoriesLoadError(character, characterId);
    showToast(error.message || "Не удалось загрузить истории");
  }
}

async function fetchStoriesForCharacter(characterId) {
  const data = await api(`/miniapp/api/stories?characterId=${encodeURIComponent(characterId)}`);
  const stories = Array.isArray(data.stories) ? data.stories : [];
  cacheStoriesForCharacter(characterId, stories);
  return stories;
}

function cacheStoriesForCharacter(characterId, stories) {
  setCachedStories(characterId, stories);
  if (!state.bootstrap) return;

  if (!state.bootstrap.storiesByCharacter || typeof state.bootstrap.storiesByCharacter !== "object") {
    state.bootstrap.storiesByCharacter = { ...state.storiesByCharacter };
  } else {
    state.bootstrap.storiesByCharacter[characterId] = [...stories];
  }
  cacheBootstrap(state.bootstrap);
}

function renderSelectedCharacter() {
  const character = previewedCharacter();
  els.selectedCharacterPanel.replaceChildren();

  if (!character) {
    els.selectedCharacterPanel.textContent = "Сначала выбери персонажа.";
    return;
  }

  const image = document.createElement("img");
  image.className = "selected-character-avatar";
  image.src = character.imageUrl;
  image.alt = character.name;

  const copy = document.createElement("div");
  copy.className = "selected-character-copy";

  const label = document.createElement("span");
  label.className = "selected-character-label";
  label.textContent = "Твой персонаж";

  const title = document.createElement("strong");
  title.textContent = character.name;

  const description = document.createElement("p");
  description.textContent = character.description;

  copy.append(label, title, description);
  els.selectedCharacterPanel.append(image, copy);
}

function renderStoriesPending() {
  els.storiesList.replaceChildren();

  for (let index = 0; index < 3; index += 1) {
    const card = document.createElement("div");
    card.className = "story-card story-card-skeleton";
    card.setAttribute("aria-hidden", "true");

    const copy = document.createElement("span");
    copy.className = "story-card-copy story-card-skeleton-copy";
    copy.append(
      document.createElement("span"),
      document.createElement("span"),
      document.createElement("span")
    );

    const arrow = document.createElement("span");
    arrow.className = "story-card-skeleton-arrow";
    arrow.setAttribute("aria-hidden", "true");

    card.append(copy, arrow);
    els.storiesList.append(card);
  }
}

function renderStoriesLoadError(character, characterId) {
  els.storiesList.replaceChildren();

  const message = document.createElement("div");
  message.className = "empty-dialogs";
  message.textContent = character
    ? `Не удалось загрузить истории для ${character.name}.`
    : "Не удалось загрузить истории.";

  const retryButton = document.createElement("button");
  retryButton.type = "button";
  retryButton.className = "dialog-choice-continue";
  retryButton.textContent = "Повторить";
  retryButton.addEventListener("click", () => {
    renderStoriesPending();
    loadStoriesForCharacter(characterId);
  });

  const actions = document.createElement("div");
  actions.className = "dialog-choice-actions";
  actions.append(retryButton);

  els.storiesList.append(message, actions);
}

function syncStoriesByCharacter(storiesByCharacter) {
  state.storiesByCharacter = storiesByCharacter && typeof storiesByCharacter === "object"
    ? { ...storiesByCharacter }
    : {};
}

function setCachedStories(characterId, stories) {
  if (!characterId || !Array.isArray(stories)) return;
  state.storiesByCharacter[characterId] = [...stories];
  if (state.bootstrap && state.bootstrap.storiesByCharacter) {
    state.bootstrap.storiesByCharacter[characterId] = [...stories];
  }
}

function storiesForCharacterFromCache(characterId) {
  const stories = state.storiesByCharacter && state.storiesByCharacter[characterId];
  return Array.isArray(stories) ? [...stories] : null;
}

function renderStories() {
  const stories = state.bootstrap && state.bootstrap.stories || [];
  els.storiesList.replaceChildren();

  stories.forEach((story) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "story-card";
    card.addEventListener("click", () => selectStory(story.id, card));

    const copy = document.createElement("span");
    copy.className = "story-card-copy";

    const title = document.createElement("h2");
    title.textContent = story.title;

    const description = document.createElement("p");
    description.textContent = story.description;

    const setup = document.createElement("p");
    setup.className = "story-setup";
    setup.textContent = story.setup;

    const arrow = document.createElement("span");
    arrow.className = "story-arrow";
    arrow.setAttribute("aria-hidden", "true");
    arrow.textContent = "→";

    copy.append(title, description);
    card.append(copy, arrow, setup);

    if (story.custom) {
      card.classList.add("story-card-custom");
      const controls = document.createElement("span");
      controls.className = "story-card-controls";
      controls.addEventListener("click", (event) => event.stopPropagation());

      const editBtn = document.createElement("button");
      editBtn.type = "button";
      editBtn.className = "story-card-edit";
      editBtn.textContent = "✎";
      editBtn.title = "Редактировать";
      editBtn.addEventListener("click", () => openCustomStoryEditor(story));

      const deleteBtn = document.createElement("button");
      deleteBtn.type = "button";
      deleteBtn.className = "story-card-delete";
      deleteBtn.textContent = "×";
      deleteBtn.title = "Удалить";
      deleteBtn.addEventListener("click", () => deleteCustomStory(story.id));

      controls.append(editBtn, deleteBtn);
      card.append(controls);
    }

    els.storiesList.append(card);
  });

  els.storiesList.append(customStoryCard());
}

function customStoryCard() {
  const access = state.bootstrap && state.bootstrap.customStory || {};
  const slotsLeft = Number(access.storySlotsLeft || 0);
  const slotsTotal = Number(access.storySlotsTotal || 30);

  const card = document.createElement("button");
  card.type = "button";
  card.className = "story-card custom-story-card";
  card.addEventListener("click", () => openCustomStoryEditor());
  if (slotsLeft > 0) {
    card.classList.add("custom-story-card-unlocked");
  }

  const plus = document.createElement("span");
  plus.className = "custom-story-plus";
  plus.textContent = "+";

  const copy = document.createElement("span");
  copy.className = "custom-story-copy";

  const title = document.createElement("h2");
  title.textContent = "Создать свою ролевую игру";

  const arrow = document.createElement("span");
  arrow.className = "custom-story-arrow";
  arrow.setAttribute("aria-hidden", "true");
  arrow.textContent = "→";

  const badge = document.createElement("span");
  badge.className = "custom-story-badge";
  badge.textContent = slotsLeft > 0
    ? `Осталось: ${slotsLeft} из ${slotsTotal}`
    : `Лимит ${slotsTotal} историй исчерпан. Удали одну, чтобы создать новую.`;
  if (slotsLeft === 0) badge.style.color = "var(--danger, #ff4444)";
  copy.append(title, badge);
  card.append(plus, copy, arrow);
  return card;
}

function openCustomStoryEditor(existingStory) {
  const character = existingStory
    ? (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === existingStory.characterId)
    : previewedCharacter();
  if (!character) {
    showToast("Сначала выбери персонажа");
    return;
  }

  const isEditing = Boolean(existingStory);
  const overlay = document.createElement("div");
  overlay.className = "custom-story-modal";
  overlay.innerHTML = `
    <form class="custom-story-form">
      <button class="custom-story-close" type="button" aria-label="Закрыть">×</button>
      <p class="custom-story-kicker">Своя история для ${escapeHtml(character.name)}</p>
      <h2>${isEditing ? "Редактировать сценарий" : "Создай сценарий"}</h2>
      <label>
        Название
        <input name="title" maxlength="60" placeholder="Например: Ночная поездка" value="${escapeHtml(isEditing ? existingStory.title : '')}" required>
      </label>
      <label>
        Короткое описание
        <input name="description" maxlength="160" placeholder="Что увидит пользователь на карточке" value="${escapeHtml(isEditing ? (existingStory.description || '') : '')}">
      </label>
      <label>
        Сцена и правила истории
        <div class="setup-wrapper">
          <textarea name="setup" maxlength="900" rows="5" placeholder="Где вы, что происходит, какая роль у персонажа..." required>${escapeHtml(isEditing ? (existingStory.setup || '') : '')}</textarea>
          <button type="button" class="setup-ai-btn" title="Улучшить с помощью ИИ" disabled>✨</button>
        </div>
      </label>
      <label>
        Первое сообщение персонажа
        <textarea name="openingLine" maxlength="240" rows="3" placeholder="Фраза, с которой начнется чат" required>${escapeHtml(isEditing ? (existingStory.openingLine || '') : '')}</textarea>
      </label>
      <button class="primary-button" type="submit">${isEditing ? "Сохранить изменения" : "Сохранить историю"}</button>
    </form>
  `;

  const close = () => overlay.remove();
  overlay.addEventListener("click", (event) => {
    if (event.target === overlay) close();
  });
  overlay.querySelector(".custom-story-close").addEventListener("click", close);
  overlay.querySelector("form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const payload = {
      characterId: character.id,
      title: String(form.get("title") || ""),
      description: String(form.get("description") || ""),
      setup: String(form.get("setup") || ""),
      openingLine: String(form.get("openingLine") || ""),
    };
    if (isEditing) {
      await updateCustomStory(existingStory.id, payload);
    } else {
      await createCustomStory(payload);
    }
    close();
  });

  const setupTextarea = overlay.querySelector("textarea[name=\"setup\"]");
  const aiBtn = overlay.querySelector(".setup-ai-btn");
  if (setupTextarea && aiBtn) {
    const MIN_AI_CHARS = 20;
    const updateAiBtn = () => {
      aiBtn.disabled = setupTextarea.value.trim().length < MIN_AI_CHARS || aiBtn.classList.contains("loading");
    };
    setupTextarea.addEventListener("input", updateAiBtn);
    aiBtn.addEventListener("click", async () => {
      if (aiBtn.disabled) return;
      aiBtn.classList.add("loading");
      aiBtn.disabled = true;
      aiBtn.title = "Улучшаю...";
      try {
        const data = await api("/miniapp/api/expand-setup", {
          method: "POST",
          body: { text: setupTextarea.value },
        });
        if (data.expanded) {
          setupTextarea.value = data.expanded;
          setupTextarea.dispatchEvent(new Event("input"));
        }
      } catch (error) {
        showToast(error.message || "Не удалось улучшить текст");
      } finally {
        aiBtn.classList.remove("loading");
        aiBtn.title = "Улучшить с помощью ИИ";
        updateAiBtn();
      }
    });
    updateAiBtn();
  }

  document.body.append(overlay);
  const firstInput = overlay.querySelector("input");
  if (firstInput) firstInput.focus();
}

async function createCustomStory(payload) {
  setLoading(true);
  try {
    const data = await api("/miniapp/api/custom-story", {
      method: "POST",
      body: payload,
    });
    state.bootstrap.stories = data.stories || state.bootstrap.stories;
    setCachedStories(payload.characterId, state.bootstrap.stories);
    state.bootstrap.customStory = data.customStory || state.bootstrap.customStory;
    cacheBootstrap(state.bootstrap);
    renderStories();
    showToast("История создана. Теперь её можно выбрать.");
  } catch (error) {
    showToast(error.message || "Не удалось создать историю");
  } finally {
    setLoading(false);
  }
}

async function updateCustomStory(storyId, payload) {
  setLoading(true);
  try {
    const data = await api("/miniapp/api/custom-story", {
      method: "PUT",
      body: { storyId, ...payload },
    });
    state.bootstrap.stories = data.stories || state.bootstrap.stories;
    setCachedStories(payload.characterId, state.bootstrap.stories);
    cacheBootstrap(state.bootstrap);
    renderStories();
    showToast("История обновлена.");
  } catch (error) {
    showToast(error.message || "Не удалось обновить историю");
  } finally {
    setLoading(false);
  }
}

async function deleteCustomStory(storyId) {
  if (!confirm("Удалить эту историю? Это действие нельзя отменить.")) return;

  const character = previewedCharacter();
  setLoading(true);
  try {
    const data = await api("/miniapp/api/delete-custom-story", {
      method: "POST",
      body: { storyId, characterId: character ? character.id : "" },
    });
    state.bootstrap.stories = data.stories || state.bootstrap.stories;
    state.bootstrap.customStory = data.customStory || state.bootstrap.customStory;
    setCachedStories(character ? character.id : "", state.bootstrap.stories);
    cacheBootstrap(state.bootstrap);
    renderStories();
    showToast("История удалена. Слот освобождён.");
  } catch (error) {
    showToast(error.message || "Не удалось удалить историю");
  } finally {
    setLoading(false);
  }
}

function renderDialogs() {
  const dialogs = state.bootstrap && state.bootstrap.dialogs || [];
  els.dialogsList.replaceChildren();

  if (dialogs.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-dialogs";
    empty.textContent = "Пока нет сохранённых диалогов. Выбери персонажа и историю, чтобы создать первый.";
    els.dialogsList.append(empty);
    return;
  }

  const contextOccurrences = new Map();
  dialogs.forEach((dialog) => {
    const contextKey = `${dialog.characterId}:${dialog.storyId || "free-chat"}`;
    const occurrence = (contextOccurrences.get(contextKey) || 0) + 1;
    contextOccurrences.set(contextKey, occurrence);
    const visual = dialogVisual(dialog);

    const row = document.createElement("button");
    row.type = "button";
    row.className = "dialog-row";
    row.style.setProperty("--dialog-accent", visual.color);
    row.addEventListener("click", () => restoreDialog(dialog.id));

    const avatar = document.createElement("div");
    avatar.className = "dialog-avatar";
    const image = document.createElement("img");
    image.src = dialog.characterImageUrl;
    image.alt = dialog.characterName;
    image.loading = "lazy";
    const storyMark = document.createElement("span");
    storyMark.className = "dialog-story-mark";
    storyMark.textContent = visual.mark;
    avatar.append(image, storyMark);
    if (occurrence > 1) {
      const sequence = document.createElement("span");
      sequence.className = "dialog-sequence";
      sequence.textContent = String(occurrence);
      avatar.append(sequence);
    }

    const main = document.createElement("div");
    main.className = "dialog-main";

    const title = document.createElement("div");
    title.className = "dialog-title";
    const character = document.createElement("span");
    character.className = "dialog-character";
    character.textContent = dialog.characterName;
    const story = document.createElement("span");
    story.className = "dialog-story";
    story.textContent = dialog.storyTitle || "Свободный чат";
    title.append(character, story);

    const preview = document.createElement("div");
    preview.className = "dialog-preview";
    preview.textContent = dialog.lastMessage || "Диалог без сообщений";

    main.append(title, preview);

    const time = document.createElement("div");
    time.className = "dialog-time";
    time.textContent = formatDialogTime(dialog.updatedAt);

    row.append(avatar, main, time);
    els.dialogsList.append(row);
  });
}

function dialogVisual(dialog) {
  const palette = ["#ff5d8f", "#35c8b2", "#f3b83f", "#5f9df7", "#cf6df2", "#ff7657"];
  const title = dialog.storyTitle || "Свободный чат";
  const words = title.trim().split(/\s+/).filter(Boolean);
  const mark = dialog.storyId
    ? words.slice(0, 2).map((word) => word[0]).join("").toLocaleUpperCase("ru-RU")
    : "ЧА";
  const key = dialog.storyId || "free-chat";
  let hash = 0;
  for (let index = 0; index < key.length; index += 1) {
    hash = ((hash << 5) - hash + key.charCodeAt(index)) | 0;
  }
  return { color: palette[Math.abs(hash) % palette.length], mark };
}

async function restoreDialog(dialogId) {
  let dialog = findDialogById(dialogId);
  try {
    setLoading(true);
    dialog = await loadDialogPreview(dialog || { id: dialogId });
  } catch (error) {
    showToast(error.message || "Не удалось загрузить диалог");
    return;
  } finally {
    setLoading(false);
  }

  const shouldContinue = await confirmDialogReuse(dialog, {
    allowRestart: false,
    title: "Открыть сохранённый диалог?",
    secondaryLabel: "Отмена",
  });
  if (shouldContinue !== true) return;

  setLoading(true);
  try {
    const data = await api("/miniapp/api/restore-dialog", {
      method: "POST",
      body: { dialogId },
    });
    finishInTelegram(data.sendData, "Диалог восстановлен. Возвращаю в чат бота.");
  } catch (error) {
    showToast(error.message || "Не удалось восстановить диалог");
  } finally {
    setLoading(false);
  }
}

function findDialogById(dialogId) {
  return (state.bootstrap && state.bootstrap.dialogs || [])
    .find((dialog) => dialog.id === dialogId) || null;
}

async function loadDialogPreview(dialog) {
  if (!dialog || !dialog.id) return dialog;
  if (Array.isArray(dialog.recentMessages) && Object.prototype.hasOwnProperty.call(dialog, "story")) return dialog;

  const data = await api(`/miniapp/api/dialog-preview?dialogId=${encodeURIComponent(dialog.id)}`);
  const nextDialog = {
    ...dialog,
    ...(data.dialog || {}),
    story: data.story || null,
    recentMessages: Array.isArray(data.recentMessages) ? data.recentMessages : [],
  };

  if (state.bootstrap && Array.isArray(state.bootstrap.dialogs)) {
    const index = state.bootstrap.dialogs.findIndex((item) => item.id === nextDialog.id);
    if (index >= 0) state.bootstrap.dialogs[index] = nextDialog;
  }

  return nextDialog;
}

function findExistingDialogForContext(characterId, storyId) {
  const dialogs = state.bootstrap && state.bootstrap.dialogs || [];
  const normalizedStoryId = storyId || null;
  return dialogs
    .filter((dialog) => dialog.characterId === characterId && (dialog.storyId || null) === normalizedStoryId)
    .sort((left, right) => Number(right.updatedAt || 0) - Number(left.updatedAt || 0))[0] || null;
}

function confirmDialogReuse(dialog, options = {}) {
  if (!dialog || document.querySelector(".dialog-choice-modal")) return Promise.resolve(null);
  const allowRestart = options.allowRestart !== false;
  const mode = dialog.storyTitle || "Свободный чат";
  return new Promise((resolve) => {
    const overlay = document.createElement("div");
    overlay.className = "dialog-choice-modal";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");

    const panel = document.createElement("div");
    panel.className = "dialog-choice-panel";

    const closeButton = document.createElement("button");
    closeButton.type = "button";
    closeButton.className = "dialog-choice-close";
    closeButton.setAttribute("aria-label", "Закрыть");
    closeButton.textContent = "×";

    const avatar = document.createElement("img");
    avatar.className = "dialog-choice-avatar";
    avatar.src = dialog.characterImageUrl || "";
    avatar.alt = "";

    const heading = document.createElement("div");
    heading.className = "dialog-choice-heading";
    const kicker = document.createElement("span");
    kicker.className = "dialog-choice-kicker";
    kicker.textContent = "Сохранённый диалог";
    const title = document.createElement("h2");
    title.textContent = options.title || "Продолжить разговор?";
    const context = document.createElement("div");
    context.className = "dialog-choice-context";
    context.textContent = `${dialog.characterName || "Диалог"} · ${mode}`;
    const meta = document.createElement("div");
    meta.className = "dialog-choice-meta";
    meta.textContent = dialog.updatedAt ? `Обновлён ${formatDialogTime(dialog.updatedAt)}` : "";

    const description = document.createElement("p");
    description.className = "dialog-choice-description";
    description.textContent = "Сцена и последние реплики помогут быстро вспомнить, где остановился разговор.";

    const storyContext = dialogStoryContext(dialog);
    const recent = dialogRecentMessages(dialog);

    const actions = document.createElement("div");
    actions.className = "dialog-choice-actions";
    const continueButton = document.createElement("button");
    continueButton.type = "button";
    continueButton.className = "dialog-choice-continue";
    continueButton.textContent = "Продолжить";
    const restartButton = document.createElement("button");
    restartButton.type = "button";
    restartButton.className = "dialog-choice-restart";
    restartButton.textContent = options.secondaryLabel || "Начать заново";

    const finish = (choice) => {
      overlay.remove();
      resolve(choice);
    };
    continueButton.addEventListener("click", () => finish(true));
    restartButton.addEventListener("click", () => finish(allowRestart ? false : null));
    closeButton.addEventListener("click", () => finish(null));
    overlay.addEventListener("click", (event) => {
      if (event.target === overlay) finish(null);
    });

    actions.append(continueButton, restartButton);
    heading.append(kicker, title, context, meta);
    panel.append(closeButton, avatar, heading, description);
    if (storyContext) panel.append(storyContext);
    panel.append(recent, actions);
    overlay.append(panel);
    document.body.append(overlay);
    continueButton.focus();
  });
}

function dialogStoryContext(dialog) {
  const story = dialog && dialog.story;
  if (!story) return null;
  const text = story.setup || story.description || "";
  if (!text.trim()) return null;

  const wrapper = document.createElement("div");
  wrapper.className = "dialog-choice-story";

  const label = document.createElement("span");
  label.className = "dialog-choice-section-label";
  label.textContent = "Сцена";

  const body = document.createElement("p");
  body.textContent = text;

  wrapper.append(label, body);
  return wrapper;
}

function dialogRecentMessages(dialog) {
  const wrapper = document.createElement("div");
  wrapper.className = "dialog-choice-recent";

  const label = document.createElement("span");
  label.className = "dialog-choice-section-label";
  label.textContent = "Последние реплики";
  wrapper.append(label);

  const messages = Array.isArray(dialog.recentMessages) ? dialog.recentMessages : [];
  if (messages.length === 0) {
    const empty = document.createElement("p");
    empty.className = "dialog-choice-recent-empty";
    empty.textContent = dialog.lastMessage || "Сообщения пока не найдены.";
    wrapper.append(empty);
    return wrapper;
  }

  messages.forEach((message) => {
    const item = document.createElement("div");
    item.className = `dialog-choice-message dialog-choice-message--${message.role === "user" ? "user" : "assistant"}`;

    const author = document.createElement("span");
    author.className = "dialog-choice-message-author";
    author.textContent = message.role === "user" ? "Ты" : dialog.characterName;

    const text = document.createElement("p");
    text.textContent = message.text || "";

    item.append(author, text);
    wrapper.append(item);
  });

  return wrapper;
}

async function selectStory(storyId, card = null) {
  const characterId = state.previewCharacterId || state.selectedCharacterId;
  if (!characterId) return showToast("Сначала выбери персонажа");
  const selectionKey = `${characterId}:${storyId}`;
  if (state.pendingStorySelectionKey) return;

  state.pendingStorySelectionKey = selectionKey;
  if (card) {
    card.disabled = true;
    card.classList.add("story-card-pending");
    card.setAttribute("aria-busy", "true");
  }

  try {
    let existingDialog = findExistingDialogForContext(characterId, storyId);
    let data;
    if (!existingDialog) {
      data = await api("/miniapp/api/select-story", {
        method: "POST",
        body: { characterId, storyId },
      });
      if (!data.needsDecision) {
        finishInTelegram(data.sendData, "История выбрана. Вернись в чат, чтобы продолжить.");
        return;
      }
      existingDialog = data.existingDialog;
    }

    if (existingDialog) {
      const shouldContinue = await confirmDialogReuse(existingDialog);
      if (shouldContinue === null) return;
      if (shouldContinue && existingDialog && existingDialog.id) {
        data = await api("/miniapp/api/restore-dialog", {
          method: "POST",
          body: { dialogId: existingDialog.id },
        });
        finishInTelegram(data.sendData, "Старый диалог восстановлен. Возвращаю в чат.");
        return;
      }

      data = await api("/miniapp/api/select-story", {
        method: "POST",
        body: { characterId, storyId, replaceExisting: true },
      });
    }
    finishInTelegram(data.sendData, "История выбрана. Вернись в чат, чтобы продолжить.");
  } catch (error) {
    if (error.status === 502 && error.data && error.data.error === "Telegram message was not delivered") {
      finishInTelegram(
        { action: "story_selected", characterId, storyId },
        "История выбрана. Открываю чат.",
      );
      return;
    }
    showToast(error.message || "Не удалось выбрать историю");
  } finally {
    if (state.pendingStorySelectionKey === selectionKey) {
      state.pendingStorySelectionKey = null;
    }
    if (card && card.isConnected) {
      card.disabled = false;
      card.classList.remove("story-card-pending");
      card.removeAttribute("aria-busy");
    }
  }
}

async function skipStory() {
  const characterId = state.previewCharacterId || state.selectedCharacterId;
  if (!characterId) return showToast("Сначала выбери персонажа");

  try {
    let existingDialog = findExistingDialogForContext(characterId, null);
    let data;
    if (!existingDialog) {
      data = await api("/miniapp/api/skip-story", {
        method: "POST",
        body: { characterId },
      });
      if (!data.needsDecision) {
        finishInTelegram(data.sendData, "История пропущена. Можно продолжать в чате.");
        return;
      }
      existingDialog = data.existingDialog;
    }

    if (existingDialog) {
      const shouldContinue = await confirmDialogReuse(existingDialog);
      if (shouldContinue === null) return;
      if (shouldContinue && existingDialog && existingDialog.id) {
        data = await api("/miniapp/api/restore-dialog", {
          method: "POST",
          body: { dialogId: existingDialog.id },
        });
        finishInTelegram(data.sendData, "Старый диалог восстановлен. Возвращаю в чат.");
        return;
      }

      data = await api("/miniapp/api/skip-story", {
        method: "POST",
        body: { characterId, replaceExisting: true },
      });
    }
    finishInTelegram(data.sendData, "История пропущена. Можно продолжать в чате.");
  } catch (error) {
    showToast(error.message || "Не удалось пропустить историю");
  }
}

async function createInvoice(type, code) {
  setLoading(true);
  try {
    const data = await api("/miniapp/api/create-invoice", {
      method: "POST",
      body: { type, code },
    });
    if (openInvoice(data.invoiceLink, () => loadBootstrap("settings"))) {
      return;
    }
    await sendInvoiceToChat(type, code, "Счёт отправлен в чат Telegram.");
  } catch (error) {
    showToast(error.message || "Не удалось создать счет");
  } finally {
    setLoading(false);
  }
}

function openInvoice(invoiceLink, onPaid) {
  if (!invoiceLink) return false;

  if (tg && tg.openInvoice) {
    try {
      tg.openInvoice(invoiceLink, (status) => {
        if (status === "paid") {
          showToast("Оплата прошла. Обновляю данные...");
          if (onPaid) onPaid();
        } else {
          showToast("Оплата не завершена.");
        }
      });
      return true;
    } catch (error) {
      console.warn("Telegram openInvoice is unavailable, falling back to link", error);
    }
  }

  return false;
}

async function sendInvoiceToChat(type, code, message) {
  await api("/miniapp/api/create-invoice", {
    method: "POST",
    body: { type, code, delivery: "chat" },
  });
  finishInTelegram(null, message);
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function findKnownStory(characterId, storyId) {
  if (!storyId) return null;
  const normalizedStoryId = String(storyId).toLowerCase();
  const sources = [];

  if (characterId) {
    sources.push(storiesForCharacterFromCache(characterId));
  }
  sources.push(state.bootstrap && state.bootstrap.stories);
  Object.values(state.storiesByCharacter || {}).forEach((stories) => sources.push(stories));

  for (const stories of sources) {
    if (!Array.isArray(stories)) continue;
    const story = stories.find((item) => item.id && item.id.toLowerCase() === normalizedStoryId);
    if (story) return story;
  }

  return null;
}

function findKnownStoryTitle(characterId, storyId) {
  if (!storyId) return null;
  const normalizedStoryId = String(storyId).toLowerCase();
  const settings = state.bootstrap && state.bootstrap.settings || {};
  if (settings.selectedStory && settings.selectedStory.toLowerCase() === normalizedStoryId && settings.selectedStoryTitle) {
    return settings.selectedStoryTitle;
  }

  const dialogs = state.bootstrap && state.bootstrap.dialogs || [];
  const activeDialog = dialogs
    .find((item) => item.id === settings.activeDialogId && item.storyId && item.storyId.toLowerCase() === normalizedStoryId);
  if (activeDialog && activeDialog.storyTitle) return activeDialog.storyTitle;

  const dialog = dialogs
    .find((item) => item.characterId === characterId && item.storyId && item.storyId.toLowerCase() === normalizedStoryId);
  return dialog && dialog.storyTitle ? dialog.storyTitle : null;
}

function renderSettings() {
  if (!state.bootstrap) return;

  renderPaymentOptions();
  renderAudienceSettings();

  const character = selectedCharacter();
  const storyId = state.bootstrap.settings && state.bootstrap.settings.selectedStory;
  const story = character ? findKnownStory(character.id, storyId) : null;
  const fallbackStoryTitle = character ? findKnownStoryTitle(character.id, storyId) : null;
  const storyTitle = story ? story.title : fallbackStoryTitle;

  els.currentSelection.textContent = character
    ? character.name
    : "Персонаж не выбран";
  els.currentStoryHint.textContent = character
    ? storyId
      ? storyTitle || "История выбрана"
      : "Свободный чат · без сюжета"
    : "Выбери персонажа, потом историю или свободный чат.";

  const balance = state.bootstrap.balance;
  if (balance) {
    els.tokenBalanceText.textContent = `${formatCompactNumber(balance.textTokensLeft)} токенов`;
    els.tokenPlanText.textContent = `${formatNumber(balance.imageCreditsLeft)} фото · ${formatNumber(balance.gifCreditsLeft || 0)} GIF осталось`;
  } else {
    els.tokenBalanceText.textContent = "Нет данных";
    els.tokenPlanText.textContent = "Баланс появится после загрузки бота.";
  }
}

function renderAudienceSettings() {
  if (!els.audienceSettings) return;
  const selected = resolveAudiencePreference();
  els.audienceSettings.replaceChildren();

  [
    { value: "female", label: "Девушки" },
    { value: "male", label: "Мужчины" },
  ].forEach((item) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "audience-settings-button";
    button.classList.toggle("active", selected === item.value);
    button.textContent = item.label;
    button.addEventListener("click", () => selectAudience(item.value, { stayOnSettings: true }));
    els.audienceSettings.append(button);
  });
}

function renderPaymentOptions() {
  const payments = state.bootstrap && state.bootstrap.payments || {};
  const plans = payments.plans || [];
  const packs = payments.packs || [];
  const gifPacks = payments.gifPacks || [];
  els.paymentOptions.replaceChildren();
  const tabs = [
    {
      key: "plans",
      label: "Токены",
      title: "Пакеты для общения",
      note: "Основной запас токенов и фото.",
      layout: "plans",
      items: plans.map((plan) => ({
        type: "plan",
        code: plan.code,
        title: plan.title,
        caption: "Пакет общения",
        badges: [
          `${formatCompactNumber(plan.textTokens)} токенов`,
          `${formatNumber(plan.imageCredits)} фото`,
        ],
        price: `${plan.priceRub} ₽`,
        featured: plan.code === "pro",
        badgeLabel: plan.code === "pro" ? "Выбор" : "",
      })),
    },
    {
      key: "packs",
      label: "Фото",
      title: "Пакеты изображений",
      note: "Если нужен запас только на новые кадры.",
      layout: "packs",
      items: packs.map((pack) => ({
        type: "pack",
        code: pack.code,
        title: pack.title,
        caption: "Разовый пакет",
        badges: [`${formatNumber(pack.imageCredits)} фото`],
        price: `${pack.priceRub} ₽`,
        featured: false,
        badgeLabel: "",
      })),
    },
    {
      key: "gif",
      label: "GIF",
      title: "Пакеты анимации",
      note: "Для оживления уже созданных изображений.",
      layout: "packs",
      items: gifPacks.map((pack) => ({
        type: "gif_pack",
        code: pack.code,
        title: pack.title,
        caption: "Разовый пакет",
        badges: [`${formatNumber(pack.gifCredits)} GIF`],
        price: `${pack.priceRub} ₽`,
        featured: false,
        badgeLabel: "",
      })),
    },
  ];

  if (!tabs.find((tab) => tab.key === state.settingsPaymentTab)) {
    state.settingsPaymentTab = "plans";
  }

  const tabsBar = document.createElement("div");
  tabsBar.className = "payment-tabs";
  tabs.forEach((tab) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "payment-tab";
    button.classList.toggle("active", state.settingsPaymentTab === tab.key);
    button.textContent = tab.label;
    button.addEventListener("click", () => {
      if (state.settingsPaymentTab === tab.key) return;
      state.settingsPaymentTab = tab.key;
      renderPaymentOptions();
    });
    tabsBar.append(button);
  });

  const currentTab = tabs.find((tab) => tab.key === state.settingsPaymentTab) || tabs[0];
  els.paymentOptions.append(tabsBar, paymentGroup(currentTab));
}

function paymentGroup(group) {
  const section = document.createElement("section");
  section.className = `payment-group payment-group--${group.layout}`;

  const head = document.createElement("div");
  head.className = "payment-group-head";

  const title = document.createElement("h3");
  title.textContent = group.title;

  const note = document.createElement("p");
  note.textContent = group.note;

  const grid = document.createElement("div");
  grid.className = `payment-group-grid payment-group-grid--${group.layout}`;

  group.items.forEach((item) => {
    grid.append(paymentButton(item));
  });

  head.append(title, note);
  section.append(head, grid);
  return section;
}

function paymentButton(option) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "payment-option";
  if (option.featured) button.classList.add("featured");
  button.addEventListener("click", () => createInvoice(option.type, option.code));

  const copy = document.createElement("span");
  copy.className = "payment-copy";

  const titleRow = document.createElement("span");
  titleRow.className = "payment-title-row";

  const title = document.createElement("strong");
  title.textContent = option.title;
  titleRow.append(title);

  if (option.badgeLabel) {
    const badge = document.createElement("span");
    badge.className = "payment-badge";
    badge.textContent = option.badgeLabel;
    titleRow.append(badge);
  }

  const caption = document.createElement("small");
  caption.className = "payment-caption";
  caption.textContent = option.caption;

  const badges = document.createElement("span");
  badges.className = "payment-badges";
  (option.badges || []).forEach((item) => {
    const chip = document.createElement("span");
    chip.className = "payment-chip";
    chip.textContent = item;
    badges.append(chip);
  });

  const price = document.createElement("span");
  price.className = "payment-price";
  price.textContent = option.price;

  copy.append(titleRow, caption, badges);
  button.append(copy, price);
  return button;
}

function planTitle(code) {
  const plans = state.bootstrap && state.bootstrap.payments && state.bootstrap.payments.plans || [];
  const plan = plans.find((item) => item.code === code);
  return plan ? plan.title : "без подписки";
}

function formatNumber(value) {
  return new Intl.NumberFormat("ru-RU").format(Number(value) || 0);
}

function formatCompactNumber(value) {
  return new Intl.NumberFormat("ru-RU", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(Number(value) || 0);
}

function selectedCharacter() {
  return (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === state.selectedCharacterId);
}

function previewedCharacter() {
  const characterId = state.previewCharacterId || state.selectedCharacterId;
  return (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === characterId);
}

function formatDialogTime(value) {
  const date = new Date(Number(value) || Date.now());
  const now = new Date();
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();

  if (sameDay) {
    return date.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  }

  return date.toLocaleDateString("ru-RU", { day: "numeric", month: "short" }).replace(".", "");
}

function finishInTelegram(sendData, fallbackText) {
  if (state.finishTimer) {
    window.clearTimeout(state.finishTimer);
  }

  if (sendData) {
    localStorage.setItem("emily:lastSelection", JSON.stringify(sendData));
  }

  showToast(fallbackText);
  state.finishTimer = window.setTimeout(openBotChat, 650);
}

function openBotChat() {
  const botUrl = state.bootstrap && state.bootstrap.bot && state.bootstrap.bot.url || DEFAULT_BOT_URL;

  if (tg && tg.openTelegramLink) {
    tg.openTelegramLink(botUrl);
    window.setTimeout(() => {
      if (typeof tg.close === "function") tg.close();
    }, 250);
    return;
  }

  window.location.href = botUrl;
}

function setLoading(isLoading) {
  if (els.loadingScreen) {
    els.loadingScreen.hidden = !isLoading;
    els.loadingScreen.style.display = isLoading ? "grid" : "none";
    els.loadingScreen.setAttribute("aria-hidden", String(!isLoading));
  }
  if (els.skipStoryButton) {
    els.skipStoryButton.disabled = isLoading;
  }
}

function showFatalError(error) {
  if (!els.loadingScreen) return;

  els.loadingScreen.hidden = false;
  els.loadingScreen.style.display = "grid";
  els.loadingScreen.setAttribute("aria-hidden", "false");
  els.loadingScreen.innerHTML = "";

  const title = document.createElement("strong");
  title.textContent = "Mini App не смог загрузиться";

  const message = document.createElement("p");
  message.textContent = error.message || "Проверь настройки MINI_APP_URL и Telegram initData.";

  els.loadingScreen.append(title, message);
}

function showToast(message) {
  if (tg && tg.HapticFeedback) {
    tg.HapticFeedback.notificationOccurred("warning");
  }

  const toast = document.createElement("div");
  toast.className = "toast";
  toast.textContent = message;
  document.body.append(toast);
  window.setTimeout(() => toast.remove(), 2600);
}
