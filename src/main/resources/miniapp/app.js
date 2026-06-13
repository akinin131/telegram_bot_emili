const tg = window.Telegram && window.Telegram.WebApp;
const DEFAULT_BOT_URL = "https://t.me/emili_test_bot";

document.documentElement.setAttribute("data-miniapp-boot", "started");
window.__miniappBootState = "started";

const state = {
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
  on(els.galleryViewer, "click", (event) => {
    if (event.target === els.galleryViewer) closeGalleryViewer();
  });
}

function on(element, eventName, handler) {
  if (element) {
    element.addEventListener(eventName, handler);
  }
}

async function loadBootstrap(nextScreen = "characters") {
  setLoading(true);
  try {
    const data = await api("/miniapp/api/bootstrap");
    state.bootstrap = data;
    const settings = data.settings || {};
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
  } catch (error) {
    document.documentElement.setAttribute("data-miniapp-stage", "load-error");
    document.documentElement.setAttribute("data-miniapp-error", String(error && error.message || error));
    showFatalError(error);
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
    throw new Error(message);
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
    if (name === "characters" || name === "preference") safeTelegramCall(() => tg.BackButton.hide());
    else safeTelegramCall(() => tg.BackButton.show());
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
  return Boolean((state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference) || state.audiencePreference);
}

function renderStatus() {
  const balance = state.bootstrap && state.bootstrap.balance;

  if (els.statusPanel) els.statusPanel.hidden = true;
  els.balanceText.textContent = balance
    ? `${balance.textTokensLeft} токенов / ${balance.imageCreditsLeft} фото`
    : "Нет данных";
}

function renderCharacters() {
  const characters = state.bootstrap && state.bootstrap.characters || [];
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

async function openGallery(characterId) {
  const character = (state.bootstrap && state.bootstrap.characters || []).find((item) => item.id === characterId);
  if (!character) return showToast("Персонаж не найден");

  setLoading(true);
  try {
    const data = await api(`/miniapp/api/gallery?characterId=${encodeURIComponent(characterId)}`);
    state.galleryCharacterId = characterId;
    state.galleryImages = data.images || [];
    renderGallery(data.character || character);
    showScreen("gallery");
  } catch (error) {
    showToast(error.message || "Не удалось открыть галерею");
  } finally {
    setLoading(false);
  }
}

async function selectAudience(audience, options = {}) {
  if (!audience) return;

  setLoading(true);
  try {
    const data = await api("/miniapp/api/audience", {
      method: "POST",
      body: { audience },
    });

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
  } catch (error) {
    showToast(error.message || "Не удалось сменить выбор");
  } finally {
    setLoading(false);
  }
}

function renderGallery(character) {
  els.galleryHeader.replaceChildren();
  els.galleryGrid.replaceChildren();

  const title = document.createElement("h2");
  title.textContent = `Галерея ${character.name}`;
  const subtitle = document.createElement("p");
  subtitle.textContent = state.galleryImages.length
    ? `${state.galleryImages.length} фото. Нажми на любое, чтобы открыть просмотр.`
    : "У этого персонажа пока нет сгенерированных фото.";
  els.galleryHeader.append(title, subtitle);

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
    button.addEventListener("click", () => openGalleryViewer(index));

    const image = document.createElement("img");
    image.src = item.imageUrl;
    image.alt = item.prompt || "Сгенерированное фото";
    image.loading = "lazy";

    const meta = document.createElement("span");
    meta.textContent = formatDialogTime(item.createdAt);

    button.append(image, meta);
    els.galleryGrid.append(button);
  });
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

async function selectCharacter(characterId) {
  setLoading(true);
  try {
    const data = await api("/miniapp/api/select-character", {
      method: "POST",
      body: { characterId },
    });

    state.selectedCharacterId = data.selectedCharacter;
    state.bootstrap.settings.selectedCharacter = data.selectedCharacter;
    state.bootstrap.settings.selectedStory = null;
    state.bootstrap.stories = data.stories || state.bootstrap.stories || [];

    renderCharacters();
    renderSelectedCharacter();
    renderStories();
    renderSettings();
    showScreen("stories");
  } catch (error) {
    showToast(error.message || "Не удалось выбрать персонажа");
  } finally {
    setLoading(false);
  }
}

function renderSelectedCharacter() {
  const character = selectedCharacter();
  els.selectedCharacterPanel.replaceChildren();

  if (!character) {
    els.selectedCharacterPanel.textContent = "Сначала выбери персонажа.";
    return;
  }

  const title = document.createElement("strong");
  title.textContent = character.name;

  const description = document.createElement("p");
  description.textContent = character.description;

  els.selectedCharacterPanel.append(title, description);
}

function renderStories() {
  const stories = state.bootstrap && state.bootstrap.stories || [];
  els.storiesList.replaceChildren();

  stories.forEach((story) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "story-card";
    card.addEventListener("click", () => selectStory(story.id));

    const title = document.createElement("h2");
    title.textContent = story.title;

    const description = document.createElement("p");
    description.textContent = story.description;

    const setup = document.createElement("p");
    setup.className = "story-setup";
    setup.textContent = story.setup;

    card.append(title, description, setup);
    els.storiesList.append(card);
  });

  els.storiesList.append(customStoryCard());
}

function customStoryCard() {
  const access = state.bootstrap && state.bootstrap.customStory || {};
  const slotsLeft = Number(access.storySlotsLeft || 0);
  const priceRub = Number(access.priceRub || 150);
  const storySlots = Number(access.storySlots || 3);

  const card = document.createElement("button");
  card.type = "button";
  card.className = "story-card custom-story-card";
  card.addEventListener("click", () => handleCustomStoryClick(slotsLeft, priceRub));
  if (slotsLeft > 0) {
    card.classList.add("custom-story-card-unlocked");
  }

  const plus = document.createElement("span");
  plus.className = "custom-story-plus";
  plus.textContent = "+";

  const title = document.createElement("h2");
  title.textContent = slotsLeft > 0 ? "Создать свою ролевую игру" : "Добавить свою историю";

  if (slotsLeft > 0) {
    const badge = document.createElement("span");
    badge.className = "custom-story-badge";
    badge.textContent = `Доступно: ${slotsLeft}`;
    card.append(plus, title, badge);
    return card;
  }

  const description = document.createElement("p");
  description.textContent = `Платная функция: ${priceRub} ₽, до ${storySlots} своих историй.`;
  card.append(plus, title, description);
  return card;
}

async function handleCustomStoryClick(slotsLeft, priceRub) {
  if (slotsLeft > 0) {
    openCustomStoryEditor();
    return;
  }

  setLoading(true);
  try {
    const data = await api("/miniapp/api/create-invoice", {
      method: "POST",
      body: { type: "custom_story" },
    });

    if (openInvoice(data.invoiceLink, () => loadBootstrap("stories"))) {
      return;
    }

    await sendInvoiceToChat("custom_story", null, `Счёт на ${priceRub} ₽ отправлен в чат бота.`);
  } catch (error) {
    showToast(error.message || "Не удалось открыть оплату");
  } finally {
    setLoading(false);
  }
}

function openCustomStoryEditor() {
  const character = selectedCharacter();
  if (!character) {
    showToast("Сначала выбери персонажа");
    return;
  }

  const overlay = document.createElement("div");
  overlay.className = "custom-story-modal";
  overlay.innerHTML = `
    <form class="custom-story-form">
      <button class="custom-story-close" type="button" aria-label="Закрыть">×</button>
      <p class="custom-story-kicker">Своя история для ${escapeHtml(character.name)}</p>
      <h2>Создай сценарий</h2>
      <label>
        Название
        <input name="title" maxlength="60" placeholder="Например: Ночная поездка" required>
      </label>
      <label>
        Короткое описание
        <input name="description" maxlength="160" placeholder="Что увидит пользователь на карточке">
      </label>
      <label>
        Сцена и правила истории
        <textarea name="setup" maxlength="900" rows="5" placeholder="Где вы, что происходит, какая роль у персонажа..." required></textarea>
      </label>
      <label>
        Первое сообщение персонажа
        <textarea name="openingLine" maxlength="240" rows="3" placeholder="Фраза, с которой начнется чат" required></textarea>
      </label>
      <button class="primary-button" type="submit">Сохранить историю</button>
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
    await createCustomStory({
      characterId: character.id,
      title: String(form.get("title") || ""),
      description: String(form.get("description") || ""),
      setup: String(form.get("setup") || ""),
      openingLine: String(form.get("openingLine") || ""),
    });
    close();
  });

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
    state.bootstrap.customStory = data.customStory || state.bootstrap.customStory;
    renderStories();
    showToast("История создана. Теперь её можно выбрать.");
  } catch (error) {
    showToast(error.message || "Не удалось создать историю");
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

  dialogs.forEach((dialog) => {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "dialog-row";
    row.addEventListener("click", () => restoreDialog(dialog.id));

    const avatar = document.createElement("div");
    avatar.className = "dialog-avatar";
    const image = document.createElement("img");
    image.src = dialog.characterImageUrl;
    image.alt = dialog.characterName;
    image.loading = "lazy";
    avatar.append(image);

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

async function restoreDialog(dialogId) {
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

async function selectStory(storyId) {
  const characterId = state.selectedCharacterId;
  if (!characterId) return showToast("Сначала выбери персонажа");

  setLoading(true);
  try {
    const data = await api("/miniapp/api/select-story", {
      method: "POST",
      body: { characterId, storyId },
    });
    finishInTelegram(data.sendData, "История выбрана. Вернись в чат, чтобы продолжить.");
  } catch (error) {
    showToast(error.message || "Не удалось выбрать историю");
  } finally {
    setLoading(false);
  }
}

async function skipStory() {
  const characterId = state.selectedCharacterId;
  if (!characterId) return showToast("Сначала выбери персонажа");

  setLoading(true);
  try {
    const data = await api("/miniapp/api/skip-story", {
      method: "POST",
      body: { characterId },
    });
    finishInTelegram(data.sendData, "История пропущена. Можно продолжать в чате.");
  } catch (error) {
    showToast(error.message || "Не удалось пропустить историю");
  } finally {
    setLoading(false);
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

function renderSettings() {
  if (!state.bootstrap) return;

  renderPaymentOptions();
  renderAudienceSettings();

  const character = selectedCharacter();
  const storyId = state.bootstrap.settings && state.bootstrap.settings.selectedStory;
  const story = (state.bootstrap.stories || []).find((item) => item.id === storyId);
  const storyText = story ? story.title : "Свободный чат";
  els.currentSelection.textContent = character
    ? storyText
    : "История ещё не выбрана";
  els.currentStoryHint.textContent = character
    ? `${character.name}${story ? " · сюжет выбран" : " · без сюжета"}`
    : "Выбери персонажа, потом историю или свободный чат.";

  const balance = state.bootstrap.balance;
  if (balance) {
    els.tokenBalanceText.textContent = `${formatCompactNumber(balance.textTokensLeft)} токенов`;
    els.tokenPlanText.textContent = `${formatNumber(balance.imageCreditsLeft)} фото · ${planTitle(balance.plan)}`;
  } else {
    els.tokenBalanceText.textContent = "Нет данных";
    els.tokenPlanText.textContent = "Баланс появится после загрузки бота.";
  }
}

function renderAudienceSettings() {
  if (!els.audienceSettings) return;
  const selected = (state.bootstrap && state.bootstrap.settings && state.bootstrap.settings.audiencePreference) || state.audiencePreference;
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
  els.paymentOptions.replaceChildren();

  plans.forEach((plan) => {
    els.paymentOptions.append(paymentButton({
      type: "plan",
      code: plan.code,
      title: plan.title,
      meta: `${formatNumber(plan.textTokens)} токенов · ${formatNumber(plan.imageCredits)} фото`,
      price: `${plan.priceRub} ₽/мес`,
      featured: plan.code === "pro",
    }));
  });

  packs.forEach((pack) => {
    els.paymentOptions.append(paymentButton({
      type: "pack",
      code: pack.code,
      title: pack.title,
      meta: `${formatNumber(pack.imageCredits)} фото`,
      price: `${pack.priceRub} ₽`,
      featured: false,
    }));
  });
}

function paymentButton(option) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "payment-option";
  if (option.featured) button.classList.add("featured");
  button.addEventListener("click", () => createInvoice(option.type, option.code));

  const copy = document.createElement("span");
  copy.className = "payment-copy";

  const title = document.createElement("strong");
  title.textContent = option.title;

  const meta = document.createElement("small");
  meta.textContent = option.meta;

  const price = document.createElement("span");
  price.className = "payment-price";
  price.textContent = option.price;

  copy.append(title, meta);
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
