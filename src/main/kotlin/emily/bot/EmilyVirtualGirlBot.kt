package emily.bot

import emily.app.BotConfig
import emily.data.*
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import emily.service.defaultSystemPrompt
import emily.resources.Strings
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.send.*
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import kotlin.text.buildString

class EmilyVirtualGirlBot(
    private val config: BotConfig,
    private val repository: BalanceRepository,
    private val selectionRepository: StorySelectionRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val chatService: ChatService,
    private val animeImageService: ImageService,
    private val realisticImageService: ImageService,
    private val memory: ConversationMemory,
    private val translator: MyMemoryTranslator?
) : TelegramLongPollingBot() {

    private val log = LoggerFactory.getLogger(EmilyVirtualGirlBot::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val systemMessages = ConcurrentHashMap<Long, MutableList<Int>>()
    private val protectedMessages = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val imageTag = "#pic"
    private val chatModel = "venice-uncensored"

    // имена моделей для логов
    private val animeImageModelName = "wai-Illustrious"
    private val realisticImageModelName = "lustify-v7"

    // стиль генерации изображений
    private enum class ImageStyle { ANIME, REALISTIC }
    private val userImageStyles = ConcurrentHashMap<Long, ImageStyle>()

    // БАЗОВАЯ ПЕРСОНА ПО УМОЛЧАНИЮ (если что-то пошло не так)
    private val defaultPersona = Strings.get("persona.default")

    // Текущие персона для каждого пользователя
    private val userPersonas = ConcurrentHashMap<Long, String>()

    private val webAppSelectionParser = WebAppSelectionParser(defaultPersona)
    private val miniAppUrl = "https://t.me/${getBotUsername()}?startapp=select_story"

    override fun getBotUsername(): String = "EmilyVirtualGirlBot"
    override fun getBotToken(): String = config.telegramToken

    private fun getPersona(chatId: Long): String {
        return userPersonas[chatId] ?: defaultPersona
    }

    private fun setPersona(chatId: Long, persona: String) {
        userPersonas[chatId] = persona
    }

    // ================== СТИЛИ КАРТИНОК ==================

    // styleCode: 1 = anime, 2 = realistic
    private fun setImageStyle(chatId: Long, styleCode: Int?) {
        val style = when (styleCode) {
            2 -> ImageStyle.REALISTIC
            1 -> ImageStyle.ANIME
            else -> ImageStyle.ANIME
        }
        userImageStyles[chatId] = style
        println("🎚 Image style set: chatId=$chatId, style=$style (code=${styleCode ?: -1})")
        log.info("Image style set for chatId={}, style={}, code={}", chatId, style, styleCode)
    }

    private fun getImageStyle(chatId: Long): ImageStyle {
        return userImageStyles[chatId] ?: ImageStyle.ANIME
    }

    // ================== МЕНЮ БОТА ==================

    fun registerBotMenu() = runBlocking {
        println("🚀 registerBotMenu() - Регистрация команд бота")
        log.info("registerBotMenu()")
        val commands = listOf(
            BotCommand("/start", Strings.get("command.start")),
            BotCommand("/buy", Strings.get("command.buy")),
            BotCommand("/balance", Strings.get("command.balance")),
            BotCommand("/reset", Strings.get("command.reset")),
            BotCommand("/pic", Strings.get("command.pic"))
        )
        executeSafe(SetMyCommands(commands, BotCommandScopeDefault(), null))
    }

    override fun onUpdateReceived(update: Update) {
        println("📥 onUpdateReceived - Новое обновление получено")
        scope.launch {
            try {
                handleUpdate(update)
            } catch (e: Exception) {
                println("❌ Ошибка в handleUpdate: ${e.message}")
                log.error("Exception in handleUpdate", e)
            }
        }
    }

    override fun onClosing() {
        println("🔴 onClosing - Бот завершает работу")
        super.onClosing()
        scope.cancel()
    }

    private suspend fun handleUpdate(update: Update) {
        println("🔄 handleUpdate - Обработка обновления")
        when {
            update.hasPreCheckoutQuery() -> {
                println("💰 handleUpdate: preCheckout id=${update.preCheckoutQuery.id}")
                log.info("handleUpdate: preCheckout id={}", update.preCheckoutQuery.id)
                val answer = AnswerPreCheckoutQuery().apply {
                    preCheckoutQueryId = update.preCheckoutQuery.id
                    ok = true
                }
                executeSafe(answer)
            }

            update.hasMessage() && update.message.webAppData != null -> {
                val dataJson = update.message.webAppData.data
                println("🌐 WebAppData: $dataJson")
            }

            update.hasMessage() && update.message.successfulPayment != null -> {
                println("✅ handleUpdate: successfulPayment")
                log.info("handleUpdate: successfulPayment")
                onSuccessfulPayment(update.message)
            }

            update.hasMessage() && update.message.hasText() -> {
                val t = update.message.text
                println("📝 handleUpdate: textMessage chatId=${update.message.chatId}, text.len=${t?.length ?: -1}")
                log.info(
                    "handleUpdate: textMessage chatId={}, text.len={}",
                    update.message.chatId,
                    t?.length ?: -1
                )
                handleTextMessage(update)
            }

            update.hasCallbackQuery() -> {
                println("🔘 handleUpdate: callback ${update.callbackQuery.data}")
                log.info("handleUpdate: callback {}", update.callbackQuery.data)
                handleCallback(update)
            }

            else -> {
                println("❓ handleUpdate: unhandled update")
                log.warn("handleUpdate: unhandled update")
            }
        }
    }

    private suspend fun handleTextMessage(update: Update) {
        val chatId = update.message.chatId
        val textRaw = update.message.text.trim()
        val messageId = update.message.messageId

        println("📨 handleTextMessage START: chatId=$chatId, msgId=$messageId, text='${textRaw.replace('\n', ' ')}'")
        log.info(
            "handleTextMessage: chatId={}, msgId={}, text='{}'",
            chatId,
            messageId,
            textRaw.replace('\n', ' ')
        )

        // 1️⃣ Пытаемся вытащить невидимые данные (charId|storyId|styleCode)
        val hidden = webAppSelectionParser.decodeHiddenData(textRaw)
        if (hidden != null) {
            println(
                "🎯 Hidden WebApp data detected: charId=${hidden.characterId}, " +
                        "storyId=${hidden.storyId}, style=${hidden.styleCode}"
            )
            log.info(
                "Hidden WebApp data: charId={}, storyId={}, style={}",
                hidden.characterId,
                hidden.storyId,
                hidden.styleCode
            )

            val parsed = webAppSelectionParser.parseWebAppMessage(textRaw)
            if (parsed == null) {
                println("❌ Не удалось распарсить текст истории из сообщения")
                sendText(chatId, Strings.get("error.story.parse"))
                return
            }

            // 🔥 Восстанавливаем внешний вид по characterId + styleCode
            val personaForSelection = webAppSelectionParser.resolvePersona(
                characterId = hidden.characterId,
                styleCode = hidden.styleCode
            )

            // 🔥 Скрытое описание истории по characterId + storyId (РУССКИЙ текст с реальным именем)
            val hiddenStoryPrompt = webAppSelectionParser.resolveStoryPrompt(
                characterId = hidden.characterId,
                storyId = hidden.storyId
            )

            // Обновляем persona для конкретного пользователя
            setPersona(chatId, personaForSelection)

            // И ОБНОВЛЯЕМ стиль картинок (1 — аниме, 2 — реализм)
            setImageStyle(chatId, hidden.styleCode)

            println("🎨 persona resolved for charId=${hidden.characterId}, style=${hidden.styleCode}, chatId=$chatId")

            val selection = StorySelection(
                userId = chatId,
                characterName = parsed.characterName,
                // внешний вид персонажа (полный промт)
                characterAppearance = personaForSelection,
                // характер: либо короткий текст из WebApp, либо используем тот же промт внешности
                characterPersonality = parsed.characterPersonality ?: personaForSelection,
                storyTitle = parsed.storyTitle,
                // сюда кладём скрытое русское описание истории + инструкцию, fallback — то что пришло из WebApp
                storyDescription = hiddenStoryPrompt.ifBlank { parsed.storyDescription ?: parsed.storyTitle },
                full_story_text = parsed.fullStoryText,
                style = hidden.styleCode.toString()
            )

            applySelection(
                chatId = chatId,
                selection = selection,
                source = "webapp_hidden",
                sendConfirmation = false
            )

            println("✅ WebApp hidden selection applied successfully for chatId=$chatId")
            return
        }

        // 2️⃣ Остальные команды / сообщения
        when {
            textRaw.equals("/start", true) -> {
                println("🔹 Обработка команды /start для chatId=$chatId")
                memory.initIfNeeded(chatId)
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendWelcome(chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/buy", true) -> {
                println("🔹 Обработка команды /buy для chatId=$chatId")
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBuyMenu(chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/balance", true) -> {
                println("🔹 Обработка команды /balance для chatId=$chatId")
                val balance = ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBalance(chatId, balance)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/reset", true) -> {
                println("🔹 Обработка команды /reset для chatId=$chatId")
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)  // 🔥 добавили
                deleteOldSystemMessages(chatId)
                sendEphemeral(chatId, Strings.get("reset.success"), ttlSeconds = 10)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/pic", true) -> {
                println("🔹 Обработка команды /pic")
                sendEphemeral(
                    chatId,
                    Strings.get("pic.hint"),
                    ttlSeconds = 20
                )
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.startsWith(imageTag, true) || textRaw.startsWith("/pic ", true) -> {
                println("🖼️ Обработка запроса изображения для chatId=$chatId")
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleImage(chatId, textRaw)
            }

            else -> {
                println("💬 Обработка обычного сообщения чата для chatId=$chatId")
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleChat(chatId, textRaw)
            }
        }
    }

    private fun preview(s: String?, max: Int = 220): String {
        if (s.isNullOrBlank()) return "∅"
        val clean = s.replace("\n", "\\n").replace("\r", "\\r")
        return if (clean.length <= max) clean else clean.take(max) + "… (len=" + clean.length + ")"
    }

    // ================== ПРИМЕНЕНИЕ ВЫБОРА ИСТОРИИ ==================
    suspend fun applySelection(
        chatId: Long,
        selection: StorySelection,
        source: String,
        sendConfirmation: Boolean = true
    ) {
        println(
            "🎭 applySelection: chatId=$chatId, source=$source, character='${selection.characterName}', " +
                    "story.len=${selection.full_story_text?.length ?: 0}"
        )
        selectionRepository.save(selection)

        // 🔥 чистим старую историю чата
        chatHistoryRepository.clear(chatId)

        setPersona(chatId, selection.characterAppearance ?: defaultPersona)
        selection.style?.toIntOrNull()?.let { setImageStyle(chatId, it) }

        val scenario = buildScenario(selection)

        memory.reset(chatId)
        memory.setSystem(chatId, scenario)

        if (sendConfirmation) {
            sendStorySelectionConfirmation(chatId, selection)
        }
    }

    private fun buildScenario(selection: StorySelection): String {
        val introStory = selection.full_story_text ?: selection.storyDescription ?: selection.storyTitle
        return buildString {
            append(Strings.get("scenario.character.intro", selection.characterName)).append(' ')

            selection.characterPersonality?.let {
                append(Strings.get("scenario.personality", it)).append(' ')
            }

            selection.style?.let {
                val styleText = when (it) {
                    "1" -> Strings.get("scenario.style.anime")
                    "2" -> Strings.get("scenario.style.realistic")
                    else -> it
                }
                append(Strings.get("scenario.style.prefix", styleText)).append(' ')
            }

            selection.storyDescription?.let {
                append(Strings.get("scenario.story.description", it)).append(' ')
            }

            append(Strings.get("scenario.story.intro", introStory)).append(' ')
            append(Strings.get("scenario.language")).append(' ')
            append(Strings.get("scenario.safety")).append(' ')
            append(Strings.get("scenario.consent"))
        }
    }

    private suspend fun ensureStorySelection(chatId: Long): StorySelection? {
        val selection = selectionRepository.get(chatId)
        if (selection == null) {
            sendStorySelectionRequest(chatId)
            return null
        }

        setPersona(chatId, selection.characterAppearance ?: defaultPersona)
        selection.style?.toIntOrNull()?.let { setImageStyle(chatId, it) }

        val history = memory.history(chatId)
        if (history.isEmpty() || history.firstOrNull()?.second == defaultSystemPrompt()) {
            memory.reset(chatId)
            memory.setSystem(chatId, buildScenario(selection))
        }

        return selection
    }

    private suspend fun sendStorySelectionRequest(chatId: Long) {
        val caption = Strings.get("story.selection.request.caption")

        val markup = InlineKeyboardMarkup().apply {
            keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = Strings.get("story.selection.button")
                        url = miniAppUrl
                    }
                )
            )
        }

        val message = SendPhoto().apply {
            this.chatId = chatId.toString()
            photo = InputFile(Plan.PRO.photoUrl)
            this.caption = caption
            parseMode = "HTML"
            replyMarkup = markup
        }

        rememberSystemMessage(chatId, executeSafe(message).messageId)
    }

    private suspend fun sendStorySelectionConfirmation(chatId: Long, selection: StorySelection) {
        println("📤 sendStorySelectionConfirmation: chatId=$chatId")
        val message = Strings.get("story.selection.confirmation", escapeHtml(selection.characterName))

        executeSafe(SendMessage(chatId.toString(), message).apply { parseMode = "HTML" })
        println("✅ Confirmation message sent for chatId=$chatId")
    }

    // ================== CALLBACK'И (покупки) ==================
    private suspend fun handleCallback(update: Update) {
        val chatId = update.callbackQuery.message.chatId
        val data = update.callbackQuery.data
        println("🔘 handleCallback chatId=$chatId, data=$data")
        log.info("handleCallback chatId={}, data={}", chatId, data)
        memory.autoClean(chatId)
        deleteOldSystemMessages(chatId)
        when {
            data.startsWith("buy:plan:") -> {
                println("💰 Создание инвойса для плана: ${data.removePrefix("buy:plan:")} для chatId=$chatId")
                createPlanInvoice(chatId, data.removePrefix("buy:plan:"))
            }

            data.startsWith("buy:pack:") -> {
                println("💰 Создание инвойса для пакета: ${data.removePrefix("buy:pack:")} для chatId=$chatId")
                createPackInvoice(chatId, data.removePrefix("buy:pack:"))
            }
        }
    }

    // ================== СИСТЕМНЫЕ СООБЩЕНИЯ ==================
    private suspend fun sendWelcome(chatId: Long) {
        println("👋 sendWelcome: chatId=$chatId")
        val text = Strings.get("welcome.text")
        val message = executeSafe(SendMessage(chatId.toString(), text))
        rememberSystemMessage(chatId, message.messageId)
    }

    private suspend fun sendBalance(chatId: Long, balance: UserBalance) {
        println("💰 sendBalance: chatId=$chatId")
        val planTitle = when (balance.plan) {
            Plan.BASIC.code -> Plan.BASIC.title
            Plan.PRO.code -> Plan.PRO.title
            Plan.ULTRA.code -> Plan.ULTRA.title
            else -> Strings.get("balance.plan.none")
        }
        val until = balance.planExpiresAt?.let { Instant.ofEpochMilli(it).toString() } ?: "—"
        val text = Strings.get(
            "balance.text",
            planTitle,
            until,
            balance.textTokensLeft,
            balance.imageCreditsLeft,
            balance.dayImageUsed
        )
        val message = SendMessage(chatId.toString(), text).apply { parseMode = "HTML" }
        rememberSystemMessage(chatId, executeSafe(message).messageId)
    }

    private suspend fun sendBuyMenu(chatId: Long) {
        println("🛍️ sendBuyMenu: chatId=$chatId")
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        Plan.values().forEach { plan ->
            rows += listOf(
                InlineKeyboardButton().apply {
                    text = Strings.get("buy.menu.plan.button", plan.title, plan.priceRub)
                    callbackData = "buy:plan:${plan.code}"
                }
            )
        }
        rows += listOf(
            InlineKeyboardButton().apply {
                text = Strings.get("buy.menu.pack.p10")
                callbackData = "buy:pack:${ImagePack.P10.code}"
            }
        )
        rows += listOf(
            InlineKeyboardButton().apply {
                text = Strings.get("buy.menu.pack.p50")
                callbackData = "buy:pack:${ImagePack.P50.code}"
            }
        )
        val markup = InlineKeyboardMarkup().apply { keyboard = rows }
        val msg = SendMessage(
            chatId.toString(),
            Strings.get("buy.menu.text")
        ).apply {
            replyMarkup = markup
        }
        rememberSystemMessage(chatId, executeSafe(msg).messageId)
    }

    private suspend fun createPlanInvoice(chatId: Long, planCode: String) {
        println("🧾 createPlanInvoice: chatId=$chatId, planCode=$planCode")
        val plan = Plan.byCode(planCode) ?: return
        val invoicePayload = "plan:${plan.code}:${UUID.randomUUID()}"
        val providerDataJson = makeProviderData(
            desc = Strings.get("invoice.plan.provider.desc", plan.title),
            rub = plan.priceRub,
            includeVat = true
        )
        val invoice = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = Strings.get("invoice.plan.title", plan.title)
            description = Strings.get(
                "invoice.plan.description",
                plan.monthlyTextTokens,
                plan.monthlyImageCredits
            )
            payload = invoicePayload
            providerToken = config.providerToken
            currency = "RUB"
            startParameter = "plan-${plan.code}"
            prices = listOf(LabeledPrice(Strings.get("invoice.plan.price.label", plan.title), plan.priceRub * 100))
            needEmail = true
            sendEmailToProvider = true
            isFlexible = false
            providerData = providerDataJson
            photoUrl = plan.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        safeExecuteInvoice(chatId, invoice)
    }

    private suspend fun createPackInvoice(chatId: Long, packCode: String) {
        println("🧾 createPackInvoice: chatId=$chatId, packCode=$packCode")
        val pack = ImagePack.byCode(packCode) ?: return
        val invoicePayload = "pack:${pack.code}:${UUID.randomUUID()}"
        val providerDataJson = makeProviderData(
            desc = Strings.get("invoice.pack.provider.desc", pack.title),
            rub = pack.priceRub,
            includeVat = true
        )
        val invoice = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = pack.title
            description = Strings.get("invoice.pack.description", pack.title)
            payload = invoicePayload
            providerToken = config.providerToken
            currency = "RUB"
            startParameter = "pack-${pack.code}"
            prices = listOf(LabeledPrice(pack.title, pack.priceRub * 100))
            needEmail = true
            sendEmailToProvider = true
            isFlexible = false
            providerData = providerDataJson
            photoUrl = pack.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        safeExecuteInvoice(chatId, invoice)
    }

    private suspend fun onSuccessfulPayment(message: Message) {
        println("✅ onSuccessfulPayment: chatId=${message.chatId}")
        val chatId = message.chatId
        val payment = message.successfulPayment ?: return
        val payload = payment.invoicePayload ?: return
        val totalRub = (payment.totalAmount / 100.0).toInt()
        val balance = ensureUserBalance(chatId)
        when {
            payload.startsWith("plan:") -> {
                val code = payload.split(":").getOrNull(1)
                val plan = Plan.byCode(code) ?: return
                val monthMs = 30L * 24 * 60 * 60 * 1000
                val now = System.currentTimeMillis()
                val base = maxOf(balance.planExpiresAt ?: 0L, now)
                balance.plan = plan.code
                balance.planExpiresAt = base + monthMs
                balance.textTokensLeft += plan.monthlyTextTokens
                balance.imageCreditsLeft += plan.monthlyImageCredits
                repository.put(balance)
                repository.addPayment(chatId, payload, totalRub)
                sendEphemeral(
                    chatId,
                    Strings.get(
                        "payment.plan.activated",
                        plan.title,
                        Instant.ofEpochMilli(balance.planExpiresAt!!),
                        plan.monthlyTextTokens,
                        plan.monthlyImageCredits
                    ),
                    ttlSeconds = 20
                )
                println("🎉 План активирован: ${plan.title} для chatId=$chatId")
            }

            payload.startsWith("pack:") -> {
                val code = payload.split(":").getOrNull(1)
                val pack = ImagePack.byCode(code) ?: return
                balance.imageCreditsLeft += pack.images
                repository.put(balance)
                repository.addPayment(chatId, payload, totalRub)
                sendEphemeral(
                    chatId,
                    Strings.get("payment.pack.activated", pack.images, pack.title),
                    ttlSeconds = 15
                )
                println("🎉 Пакет активирован: ${pack.title} для chatId=$chatId")
            }
        }
    }

    // ================== ЧАТ ==================
    private suspend fun handleChat(chatId: Long, text: String) {
        println("💬 handleChat: chatId=$chatId, text='${preview(text, 50)}'")

        val isNewDialogue = memory.history(chatId).isEmpty()

        if (isNewDialogue) {
            // 1️⃣ Восстанавливаем выбор истории + system-промт
            val selection = ensureStorySelection(chatId) ?: return
            println("🧭 Story selection restored for chatId=$chatId, character='${selection.characterName}'")

            // 2️⃣ ДОТЯГИВАЕМ ПОСЛЕДНИЕ 20 РЕПЛИК ИЗ FIREBASE
            val lastTurns = chatHistoryRepository.getLast(chatId, limit = 20)
            if (lastTurns.isNotEmpty()) {
                println("♻️ Restoring ${lastTurns.size} chat turns from history for chatId=$chatId")

                // ensureStorySelection уже положил system-промт (buildScenario(selection))
                // поэтому просто возвращаем user/assistant-реплики в память
                lastTurns.forEach { turn ->
                    memory.append(chatId, turn.role, turn.text)
                }
            }
        }

        val balance = ensureUserBalance(chatId)
        if (balance.textTokensLeft <= 0) {
            println("⚠️ Недостаточно токенов: chatId=$chatId")
            sendEphemeral(
                chatId,
                Strings.get("text.tokens.not.enough"),
                ttlSeconds = 15
            )
            return
        }

        memory.initIfNeeded(chatId)

        // 3️⃣ Сохраняем ТЕКУЩЕЕ сообщение пользователя и в память, и в Firebase
        memory.append(chatId, "user", text)
        chatHistoryRepository.append(chatId, "user", text)

        val history = memory.history(chatId)

        val result = withTyping(chatId) { chatService.generateReply(history) }
        println("🤖 ChatService result: text.len=${result.text.length}, tokensUsed=${result.tokensUsed} для chatId=$chatId")
        log.info("ChatService result: text.len={}, tokensUsed={}", result.text.length, result.tokensUsed)

        // 4️⃣ То же самое для ответа ассистента
        memory.append(chatId, "assistant", result.text)
        chatHistoryRepository.append(chatId, "assistant", result.text)

        sendText(chatId, result.text)

        if (result.tokensUsed > 0) {
            balance.textTokensLeft -= result.tokensUsed
            if (balance.textTokensLeft < 0) balance.textTokensLeft = 0
            repository.put(balance)
            repository.logUsage(chatId, result.tokensUsed, mapOf("type" to "chat", "model" to chatModel))
            println("📊 Токены обновлены: chatId=$chatId, tokensLeft=${balance.textTokensLeft}")
            log.info("tokens updated chatId={}, tokensLeft={}", chatId, balance.textTokensLeft)
        }
        if (balance.plan == null && balance.textTokensLeft <= 0) {
            println("⚠️ Бесплатный лимит исчерпан: chatId=$chatId")
            sendEphemeral(chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }


    // ================== КАРТИНКИ ==================
    private suspend fun handleImage(chatId: Long, textRaw: String) {
        println("🖼️ handleImage: chatId=$chatId, text='${preview(textRaw, 50)}'")
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)
        if (balance.plan == null && balance.imageCreditsLeft < 1) {
            println("⚠️ Дневной лимит изображений исчерпан: chatId=$chatId")
            sendEphemeral(
                chatId,
                Strings.get("image.daily.limit", cap),
                ttlSeconds = 20
            )
            return
        }
        if (balance.imageCreditsLeft <= 0) {
            println("⚠️ Нет кредитов на изображения: chatId=$chatId")
            sendEphemeral(
                chatId,
                Strings.get("image.no.credits"),
                ttlSeconds = 20
            )
            return
        }
        val originalPrompt = textRaw.removePrefix(imageTag).removePrefix("/pic").trim()
        if (originalPrompt.isBlank()) {
            println("⚠️ Пустой промпт для изображения: chatId=$chatId")
            sendEphemeral(chatId, Strings.get("image.empty.prompt"), ttlSeconds = 10)
            return
        }

        val containsCyrillic = hasCyrillic(originalPrompt)
        println("🔤 Проверка языка: containsCyrillic=$containsCyrillic, prompt='${preview(originalPrompt, 30)}'")

        val finalPrompt = if (containsCyrillic) {
            println("🔤 Перевод промпта с русского: chatId=$chatId")
            val translated = withUploadPhoto(chatId) { translateRuToEn(originalPrompt) }
            if (translated != null) {
                println("✅ Переведено: '$translated'")
                translated
            } else {
                println("❌ Перевод не удался, использую оригинал")
                originalPrompt
            }
        } else {
            println("🔤 Английский промпт, перевод не требуется")
            originalPrompt
        }

        val style = getImageStyle(chatId)
        val (service, modelName) = when (style) {
            ImageStyle.ANIME -> animeImageService to animeImageModelName
            ImageStyle.REALISTIC -> realisticImageService to realisticImageModelName
        }

        println(
            "🎨 Генерация изображения: chatId=$chatId, style=$style, model=$modelName, " +
                    "finalPrompt='${preview(finalPrompt, 50)}'"
        )

        val bytes = withUploadPhoto(chatId) {
            service.generateImage(finalPrompt, getPersona(chatId))
        }
        if (bytes == null) {
            println("❌ Ошибка генерации изображения: chatId=$chatId")
            sendEphemeral(chatId, Strings.get("image.generate.fail"), ttlSeconds = 12)
            return
        }
        sendPhoto(chatId, bytes, caption = null)
        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)
        repository.logUsage(
            chatId,
            0,
            mapOf("type" to "image", "model" to modelName, "credits_used" to 1)
        )
        println("✅ Изображение сгенерировано: chatId=$chatId, creditsLeft=${balance.imageCreditsLeft}")
        if (balance.plan == null && (balance.textTokensLeft <= 0 || balance.imageCreditsLeft <= 0)) {
            println("⚠️ Бесплатный лимит исчерпан после генерации: chatId=$chatId")
            sendEphemeral(chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    // УЛУЧШЕННАЯ ФУНКЦИЯ ПРОВЕРКИ КИРИЛЛИЦЫ
    private fun hasCyrillic(text: String): Boolean {
        val cyrillicPattern = Regex("[а-яА-ЯёЁ]")
        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        println("🔍 Проверка кириллицы: text='${preview(text, 20)}', hasCyrillic=$hasCyrillic")
        return hasCyrillic
    }

    // УЛУЧШЕННАЯ ФУНКЦИЯ ПЕРЕВОДА (через MyMemoryTranslator)
    private suspend fun translateRuToEn(text: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            println("🌐 Перевод текста: '${preview(text, 30)}'")
            val result = translator?.translate(text, "ru", "en")
            println("🌐 Результат перевода: '${preview(result, 30)}'")
            result
        } catch (e: Exception) {
            println("❌ Ошибка перевода: ${e.message}")
            null
        }
    }

    // ================== ВСПОМОГАТЕЛЬНЫЕ ШТУКИ ==================
    private suspend fun deleteOldSystemMessages(chatId: Long) {
        val ids = systemMessages[chatId] ?: return
        println("🗑️ deleteOldSystemMessages: chatId=$chatId, count=${ids.size}")
        val iterator = ids.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (protectedMessages[chatId]?.contains(id) == true) continue
            try {
                executeSafe(DeleteMessage(chatId.toString(), id))
            } catch (_: Exception) {
            }
            iterator.remove()
        }
    }

    private suspend fun sendText(chatId: Long, text: String, html: Boolean = false) {
        println("📤 sendText: chatId=$chatId, text='${preview(text, 50)}'")
        val message = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
        executeSafe(message)
    }

    private suspend fun sendPhoto(chatId: Long, bytes: ByteArray, caption: String?) {
        println("📸 sendPhoto: chatId=$chatId, bytes=${bytes.size}, caption=$caption")
        val photo = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(ByteArrayInputStream(bytes), "image.png")
            this.caption = caption ?: Strings.get("photo.default.caption")
        }
        executeSafe(photo)
    }

    private fun rememberSystemMessage(chatId: Long, messageId: Int) {
        val list = systemMessages.computeIfAbsent(chatId) { mutableListOf() }
        list += messageId
        println("💾 rememberSystemMessage: chatId=$chatId, messageId=$messageId")
    }

    private fun markProtected(chatId: Long, messageId: Int) {
        val set = protectedMessages.computeIfAbsent(chatId) { mutableSetOf() }
        set += messageId
        println("🛡️ markProtected: chatId=$chatId, messageId=$messageId")
    }

    private suspend fun safeExecuteInvoice(chatId: Long, invoice: SendInvoice) {
        println("🧾 safeExecuteInvoice: chatId=$chatId")
        try {
            val message = executeSafe(invoice)
            markProtected(chatId, message.messageId)
            println("✅ Invoice sent successfully: chatId=$chatId")
        } catch (ex: TelegramApiRequestException) {
            println("❌ Invoice error: ${ex.message}")
            val details = buildString {
                appendLine(Strings.get("invoice.error.details", ex.message, ex.apiResponse, ex.parameters))
            }
            sendEphemeral(chatId, "❌ $details", ttlSeconds = 20)
        } catch (ex: Exception) {
            println("❌ Unexpected invoice error: ${ex.message}")
            sendEphemeral(
                chatId,
                Strings.get("invoice.error.unexpected", ex.message ?: ex.toString()),
                ttlSeconds = 20
            )
        }
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

    private suspend fun ensureUserBalance(userId: Long): UserBalance {
        val balance = repository.get(userId)
        val now = System.currentTimeMillis()
        if (balance.planExpiresAt?.let { now > it } == true) {
            balance.plan = null
            balance.planExpiresAt = null
            println("🔄 План истек: userId=$userId")
        }
        val today = LocalDate.now().toString()
        if (balance.dayStamp != today) {
            balance.dayStamp = today
            balance.dayImageUsed = 0
            println("🔄 Сброс дневного лимита: userId=$userId")
        }
        repository.put(balance)
        return balance
    }

    private fun dailyCap(plan: String?): Int = when (plan) {
        Plan.BASIC.code -> DAILY_IMAGE_CAP_BASIC
        Plan.PRO.code -> DAILY_IMAGE_CAP_PRO
        Plan.ULTRA.code -> DAILY_IMAGE_CAP_ULTRA
        else -> 1
    }

    private fun isDeletableCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "/start" || t == "/buy" || t == "/balance" || t == "/reset" || t == "/pic"
    }

    private suspend fun deleteUserCommand(chatId: Long, messageId: Int, text: String) {
        if (isDeletableCommand(text)) {
            println("🗑️ deleteUserCommand: chatId=$chatId, messageId=$messageId")
            try {
                executeSafe(DeleteMessage(chatId.toString(), messageId))
            } catch (_: Exception) {
            }
        }
    }

    private fun escapeHtml(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '&' -> append("&amp;")
                else -> append(ch)
            }
        }
    }

    private suspend fun sendEphemeral(
        chatId: Long,
        text: String,
        ttlSeconds: Long,
        html: Boolean = false
    ) {
        println("⏳ sendEphemeral: chatId=$chatId, text='${preview(text, 50)}', ttl=$ttlSeconds")
        val message = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
        val sent = executeSafe(message)
        scope.launch {
            delay(ttlSeconds * 1000)
            try {
                executeSafe(DeleteMessage(chatId.toString(), sent.messageId))
                println("🗑️ Ephemeral message deleted: chatId=$chatId")
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun <T> withChatAction(
        chatId: Long,
        action: ActionType,
        block: suspend () -> T
    ): T {
        val job = scope.launch {
            while (isActive) {
                try {
                    val chatAction = SendChatAction.builder()
                        .chatId(chatId.toString())
                        .action(action.toString())
                        .build()
                    executeSafe(chatAction)
                } catch (_: Exception) {
                }
                delay(1000)
            }
        }
        return try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun <T> withTyping(chatId: Long, block: suspend () -> T): T =
        withChatAction(chatId, ActionType.TYPING, block)

    private suspend fun <T> withUploadPhoto(chatId: Long, block: suspend () -> T): T =
        withChatAction(chatId, ActionType.UPLOADPHOTO, block)

    // --- Telegram execute wrappers ---
    private suspend fun executeSafe(method: SendMessage): Message =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SendPhoto): Message =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: DeleteMessage): Boolean =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SendInvoice): Message =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: AnswerPreCheckoutQuery): Boolean =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SetMyCommands): Boolean =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SendChatAction): Boolean =
        withContext(Dispatchers.IO) { execute(method) }
}
