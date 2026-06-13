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
var DEFAULT_BOT_URL = "https://t.me/emili_test_bot";
document.documentElement.setAttribute("data-miniapp-boot", "started");
window.__miniappBootState = "started";
var state = {
    bootstrap: null,
    currentScreen: "characters",
    selectedCharacterId: null,
    audiencePreference: null,
    galleryCharacterId: null,
    galleryImages: [],
    galleryIndex: 0,
    lastNonSettingsScreen: "characters",
    finishTimer: null,
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
        var data, settings, error_1;
        if (nextScreen === void 0) { nextScreen = "characters"; }
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, , 4]);
                    return [4 /*yield*/, api("/miniapp/api/bootstrap")];
                case 2:
                    data = _a.sent();
                    state.bootstrap = data;
                    settings = data.settings || {};
                    state.audiencePreference = settings.audiencePreference || null;
                    state.selectedCharacterId =
                        settings.selectedCharacter ||
                            firstId(data.characters) ||
                            null;
                    renderStatus();
                    renderCharacters();
                    renderDialogs();
                    renderSettings();
                    showScreen(state.audiencePreference ? nextScreen : "preference");
                    setLoading(false);
                    document.documentElement.setAttribute("data-miniapp-stage", "ready");
                    return [3 /*break*/, 4];
                case 3:
                    error_1 = _a.sent();
                    document.documentElement.setAttribute("data-miniapp-stage", "load-error");
                    document.documentElement.setAttribute("data-miniapp-error", String(error_1 && error_1.message || error_1));
                    showFatalError(error_1);
                    return [3 /*break*/, 4];
                case 4: return [2 /*return*/];
            }
        });
    });
}
function api(path_1) {
    return __awaiter(this, arguments, void 0, function (path, options) {
        var timeoutMs, response, _a, raw, data, telegramDescription, message;
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
                        throw new Error(message);
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
    state.currentScreen = name;
    document.body.classList.toggle("preference-mode", name === "preference");
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
        if (name === "characters" || name === "preference")
            safeTelegramCall(function () { return tg.BackButton.hide(); });
        else
            safeTelegramCall(function () { return tg.BackButton.show(); });
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
    return Boolean((state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference) || state.audiencePreference);
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
    var characters = state.bootstrap && state.bootstrap.characters || [];
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
    return __awaiter(this, void 0, void 0, function () {
        var character, data, error_2;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    character = (state.bootstrap && state.bootstrap.characters || []).find(function (item) { return item.id === characterId; });
                    if (!character)
                        return [2 /*return*/, showToast("Персонаж не найден")];
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/gallery?characterId=".concat(encodeURIComponent(characterId)))];
                case 2:
                    data = _a.sent();
                    state.galleryCharacterId = characterId;
                    state.galleryImages = data.images || [];
                    renderGallery(data.character || character);
                    showScreen("gallery");
                    return [3 /*break*/, 5];
                case 3:
                    error_2 = _a.sent();
                    showToast(error_2.message || "Не удалось открыть галерею");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function selectAudience(audience_1) {
    return __awaiter(this, arguments, void 0, function (audience, options) {
        var data, error_3;
        if (options === void 0) { options = {}; }
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    if (!audience)
                        return [2 /*return*/];
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/audience", {
                            method: "POST",
                            body: { audience: audience },
                        })];
                case 2:
                    data = _a.sent();
                    state.audiencePreference = data.audiencePreference;
                    state.bootstrap.settings.audiencePreference = data.audiencePreference;
                    state.bootstrap.settings.selectedCharacter = data.selectedCharacter;
                    state.bootstrap.settings.selectedStory = null;
                    state.bootstrap.characters = data.characters || [];
                    state.bootstrap.stories = data.stories || [];
                    state.selectedCharacterId = data.selectedCharacter || firstId(state.bootstrap.characters) || null;
                    renderCharacters();
                    renderSelectedCharacter();
                    renderStories();
                    renderSettings();
                    showScreen(options.stayOnSettings ? "settings" : "characters");
                    return [3 /*break*/, 5];
                case 3:
                    error_3 = _a.sent();
                    showToast(error_3.message || "Не удалось сменить выбор");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function renderGallery(character) {
    els.galleryHeader.replaceChildren();
    els.galleryGrid.replaceChildren();
    var title = document.createElement("h2");
    title.textContent = "\u0413\u0430\u043B\u0435\u0440\u0435\u044F ".concat(character.name);
    var subtitle = document.createElement("p");
    subtitle.textContent = state.galleryImages.length
        ? "".concat(state.galleryImages.length, " \u0444\u043E\u0442\u043E. \u041D\u0430\u0436\u043C\u0438 \u043D\u0430 \u043B\u044E\u0431\u043E\u0435, \u0447\u0442\u043E\u0431\u044B \u043E\u0442\u043A\u0440\u044B\u0442\u044C \u043F\u0440\u043E\u0441\u043C\u043E\u0442\u0440.")
        : "У этого персонажа пока нет сгенерированных фото.";
    els.galleryHeader.append(title, subtitle);
    if (state.galleryImages.length === 0) {
        var empty = document.createElement("div");
        empty.className = "empty-dialogs";
        empty.textContent = "Сгенерируй картинку в чате, и она появится здесь.";
        els.galleryGrid.append(empty);
        return;
    }
    state.galleryImages.forEach(function (item, index) {
        var button = document.createElement("button");
        button.type = "button";
        button.className = "gallery-tile";
        button.addEventListener("click", function () { return openGalleryViewer(index); });
        var image = document.createElement("img");
        image.src = item.imageUrl;
        image.alt = item.prompt || "Сгенерированное фото";
        image.loading = "lazy";
        var meta = document.createElement("span");
        meta.textContent = formatDialogTime(item.createdAt);
        button.append(image, meta);
        els.galleryGrid.append(button);
    });
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
function selectCharacter(characterId) {
    return __awaiter(this, void 0, void 0, function () {
        var data, error_4;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/select-character", {
                            method: "POST",
                            body: { characterId: characterId },
                        })];
                case 2:
                    data = _a.sent();
                    state.selectedCharacterId = data.selectedCharacter;
                    state.bootstrap.settings.selectedCharacter = data.selectedCharacter;
                    state.bootstrap.settings.selectedStory = null;
                    state.bootstrap.stories = data.stories || state.bootstrap.stories || [];
                    renderCharacters();
                    renderSelectedCharacter();
                    renderStories();
                    renderSettings();
                    showScreen("stories");
                    return [3 /*break*/, 5];
                case 3:
                    error_4 = _a.sent();
                    showToast(error_4.message || "Не удалось выбрать персонажа");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function renderSelectedCharacter() {
    var character = selectedCharacter();
    els.selectedCharacterPanel.replaceChildren();
    if (!character) {
        els.selectedCharacterPanel.textContent = "Сначала выбери персонажа.";
        return;
    }
    var title = document.createElement("strong");
    title.textContent = character.name;
    var description = document.createElement("p");
    description.textContent = character.description;
    els.selectedCharacterPanel.append(title, description);
}
function renderStories() {
    var stories = state.bootstrap && state.bootstrap.stories || [];
    els.storiesList.replaceChildren();
    stories.forEach(function (story) {
        var card = document.createElement("button");
        card.type = "button";
        card.className = "story-card";
        card.addEventListener("click", function () { return selectStory(story.id); });
        var title = document.createElement("h2");
        title.textContent = story.title;
        var description = document.createElement("p");
        description.textContent = story.description;
        var setup = document.createElement("p");
        setup.className = "story-setup";
        setup.textContent = story.setup;
        card.append(title, description, setup);
        els.storiesList.append(card);
    });
    els.storiesList.append(customStoryCard());
}
function customStoryCard() {
    var access = state.bootstrap && state.bootstrap.customStory || {};
    var slotsLeft = Number(access.storySlotsLeft || 0);
    var priceRub = Number(access.priceRub || 150);
    var storySlots = Number(access.storySlots || 3);
    var card = document.createElement("button");
    card.type = "button";
    card.className = "story-card custom-story-card";
    card.addEventListener("click", function () { return handleCustomStoryClick(slotsLeft, priceRub); });
    if (slotsLeft > 0) {
        card.classList.add("custom-story-card-unlocked");
    }
    var plus = document.createElement("span");
    plus.className = "custom-story-plus";
    plus.textContent = "+";
    var title = document.createElement("h2");
    title.textContent = slotsLeft > 0 ? "Создать свою ролевую игру" : "Добавить свою историю";
    if (slotsLeft > 0) {
        var badge = document.createElement("span");
        badge.className = "custom-story-badge";
        badge.textContent = "\u0414\u043E\u0441\u0442\u0443\u043F\u043D\u043E: ".concat(slotsLeft);
        card.append(plus, title, badge);
        return card;
    }
    var description = document.createElement("p");
    description.textContent = "\u041F\u043B\u0430\u0442\u043D\u0430\u044F \u0444\u0443\u043D\u043A\u0446\u0438\u044F: ".concat(priceRub, " \u20BD, \u0434\u043E ").concat(storySlots, " \u0441\u0432\u043E\u0438\u0445 \u0438\u0441\u0442\u043E\u0440\u0438\u0439.");
    card.append(plus, title, description);
    return card;
}
function handleCustomStoryClick(slotsLeft, priceRub) {
    return __awaiter(this, void 0, void 0, function () {
        var data, error_5;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    if (slotsLeft > 0) {
                        openCustomStoryEditor();
                        return [2 /*return*/];
                    }
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 4, 5, 6]);
                    return [4 /*yield*/, api("/miniapp/api/create-invoice", {
                            method: "POST",
                            body: { type: "custom_story" },
                        })];
                case 2:
                    data = _a.sent();
                    if (openInvoice(data.invoiceLink, function () { return loadBootstrap("stories"); })) {
                        return [2 /*return*/];
                    }
                    return [4 /*yield*/, sendInvoiceToChat("custom_story", null, "\u0421\u0447\u0451\u0442 \u043D\u0430 ".concat(priceRub, " \u20BD \u043E\u0442\u043F\u0440\u0430\u0432\u043B\u0435\u043D \u0432 \u0447\u0430\u0442 \u0431\u043E\u0442\u0430."))];
                case 3:
                    _a.sent();
                    return [3 /*break*/, 6];
                case 4:
                    error_5 = _a.sent();
                    showToast(error_5.message || "Не удалось открыть оплату");
                    return [3 /*break*/, 6];
                case 5:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 6: return [2 /*return*/];
            }
        });
    });
}
function openCustomStoryEditor() {
    var _this = this;
    var character = selectedCharacter();
    if (!character) {
        showToast("Сначала выбери персонажа");
        return;
    }
    var overlay = document.createElement("div");
    overlay.className = "custom-story-modal";
    overlay.innerHTML = "\n    <form class=\"custom-story-form\">\n      <button class=\"custom-story-close\" type=\"button\" aria-label=\"\u0417\u0430\u043A\u0440\u044B\u0442\u044C\">\u00D7</button>\n      <p class=\"custom-story-kicker\">\u0421\u0432\u043E\u044F \u0438\u0441\u0442\u043E\u0440\u0438\u044F \u0434\u043B\u044F ".concat(escapeHtml(character.name), "</p>\n      <h2>\u0421\u043E\u0437\u0434\u0430\u0439 \u0441\u0446\u0435\u043D\u0430\u0440\u0438\u0439</h2>\n      <label>\n        \u041D\u0430\u0437\u0432\u0430\u043D\u0438\u0435\n        <input name=\"title\" maxlength=\"60\" placeholder=\"\u041D\u0430\u043F\u0440\u0438\u043C\u0435\u0440: \u041D\u043E\u0447\u043D\u0430\u044F \u043F\u043E\u0435\u0437\u0434\u043A\u0430\" required>\n      </label>\n      <label>\n        \u041A\u043E\u0440\u043E\u0442\u043A\u043E\u0435 \u043E\u043F\u0438\u0441\u0430\u043D\u0438\u0435\n        <input name=\"description\" maxlength=\"160\" placeholder=\"\u0427\u0442\u043E \u0443\u0432\u0438\u0434\u0438\u0442 \u043F\u043E\u043B\u044C\u0437\u043E\u0432\u0430\u0442\u0435\u043B\u044C \u043D\u0430 \u043A\u0430\u0440\u0442\u043E\u0447\u043A\u0435\">\n      </label>\n      <label>\n        \u0421\u0446\u0435\u043D\u0430 \u0438 \u043F\u0440\u0430\u0432\u0438\u043B\u0430 \u0438\u0441\u0442\u043E\u0440\u0438\u0438\n        <textarea name=\"setup\" maxlength=\"900\" rows=\"5\" placeholder=\"\u0413\u0434\u0435 \u0432\u044B, \u0447\u0442\u043E \u043F\u0440\u043E\u0438\u0441\u0445\u043E\u0434\u0438\u0442, \u043A\u0430\u043A\u0430\u044F \u0440\u043E\u043B\u044C \u0443 \u043F\u0435\u0440\u0441\u043E\u043D\u0430\u0436\u0430...\" required></textarea>\n      </label>\n      <label>\n        \u041F\u0435\u0440\u0432\u043E\u0435 \u0441\u043E\u043E\u0431\u0449\u0435\u043D\u0438\u0435 \u043F\u0435\u0440\u0441\u043E\u043D\u0430\u0436\u0430\n        <textarea name=\"openingLine\" maxlength=\"240\" rows=\"3\" placeholder=\"\u0424\u0440\u0430\u0437\u0430, \u0441 \u043A\u043E\u0442\u043E\u0440\u043E\u0439 \u043D\u0430\u0447\u043D\u0435\u0442\u0441\u044F \u0447\u0430\u0442\" required></textarea>\n      </label>\n      <button class=\"primary-button\" type=\"submit\">\u0421\u043E\u0445\u0440\u0430\u043D\u0438\u0442\u044C \u0438\u0441\u0442\u043E\u0440\u0438\u044E</button>\n    </form>\n  ");
    var close = function () { return overlay.remove(); };
    overlay.addEventListener("click", function (event) {
        if (event.target === overlay)
            close();
    });
    overlay.querySelector(".custom-story-close").addEventListener("click", close);
    overlay.querySelector("form").addEventListener("submit", function (event) { return __awaiter(_this, void 0, void 0, function () {
        var form;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    event.preventDefault();
                    form = new FormData(event.currentTarget);
                    return [4 /*yield*/, createCustomStory({
                            characterId: character.id,
                            title: String(form.get("title") || ""),
                            description: String(form.get("description") || ""),
                            setup: String(form.get("setup") || ""),
                            openingLine: String(form.get("openingLine") || ""),
                        })];
                case 1:
                    _a.sent();
                    close();
                    return [2 /*return*/];
            }
        });
    }); });
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
                    state.bootstrap.customStory = data.customStory || state.bootstrap.customStory;
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
    dialogs.forEach(function (dialog) {
        var row = document.createElement("button");
        row.type = "button";
        row.className = "dialog-row";
        row.addEventListener("click", function () { return restoreDialog(dialog.id); });
        var avatar = document.createElement("div");
        avatar.className = "dialog-avatar";
        var image = document.createElement("img");
        image.src = dialog.characterImageUrl;
        image.alt = dialog.characterName;
        image.loading = "lazy";
        avatar.append(image);
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
function restoreDialog(dialogId) {
    return __awaiter(this, void 0, void 0, function () {
        var data, error_7;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/restore-dialog", {
                            method: "POST",
                            body: { dialogId: dialogId },
                        })];
                case 2:
                    data = _a.sent();
                    finishInTelegram(data.sendData, "Диалог восстановлен. Возвращаю в чат бота.");
                    return [3 /*break*/, 5];
                case 3:
                    error_7 = _a.sent();
                    showToast(error_7.message || "Не удалось восстановить диалог");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function selectStory(storyId) {
    return __awaiter(this, void 0, void 0, function () {
        var characterId, data, error_8;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    characterId = state.selectedCharacterId;
                    if (!characterId)
                        return [2 /*return*/, showToast("Сначала выбери персонажа")];
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/select-story", {
                            method: "POST",
                            body: { characterId: characterId, storyId: storyId },
                        })];
                case 2:
                    data = _a.sent();
                    finishInTelegram(data.sendData, "История выбрана. Вернись в чат, чтобы продолжить.");
                    return [3 /*break*/, 5];
                case 3:
                    error_8 = _a.sent();
                    showToast(error_8.message || "Не удалось выбрать историю");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
    });
}
function skipStory() {
    return __awaiter(this, void 0, void 0, function () {
        var characterId, data, error_9;
        return __generator(this, function (_a) {
            switch (_a.label) {
                case 0:
                    characterId = state.selectedCharacterId;
                    if (!characterId)
                        return [2 /*return*/, showToast("Сначала выбери персонажа")];
                    setLoading(true);
                    _a.label = 1;
                case 1:
                    _a.trys.push([1, 3, 4, 5]);
                    return [4 /*yield*/, api("/miniapp/api/skip-story", {
                            method: "POST",
                            body: { characterId: characterId },
                        })];
                case 2:
                    data = _a.sent();
                    finishInTelegram(data.sendData, "История пропущена. Можно продолжать в чате.");
                    return [3 /*break*/, 5];
                case 3:
                    error_9 = _a.sent();
                    showToast(error_9.message || "Не удалось пропустить историю");
                    return [3 /*break*/, 5];
                case 4:
                    setLoading(false);
                    return [7 /*endfinally*/];
                case 5: return [2 /*return*/];
            }
        });
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
function renderSettings() {
    if (!state.bootstrap)
        return;
    renderPaymentOptions();
    renderAudienceSettings();
    var character = selectedCharacter();
    var storyId = state.bootstrap.settings && state.bootstrap.settings.selectedStory;
    var story = (state.bootstrap.stories || []).find(function (item) { return item.id === storyId; });
    var storyText = story ? story.title : "Свободный чат";
    els.currentSelection.textContent = character
        ? storyText
        : "История ещё не выбрана";
    els.currentStoryHint.textContent = character
        ? "".concat(character.name).concat(story ? " · сюжет выбран" : " · без сюжета")
        : "Выбери персонажа, потом историю или свободный чат.";
    var balance = state.bootstrap.balance;
    if (balance) {
        els.tokenBalanceText.textContent = "".concat(formatCompactNumber(balance.textTokensLeft), " \u0442\u043E\u043A\u0435\u043D\u043E\u0432");
        els.tokenPlanText.textContent = "".concat(formatNumber(balance.imageCreditsLeft), " \u0444\u043E\u0442\u043E \u00B7 ").concat(planTitle(balance.plan));
    }
    else {
        els.tokenBalanceText.textContent = "Нет данных";
        els.tokenPlanText.textContent = "Баланс появится после загрузки бота.";
    }
}
function renderAudienceSettings() {
    if (!els.audienceSettings)
        return;
    var selected = (state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference) || state.audiencePreference;
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
    els.paymentOptions.replaceChildren();
    plans.forEach(function (plan) {
        els.paymentOptions.append(paymentButton({
            type: "plan",
            code: plan.code,
            title: plan.title,
            meta: "".concat(formatNumber(plan.textTokens), " \u0442\u043E\u043A\u0435\u043D\u043E\u0432 \u00B7 ").concat(formatNumber(plan.imageCredits), " \u0444\u043E\u0442\u043E"),
            price: "".concat(plan.priceRub, " \u20BD/\u043C\u0435\u0441"),
            featured: plan.code === "pro",
        }));
    });
    packs.forEach(function (pack) {
        els.paymentOptions.append(paymentButton({
            type: "pack",
            code: pack.code,
            title: pack.title,
            meta: "".concat(formatNumber(pack.imageCredits), " \u0444\u043E\u0442\u043E"),
            price: "".concat(pack.priceRub, " \u20BD"),
            featured: false,
        }));
    });
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
    var title = document.createElement("strong");
    title.textContent = option.title;
    var meta = document.createElement("small");
    meta.textContent = option.meta;
    var price = document.createElement("span");
    price.className = "payment-price";
    price.textContent = option.price;
    copy.append(title, meta);
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
