package emily.miniapp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import emily.data.BalanceRepository
import emily.data.ChatHistoryRepository
import emily.data.CustomStoryAccess
import emily.data.CustomStoryPack
import emily.data.CustomStory
import emily.data.CustomStoryRepository
import emily.data.DialogMessage
import emily.data.DialogRepository
import emily.data.DialogSummary
import emily.data.GeneratedImageItem
import emily.data.GeneratedImageRepository
import emily.data.ImagePack
import emily.data.Plan
import emily.data.ReferralRepository
import emily.data.UserSettingsRepository
import emily.domain.AudiencePreference
import emily.domain.BotCatalog
import emily.domain.CharacterProfile
import emily.domain.StoryScenario
import emily.resources.Strings
import emily.service.ConversationMemory
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class MiniAppConfig(
    val port: Int,
    val publicUrl: String,
    val botToken: String,
    val providerToken: String,
    val botUsername: String,
    val devUserId: Long? = null
)

class MiniAppServer(
    private val config: MiniAppConfig,
    private val balanceRepository: BalanceRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val dialogRepository: DialogRepository,
    private val generatedImageRepository: GeneratedImageRepository,
    private val customStoryRepository: CustomStoryRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val referralRepository: ReferralRepository,
    private val memory: ConversationMemory
) {
    private var server: HttpServer? = null
    private val verifier = TelegramInitDataVerifier(config.botToken)
    private val telegramApi = TelegramBotApiClient(config.botToken)
    private val imageCache = ConcurrentHashMap<String, CachedImage>()
    private val generatedImageCache = ConcurrentHashMap<String, CachedImage>()
    private val characterImageVersion = "2026061412442-male-refresh"

    fun start() {
        val httpServer = HttpServer.create(InetSocketAddress(config.port), 0)
        httpServer.createContext("/miniapp") { exchange ->
            runCatching { handle(exchange) }
                .onFailure { error ->
                    sendJson(
                        exchange = exchange,
                        status = 500,
                        body = JSONObject().put("ok", false).put("error", error.message ?: "Internal error")
                    )
                }
        }
        httpServer.executor = Executors.newFixedThreadPool(4)
        httpServer.start()
        server = httpServer
        println("MiniAppServer: started on port ${config.port}, publicUrl=${config.publicUrl}")
    }

    fun stop() {
        server?.stop(1)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        addBaseHeaders(exchange)

        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            exchange.sendResponseHeaders(204, -1)
            return
        }

        val path = exchange.requestURI.path
        when {
            path == "/miniapp" -> redirect(exchange, "/miniapp/")
            path == "/miniapp/" -> serveResource(exchange, "miniapp/index.html", "text/html; charset=utf-8")
            path == "/miniapp/app.css" -> serveResource(exchange, "miniapp/app.css", "text/css; charset=utf-8")
            path == "/miniapp/app.js" -> serveResource(exchange, "miniapp/app.js", "application/javascript; charset=utf-8")
            path == "/miniapp/app.compat.js" -> serveResource(exchange, "miniapp/app.compat.js", "application/javascript; charset=utf-8")
            path.startsWith("/miniapp/assets/") && exchange.requestMethod == "GET" -> serveMiniAppAsset(exchange)
            path.startsWith("/miniapp/image/character/") && exchange.requestMethod == "GET" -> serveCharacterImage(exchange)
            path.startsWith("/miniapp/image/gallery/") && exchange.requestMethod == "GET" -> serveGalleryImage(exchange)
            path == "/miniapp/api/bootstrap" && exchange.requestMethod == "GET" -> handleBootstrap(exchange)
            path == "/miniapp/api/gallery" && exchange.requestMethod == "GET" -> handleGallery(exchange)
            path == "/miniapp/api/audience" && exchange.requestMethod == "POST" -> handleAudiencePreference(exchange)
            path == "/miniapp/api/select-character" && exchange.requestMethod == "POST" -> handleSelectCharacter(exchange)
            path == "/miniapp/api/custom-story" && exchange.requestMethod == "POST" -> handleCreateCustomStory(exchange)
            path == "/miniapp/api/select-story" && exchange.requestMethod == "POST" -> handleSelectStory(exchange)
            path == "/miniapp/api/skip-story" && exchange.requestMethod == "POST" -> handleSkipStory(exchange)
            path == "/miniapp/api/restore-dialog" && exchange.requestMethod == "POST" -> handleRestoreDialog(exchange)
            path == "/miniapp/api/create-invoice" && exchange.requestMethod == "POST" -> handleCreateInvoice(exchange)
            path == "/miniapp/api/settings" && exchange.requestMethod == "POST" -> handleSettings(exchange)
            path == "/miniapp/health" -> sendJson(exchange, 200, JSONObject().put("ok", true))
            else -> sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Not found"))
        }
    }

    private fun handleBootstrap(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        referralRepository.ensureUserProfile(user.id)

        val balance = balanceRepository.get(user.id)
        val selectedCharacterId = userSettingsRepository.getSelectedCharacter(user.id)
        val selectedStoryId = userSettingsRepository.getSelectedStory(user.id)
        val activeDialogId = userSettingsRepository.getActiveDialogId(user.id)
        val language = userSettingsRepository.getLanguage(user.id)
        val audiencePreference = userSettingsRepository.getAudiencePreference(user.id)
        val characters = BotCatalog.charactersForAudience(audiencePreference)
        val turns = chatHistoryRepository.getLast(user.id, limit = 20)
        val dialogs = dialogRepository.listDialogs(user.id, limit = 50)
        val selectedCharacter = BotCatalog.characterById(selectedCharacterId)
            ?.takeIf { it.audience == (audiencePreference ?: it.audience) }
            ?: BotCatalog.defaultCharacterForAudience(audiencePreference)
        val customStoryAccess = customStoryRepository.getAccess(user.id)

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("user", user.toJson())
                .put("bot", JSONObject()
                    .put("username", config.botUsername)
                    .put("url", "https://t.me/${config.botUsername}")
                )
                .put("settings", JSONObject()
                    .put("language", language ?: "ru")
                    .put("audiencePreference", audiencePreference ?: JSONObject.NULL)
                    .put("selectedCharacter", selectedCharacter.id)
                    .put("selectedStory", selectedStoryId)
                    .put("activeDialogId", activeDialogId)
                )
                .put("balance", JSONObject()
                    .put("plan", balance.plan)
                    .put("planExpiresAt", balance.planExpiresAt)
                    .put("textTokensLeft", balance.textTokensLeft)
                    .put("imageCreditsLeft", balance.imageCreditsLeft)
                    .put("dayImageUsed", balance.dayImageUsed)
                )
                .put("characters", JSONArray(characters.map { it.toMiniAppJson() }))
                .put("stories", storiesJson(user.id, selectedCharacter.id))
                .put("dialogs", JSONArray(dialogs.map { it.toMiniAppJson() }))
                .put("customStory", customStoryAccessJson(customStoryAccess))
                .put("payments", JSONObject()
                    .put("plans", JSONArray(Plan.entries.map { it.toMiniAppJson() }))
                    .put("packs", JSONArray(ImagePack.entries.map { it.toMiniAppJson() }))
                )
                .put("progress", JSONObject()
                    .put("hasHistory", turns.isNotEmpty())
                    .put("lastTurns", JSONArray(turns.map {
                        JSONObject()
                            .put("role", it.role)
                            .put("text", it.text)
                            .put("createdAt", it.createdAt)
                    }))
                )
        )
    }

    private fun handleAudiencePreference(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val audience = AudiencePreference.normalize(readJson(exchange).optString("audience"))
            ?: return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "Unknown audience preference"))
        val characters = BotCatalog.charactersForAudience(audience)
        val selected = characters.firstOrNull() ?: BotCatalog.defaultCharacterForAudience(audience)

        userSettingsRepository.setAudiencePreference(user.id, audience)
        userSettingsRepository.setSelectedCharacter(user.id, selected.id)
        userSettingsRepository.clearSelectedStory(user.id)

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("audiencePreference", audience)
                .put("selectedCharacter", selected.id)
                .put("selectedStory", JSONObject.NULL)
                .put("characters", JSONArray(characters.map { it.toMiniAppJson() }))
                .put("stories", storiesJson(user.id, selected.id))
        )
    }

    private fun handleCreateInvoice(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val type = body.optString("type")
        val code = body.optString("code")
        val delivery = body.optString("delivery")
        val sendToChat = delivery == "chat"

        val telegramResult = when (type) {
            "plan" -> {
                val plan = Plan.byCode(code)
                    ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Plan not found"))
                if (sendToChat) telegramApi.sendInvoice(
                    chatId = user.id,
                    title = Strings.get("invoice.plan.title", plan.title),
                    description = Strings.get(
                        "invoice.plan.description",
                        plan.monthlyTextTokens,
                        plan.monthlyImageCredits
                    ),
                    payload = "plan:${plan.code}:${UUID.randomUUID()}",
                    providerToken = config.providerToken,
                    priceLabel = Strings.get("invoice.plan.price.label", plan.title),
                    priceRub = plan.priceRub,
                    providerData = makeProviderData(
                        desc = Strings.get("invoice.plan.provider.desc", plan.title),
                        rub = plan.priceRub
                    ),
                    photoUrl = plan.photoUrl,
                    startParameter = "plan-${plan.code}"
                ) else telegramApi.createInvoiceLink(
                    title = Strings.get("invoice.plan.title", plan.title),
                    description = Strings.get(
                        "invoice.plan.description",
                        plan.monthlyTextTokens,
                        plan.monthlyImageCredits
                    ),
                    payload = "plan:${plan.code}:${UUID.randomUUID()}",
                    providerToken = config.providerToken,
                    priceLabel = Strings.get("invoice.plan.price.label", plan.title),
                    priceRub = plan.priceRub,
                    providerData = makeProviderData(
                        desc = Strings.get("invoice.plan.provider.desc", plan.title),
                        rub = plan.priceRub
                    ),
                    photoUrl = plan.photoUrl,
                    startParameter = "plan-${plan.code}"
                )
            }
            "pack" -> {
                val pack = ImagePack.byCode(code)
                    ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Pack not found"))
                if (sendToChat) telegramApi.sendInvoice(
                    chatId = user.id,
                    title = pack.title,
                    description = Strings.get("invoice.pack.description", pack.title),
                    payload = "pack:${pack.code}:${UUID.randomUUID()}",
                    providerToken = config.providerToken,
                    priceLabel = pack.title,
                    priceRub = pack.priceRub,
                    providerData = makeProviderData(
                        desc = Strings.get("invoice.pack.provider.desc", pack.title),
                        rub = pack.priceRub
                    ),
                    photoUrl = pack.photoUrl,
                    startParameter = "pack-${pack.code}"
                ) else telegramApi.createInvoiceLink(
                    title = pack.title,
                    description = Strings.get("invoice.pack.description", pack.title),
                    payload = "pack:${pack.code}:${UUID.randomUUID()}",
                    providerToken = config.providerToken,
                    priceLabel = pack.title,
                    priceRub = pack.priceRub,
                    providerData = makeProviderData(
                        desc = Strings.get("invoice.pack.provider.desc", pack.title),
                        rub = pack.priceRub
                    ),
                    photoUrl = pack.photoUrl,
                    startParameter = "pack-${pack.code}"
                )
            }
            "custom_story" -> {
                if (sendToChat) telegramApi.sendInvoice(
                    chatId = user.id,
                    title = CustomStoryPack.title,
                    description = CustomStoryPack.description,
                    payload = "custom_story:${CustomStoryPack.code}:${UUID.randomUUID()}",
                    providerToken = config.providerToken,
                    priceLabel = CustomStoryPack.title,
                    priceRub = CustomStoryPack.priceRub,
                    providerData = makeProviderData(
                        desc = CustomStoryPack.description,
                        rub = CustomStoryPack.priceRub
                    ),
                    photoUrl = null,
                    startParameter = CustomStoryPack.code
                ) else telegramApi.createInvoiceLink(
                    title = CustomStoryPack.title,
                    description = CustomStoryPack.description,
                    payload = "custom_story:${CustomStoryPack.code}:${UUID.randomUUID()}",
                    providerToken = config.providerToken,
                    priceLabel = CustomStoryPack.title,
                    priceRub = CustomStoryPack.priceRub,
                    providerData = makeProviderData(
                        desc = CustomStoryPack.description,
                        rub = CustomStoryPack.priceRub
                    ),
                    startParameter = CustomStoryPack.code
                )
            }
            else -> return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "Unknown invoice type"))
        }

        if (!telegramResult.ok) {
            return@runBlocking sendTelegramFailure(exchange, telegramResult)
        }

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("telegram", telegramResult.toJson())
                .put("invoiceLink", telegramResult.invoiceLink ?: JSONObject.NULL)
        )
    }

    private fun handleGallery(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val characterId = queryParam(exchange, "characterId")
            ?: return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "characterId is required"))
        val character = BotCatalog.characterById(characterId)
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character not found"))
        val images = generatedImageRepository.list(user.id, character.id, limit = 120)

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("character", character.toMiniAppJson())
                .put("images", JSONArray(images.map { it.toMiniAppJson(user.id) }))
        )
    }

    private fun handleSelectCharacter(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val character = BotCatalog.characterById(body.optString("characterId"))
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character not found"))

        userSettingsRepository.setSelectedCharacter(user.id, character.id)
        userSettingsRepository.clearSelectedStory(user.id)
        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("selectedCharacter", character.id)
                .put("selectedStory", JSONObject.NULL)
                .put("stories", storiesJson(user.id, character.id))
        )
    }

    private fun handleCreateCustomStory(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val character = BotCatalog.characterById(body.optString("characterId"))
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character not found"))

        val title = cleanInput(body.optString("title"), maxLength = 60)
        val description = cleanInput(body.optString("description"), maxLength = 160)
        val setup = cleanInput(body.optString("setup"), maxLength = 900)
        val openingLine = cleanInput(body.optString("openingLine"), maxLength = 240)
        if (title.length < 3 || setup.length < 20 || openingLine.length < 3) {
            return@runBlocking sendJson(
                exchange,
                400,
                JSONObject().put("ok", false).put("error", "Заполни название, описание сцены и первое сообщение.")
            )
        }

        val story = runCatching {
            customStoryRepository.createStory(
                userId = user.id,
                characterId = character.id,
                title = title,
                shortDescription = description.ifBlank { "Твоя собственная история с ${character.name}." },
                setup = setup,
                openingLine = openingLine
            )
        }.getOrElse { error ->
            val message = if (error.message == "No custom story slots left") {
                "Сначала купи доступ: пакет открывает создание 3 своих историй."
            } else {
                error.message ?: "Не удалось создать историю."
            }
            return@runBlocking sendJson(
                exchange,
                400,
                JSONObject().put("ok", false).put("error", message)
            )
        }
        val access = customStoryRepository.getAccess(user.id)

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("story", story.toMiniAppJson())
                .put("stories", storiesJson(user.id, character.id))
                .put("customStory", customStoryAccessJson(access))
        )
    }

    private fun handleSelectStory(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val character = BotCatalog.characterById(body.optString("characterId"))
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character not found"))
        val story = resolveStory(user.id, body.optString("storyId"))
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Story not found"))
        if (story !in BotCatalog.storiesForCharacter(character.id)) {
            val isCustomStoryForCharacter = story.id.startsWith("custom_") && character.id in story.characterIds
            if (!isCustomStoryForCharacter) {
                return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "Story is not available for this character"))
            }
        }

        userSettingsRepository.setSelectedCharacter(user.id, character.id)
        userSettingsRepository.setSelectedStory(user.id, story.id)
        chatHistoryRepository.clear(user.id)
        resetMemory(user.id, character, story)

        val openingLine = BotCatalog.openingLine(character, story)
        val dialogId = dialogRepository.createDialog(
            userId = user.id,
            characterId = character.id,
            characterName = character.name,
            characterImageUrl = character.selectionPhotoUrl,
            storyId = story.id,
            storyTitle = story.title,
            initialMessage = openingLine
        )
        userSettingsRepository.setActiveDialogId(user.id, dialogId)
        memory.append(user.id, "assistant", openingLine)
        chatHistoryRepository.append(user.id, "assistant", openingLine)
        val telegramResult = notifyStorySelected(user.id, character, story, openingLine)
        if (!telegramResult.ok) {
            return@runBlocking sendTelegramFailure(exchange, telegramResult)
        }

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("selectedCharacter", character.id)
                .put("selectedStory", story.id)
                .put("activeDialogId", dialogId)
                .put("telegram", telegramResult.toJson())
                .put("sendData", JSONObject()
                    .put("action", "story_selected")
                    .put("dialogId", dialogId)
                    .put("characterId", character.id)
                    .put("characterName", character.name)
                    .put("storyId", story.id)
                    .put("storyTitle", story.title)
                    .put("openingLine", openingLine)
                )
        )
    }

    private fun handleSkipStory(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val character = BotCatalog.characterById(body.optString("characterId"))
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character not found"))

        userSettingsRepository.setSelectedCharacter(user.id, character.id)
        userSettingsRepository.clearSelectedStory(user.id)
        chatHistoryRepository.clear(user.id)
        resetMemory(user.id, character, null)
        val dialogId = dialogRepository.createDialog(
            userId = user.id,
            characterId = character.id,
            characterName = character.name,
            characterImageUrl = character.selectionPhotoUrl,
            storyId = null,
            storyTitle = null
        )
        userSettingsRepository.setActiveDialogId(user.id, dialogId)
        val telegramResult = notifyStorySkipped(user.id, character)
        if (!telegramResult.ok) {
            return@runBlocking sendTelegramFailure(exchange, telegramResult)
        }

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("selectedCharacter", character.id)
                .put("selectedStory", JSONObject.NULL)
                .put("activeDialogId", dialogId)
                .put("telegram", telegramResult.toJson())
                .put("sendData", JSONObject()
                    .put("action", "story_skipped")
                    .put("dialogId", dialogId)
                    .put("characterId", character.id)
                    .put("characterName", character.name)
                )
        )
    }

    private fun handleRestoreDialog(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val dialogId = body.optString("dialogId").takeIf { it.isNotBlank() }
            ?: return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "dialogId is required"))

        val dialog = dialogRepository.getDialog(user.id, dialogId)
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Dialog not found"))
        val character = BotCatalog.characterById(dialog.characterId)
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character not found"))
        val story = BotCatalog.storyById(dialog.storyId)

        if (story != null) {
            userSettingsRepository.setSelectedStory(user.id, story.id)
        } else {
            userSettingsRepository.clearSelectedStory(user.id)
        }
        userSettingsRepository.setSelectedCharacter(user.id, character.id)
        userSettingsRepository.setActiveDialogId(user.id, dialog.id)

        val messages = dialogRepository.getMessages(user.id, dialog.id, limit = 80)
        restoreConversation(user.id, character, story, messages)
        val telegramResult = notifyDialogRestored(user.id, dialog)
        if (!telegramResult.ok) {
            return@runBlocking sendTelegramFailure(exchange, telegramResult)
        }

        sendJson(
            exchange = exchange,
            status = 200,
            body = JSONObject()
                .put("ok", true)
                .put("activeDialogId", dialog.id)
                .put("selectedCharacter", character.id)
                .put("selectedStory", story?.id ?: JSONObject.NULL)
                .put("telegram", telegramResult.toJson())
                .put("sendData", JSONObject()
                    .put("action", "dialog_restored")
                    .put("dialogId", dialog.id)
                    .put("characterId", character.id)
                    .put("characterName", character.name)
                    .put("storyId", story?.id ?: JSONObject.NULL)
                    .put("storyTitle", story?.title ?: "Свободный чат")
                )
        )
    }

    private fun handleSettings(exchange: HttpExchange) = runBlocking {
        val user = authenticate(exchange) ?: return@runBlocking
        val body = readJson(exchange)
        val language = body.optString("language").takeIf { it.isNotBlank() }
        if (language != null) {
            userSettingsRepository.setLanguage(user.id, language)
        }
        sendJson(exchange, 200, JSONObject().put("ok", true))
    }

    private fun resetMemory(userId: Long, character: CharacterProfile, story: StoryScenario?) {
        memory.reset(userId)
        memory.initIfNeeded(userId)
        memory.setSystem(userId, BotCatalog.composeSystemPrompt(character, story))
    }

    private suspend fun restoreConversation(
        userId: Long,
        character: CharacterProfile,
        story: StoryScenario?,
        messages: List<DialogMessage>
    ) {
        chatHistoryRepository.clear(userId)
        resetMemory(userId, character, story)

        messages.forEach { message ->
            if (message.role == "user" || message.role == "assistant") {
                memory.append(userId, message.role, message.text)
                chatHistoryRepository.append(userId, message.role, message.text)
            }
        }
    }

    private fun notifyStorySelected(
        userId: Long,
        character: CharacterProfile,
        story: StoryScenario,
        openingLine: String
    ): TelegramSendResult {
        val text = buildString {
            append("<b>🔥 Новый чат</b>\n\n")
            append("👤 <b>Персонаж:</b> ")
            append(html(character.name))
            append("\n")
            append("📖 <b>История:</b> ")
            append(html(story.title))
            append("\n\n")
            append("<i>")
            append(html(openingLine))
            append("</i>")
            append("\n\n")
            append("Напиши ответ, чтобы продолжить историю.")
        }
        return sendCharacterPhotoNotice(userId, character, text)
    }

    private fun notifyCharacterSelected(userId: Long, character: CharacterProfile): TelegramSendResult {
        val text = buildString {
            append("<b>🔥 Персонаж выбран</b>\n\n")
            append("👤 <b>Персонаж:</b> ")
            append(html(character.name))
            append("\n")
            append("📖 <b>История:</b> пока не выбрана")
            append("\n\n")
            append("Выбери историю в Mini App или нажми «Пропустить выбор истории».")
        }
        return sendCharacterPhotoNotice(userId, character, text)
    }

    private fun notifyStorySkipped(userId: Long, character: CharacterProfile): TelegramSendResult {
        val text = buildString {
            append("<b>🔥 Новый чат</b>\n\n")
            append("👤 <b>Персонаж:</b> ")
            append(html(character.name))
            append("\n")
            append("📖 <b>История:</b> свободный чат")
            append("\n\n")
            append("Напиши сообщение, чтобы начать.")
        }
        return sendCharacterPhotoNotice(userId, character, text)
    }

    private fun notifyDialogRestored(userId: Long, dialog: DialogSummary): TelegramSendResult {
        val text = buildString {
            append("<b>↩️ Диалог открыт</b>\n\n")
            append("👤 <b>Персонаж:</b> ")
            append(html(dialog.characterName))
            append("\n")
            append("📖 <b>История:</b> ")
            append(html(dialog.storyTitle ?: "Свободный чат"))
            append("\n\n")
            append("Продолжай с того места, где остановился.")
        }
        val character = BotCatalog.characterById(dialog.characterId)
        return if (character != null) {
            sendCharacterPhotoNotice(userId, character, text)
        } else {
            telegramApi.sendMessage(userId, text, parseMode = "HTML")
        }
    }

    private fun sendCharacterPhotoNotice(
        userId: Long,
        character: CharacterProfile,
        caption: String
    ): TelegramSendResult {
        val imagePath = character.welcomePhotoUrl.ifBlank { character.selectionPhotoUrl }
        val photoCandidates = listOfNotNull(
            character.welcomePhotoFileId,
            telegramPhotoUrl(imagePath)
        ).distinct()

        photoCandidates.forEach { photo ->
            val result = telegramApi.sendPhoto(chatId = userId, photo = photo, caption = caption, parseMode = "HTML")
            if (result.ok) return result
        }

        return telegramApi.sendMessage(userId, caption, parseMode = "HTML")
    }

    private fun telegramPhotoUrl(pathOrUrl: String): String? {
        if (!pathOrUrl.startsWith("/miniapp/")) return pathOrUrl
        if (config.publicUrl.contains("localhost") || config.publicUrl.contains("127.0.0.1")) return null
        return config.publicUrl.trimEnd('/') + pathOrUrl.removePrefix("/miniapp")
    }

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun authenticate(exchange: HttpExchange): MiniAppUser? {
        val initData = exchange.requestHeaders.getFirst("X-Telegram-Init-Data").orEmpty()
        if (initData.isBlank()) {
            val devUserId = config.devUserId
            if (devUserId != null) {
                return MiniAppUser(id = devUserId, username = "dev", firstName = "Dev")
            }
            sendJson(exchange, 401, JSONObject().put("ok", false).put("error", "Missing Telegram initData"))
            return null
        }

        val user = verifier.verify(initData)
        if (user == null) {
            sendJson(exchange, 401, JSONObject().put("ok", false).put("error", "Invalid Telegram initData"))
            return null
        }
        return user
    }

    private fun readJson(exchange: HttpExchange): JSONObject {
        val raw = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8).trim()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun serveResource(exchange: HttpExchange, resourcePath: String, contentType: String) {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: return sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Resource not found"))
        val bytes = stream.use { it.readBytes() }
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        exchange.responseHeaders.set("Pragma", "no-cache")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serveMiniAppAsset(exchange: HttpExchange) {
        val asset = exchange.requestURI.path.removePrefix("/miniapp/assets/")
            .takeIf { it.isNotBlank() && !it.contains("..") }
            ?: return sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "Invalid asset path"))
        val contentType = when (asset.substringAfterLast(".").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        serveResource(exchange, "miniapp/assets/$asset", contentType)
    }

    private fun serveCharacterImage(exchange: HttpExchange) {
        val characterId = exchange.requestURI.path
            .removePrefix("/miniapp/image/character/")
            .takeIf { it.isNotBlank() }
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        val character = BotCatalog.characterById(characterId)
            ?: return sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Character image not found"))

        val imageUrl = character.welcomePhotoUrl.ifBlank { character.selectionPhotoUrl }
        val cacheKey = "${character.id}:${imageUrl}"
        val image = imageCache.getOrPut(cacheKey) {
            if (imageUrl.startsWith("/miniapp/assets/")) {
                readLocalAssetImage(imageUrl.removePrefix("/miniapp/"))
            } else {
                downloadImage(imageUrl)
            }
        }

        exchange.responseHeaders.set("Content-Type", image.contentType)
        exchange.responseHeaders.set("Cache-Control", "public, max-age=86400")
        exchange.sendResponseHeaders(200, image.bytes.size.toLong())
        exchange.responseBody.use { it.write(image.bytes) }
    }

    private fun readLocalAssetImage(resourcePath: String): CachedImage {
        val bytes = javaClass.classLoader.getResourceAsStream("miniapp/$resourcePath")
            ?.use { it.readBytes() }
            ?: error("Local image asset not found: $resourcePath")
        val contentType = when (resourcePath.substringAfterLast(".").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        return CachedImage(contentType = contentType, bytes = bytes)
    }

    private fun serveGalleryImage(exchange: HttpExchange) = runBlocking {
        val userId = queryParam(exchange, "userId")?.toLongOrNull()
            ?: return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "userId is required"))
        val parts = exchange.requestURI.path
            .removePrefix("/miniapp/image/gallery/")
            .split("/")
            .filter { it.isNotBlank() }
        if (parts.size != 2) {
            return@runBlocking sendJson(exchange, 400, JSONObject().put("ok", false).put("error", "Invalid gallery image path"))
        }

        val characterId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
        val imageId = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        val image = generatedImageRepository.get(userId, characterId, imageId)
            ?: return@runBlocking sendJson(exchange, 404, JSONObject().put("ok", false).put("error", "Generated image not found"))

        val cached = generatedImageCache.getOrPut("$userId:${image.characterId}:${image.id}") {
            telegramApi.downloadFile(image.telegramFileId)
        }

        exchange.responseHeaders.set("Content-Type", cached.contentType)
        exchange.responseHeaders.set("Cache-Control", "private, max-age=86400")
        exchange.sendResponseHeaders(200, cached.bytes.size.toLong())
        exchange.responseBody.use { it.write(cached.bytes) }
    }

    private fun downloadImage(url: String): CachedImage {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "EmilyMiniApp/1.0")

            val status = connection.responseCode
            if (status !in 200..299) {
                error("Image download failed: HTTP $status")
            }

            val bytes = connection.inputStream.use { it.readBytes() }
            val contentType = connection.contentType
                ?.substringBefore(";")
                ?.takeIf { it.startsWith("image/") }
                ?: "image/jpeg"
            return CachedImage(contentType = contentType, bytes = bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.set("Location", location)
        exchange.sendResponseHeaders(302, -1)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: JSONObject) {
        addBaseHeaders(exchange)
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendTelegramFailure(exchange: HttpExchange, result: TelegramSendResult) {
        sendJson(
            exchange = exchange,
            status = 502,
            body = JSONObject()
                .put("ok", false)
                .put("error", "Telegram message was not delivered")
                .put("telegram", result.toJson())
        )
    }

    private fun queryParam(exchange: HttpExchange, name: String): String? {
        return exchange.requestURI.rawQuery
            ?.split("&")
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.map {
                val idx = it.indexOf("=")
                val key = if (idx >= 0) it.substring(0, idx) else it
                val value = if (idx >= 0) it.substring(idx + 1) else ""
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
            ?.firstOrNull { it.first == name }
            ?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun addBaseHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, X-Telegram-Init-Data")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }

    private fun CharacterProfile.toMiniAppJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("audience", audience)
        .put("name", name)
        .put("description", shortDescription)
        .put("imageUrl", characterImagePath(id))

    private fun StoryScenario.toMiniAppJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", shortDescription)
        .put("setup", setup)

    private fun Plan.toMiniAppJson(): JSONObject = JSONObject()
        .put("code", code)
        .put("title", title)
        .put("priceRub", priceRub)
        .put("textTokens", monthlyTextTokens)
        .put("imageCredits", monthlyImageCredits)

    private fun ImagePack.toMiniAppJson(): JSONObject = JSONObject()
        .put("code", code)
        .put("title", title)
        .put("priceRub", priceRub)
        .put("imageCredits", images)

    private fun GeneratedImageItem.toMiniAppJson(userId: Long): JSONObject = JSONObject()
        .put("id", id)
        .put("characterId", characterId)
        .put("characterName", characterName)
        .put("prompt", prompt)
        .put("model", model)
        .put("source", source)
        .put("createdAt", createdAt)
        .put("imageUrl", "/miniapp/image/gallery/$characterId/$id?userId=$userId")

    private fun DialogSummary.toMiniAppJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("characterId", characterId)
        .put("characterName", characterName)
        .put("characterImageUrl", BotCatalog.characterById(characterId)?.let { characterImagePath(it.id) } ?: characterImageUrl)
        .put("storyId", storyId ?: JSONObject.NULL)
        .put("storyTitle", storyTitle ?: "Свободный чат")
        .put("lastMessage", lastMessage)
        .put("lastRole", lastRole ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private fun characterImagePath(characterId: String): String =
        "/miniapp/image/character/$characterId?v=$characterImageVersion"

    private suspend fun storiesJson(userId: Long, characterId: String): JSONArray {
        val builtInStories = BotCatalog.storiesForCharacter(characterId).map { it.toMiniAppJson() }
        val customStories = customStoryRepository.listStories(userId, characterId).map { it.toMiniAppJson() }
        return JSONArray(builtInStories + customStories)
    }

    private suspend fun resolveStory(userId: Long, storyId: String): StoryScenario? {
        return BotCatalog.storyById(storyId) ?: customStoryRepository.getStory(userId, storyId)?.toScenario()
    }

    private fun customStoryAccessJson(access: CustomStoryAccess): JSONObject = JSONObject()
        .put("storySlotsTotal", access.storySlotsTotal)
        .put("storySlotsUsed", access.storySlotsUsed)
        .put("storySlotsLeft", access.storySlotsLeft)
        .put("priceRub", CustomStoryPack.priceRub)
        .put("storySlots", CustomStoryPack.storySlots)

    private fun CustomStory.toMiniAppJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", shortDescription)
        .put("setup", setup)
        .put("custom", true)

    private fun cleanInput(value: String, maxLength: Int): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
    }

    private fun makeProviderData(desc: String, rub: Int, includeVat: Boolean = true): String {
        val item = JSONObject()
            .put("description", desc.take(128))
            .put("quantity", "1")
            .put("amount", JSONObject().put("value", rubToStr(rub)).put("currency", "RUB"))
            .apply { if (includeVat) put("vat_code", 1) }
        val receipt = JSONObject().put("items", JSONArray().put(item))
        return JSONObject().put("receipt", receipt).toString()
    }

    private fun rubToStr(rub: Int) = String.format(Locale.US, "%.2f", rub.toDouble())

    private data class CachedImage(
        val contentType: String,
        val bytes: ByteArray
    )

    private data class MiniAppUser(
        val id: Long,
        val username: String?,
        val firstName: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("username", username)
            .put("firstName", firstName)
    }

    private class TelegramInitDataVerifier(private val botToken: String) {
        fun verify(initData: String): MiniAppUser? {
            val params = parseQuery(initData)
            val hash = params["hash"] ?: return null
            val dataCheckString = params
                .filterKeys { it != "hash" }
                .toSortedMap()
                .entries
                .joinToString("\n") { "${it.key}=${it.value}" }

            val secret = hmacSha256("WebAppData".toByteArray(StandardCharsets.UTF_8), botToken)
            val expected = hmacSha256Hex(secret, dataCheckString)
            if (!MessageDigest.isEqual(
                    expected.lowercase().toByteArray(StandardCharsets.UTF_8),
                    hash.lowercase().toByteArray(StandardCharsets.UTF_8)
                )
            ) {
                return null
            }

            val userJson = params["user"] ?: return null
            val user = JSONObject(userJson)
            return MiniAppUser(
                id = user.getLong("id"),
                username = user.optString("username").takeIf { it.isNotBlank() },
                firstName = user.optString("first_name").takeIf { it.isNotBlank() }
            )
        }

        private fun parseQuery(value: String): Map<String, String> {
            return value.split("&")
                .filter { it.isNotBlank() }
                .associate { part ->
                    val idx = part.indexOf("=")
                    val rawKey = if (idx >= 0) part.substring(0, idx) else part
                    val rawValue = if (idx >= 0) part.substring(idx + 1) else ""
                    decode(rawKey) to decode(rawValue)
                }
        }

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

        private fun hmacSha256(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        }

        private fun hmacSha256Hex(key: ByteArray, data: String): String {
            return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
        }
    }

    private data class TelegramSendResult(
        val ok: Boolean,
        val statusCode: Int,
        val description: String,
        val filePath: String? = null,
        val invoiceLink: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ok", ok)
            .put("statusCode", statusCode)
            .put("description", description)
    }

    private class TelegramBotApiClient(private val botToken: String) {
        fun sendMessage(chatId: Long, text: String, parseMode: String? = null): TelegramSendResult {
            return runCatching {
                val request = JSONObject()
                    .put("chat_id", chatId)
                    .put("text", text)
                    .put("disable_web_page_preview", true)
                if (parseMode != null) {
                    request.put("parse_mode", parseMode)
                }
                val body = request.toString().toByteArray(StandardCharsets.UTF_8)

                val connection = URI.create("https://api.telegram.org/bot$botToken/sendMessage")
                    .toURL()
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body) }

                    val status = connection.responseCode
                    val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    val json = runCatching { JSONObject(response) }.getOrNull()
                    val ok = status in 200..299 && json?.optBoolean("ok", false) == true
                    val description = json?.optString("description")?.takeIf { it.isNotBlank() }
                        ?: if (ok) "Telegram accepted message" else response.ifBlank { "Empty Telegram response" }

                    TelegramSendResult(ok = ok, statusCode = status, description = description)
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                TelegramSendResult(
                    ok = false,
                    statusCode = 0,
                    description = error.message ?: "Telegram request failed"
                )
            }
        }

        fun sendPhoto(chatId: Long, photo: String, caption: String? = null, parseMode: String? = null): TelegramSendResult {
            return runCatching {
                val request = JSONObject()
                    .put("chat_id", chatId)
                    .put("photo", photo)
                if (!caption.isNullOrBlank()) {
                    request.put("caption", caption)
                }
                if (!parseMode.isNullOrBlank()) {
                    request.put("parse_mode", parseMode)
                }
                val body = request.toString().toByteArray(StandardCharsets.UTF_8)

                val connection = URI.create("https://api.telegram.org/bot$botToken/sendPhoto")
                    .toURL()
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 15_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body) }

                    val status = connection.responseCode
                    val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    val json = runCatching { JSONObject(response) }.getOrNull()
                    val ok = status in 200..299 && json?.optBoolean("ok", false) == true
                    val description = json?.optString("description")?.takeIf { it.isNotBlank() }
                        ?: if (ok) "Telegram accepted photo" else response.ifBlank { "Empty Telegram response" }

                    TelegramSendResult(ok = ok, statusCode = status, description = description)
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                TelegramSendResult(
                    ok = false,
                    statusCode = 0,
                    description = error.message ?: "Telegram photo request failed"
                )
            }
        }

        fun sendInvoice(
            chatId: Long,
            title: String,
            description: String,
            payload: String,
            providerToken: String,
            priceLabel: String,
            priceRub: Int,
            providerData: String,
            photoUrl: String?,
            startParameter: String
        ): TelegramSendResult {
            val request = JSONObject()
                .put("chat_id", chatId)
                .put("title", title)
                .put("description", description)
                .put("payload", payload)
                .put("provider_token", providerToken)
                .put("currency", "RUB")
                .put("start_parameter", startParameter)
                .put("prices", JSONArray().put(JSONObject()
                    .put("label", priceLabel)
                    .put("amount", priceRub * 100)
                ))
                .put("need_email", true)
                .put("send_email_to_provider", true)
                .put("is_flexible", false)
                .put("provider_data", providerData)
            if (!photoUrl.isNullOrBlank()) {
                request
                    .put("photo_url", photoUrl)
                    .put("photo_width", 960)
                    .put("photo_height", 1280)
            }

            return postTelegram("sendInvoice", request)
        }

        fun createInvoiceLink(
            title: String,
            description: String,
            payload: String,
            providerToken: String,
            priceLabel: String,
            priceRub: Int,
            providerData: String,
            startParameter: String,
            photoUrl: String? = null
        ): TelegramSendResult {
            val request = JSONObject()
                .put("title", title)
                .put("description", description)
                .put("payload", payload)
                .put("provider_token", providerToken)
                .put("currency", "RUB")
                .put("start_parameter", startParameter)
                .put("prices", JSONArray().put(JSONObject()
                    .put("label", priceLabel)
                    .put("amount", priceRub * 100)
                ))
                .put("need_email", true)
                .put("send_email_to_provider", true)
                .put("is_flexible", false)
                .put("provider_data", providerData)
            if (!photoUrl.isNullOrBlank()) {
                request
                    .put("photo_url", photoUrl)
                    .put("photo_width", 960)
                    .put("photo_height", 1280)
            }

            return postTelegram("createInvoiceLink", request)
        }

        fun downloadFile(fileId: String): CachedImage {
            val fileInfo = postTelegram(
                method = "getFile",
                request = JSONObject().put("file_id", fileId)
            )
            if (!fileInfo.ok || fileInfo.filePath.isNullOrBlank()) {
                error("Telegram getFile failed: ${fileInfo.description}")
            }

            val connection = URI.create("https://api.telegram.org/file/bot$botToken/${fileInfo.filePath}")
                .toURL()
                .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 20_000

                val status = connection.responseCode
                if (status !in 200..299) {
                    error("Telegram file download failed: HTTP $status")
                }

                val bytes = connection.inputStream.use { it.readBytes() }
                val contentType = connection.contentType
                    ?.substringBefore(";")
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                return CachedImage(contentType = contentType, bytes = bytes)
            } finally {
                connection.disconnect()
            }
        }

        private fun postTelegram(method: String, request: JSONObject): TelegramSendResult {
            return runCatching {
                val body = request.toString().toByteArray(StandardCharsets.UTF_8)
                val connection = URI.create("https://api.telegram.org/bot$botToken/$method")
                    .toURL()
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body) }

                    val status = connection.responseCode
                    val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    val json = runCatching { JSONObject(response) }.getOrNull()
                    val ok = status in 200..299 && json?.optBoolean("ok", false) == true
                    val description = json?.optString("description")?.takeIf { it.isNotBlank() }
                        ?: if (ok) "Telegram accepted request" else response.ifBlank { "Empty Telegram response" }
                    val filePath = json?.optJSONObject("result")?.optString("file_path")?.takeIf { it.isNotBlank() }
                    val invoiceLink = json?.optString("result")?.takeIf { it.startsWith("http") }

                    TelegramSendResult(
                        ok = ok,
                        statusCode = status,
                        description = description,
                        filePath = filePath,
                        invoiceLink = invoiceLink
                    )
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                TelegramSendResult(
                    ok = false,
                    statusCode = 0,
                    description = error.message ?: "Telegram request failed"
                )
            }
        }
    }
}
