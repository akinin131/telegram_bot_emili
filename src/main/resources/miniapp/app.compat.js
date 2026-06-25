"use strict";
var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __generator = (this && this.__generator) || function (thisArg, body) {
    var _ = { label: 0, sent: function() { if (t[0] & 1) throw t[1]; return t[1]; }, trys: [], ops: [] }, f, y, t, g = Object.create((typeof Iterator === "function" ? Iterator : Object).prototype);
    return g.next = verb(0), g["throw"] = verb(1), g["return"] = verb(2), typeof Symbol === "function" && (g[Symbol.iterator] = function() { return this; }), g;
    function verb(n) { return function (v) { return step([n, v]); }; }
    function step(op) {
        if (f) throw new TypeError("Generator is already executing.");
        while (g && (g = 0, op[0] && (_ = 0)), _) try {
            if (f = 1, y && (t = op[0] & 2 ? y["return"] : op[0] ? y["throw"] || ((t = y["return"]) && t.call(y), 0) : y.next) && !(t = t.call(y, op[1])).done) return t;
            if (y = 0, t) op = [op[0] & 2, t.value];
            switch (op[0]) {
                case 0: case 1: t = op; break;
                case 4: _.label++; return { value: op[1], done: false };
                case 5: _.label++; y = op[1]; op = [0]; continue;
                case 7: op = _.ops.pop(); _.trys.pop(); continue;
                default:
                    if (!(t = _.trys, t = t.length > 0 && t[t.length - 1]) && (op[0] === 6 || op[0] === 2)) { _ = 0; continue; }
                    if (op[0] === 3 && (!t || (op[1] > t[0] && op[1] < t[3]))) { _.label = op[1]; break; }
                    if (op[0] === 6 && _.label < t[1]) { _.label = t[1]; t = op; break; }
                    if (t && _.label < t[2]) { _.label = t[2]; _.ops.push(op); break; }
                    if (t[2]) _.ops.pop();
                    _.trys.pop(); continue;
            }
            op = body.call(thisArg, _);
        } catch (e) { op = [6, e]; y = 0; } finally { f = t = 0; }
        if (op[0] & 5) throw op[1]; return { value: op[0] ? op[1] : void 0, done: true };
    }
};
var tg = window.Telegram && window.Telegram.WebApp;
var DEFAULT_BOT_URL = "https://t.me/you_emily_bot";
var BOOTSTRAP_CACHE_KEY = "emily:miniappBootstrap:v2";
var GALLERY_CACHE_KEY = "emily:validatedGalleries:v1";
var PENDING_AUDIENCE_KEY = "emily:pendingAudience";
var DIALOG_PREVIEW_MESSAGE_LIMIT = 12;
document.documentElement.setAttribute("data-miniapp-boot", "started");
window.__miniappBootState = "started";
var state = {
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
var els = {
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
var screenTitles = {
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
}
catch (error) {
    document.documentElement.setAttribute("data-miniapp-stage", "crash");
    document.documentElement.setAttribute("data-miniapp-error", String(error && error.message || error));
    throw error;
}
function initTelegram() {
    if (!tg)
        return;
    safeTelegramCall(function () { return tg.ready(); });
    safeTelegramCall(function () { return tg.expand(); });
    safeTelegramCall(function () {
        if (typeof tg.setBackgroundColor === "function")
            tg.setBackgroundColor("#0b0b0f");
    });
    safeTelegramCall(function () {
        if (typeof tg.setHeaderColor === "function")
            tg.setHeaderColor("#0b0b0f");
    });
    var theme = tg.themeParams || {};
    if (theme.text_color)
        document.documentElement.style.setProperty("--text", theme.text_color);
    if (theme.hint_color)
        document.documentElement.style.setProperty("--muted", theme.hint_color);
    if (theme.button_color)
        document.documentElement.style.setProperty("--accent", theme.button_color);
    if (tg.BackButton && typeof tg.BackButton.onClick === "function") {
        safeTelegramCall(function () { return tg.BackButton.onClick(function () {
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
        }); });
    }
}
function bindEvents() {
    els.navPills.forEach(function (button) {
        button.addEventListener("click", function () {
            var target = button.dataset.target || "characters";
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
    document.querySelectorAll("[data-audience-choice]").forEach(function (button) {
        button.addEventListener("click", function () { return selectAudience(button.dataset.audienceChoice); });
    });
    on(els.backToCharacters, "click", function () { return showScreen("characters"); });
    on(els.backFromGallery, "click", function () { return showScreen("characters"); });
    on(els.backFromSettings, "click", function () { return showScreen(state.lastNonSettingsScreen); });
    on(els.skipStoryButton, "click", skipStory);
    on(els.closeGalleryViewer, "click", closeGalleryViewer);
    on(els.prevGalleryImage, "click", function () { return showGalleryImage(state.galleryIndex - 1); });
    on(els.nextGalleryImage, "click", function () { return showGalleryImage(state.galleryIndex + 1); });
    on(els.galleryViewerImage, "error", handleGalleryViewerError);
    on(els.galleryViewer, "click", function (event) {
        if (event.target === els.galleryViewer)
            closeGalleryViewer();
    });
}
function on(element, eventName, handler) {
    if (element) {
        element.addEventListener(eventName, handler);
    }
}
function loadBootstrap() {
    return __awaiter(this, arguments, void 0, function (nextScreen) {
        var data, targetScreen, settings, pendingAudience, error_1;
        if (nextScreen === void 0) { nextScreen = null; }
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    _a.trys.push([0, 2, , 3]);
                    return [4 /*yield*/, api("/miniapp/api/bootstrap")];
                case 1:
                    data = _a.sent();
                    targetScreen = nextScreen || state.currentScreen || "characters";
                    cacheBootstrap(data);
                    state.bootstrap = data;
                    syncStoriesByCharacter(data.storiesByCharacter);
                    applyPendingAudience();
                    if (state.pendingAudienceTarget) {
                        pendingAudience = state.pendingAudienceTarget;
                        applyAudienceSwitch(pendingAudience, {
                            characters: data.characters || [],
                            stories: data.stories,
                            selectedCharacter: (data.settings && data.settings.selectedCharacter) ||
                                state.selectedCharacterId ||
                                firstId(data.characters || []),
                        });
                    }
                    else {
                        reconcileAudienceState({ persist: true });
                    }
                    settings = state.bootstrap.settings || {};
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
                    return [3 /*break*/, 3];
                case 2:
                    error_1 = _a.sent();
                    document.documentElement.setAttribute("data-miniapp-stage", "load-error");
                    document.documentElement.setAttribute("data-miniapp-error", String(error_1 && error_1.message || error_1));
                    if (state.bootstrap) {
                        showToast(error_1.message || "Не удалось обновить данные");
                    }
                    else {
                        showFatalError(error_1);
                    }
                    return [3 /*break*/, 3];
                case 3: return [2 /*return*/];
            }
        });
    });
}
function hydrateCachedBootstrap() {
    var cached = readCachedBootstrap();
    if (!cached)
        return false;
    state.bootstrap = cached;
    syncStoriesByCharacter(cached.storiesByCharacter);
    applyPendingAudience();
    reconcileAudienceState({ persist: true });
    var settings = state.bootstrap.settings || {};
    state.audiencePreference = resolveAudiencePreference();
    state.selectedCharacterId =
        settings.selectedCharacter ||
            firstId(state.bootstrap.characters) ||
            null;
    if (ensureSelectedStoryTitle()) {
        cacheBootstrap(state.bootstrap);
    }
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
        var raw = localStorage.getItem(BOOTSTRAP_CACHE_KEY);
        if (!raw)
            return null;
        var data = JSON.parse(raw);
        return data && Array.isArray(data.characters) ? data : null;
    }
    catch (_error) {
        localStorage.removeItem(BOOTSTRAP_CACHE_KEY);
        return null;
    }
}
function cacheBootstrap(data) {
    try {
        localStorage.setItem(BOOTSTRAP_CACHE_KEY, JSON.stringify(data));
    }
    catch (_error) {
    }
}
function persistPendingAudience(audience) {
    try {
        localStorage.setItem(PENDING_AUDIENCE_KEY, audience);
    }
    catch (_e) { }
}
function clearPendingAudience() {
    try {
        localStorage.removeItem(PENDING_AUDIENCE_KEY);
    }
    catch (_e) { }
}
function applyPendingAudience() {
    try {
        var pending = localStorage.getItem(PENDING_AUDIENCE_KEY);
        if (pending && state.bootstrap && state.bootstrap.settings) {
            state.bootstrap.settings.audiencePreference = pending;
        }
    }
    catch (_e) { }
}
function hydrateCachedGalleries() {
    try {
        var cached_1 = JSON.parse(localStorage.getItem(GALLERY_CACHE_KEY) || "null");
        if (!cached_1 || Date.now() - Number(cached_1.savedAt || 0) > 86400000)
            return;
        Object.keys(cached_1.entries || {}).forEach(function (characterId) {
            var entry = cached_1.entries[characterId];
            if (entry && entry.character && Array.isArray(entry.images))
                state.galleryByCharacter[characterId] = Object.assign(Object.assign({}, entry), { validated: true });
        });
    }
    catch (_error) {
        localStorage.removeItem(GALLERY_CACHE_KEY);
    }
}
function cacheValidatedGalleries() {
    try {
        var entries_1 = {};
        Object.keys(state.galleryByCharacter).forEach(function (characterId) {
            var entry = state.galleryByCharacter[characterId];
            if (entry && entry.validated)
                entries_1[characterId] = entry;
        });
        localStorage.setItem(GALLERY_CACHE_KEY, JSON.stringify({ savedAt: Date.now(), entries: entries_1 }));
    }
    catch (_error) {
    }
}
function api(path_1) {
    return __awaiter(this, arguments, void 0, function (path, options) {
        var timeoutMs, response, _a, raw, data, telegramDescription, message, error;
        if (options === void 0) { options = {}; }
        return __generator(this, function (_b) {
            switch (_b.label) {
                case 0:
                    timeoutMs = options.timeoutMs || 20000;
                    if (!(typeof fetch === "function")) return [3 /*break*/, 2];
                    return [4 /*yield*/, apiWithFetch(path, options, timeoutMs)];
                case 1:
                    _a = _b.sent();
                    return [3 /*break*/, 4];
                case 2: return [4 /*yield*/, apiWithXhr(path, options, timeoutMs)];
                case 3:
                    _a = _b.sent();
                    _b.label = 4;
                case 4:
                    response = _a;
                    raw = response.text;
                    data = raw ? JSON.parse(raw) : {};
                    if (!response.ok || data.ok === false) {
                        telegramDescription = data.telegram && data.telegram.description;
                        message = telegramDescription
                            ? "".concat(data.error || "Ошибка Telegram", ": ").concat(telegramDescription)
                            : data.error || "\u041E\u0448\u0438\u0431\u043A\u0430 API ".concat(response.status);
                        error = new Error(message);
                        error.status = response.status;
                        error.data = data;
                        throw error;
                    }
                    return [2 /*return*/, data];
            }
        });
    });
}
function apiWithFetch(path, options, timeoutMs) {
    return __awaiter(this, void 0, void 0, function () {
        var controller, timeoutId, response;
        var _a;
        return __generator(this, function (_b) {
            switch (_b.label) {
                case 0:
                    controller = typeof AbortController === "function" ? new AbortController() : null;
                    timeoutId = controller
                        ? window.setTimeout(function () { return controller.abort(); }, timeoutMs)
                        : null;
                    _b.label = 1;
                case 1:
                    _b.trys.push([1, , 4, 5]);
                    return [4 /*yield*/, fetch(path, {
                            method: options.method || "GET",
                            signal: controller ? controller.signal : undefined,
                            headers: {
                                "Content-Type": "application/json",
                                "X-Telegram-Init-Data": tg ? tg.initData || "" : "",
                            },
                            body: options.body ? JSON.stringify(options.body) : undefined,
                        }).catch(function (error) {
                            if (error.name === "AbortError") {
                                throw new Error("Сервер бота отвечает слишком долго. Перезапусти бота и обнови Mini App.");
                            }
                            throw error;
                        })];
                case 2:
                    response = _b.sent();
                    _a = {
                        ok: response.ok,
                        status: response.status
                    };
                    return [4 /*yield*/, response.text()];
                case 3: return [2 /*return*/, (_a.text = _b.sent(),
                        _a)];
                case 4:
                    if (timeoutId != null) {
                        window.clearTimeout(timeoutId);
                    }
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function apiWithXhr(path, options, timeoutMs) {
    return new Promise(function (resolve, reject) {
        var xhr = new XMLHttpRequest();
        xhr.open(options.method || "GET", path, true);
        xhr.timeout = timeoutMs;
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.setRequestHeader("X-Telegram-Init-Data", tg ? tg.initData || "" : "");
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4)
                return;
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
        var savedStories = storiesForCharacterFromCache(state.selectedCharacterId);
        if (savedStories)
            state.bootstrap.stories = savedStories;
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
    ].forEach(function (screen) { return screen.classList.remove("active"); });
    if (name === "preference")
        els.preferenceScreen.classList.add("active");
    if (name === "characters")
        els.charactersScreen.classList.add("active");
    if (name === "stories")
        els.storiesScreen.classList.add("active");
    if (name === "dialogs")
        els.dialogsScreen.classList.add("active");
    if (name === "gallery")
        els.galleryScreen.classList.add("active");
    if (name === "settings")
        els.settingsScreen.classList.add("active");
    els.navPills.forEach(function (button) {
        var target = button.dataset.target;
        var active = name === target || (name === "stories" && target === "characters");
        button.classList.toggle("active", active);
    });
    if (tg && tg.BackButton && typeof tg.BackButton.hide === "function" && typeof tg.BackButton.show === "function") {
        if (name === "stories")
            safeTelegramCall(function () { return tg.BackButton.show(); });
        else
            safeTelegramCall(function () { return tg.BackButton.hide(); });
    }
}
function safeTelegramCall(callback) {
    try {
        callback();
    }
    catch (error) {
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
    if (!Array.isArray(characters) || characters.length === 0)
        return null;
    var audience = characters[0] && characters[0].audience;
    if (!audience)
        return null;
    return characters.every(function (item) { return item.audience === audience; }) ? audience : null;
}
function resolveAudiencePreference() {
    var settingsAudience, inferred;
    if (state.pendingAudienceTarget)
        return state.pendingAudienceTarget;
    settingsAudience = state.audiencePreference ||
        (state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference);
    inferred = inferAudienceFromCharacters(state.bootstrap && state.bootstrap.characters);
    if (inferred && settingsAudience && inferred !== settingsAudience) {
        return inferred;
    }
    return settingsAudience || inferred || null;
}
function syncAudiencePreference(audience) {
    if (!audience || !state.bootstrap)
        return;
    state.audiencePreference = audience;
    if (!state.bootstrap.settings)
        state.bootstrap.settings = {};
    state.bootstrap.settings.audiencePreference = audience;
}
function reconcileAudienceState(options) {
    if (options === void 0) { options = {}; }
    var inferred, audience;
    if (!state.bootstrap)
        return null;
    inferred = inferAudienceFromCharacters(state.bootstrap.characters);
    audience = resolveAudiencePreference();
    if (inferred && audience && inferred !== audience && !state.pendingAudienceTarget) {
        audience = inferred;
    }
    if (!audience)
        return null;
    syncAudiencePreference(audience);
    if (options.persist)
        cacheBootstrap(state.bootstrap);
    return audience;
}
function renderStatus() {
    var balance = state.bootstrap && state.bootstrap.balance;
    if (els.statusPanel)
        els.statusPanel.hidden = true;
    els.balanceText.textContent = balance
        ? "".concat(balance.textTokensLeft, " \u0442\u043E\u043A\u0435\u043D\u043E\u0432 / ").concat(balance.imageCreditsLeft, " \u0444\u043E\u0442\u043E")
        : "Нет данных";
}
function renderCharacters() {
    var allCharacters = state.bootstrap && state.bootstrap.characters || [];
    var audience = resolveAudiencePreference();
    var characters = audience
        ? allCharacters.filter(function (c) { return c.audience === audience; })
        : allCharacters;
    els.charactersGrid.replaceChildren();
    characters.forEach(function (character) {
        var card = document.createElement("button");
        card.type = "button";
        card.className = "character-card";
        if (character.id === state.selectedCharacterId)
            card.classList.add("selected");
        card.addEventListener("click", function () { return selectCharacter(character.id); });
        var image = document.createElement("img");
        image.src = character.imageUrl;
        image.alt = character.name;
        image.loading = "lazy";
        var info = document.createElement("div");
        info.className = "character-info";
        var title = document.createElement("h2");
        title.textContent = character.name;
        var description = document.createElement("p");
        description.textContent = character.description;
        var galleryButton = document.createElement("span");
        galleryButton.className = "character-gallery-button";
        galleryButton.textContent = "Фото";
        galleryButton.addEventListener("click", function (event) {
            event.stopPropagation();
            openGallery(character.id);
        });
        info.append(title, description);
        card.append(image, galleryButton, info);
        els.charactersGrid.append(card);
    });
}
function openGallery(characterId) {
    var character = (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === characterId; });
    if (!character)
        return showToast("Персонаж не найден");
    var cached = state.galleryByCharacter[characterId];
    state.galleryCharacterId = characterId;
    state.galleryImages = cached && cached.validated ? cached.images.slice() : [];
    renderGallery(cached ? cached.character : character, { pending: !(cached && cached.validated) });
    showScreen("gallery");
    fetchGallery(characterId, character)
        .then(function (entry) { return validateGalleryEntry(characterId, entry); })
        .then(function (entry) {
        if (state.currentScreen === "gallery" && state.galleryCharacterId === characterId) {
            state.galleryImages = entry.images.slice();
            renderGallery(entry.character);
        }
    })
        .catch(function (error) {
        if (state.currentScreen === "gallery" && state.galleryCharacterId === characterId) {
            renderGallery(character);
            showToast(error.message || "Не удалось обновить галерею");
        }
    });
}
function prefetchGalleries(characters) {
    var orderedCharacters = (characters || []).slice().sort(function (left, right) {
        if (left.id === state.selectedCharacterId)
            return -1;
        if (right.id === state.selectedCharacterId)
            return 1;
        return 0;
    });
    orderedCharacters.forEach(function (character) {
        fetchGallery(character.id, character)
            .then(function (entry) { return validateGalleryEntry(character.id, entry); })
            .catch(function () { });
    });
}
function fetchGallery(characterId, fallbackCharacter) {
    if (state.galleryRequests[characterId])
        return state.galleryRequests[characterId];
    var request = api("/miniapp/api/gallery?characterId=".concat(encodeURIComponent(characterId)))
        .then(function (data) {
        var images = (data.images || []).filter(function (item) { return !state.brokenGalleryImageIds.has(item.id); });
        var entry = { character: data.character || fallbackCharacter, images: images, validated: false };
        state.galleryByCharacter[characterId] = entry;
        delete state.galleryRequests[characterId];
        return entry;
    })
        .catch(function (error) {
        delete state.galleryRequests[characterId];
        throw error;
    });
    state.galleryRequests[characterId] = request;
    return request;
}
function validateGalleryEntry(characterId, entry) {
    if (entry.validated)
        return Promise.resolve(entry);
    if (state.galleryValidationRequests[characterId])
        return state.galleryValidationRequests[characterId];
    var request = Promise.all(entry.images.map(preloadGalleryImage))
        .then(function (results) {
        entry.images = results.filter(Boolean);
        entry.validated = true;
        state.galleryByCharacter[characterId] = entry;
        cacheValidatedGalleries();
        delete state.galleryValidationRequests[characterId];
        return entry;
    })
        .catch(function (error) {
        delete state.galleryValidationRequests[characterId];
        throw error;
    });
    state.galleryValidationRequests[characterId] = request;
    return request;
}
function preloadGalleryImage(item) {
    if (state.brokenGalleryImageIds.has(item.id))
        return Promise.resolve(null);
    return new Promise(function (resolve) {
        var image = new Image();
        var settled = false;
        var timeoutId;
        var finish = function (result, broken) {
            if (broken === void 0) { broken = false; }
            if (settled)
                return;
            settled = true;
            window.clearTimeout(timeoutId);
            image.onload = null;
            image.onerror = null;
            if (broken)
                state.brokenGalleryImageIds.add(item.id);
            resolve(result);
        };
        timeoutId = window.setTimeout(function () { return finish(null); }, 20000);
        image.onload = function () {
            if (typeof image.decode === "function") {
                image.decode().catch(function () { }).then(function () { return finish(item); });
                return;
            }
            finish(item);
        };
        image.onerror = function () { return finish(null, true); };
        image.src = item.imageUrl;
    });
}
function charactersForAudience(audience) {
    var byAudience = state.bootstrap && state.bootstrap.charactersByAudience;
    if (byAudience && Array.isArray(byAudience[audience])) {
        return byAudience[audience];
    }
    var currentAudience = (state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference) ||
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
        settings: state.bootstrap && state.bootstrap.settings ? Object.assign({}, state.bootstrap.settings) : null,
        characters: state.bootstrap && state.bootstrap.characters ? state.bootstrap.characters.slice() : [],
        stories: state.bootstrap && state.bootstrap.stories ? state.bootstrap.stories.slice() : [],
    };
}
function restoreAudienceSnapshot(snapshot) {
    if (!snapshot || !state.bootstrap)
        return;
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
function applyAudienceSwitch(audience, payload) {
    if (payload === void 0) { payload = {}; }
    if (!state.bootstrap)
        return;
    if (!state.bootstrap.settings)
        state.bootstrap.settings = {};
    var currentStory = state.bootstrap.settings.selectedStory || null;
    var currentStoryTitle = state.bootstrap.settings.selectedStoryTitle || null;
    var characters = payload.characters !== undefined
        ? payload.characters
        : (state.bootstrap.characters || charactersForAudience(audience) || []);
    var selectedCharacter = payload.selectedCharacter !== undefined
        ? payload.selectedCharacter
        : (state.selectedCharacterId || firstId(characters));
    var stories = payload.stories ||
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
    if (payload.storiesByCharacter)
        syncStoriesByCharacter(payload.storiesByCharacter);
    state.selectedCharacterId = selectedCharacter;
    cacheBootstrap(state.bootstrap);
    renderCharacters();
    renderSelectedCharacter();
    renderStories();
    renderSettings();
}
function selectAudience(audience_1) {
    return __awaiter(this, arguments, void 0, function (audience, options) {
        var requestId, previousSnapshot, localCharacters, targetScreen, data, error_3;
        if (options === void 0) { options = {}; }
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    if (!audience)
                        return [2 /*return*/];
                    if (resolveAudiencePreference() === audience)
                        return [2 /*return*/];
                    state.pendingAudienceTarget = audience;
                    requestId = ++state.pendingAudienceSwitchRequestId;
                    previousSnapshot = snapshotAudienceState();
                    localCharacters = charactersForAudience(audience);
                    if (!localCharacters && state.bootstrap) {
                        localCharacters = (state.bootstrap.characters || []).filter(function (c) { return c.audience === audience; });
                        if (localCharacters.length === 0) { localCharacters = null; }
                    }
                    targetScreen = options.stayOnSettings ? "settings" : "characters";
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
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
                    }
                    else {
                        syncAudiencePreference(audience);
                        cacheBootstrap(state.bootstrap);
                        renderAudienceSettings();
                    }
                    return [4 /*yield*/, api("/miniapp/api/audience", {
                            method: "POST",
                            body: { audience: audience },
                        })];
                case 2:
                    data = _a.sent();
                    if (requestId !== state.pendingAudienceSwitchRequestId)
                        return [2 /*return*/];
                    clearPendingAudience();
                    applyAudienceSwitch(data.audiencePreference, {
                        characters: data.characters,
                        stories: data.stories,
                        storiesByCharacter: data.storiesByCharacter,
                        selectedCharacter: data.selectedCharacter,
                        selectedStory: data.selectedStory,
                        selectedStoryTitle: data.selectedStoryTitle,
                    });
                    if (state.currentScreen === targetScreen) {
                        showScreen(targetScreen);
                    }
                    prefetchGalleries(data.characters || []);
                    return [3 /*break*/, 5];
                case 3:
                    error_3 = _a.sent();
                    if (requestId !== state.pendingAudienceSwitchRequestId)
                        return [2 /*return*/];
                    clearPendingAudience();
                    if (localCharacters) {
                        restoreAudienceSnapshot(previousSnapshot);
                        if (state.currentScreen === targetScreen) {
                            showScreen(targetScreen);
                        }
                    }
                    else {
                        state.audiencePreference = previousSnapshot.audiencePreference;
                        if (state.bootstrap && previousSnapshot.settings) {
                            state.bootstrap.settings = Object.assign({}, previousSnapshot.settings);
                        }
                        renderAudienceSettings();
                    }
                    showToast(error_3.message || "Не удалось сменить выбор");
                    return [3 /*break*/, 5];
                case 4:
                    if (requestId === state.pendingAudienceSwitchRequestId) {
                        state.pendingAudienceTarget = null;
                    }
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function renderGallery(character, options) {
    if (options === void 0) { options = {}; }
    els.galleryHeader.replaceChildren();
    els.galleryGrid.replaceChildren();
    var title = document.createElement("h2");
    title.textContent = "\u0413\u0430\u043B\u0435\u0440\u0435\u044F ".concat(character.name);
    var subtitle = document.createElement("p");
    subtitle.textContent = options.pending
        ? "Галерея открыта. Фотографии появятся через мгновение."
        : state.galleryImages.length
        ? "".concat(state.galleryImages.length, " \u0444\u043E\u0442\u043E. \u041D\u0430\u0436\u043C\u0438 \u043D\u0430 \u043B\u044E\u0431\u043E\u0435, \u0447\u0442\u043E\u0431\u044B \u043E\u0442\u043A\u0440\u044B\u0442\u044C \u043F\u0440\u043E\u0441\u043C\u043E\u0442\u0440.")
        : "У этого персонажа пока нет сгенерированных фото.";
    els.galleryHeader.append(title, subtitle);
    if (options.pending) {
        for (var index = 0; index < 6; index += 1) {
            var skeleton = document.createElement("div");
            skeleton.className = "gallery-tile gallery-tile-skeleton";
            els.galleryGrid.append(skeleton);
        }
        return;
    }
    if (state.galleryImages.length === 0) {
        var empty = document.createElement("div");
        empty.className = "empty-dialogs";
        empty.textContent = "Сгенерируй картинку в чате, и она появится здесь.";
        els.galleryGrid.append(empty);
        return;
    }
    state.galleryImages.forEach(function (item) {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "gallery-tile";
        button.addEventListener("click", function () {
            var currentIndex = state.galleryImages.findIndex(function (candidate) { return candidate.id === item.id; });
            if (currentIndex >= 0)
                openGalleryViewer(currentIndex);
        });
        var image = document.createElement("img");
        image.src = item.imageUrl;
        image.alt = item.prompt || "Сгенерированное фото";
        image.loading = "eager";
        image.decoding = "async";
        image.addEventListener("error", function () { return removeBrokenGalleryImage(character, item.id); });
        var meta = document.createElement("span");
        meta.textContent = formatDialogTime(item.createdAt);
        button.append(image, meta);
        els.galleryGrid.append(button);
    });
}
function removeBrokenGalleryImage(character, imageId) {
    state.brokenGalleryImageIds.add(imageId);
    state.galleryImages = state.galleryImages.filter(function (item) { return item.id !== imageId; });
    var cached = state.galleryByCharacter[state.galleryCharacterId];
    if (cached)
        cached.images = cached.images.filter(function (item) { return item.id !== imageId; });
    cacheValidatedGalleries();
    renderGallery(character);
}
function openGalleryViewer(index) {
    if (!state.galleryImages.length)
        return;
    els.galleryViewer.hidden = false;
    showGalleryImage(index);
}
function closeGalleryViewer() {
    els.galleryViewer.hidden = true;
    els.galleryViewerImage.removeAttribute("src");
}
function showGalleryImage(index) {
    if (!state.galleryImages.length)
        return;
    state.galleryIndex = (index + state.galleryImages.length) % state.galleryImages.length;
    var item = state.galleryImages[state.galleryIndex];
    els.galleryViewerImage.src = item.imageUrl;
    els.galleryViewerImage.alt = item.prompt || "Сгенерированное фото";
    els.galleryViewerMeta.textContent = "".concat(state.galleryIndex + 1, " \u0438\u0437 ").concat(state.galleryImages.length, " \u00B7 ").concat(formatDialogTime(item.createdAt));
}
function handleGalleryViewerError() {
    var item = state.galleryImages[state.galleryIndex];
    var character = (state.bootstrap && state.bootstrap.characters || [])
        .find(function (candidate) { return candidate.id === state.galleryCharacterId; });
    if (!item || !character) {
        closeGalleryViewer();
        return;
    }
    removeBrokenGalleryImage(character, item.id);
    if (state.galleryImages.length === 0) {
        closeGalleryViewer();
        return;
    }
    state.galleryIndex %= state.galleryImages.length;
    showGalleryImage(state.galleryIndex);
}
function selectCharacter(characterId) {
    var character = (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === characterId; });
    if (!character) {
        showToast("Персонаж не найден");
        return;
    }
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
        body: { characterId: characterId },
    }).catch(function () {});
    var cachedStories = storiesForCharacterFromCache(characterId);
    if (cachedStories) {
        state.bootstrap.stories = cachedStories;
        renderStories();
        return;
    }
    loadStoriesForCharacter(characterId);
}
function refreshStoriesScreenIfPending() {
    var stories;
    if (state.currentScreen !== "stories" || !state.previewCharacterId || !state.bootstrap)
        return;
    stories = storiesForCharacterFromCache(state.previewCharacterId);
    if (!stories)
        return;
    state.bootstrap.stories = stories;
    renderStories();
}
function loadStoriesForCharacter(characterId) {
    return __awaiter(this, void 0, void 0, function () {
        var requestId, stories, character, error_4;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    requestId = ++state.pendingCharacterRequestId;
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, , 4]);
                    return [4 /*yield*/, fetchStoriesForCharacter(characterId)];
                case 2:
                    stories = _a.sent();
                    if (requestId !== state.pendingCharacterRequestId)
                        return [2 /*return*/];
                    if (state.currentScreen !== "stories" || state.previewCharacterId !== characterId)
                        return [2 /*return*/];
                    state.bootstrap.stories = stories;
                    renderStories();
                    return [3 /*break*/, 4];
                case 3:
                    error_4 = _a.sent();
                    if (requestId !== state.pendingCharacterRequestId)
                        return [2 /*return*/];
                    if (state.currentScreen !== "stories" || state.previewCharacterId !== characterId)
                        return [2 /*return*/];
                    character = (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === characterId; });
                    renderStoriesLoadError(character, characterId);
                    showToast(error_4.message || "Не удалось загрузить истории");
                    return [3 /*break*/, 4];
                case 4: return [2 /*return*/];
            }
        });
    });
}
function fetchStoriesForCharacter(characterId) {
    return __awaiter(this, void 0, void 0, function () {
        var data, stories;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0: return [4 /*yield*/, api("/miniapp/api/stories?characterId=".concat(encodeURIComponent(characterId)))];
                case 1:
                    data = _a.sent();
                    stories = Array.isArray(data.stories) ? data.stories : [];
                    cacheStoriesForCharacter(characterId, stories);
                    return [2 /*return*/, stories];
            }
        });
    });
}
function cacheStoriesForCharacter(characterId, stories) {
    setCachedStories(characterId, stories);
    if (!state.bootstrap)
        return;
    if (!state.bootstrap.storiesByCharacter || typeof state.bootstrap.storiesByCharacter !== "object") {
        state.bootstrap.storiesByCharacter = Object.assign({}, state.storiesByCharacter);
    }
    else {
        state.bootstrap.storiesByCharacter[characterId] = stories.slice();
    }
    cacheBootstrap(state.bootstrap);
}
function renderSelectedCharacter() {
    var character = previewedCharacter();
    els.selectedCharacterPanel.replaceChildren();
    if (!character) {
        els.selectedCharacterPanel.textContent = "Сначала выбери персонажа.";
        return;
    }
    var image = document.createElement("img");
    image.className = "selected-character-avatar";
    image.src = character.imageUrl;
    image.alt = character.name;
    var copy = document.createElement("div");
    copy.className = "selected-character-copy";
    var label = document.createElement("span");
    label.className = "selected-character-label";
    label.textContent = "Твой персонаж";
    var title = document.createElement("strong");
    title.textContent = character.name;
    var description = document.createElement("p");
    description.textContent = character.description;
    copy.append(label, title, description);
    els.selectedCharacterPanel.append(image, copy);
}
function renderStoriesPending() {
    els.storiesList.replaceChildren();
    for (var index = 0; index < 3; index += 1) {
        var card = document.createElement("div");
        card.className = "story-card story-card-skeleton";
        card.setAttribute("aria-hidden", "true");
        var copy = document.createElement("span");
        copy.className = "story-card-copy story-card-skeleton-copy";
        copy.append(document.createElement("span"), document.createElement("span"), document.createElement("span"));
        var arrow = document.createElement("span");
        arrow.className = "story-card-skeleton-arrow";
        arrow.setAttribute("aria-hidden", "true");
        card.append(copy, arrow);
        els.storiesList.append(card);
    }
}
function renderStoriesLoadError(character, characterId) {
    els.storiesList.replaceChildren();
    var message = document.createElement("div");
    message.className = "empty-dialogs";
    message.textContent = character
        ? "\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u0437\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u044C \u0438\u0441\u0442\u043E\u0440\u0438\u0438 \u0434\u043B\u044F ".concat(character.name, ".")
        : "\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u0437\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u044C \u0438\u0441\u0442\u043E\u0440\u0438\u0438.";
    var retryButton = document.createElement("button");
    retryButton.type = "button";
    retryButton.className = "dialog-choice-continue";
    retryButton.textContent = "Повторить";
    retryButton.addEventListener("click", function () {
        renderStoriesPending();
        loadStoriesForCharacter(characterId);
    });
    var actions = document.createElement("div");
    actions.className = "dialog-choice-actions";
    actions.append(retryButton);
    els.storiesList.append(message, actions);
}
function syncStoriesByCharacter(storiesByCharacter) {
    state.storiesByCharacter = storiesByCharacter && typeof storiesByCharacter === "object"
        ? Object.assign({}, storiesByCharacter)
        : {};
}
function setCachedStories(characterId, stories) {
    if (!characterId || !Array.isArray(stories))
        return;
    state.storiesByCharacter[characterId] = stories.slice();
    if (state.bootstrap && state.bootstrap.storiesByCharacter) {
        state.bootstrap.storiesByCharacter[characterId] = stories.slice();
    }
}
function storiesForCharacterFromCache(characterId) {
    var stories = state.storiesByCharacter && state.storiesByCharacter[characterId];
    return Array.isArray(stories) ? stories.slice() : null;
}
function renderStories() {
    var stories = state.bootstrap && state.bootstrap.stories || [];
    els.storiesList.replaceChildren();
    stories.forEach(function (story) {
        var card = document.createElement("button");
        card.type = "button";
        card.className = "story-card";
        card.addEventListener("click", function () { return selectStory(story.id, card); });
        var copy = document.createElement("span");
        copy.className = "story-card-copy";
        if (story.custom) { card.classList.add("story-card-custom"); }
        var title = document.createElement("h2");
        title.textContent = story.title;
        var description = document.createElement("p");
        description.textContent = story.description;
        var setup = document.createElement("p");
        setup.className = "story-setup";
        setup.textContent = story.setup;
        var arrow = document.createElement("span");
        arrow.className = "story-arrow";
        arrow.setAttribute("aria-hidden", "true");
        arrow.textContent = "→";
        copy.append(title, description);
        card.append(copy, arrow, setup);
        if (story.custom) {
            var controls = document.createElement("span");
            controls.className = "story-card-controls";
            var editBtn = document.createElement("button");
            editBtn.className = "story-card-edit";
            editBtn.type = "button";
            editBtn.textContent = "\u270E";
            editBtn.addEventListener("click", function (event) {
                event.stopPropagation();
                openCustomStoryEditor(story);
            });
            var deleteBtn = document.createElement("button");
            deleteBtn.className = "story-card-delete";
            deleteBtn.type = "button";
            deleteBtn.textContent = "\u00D7";
            deleteBtn.addEventListener("click", function (event) {
                event.stopPropagation();
                deleteCustomStory(story.id);
            });
            controls.append(editBtn, deleteBtn);
            card.append(controls);
        }
        els.storiesList.append(card);
    });
    els.storiesList.append(customStoryCard());
}
function customStoryCard() {
    var access = state.bootstrap && state.bootstrap.customStory || {};
    var slotsLeft = Number(access.storySlotsLeft || 0);
    var slotsTotal = Number(access.storySlotsTotal || 30);
    var card = document.createElement("button");
    card.type = "button";
    card.className = "story-card custom-story-card";
    card.addEventListener("click", function () { return openCustomStoryEditor(); });
    if (slotsLeft > 0) {
        card.classList.add("custom-story-card-unlocked");
    }
    var plus = document.createElement("span");
    plus.className = "custom-story-plus";
    plus.textContent = "+";
    var copy = document.createElement("span");
    copy.className = "custom-story-copy";
    var title = document.createElement("h2");
    title.textContent = "Создать свою ролевую игру";
    var arrow = document.createElement("span");
    arrow.className = "custom-story-arrow";
    arrow.setAttribute("aria-hidden", "true");
    arrow.textContent = "→";
    var badge = document.createElement("span");
    badge.className = "custom-story-badge";
    if (slotsLeft > 0) {
        badge.textContent = "Осталось: ".concat(slotsLeft, " из ").concat(slotsTotal);
    } else {
        badge.textContent = "Лимит ".concat(slotsTotal, " историй исчерпан. Удали одну, чтобы создать новую.");
        badge.style.color = "var(--danger, #ff4444)";
    }
    copy.append(title, badge);
    card.append(plus, copy, arrow);
    return card;
}
function openCustomStoryEditor(existingStory) {
    var _this = this;
    var character;
    if (existingStory) {
        character = (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === existingStory.characterId; });
    } else {
        character = previewedCharacter();
    }
    if (!character) {
        showToast("Сначала выбери персонажа");
        return;
    }
    var isEditing = Boolean(existingStory);
    var overlay = document.createElement("div");
    overlay.className = "custom-story-modal";
    overlay.innerHTML = "\n    <form class=\"custom-story-form\">\n      <button class=\"custom-story-close\" type=\"button\" aria-label=\"\u0417\u0430\u043A\u0440\u044B\u0442\u044C\">\u00D7</button>\n      <p class=\"custom-story-kicker\">\u0421\u0432\u043E\u044F \u0438\u0441\u0442\u043E\u0440\u0438\u044F \u0434\u043B\u044F ".concat(escapeHtml(character.name), "</p>\n      <h2>").concat(isEditing ? "Редактировать сценарий" : "Создай сценарий", "</h2>\n      <label>\n        \u041D\u0430\u0437\u0432\u0430\u043D\u0438\u0435\n        <input name=\"title\" maxlength=\"60\" placeholder=\"\u041D\u0430\u043F\u0440\u0438\u043C\u0435\u0440: \u041D\u043E\u0447\u043D\u0430\u044F \u043F\u043E\u0435\u0437\u0434\u043A\u0430\" required>\n      </label>\n      <label>\n        \u041A\u043E\u0440\u043E\u0442\u043A\u043E\u0435 \u043E\u043F\u0438\u0441\u0430\u043D\u0438\u0435\n        <input name=\"description\" maxlength=\"160\" placeholder=\"\u0427\u0442\u043E \u0443\u0432\u0438\u0434\u0438\u0442 \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044C \u043D\u0430 \u043A\u0430\u0440\u0442\u043E\u0447\u043A\u0435\">\n      </label>\n      <label>\n        \u0421\u0446\u0435\u043D\u0430 \u0438 \u043F\u0440\u0430\u0432\u0438\u043B\u0430 \u0438\u0441\u0442\u043E\u0440\u0438\u0438\n        <div class=\"setup-wrapper\">\n          <textarea name=\"setup\" maxlength=\"900\" rows=\"5\" placeholder=\"\u0413\u0434\u0435 \u0432\u044B, \u0447\u0442\u043E \u043F\u0440\u043E\u0438\u0441\u0445\u043E\u0434\u0438\u0442, \u043A\u0430\u043A\u0430\u044F \u0440\u043E\u043B\u044C \u0443 \u043F\u0435\u0440\u0441\u043E\u043D\u0430\u0436\u0430...\" required></textarea>\n          <button type=\"button\" class=\"setup-ai-btn\" title=\"\u0423\u043B\u0443\u0447\u0448\u0438\u0442\u044C \u0441 \u043F\u043E\u043C\u043E\u0449\u044C\u044E \u0418\u0418\" disabled>\u2728</button>\n        </div>\n      </label>\n      <label>\n        \u041F\u0435\u0440\u0432\u043E\u0435 \u0441\u043E\u043E\u0431\u0449\u0435\u043D\u0438\u0435 \u043F\u0435\u0440\u0441\u043E\u043D\u0430\u0436\u0430\n        <textarea name=\"openingLine\" maxlength=\"240\" rows=\"3\" placeholder=\"\u0424\u0440\u0430\u0437\u0430, \u0441 \u043A\u043E\u0442\u043E\u0440\u043E\u0439 \u043D\u0430\u0447\u043D\u0435\u0442\u0441\u044F \u0447\u0430\u0442\" required></textarea>\n      </label>\n      <button class=\"primary-button\" type=\"submit\">").concat(isEditing ? "Сохранить изменения" : "Сохранить историю", "</button>\n    </form>\n  ");
    if (isEditing) {
        var titleInput = overlay.querySelector("input[name=\"title\"]");
        if (titleInput) titleInput.value = existingStory.title;
        var descInput = overlay.querySelector("input[name=\"description\"]");
        if (descInput) descInput.value = existingStory.description || "";
        var setupTA = overlay.querySelector("textarea[name=\"setup\"]");
        if (setupTA) setupTA.value = existingStory.setup || "";
        var openingTA = overlay.querySelector("textarea[name=\"openingLine\"]");
        if (openingTA) openingTA.value = existingStory.openingLine || "";
    }
    var close = function () { return overlay.remove(); };
    overlay.addEventListener("click", function (event) {
        if (event.target === overlay)
            close();
    });
    overlay.querySelector(".custom-story-close").addEventListener("click", close);
    overlay.querySelector("form").addEventListener("submit", function (event) { return __awaiter(_this, void 0, void 0, function () {
        var form, payload;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    event.preventDefault();
                    form = new FormData(event.currentTarget);
                    payload = {
                        characterId: character.id,
                        title: String(form.get("title") || ""),
                        description: String(form.get("description") || ""),
                        setup: String(form.get("setup") || ""),
                        openingLine: String(form.get("openingLine") || ""),
                    };
                    if (!isEditing) return [3 /*break*/, 2];
                    return [4 /*yield*/, updateCustomStory(existingStory.id, payload)];
                case 1:
                    _a.sent();
                    return [3 /*break*/, 4];
                case 2: return [4 /*yield*/, createCustomStory(payload)];
                case 3:
                    _a.sent();
                    _a.label = 4;
                case 4:
                    close();
                    return [2 /*return*/];
            }
        });
    }); });
    var setupTextarea = overlay.querySelector("textarea[name=\"setup\"]");
    var aiBtn = overlay.querySelector(".setup-ai-btn");
    if (setupTextarea && aiBtn) {
        var MIN_AI_CHARS = 20;
        var updateAiBtn = function () {
            aiBtn.disabled = setupTextarea.value.trim().length < MIN_AI_CHARS || aiBtn.classList.contains("loading");
        };
        setupTextarea.addEventListener("input", updateAiBtn);
        aiBtn.addEventListener("click", function () { return __awaiter(_this, void 0, void 0, function () {
            var data, error_9;
            return __generator(this, function (_a) {
                switch (_a.label) {
                    case 0:
                        if (aiBtn.disabled) return [2 /*return*/];
                        aiBtn.classList.add("loading");
                        aiBtn.disabled = true;
                        aiBtn.title = "\u0423\u043B\u0443\u0447\u0448\u0430\u044E...";
                        _a.label = 1;
                    case 1:
                        _a.trys.push([1, 3, 4, 5]);
                        return [4 /*yield*/, api("/miniapp/api/expand-setup", {
                                method: "POST",
                                body: { text: setupTextarea.value },
                            })];
                    case 2:
                        data = _a.sent();
                        if (data.expanded) {
                            setupTextarea.value = data.expanded;
                            setupTextarea.dispatchEvent(new Event("input"));
                        }
                        return [3 /*break*/, 5];
                    case 3:
                        error_9 = _a.sent();
                        showToast(error_9.message || "\u041D\u0435 \u0443\u0434\u0430\u043B\u043E\u0441\u044C \u0443\u043B\u0443\u0447\u0448\u0438\u0442\u044C \u0442\u0435\u043A\u0441\u0442");
                        return [3 /*break*/, 5];
                    case 4:
                        aiBtn.classList.remove("loading");
                        aiBtn.title = "\u0423\u043B\u0443\u0447\u0448\u0438\u0442\u044C \u0441 \u043F\u043E\u043C\u043E\u0449\u044C\u044E \u0418\u0418";
                        updateAiBtn();
                        return [7 /*endfinally*/];
                    case 5: return [2 /*return*/];
                }
            });
        }); });
        updateAiBtn();
    }
    document.body.append(overlay);
    var firstInput = overlay.querySelector("input");
    if (firstInput)
        firstInput.focus();
}
function createCustomStory(payload) {
    return __awaiter(this, void 0, void 0, function () {
        var data, error_6;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/custom-story", {
                            method: "POST",
                            body: payload,
                        })];
                case 2:
                    data = _a.sent();
                    state.bootstrap.stories = data.stories || state.bootstrap.stories;
                    setCachedStories(payload.characterId, state.bootstrap.stories);
                    state.bootstrap.customStory = data.customStory || state.bootstrap.customStory;
                    cacheBootstrap(state.bootstrap);
                    renderStories();
                    showToast("История создана. Теперь её можно выбрать.");
                    return [3 /*break*/, 5];
                case 3:
                    error_6 = _a.sent();
                    showToast(error_6.message || "Не удалось создать историю");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function updateCustomStory(storyId, payload) {
    return __awaiter(this, void 0, void 0, function () {
        var data, error_7;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/custom-story", {
                            method: "PUT",
                            body: { storyId: storyId, characterId: payload.characterId, title: payload.title, description: payload.description, setup: payload.setup, openingLine: payload.openingLine },
                        })];
                case 2:
                    data = _a.sent();
                    state.bootstrap.stories = data.stories || state.bootstrap.stories;
                    setCachedStories(payload.characterId, state.bootstrap.stories);
                    cacheBootstrap(state.bootstrap);
                    renderStories();
                    showToast("История обновлена.");
                    return [3 /*break*/, 5];
                case 3:
                    error_7 = _a.sent();
                    showToast(error_7.message || "Не удалось обновить историю");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function deleteCustomStory(storyId) {
    return __awaiter(this, void 0, void 0, function () {
        var character, data, error_8;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    if (!confirm("Удалить эту историю? Это действие нельзя отменить.")) return [2 /*return*/];
                    character = previewedCharacter();
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/delete-custom-story", {
                            method: "POST",
                            body: { storyId: storyId, characterId: character ? character.id : "" },
                        })];
                case 2:
                    data = _a.sent();
                    state.bootstrap.stories = data.stories || state.bootstrap.stories;
                    state.bootstrap.customStory = data.customStory || state.bootstrap.customStory;
                    setCachedStories(character ? character.id : "", state.bootstrap.stories);
                    cacheBootstrap(state.bootstrap);
                    renderStories();
                    showToast("История удалена. Слот освобождён.");
                    return [3 /*break*/, 5];
                case 3:
                    error_8 = _a.sent();
                    showToast(error_8.message || "Не удалось удалить историю");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function renderDialogs() {
    var dialogs = state.bootstrap && state.bootstrap.dialogs || [];
    els.dialogsList.replaceChildren();
    if (dialogs.length === 0) {
        var empty = document.createElement("div");
        empty.className = "empty-dialogs";
        empty.textContent = "Пока нет сохранённых диалогов. Выбери персонажа и историю, чтобы создать первый.";
        els.dialogsList.append(empty);
        return;
    }
    var contextOccurrences = new Map();
    dialogs.forEach(function (dialog) {
        var contextKey = "".concat(dialog.characterId, ":").concat(dialog.storyId || "free-chat");
        var occurrence = (contextOccurrences.get(contextKey) || 0) + 1;
        contextOccurrences.set(contextKey, occurrence);
        var visual = dialogVisual(dialog);
        var row = document.createElement("button");
        row.type = "button";
        row.className = "dialog-row";
        row.style.setProperty("--dialog-accent", visual.color);
        row.addEventListener("click", function () { return restoreDialog(dialog.id); });
        var avatar = document.createElement("div");
        avatar.className = "dialog-avatar";
        var image = document.createElement("img");
        image.src = dialog.characterImageUrl;
        image.alt = dialog.characterName;
        image.loading = "lazy";
        var storyMark = document.createElement("span");
        storyMark.className = "dialog-story-mark";
        storyMark.textContent = visual.mark;
        avatar.append(image, storyMark);
        if (occurrence > 1) {
            var sequence = document.createElement("span");
            sequence.className = "dialog-sequence";
            sequence.textContent = String(occurrence);
            avatar.append(sequence);
        }
        var main = document.createElement("div");
        main.className = "dialog-main";
        var title = document.createElement("div");
        title.className = "dialog-title";
        var character = document.createElement("span");
        character.className = "dialog-character";
        character.textContent = dialog.characterName;
        var story = document.createElement("span");
        story.className = "dialog-story";
        story.textContent = dialog.storyTitle || "Свободный чат";
        title.append(character, story);
        var preview = document.createElement("div");
        preview.className = "dialog-preview";
        preview.textContent = dialog.lastMessage || "Диалог без сообщений";
        main.append(title, preview);
        var time = document.createElement("div");
        time.className = "dialog-time";
        time.textContent = formatDialogTime(dialog.updatedAt);
        row.append(avatar, main, time);
        els.dialogsList.append(row);
    });
}
function dialogVisual(dialog) {
    var palette = ["#ff5d8f", "#35c8b2", "#f3b83f", "#5f9df7", "#cf6df2", "#ff7657"];
    var title = dialog.storyTitle || "Свободный чат";
    var words = title.trim().split(/\s+/).filter(Boolean);
    var mark = dialog.storyId
        ? words.slice(0, 2).map(function (word) { return word[0]; }).join("").toLocaleUpperCase("ru-RU")
        : "ЧА";
    var key = dialog.storyId || "free-chat";
    var hash = 0;
    for (var index = 0; index < key.length; index += 1) {
        hash = ((hash << 5) - hash + key.charCodeAt(index)) | 0;
    }
    return { color: palette[Math.abs(hash) % palette.length], mark: mark };
}
function restoreDialog(dialogId) {
    var dialog = findDialogById(dialogId);
    setLoading(true);
    return loadDialogPreview(dialog || { id: dialogId })
        .then(function (previewDialog) {
        dialog = previewDialog;
    })
        .catch(function (error) {
        showToast(error.message || "Не удалось загрузить диалог");
        dialog = null;
        return null;
    })
        .then(function () {
        setLoading(false);
        if (!dialog)
            return null;
        return confirmDialogReuse(dialog, {
            allowRestart: false,
            title: "Открыть сохранённый диалог?",
            secondaryLabel: "Отмена",
        });
    })
        .then(function (shouldContinue) {
        if (shouldContinue !== true)
            return null;
        setLoading(true);
        return api("/miniapp/api/restore-dialog", {
            method: "POST",
            body: { dialogId: dialogId },
        })
            .then(function (data) {
            finishInTelegram(data.sendData, "Диалог восстановлен. Возвращаю в чат бота.");
        })
            .catch(function (error) {
            showToast(error.message || "Не удалось восстановить диалог");
        })
            .then(function () {
            setLoading(false);
        });
    });
}
function findDialogById(dialogId) {
    return (state.bootstrap && state.bootstrap.dialogs || [])
        .find(function (dialog) { return dialog.id === dialogId; }) || null;
}
function loadDialogPreview(dialog) {
    if (!dialog || !dialog.id)
        return Promise.resolve(dialog);
    if (Array.isArray(dialog.recentMessages) &&
        dialog.recentMessages.length >= DIALOG_PREVIEW_MESSAGE_LIMIT &&
        Object.prototype.hasOwnProperty.call(dialog, "story"))
        return Promise.resolve(dialog);
    return api("/miniapp/api/dialog-preview?dialogId=".concat(encodeURIComponent(dialog.id)))
        .then(function (data) {
        var nextDialog = Object.assign({}, dialog, data.dialog || {}, {
            story: data.story || null,
            recentMessages: Array.isArray(data.recentMessages) ? data.recentMessages : [],
        });
        if (state.bootstrap && Array.isArray(state.bootstrap.dialogs)) {
            var index = state.bootstrap.dialogs.findIndex(function (item) { return item.id === nextDialog.id; });
            if (index >= 0)
                state.bootstrap.dialogs[index] = nextDialog;
        }
        return nextDialog;
    });
}
function confirmExistingDialog(dialog, options) {
    if (options === void 0) { options = {}; }
    return __awaiter(this, void 0, void 0, function () {
        var previewDialog, error_6;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    previewDialog = dialog;
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    setLoading(true);
                    return [4 /*yield*/, loadDialogPreview(dialog)];
                case 2:
                    previewDialog = _a.sent();
                    return [3 /*break*/, 5];
                case 3:
                    error_6 = _a.sent();
                    showToast(error_6.message || "Не удалось загрузить диалог");
                    return [2 /*return*/, null];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/, confirmDialogReuse(previewDialog, options)];
            }
        });
    });
}
function findExistingDialogForContext(characterId, storyId) {
    var dialogs = state.bootstrap && state.bootstrap.dialogs || [];
    var normalizedStoryId = storyId || null;
    return dialogs
        .filter(function (dialog) { return dialog.characterId === characterId && (dialog.storyId || null) === normalizedStoryId; })
        .sort(function (left, right) { return Number(right.updatedAt || 0) - Number(left.updatedAt || 0); })[0] || null;
}
function confirmDialogReuse(dialog, options) {
    if (options === void 0) { options = {}; }
    if (!dialog || document.querySelector(".dialog-choice-modal"))
        return Promise.resolve(null);
    var allowRestart = options.allowRestart !== false;
    var mode = dialog.storyTitle || "Свободный чат";
    return new Promise(function (resolve) {
        var overlay = document.createElement("div");
        overlay.className = "dialog-choice-modal";
        overlay.setAttribute("role", "dialog");
        overlay.setAttribute("aria-modal", "true");
        var panel = document.createElement("div");
        panel.className = "dialog-choice-panel";
        var closeButton = document.createElement("button");
        closeButton.type = "button";
        closeButton.className = "dialog-choice-close";
        closeButton.setAttribute("aria-label", "Закрыть");
        closeButton.textContent = "×";
        var avatar = document.createElement("img");
        avatar.className = "dialog-choice-avatar";
        avatar.src = dialog.characterImageUrl || "";
        avatar.alt = "";
        var heading = document.createElement("div");
        heading.className = "dialog-choice-heading";
        var kicker = document.createElement("span");
        kicker.className = "dialog-choice-kicker";
        kicker.textContent = "Сохранённый диалог";
        var title = document.createElement("h2");
        title.textContent = options.title || "Продолжить разговор?";
        var context = document.createElement("div");
        context.className = "dialog-choice-context";
        context.textContent = "".concat(dialog.characterName || "Диалог", " \u00B7 ").concat(mode);
        var meta = document.createElement("div");
        meta.className = "dialog-choice-meta";
        meta.textContent = dialog.updatedAt ? "\u041E\u0431\u043D\u043E\u0432\u043B\u0451\u043D ".concat(formatDialogTime(dialog.updatedAt)) : "";
        var description = document.createElement("p");
        description.className = "dialog-choice-description";
        description.textContent = "Сцена и последние реплики помогут быстро вспомнить, где остановился разговор.";
        var storyContext = dialogStoryContext(dialog);
        var recent = dialogRecentMessages(dialog);
        var actions = document.createElement("div");
        actions.className = "dialog-choice-actions";
        var continueButton = document.createElement("button");
        continueButton.type = "button";
        continueButton.className = "dialog-choice-continue";
        continueButton.textContent = "Продолжить";
        var restartButton = document.createElement("button");
        restartButton.type = "button";
        restartButton.className = "dialog-choice-restart";
        restartButton.textContent = options.secondaryLabel || "Начать заново";
        var finish = function (choice) {
            overlay.remove();
            resolve(choice);
        };
        continueButton.addEventListener("click", function () { return finish(true); });
        restartButton.addEventListener("click", function () { return finish(allowRestart ? false : null); });
        closeButton.addEventListener("click", function () { return finish(null); });
        overlay.addEventListener("click", function (event) {
            if (event.target === overlay)
                finish(null);
        });
        actions.append(continueButton, restartButton);
        heading.append(kicker, title, context, meta);
        panel.append(closeButton, avatar, heading, description);
        if (storyContext)
            panel.append(storyContext);
        panel.append(recent, actions);
        overlay.append(panel);
        document.body.append(overlay);
        continueButton.focus();
    });
}
function dialogStoryContext(dialog) {
    var story = dialog && dialog.story;
    if (!story)
        return null;
    var text = story.setup || story.description || "";
    if (!text.trim())
        return null;
    var wrapper = document.createElement("div");
    wrapper.className = "dialog-choice-story";
    var label = document.createElement("span");
    label.className = "dialog-choice-section-label";
    label.textContent = "Сцена";
    var body = document.createElement("p");
    body.textContent = text;
    wrapper.append(label, body);
    return wrapper;
}
function dialogRecentMessages(dialog) {
    var wrapper = document.createElement("div");
    wrapper.className = "dialog-choice-recent";
    var label = document.createElement("span");
    label.className = "dialog-choice-section-label";
    label.textContent = "Последние реплики";
    wrapper.append(label);
    var messages = Array.isArray(dialog.recentMessages) ? dialog.recentMessages : [];
    if (messages.length === 0) {
        var empty = document.createElement("p");
        empty.className = "dialog-choice-recent-empty";
        empty.textContent = dialog.lastMessage || "Сообщения пока не найдены.";
        wrapper.append(empty);
        return wrapper;
    }
    messages.forEach(function (message) {
        var item = document.createElement("div");
        item.className = "dialog-choice-message dialog-choice-message--".concat(message.role === "user" ? "user" : "assistant");
        var author = document.createElement("span");
        author.className = "dialog-choice-message-author";
        author.textContent = message.role === "user" ? "Ты" : dialog.characterName;
        var text = document.createElement("p");
        text.textContent = message.text || "";
        item.append(author, text);
        wrapper.append(item);
    });
    return wrapper;
}
function selectStory(storyId, card) {
    var characterId = state.previewCharacterId || state.selectedCharacterId;
    if (!characterId)
        return showToast("Сначала выбери персонажа");
    var selectionKey = "".concat(characterId, ":").concat(storyId);
    if (state.pendingStorySelectionKey)
        return;
    state.pendingStorySelectionKey = selectionKey;
    if (card) {
        card.disabled = true;
        card.classList.add("story-card-pending");
        card.setAttribute("aria-busy", "true");
    }
    var knownDialog = findExistingDialogForContext(characterId, storyId);
    var initial = knownDialog
        ? Promise.resolve({ needsDecision: true, existingDialog: knownDialog })
        : api("/miniapp/api/select-story", {
            method: "POST",
            body: { characterId: characterId, storyId: storyId },
        });
    return initial.then(function (data) {
        if (!data.needsDecision) {
            applyStorySelection(data);
            finishInTelegram(data.sendData, "История выбрана. Вернись в чат, чтобы продолжить.");
            return null;
        }
        var existingDialog = data.existingDialog || knownDialog;
        return confirmExistingDialog(existingDialog).then(function (shouldContinue) {
            if (shouldContinue === null)
                return null;
            if (shouldContinue && existingDialog && existingDialog.id) {
                return api("/miniapp/api/restore-dialog", {
                    method: "POST",
                    body: { dialogId: existingDialog.id },
                }).then(function (result) {
                    applyStorySelection(result);
                    finishInTelegram(result.sendData, "Старый диалог восстановлен. Возвращаю в чат.");
                });
            }
            return api("/miniapp/api/select-story", {
                method: "POST",
                body: { characterId: characterId, storyId: storyId, replaceExisting: true },
            }).then(function (result) {
                applyStorySelection(result);
                finishInTelegram(result.sendData, "История выбрана. Вернись в чат, чтобы продолжить.");
            });
        });
    }).catch(function (error) {
        if (error.status === 502 && error.data && error.data.error === "Telegram message was not delivered") {
            finishInTelegram({ action: "story_selected", characterId: characterId, storyId: storyId }, "История выбрана. Открываю чат.");
            return;
        }
        showToast(error.message || "Не удалось выбрать историю");
    }).then(function (result) {
        if (state.pendingStorySelectionKey === selectionKey)
            state.pendingStorySelectionKey = null;
        if (card && card.isConnected) {
            card.disabled = false;
            card.classList.remove("story-card-pending");
            card.removeAttribute("aria-busy");
        }
        return result;
    });
}
function applyStorySelection(data) {
    if (!data || !state.bootstrap || !state.bootstrap.settings)
        return;
    state.bootstrap.settings.selectedStory =
        data.selectedStory !== undefined ? data.selectedStory : state.bootstrap.settings.selectedStory || null;
    state.bootstrap.settings.selectedStoryTitle =
        data.selectedStoryTitle !== undefined
            ? data.selectedStoryTitle
            : data.sendData && data.sendData.storyTitle || state.bootstrap.settings.selectedStoryTitle || null;
    if (data.activeDialogId) {
        state.bootstrap.settings.activeDialogId = data.activeDialogId;
    }
    if (data.selectedCharacter) {
        state.bootstrap.settings.selectedCharacter = data.selectedCharacter;
        state.selectedCharacterId = data.selectedCharacter;
    }
    cacheBootstrap(state.bootstrap);
    renderSettings();
}
function skipStory() {
    var characterId = state.previewCharacterId || state.selectedCharacterId;
    if (!characterId)
        return showToast("Сначала выбери персонажа");
    var knownDialog = findExistingDialogForContext(characterId, null);
    var initial = knownDialog
        ? Promise.resolve({ needsDecision: true, existingDialog: knownDialog })
        : api("/miniapp/api/skip-story", {
            method: "POST",
            body: { characterId: characterId },
        });
    return initial.then(function (data) {
        if (!data.needsDecision) {
            finishInTelegram(data.sendData, "История пропущена. Можно продолжать в чате.");
            return null;
        }
        var existingDialog = data.existingDialog || knownDialog;
        return confirmExistingDialog(existingDialog).then(function (shouldContinue) {
            if (shouldContinue === null)
                return null;
            if (shouldContinue && existingDialog && existingDialog.id) {
                return api("/miniapp/api/restore-dialog", {
                    method: "POST",
                    body: { dialogId: existingDialog.id },
                }).then(function (result) {
                    finishInTelegram(result.sendData, "Старый диалог восстановлен. Возвращаю в чат.");
                });
            }
            return api("/miniapp/api/skip-story", {
                method: "POST",
                body: { characterId: characterId, replaceExisting: true },
            }).then(function (result) {
                finishInTelegram(result.sendData, "История пропущена. Можно продолжать в чате.");
            });
        });
    }).catch(function (error) {
        showToast(error.message || "Не удалось пропустить историю");
    });
}
function createInvoice(type, code) {
    return __awaiter(this, void 0, void 0, function () {
        var data, error_10;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 4, 5, 6]);
                    return [4 /*yield*/, api("/miniapp/api/create-invoice", {
                            method: "POST",
                            body: { type: type, code: code },
                        })];
                case 2:
                    data = _a.sent();
                    if (openInvoice(data.invoiceLink, function () { return loadBootstrap("settings"); })) {
                        return [2 /*return*/];
                    }
                    return [4 /*yield*/, sendInvoiceToChat(type, code, "Счёт отправлен в чат Telegram.")];
                case 3:
                    _a.sent();
                    return [3 /*break*/, 6];
                case 4:
                    error_10 = _a.sent();
                    showToast(error_10.message || "Не удалось создать счет");
                    return [3 /*break*/, 6];
                case 5:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 6: return [2 /*return*/];
            }
        });
    });
}
function openInvoice(invoiceLink, onPaid) {
    if (!invoiceLink)
        return false;
    if (tg && tg.openInvoice) {
        try {
            tg.openInvoice(invoiceLink, function (status) {
                if (status === "paid") {
                    showToast("Оплата прошла. Обновляю данные...");
                    if (onPaid)
                        onPaid();
                }
                else {
                    showToast("Оплата не завершена.");
                }
            });
            return true;
        }
        catch (error) {
            console.warn("Telegram openInvoice is unavailable, falling back to link", error);
        }
    }
    return false;
}
function sendInvoiceToChat(type, code, message) {
    return __awaiter(this, void 0, void 0, function () {
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0: return [4 /*yield*/, api("/miniapp/api/create-invoice", {
                        method: "POST",
                        body: { type: type, code: code, delivery: "chat" },
                    })];
                case 1:
                    _a.sent();
                    finishInTelegram(null, message);
                    return [2 /*return*/];
            }
        });
    });
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
    if (!storyId)
        return null;
    var normalizedStoryId = String(storyId).toLowerCase();
    var sources = [];
    if (characterId) {
        sources.push(storiesForCharacterFromCache(characterId));
    }
    sources.push(state.bootstrap && state.bootstrap.stories);
    var storiesByCharacter = state.storiesByCharacter || {};
    Object.keys(storiesByCharacter).forEach(function (characterKey) { return sources.push(storiesByCharacter[characterKey]); });
    for (var _i = 0, sources_1 = sources; _i < sources_1.length; _i++) {
        var stories = sources_1[_i];
        if (!Array.isArray(stories))
            continue;
        var story = stories.find(function (item) { return item.id && item.id.toLowerCase() === normalizedStoryId; });
        if (story)
            return story;
    }
    return null;
}
function findKnownStoryTitle(characterId, storyId) {
    if (!storyId)
        return null;
    var normalizedStoryId = String(storyId).toLowerCase();
    var settings = state.bootstrap && state.bootstrap.settings || {};
    if (settings.selectedStory && settings.selectedStory.toLowerCase() === normalizedStoryId && settings.selectedStoryTitle) {
        return settings.selectedStoryTitle;
    }
    var dialogs = state.bootstrap && state.bootstrap.dialogs || [];
    var activeDialog = dialogs
        .find(function (item) { return item.id === settings.activeDialogId && item.storyId && item.storyId.toLowerCase() === normalizedStoryId; });
    if (activeDialog && activeDialog.storyTitle)
        return activeDialog.storyTitle;
    var dialog = dialogs
        .find(function (item) { return item.characterId === characterId && item.storyId && item.storyId.toLowerCase() === normalizedStoryId; });
    return dialog && dialog.storyTitle ? dialog.storyTitle : null;
}
function ensureSelectedStoryTitle() {
    if (!state.bootstrap || !state.bootstrap.settings)
        return false;
    var settings = state.bootstrap.settings;
    if (!settings.selectedStory || settings.selectedStoryTitle)
        return false;
    var characterId = settings.selectedCharacter || state.selectedCharacterId;
    var story = characterId ? findKnownStory(characterId, settings.selectedStory) : null;
    var fallbackTitle = characterId ? findKnownStoryTitle(characterId, settings.selectedStory) : null;
    var resolvedTitle = story ? story.title : fallbackTitle;
    if (!resolvedTitle)
        return false;
    settings.selectedStoryTitle = resolvedTitle;
    return true;
}
function renderSettings() {
    if (!state.bootstrap)
        return;
    renderPaymentOptions();
    renderAudienceSettings();
    ensureSelectedStoryTitle();
    var character = selectedCharacter();
    var storyId = state.bootstrap.settings && state.bootstrap.settings.selectedStory;
    var story = character ? findKnownStory(character.id, storyId) : null;
    var fallbackStoryTitle = character ? findKnownStoryTitle(character.id, storyId) : null;
    var storyTitle = story ? story.title : fallbackStoryTitle;
    els.currentSelection.textContent = character
        ? character.name
        : "Персонаж не выбран";
    els.currentStoryHint.textContent = character
        ? storyId
            ? storyTitle || "История выбрана"
            : "Свободный чат · без сюжета"
        : "Выбери персонажа, потом историю или свободный чат.";
    var balance = state.bootstrap.balance;
    if (balance) {
        els.tokenBalanceText.textContent = "".concat(formatCompactNumber(balance.textTokensLeft), " \u0442\u043E\u043A\u0435\u043D\u043E\u0432");
        els.tokenPlanText.textContent = "".concat(formatNumber(balance.imageCreditsLeft), " \u0444\u043E\u0442\u043E \u00B7 ").concat(formatNumber(balance.gifCreditsLeft || 0), " GIF \u043E\u0441\u0442\u0430\u043B\u043E\u0441\u044C");
    }
    else {
        els.tokenBalanceText.textContent = "Нет данных";
        els.tokenPlanText.textContent = "Баланс появится после загрузки бота.";
    }
}
function renderAudienceSettings() {
    if (!els.audienceSettings)
        return;
    var selected = resolveAudiencePreference();
    els.audienceSettings.replaceChildren();
    [
        { value: "female", label: "Девушки" },
        { value: "male", label: "Мужчины" },
    ].forEach(function (item) {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "audience-settings-button";
        button.classList.toggle("active", selected === item.value);
        button.textContent = item.label;
        button.addEventListener("click", function () { return selectAudience(item.value, { stayOnSettings: true }); });
        els.audienceSettings.append(button);
    });
}
function renderPaymentOptions() {
    var payments = state.bootstrap && state.bootstrap.payments || {};
    var plans = payments.plans || [];
    var packs = payments.packs || [];
    var gifPacks = payments.gifPacks || [];
    els.paymentOptions.replaceChildren();
    var tabs = [
        {
            key: "plans",
            label: "Токены",
            title: "Пакеты для общения",
            note: "Основной запас токенов и фото.",
            layout: "plans",
            items: plans.map(function (plan) { return ({
                type: "plan",
                code: plan.code,
                title: plan.title,
                caption: "Пакет общения",
                badges: [
                    "".concat(formatCompactNumber(plan.textTokens), " \u0442\u043E\u043A\u0435\u043D\u043E\u0432"),
                    "".concat(formatNumber(plan.imageCredits), " \u0444\u043E\u0442\u043E"),
                ],
                price: "".concat(plan.priceRub, " \u20BD"),
                featured: plan.code === "pro",
                badgeLabel: plan.code === "pro" ? "Выбор" : "",
            }); }),
        },
        {
            key: "packs",
            label: "Фото",
            title: "Пакеты изображений",
            note: "Если нужен запас только на новые кадры.",
            layout: "packs",
            items: packs.map(function (pack) { return ({
                type: "pack",
                code: pack.code,
                title: pack.title,
                caption: "Разовый пакет",
                badges: ["".concat(formatNumber(pack.imageCredits), " \u0444\u043E\u0442\u043E")],
                price: "".concat(pack.priceRub, " \u20BD"),
                featured: false,
                badgeLabel: "",
            }); }),
        },
        {
            key: "gif",
            label: "GIF",
            title: "Пакеты анимации",
            note: "Для оживления уже созданных изображений.",
            layout: "packs",
            items: gifPacks.map(function (pack) { return ({
                type: "gif_pack",
                code: pack.code,
                title: pack.title,
                caption: "Разовый пакет",
                badges: ["".concat(formatNumber(pack.gifCredits), " GIF")],
                price: "".concat(pack.priceRub, " \u20BD"),
                featured: false,
                badgeLabel: "",
            }); }),
        },
    ];
    if (!tabs.find(function (tab) { return tab.key === state.settingsPaymentTab; })) {
        state.settingsPaymentTab = "plans";
    }
    var tabsBar = document.createElement("div");
    tabsBar.className = "payment-tabs";
    tabs.forEach(function (tab) {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "payment-tab";
        button.classList.toggle("active", state.settingsPaymentTab === tab.key);
        button.textContent = tab.label;
        button.addEventListener("click", function () {
            if (state.settingsPaymentTab === tab.key)
                return;
            state.settingsPaymentTab = tab.key;
            renderPaymentOptions();
        });
        tabsBar.append(button);
    });
    var currentTab = tabs.find(function (tab) { return tab.key === state.settingsPaymentTab; }) || tabs[0];
    els.paymentOptions.append(tabsBar, paymentGroup(currentTab));
}
function paymentGroup(group) {
    var section = document.createElement("section");
    section.className = "payment-group payment-group--".concat(group.layout);
    var head = document.createElement("div");
    head.className = "payment-group-head";
    var title = document.createElement("h3");
    title.textContent = group.title;
    var note = document.createElement("p");
    note.textContent = group.note;
    var grid = document.createElement("div");
    grid.className = "payment-group-grid payment-group-grid--".concat(group.layout);
    group.items.forEach(function (item) {
        grid.append(paymentButton(item));
    });
    head.append(title, note);
    section.append(head, grid);
    return section;
}
function paymentButton(option) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "payment-option";
    if (option.featured)
        button.classList.add("featured");
    button.addEventListener("click", function () { return createInvoice(option.type, option.code); });
    var copy = document.createElement("span");
    copy.className = "payment-copy";
    var titleRow = document.createElement("span");
    titleRow.className = "payment-title-row";
    var title = document.createElement("strong");
    title.textContent = option.title;
    titleRow.append(title);
    if (option.badgeLabel) {
        var badge = document.createElement("span");
        badge.className = "payment-badge";
        badge.textContent = option.badgeLabel;
        titleRow.append(badge);
    }
    var caption = document.createElement("small");
    caption.className = "payment-caption";
    caption.textContent = option.caption;
    var badges = document.createElement("span");
    badges.className = "payment-badges";
    (option.badges || []).forEach(function (item) {
        var chip = document.createElement("span");
        chip.className = "payment-chip";
        chip.textContent = item;
        badges.append(chip);
    });
    var price = document.createElement("span");
    price.className = "payment-price";
    price.textContent = option.price;
    copy.append(titleRow, caption, badges);
    button.append(copy, price);
    return button;
}
function planTitle(code) {
    var plans = state.bootstrap && state.bootstrap.payments && state.bootstrap.payments.plans || [];
    var plan = plans.find(function (item) { return item.code === code; });
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
    return (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === state.selectedCharacterId; });
}
function previewedCharacter() {
    var characterId = state.previewCharacterId || state.selectedCharacterId;
    return (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === characterId; });
}
function formatDialogTime(value) {
    var date = new Date(Number(value) || Date.now());
    var now = new Date();
    var sameDay = date.getFullYear() === now.getFullYear() &&
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
    var botUrl = state.bootstrap && state.bootstrap.bot && state.bootstrap.bot.url || DEFAULT_BOT_URL;
    if (tg && tg.openTelegramLink) {
        tg.openTelegramLink(botUrl);
        window.setTimeout(function () {
            if (typeof tg.close === "function")
                tg.close();
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
    if (!els.loadingScreen)
        return;
    els.loadingScreen.hidden = false;
    els.loadingScreen.style.display = "grid";
    els.loadingScreen.setAttribute("aria-hidden", "false");
    els.loadingScreen.innerHTML = "";
    var title = document.createElement("strong");
    title.textContent = "Mini App не смог загрузиться";
    var message = document.createElement("p");
    message.textContent = error.message || "Проверь настройки MINI_APP_URL и Telegram initData.";
    els.loadingScreen.append(title, message);
}
function showToast(message) {
    if (tg && tg.HapticFeedback) {
        tg.HapticFeedback.notificationOccurred("warning");
    }
    var toast = document.createElement("div");
    toast.className = "toast";
    toast.textContent = message;
    document.body.append(toast);
    window.setTimeout(function () { return toast.remove(); }, 2600);
}
