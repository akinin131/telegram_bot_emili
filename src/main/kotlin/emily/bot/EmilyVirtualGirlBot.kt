package emily.bot

import emily.app.BotConfig
import emily.data.*
import emily.resources.Strings
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import kotlin.text.buildString

class EmilyVirtualGirlBot(
    private val config: BotConfig,
    private val repository: BalanceRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val chatService: ChatService,
    private val animeImageService: ImageService,
    private val realisticImageService: ImageService,
    private val memory: ConversationMemory,
    private val translator: MyMemoryTranslator?
) : TelegramLongPollingBot() {

    override fun getBotUsername(): String = "virtal_girl_sex_bot"
    override fun getBotToken(): String = config.telegramToken

    private val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-chat actor (исключает конфликты/гонки внутри одного чата, но не блокирует другие чаты)
    // ─────────────────────────────────────────────────────────────────────────────

    private data class ChatSession(
        val scope: CoroutineScope,
        val inbox: Channel<Update>,
        val state: SessionState
    )

    private data class SessionState(
        @Volatile var awaitingImagePrompt: Boolean = false,
        @Volatile var lastSystemMessageId: Int? = null,
        val protectedMessageIds: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
        val ephemeralJobs: MutableMap<Int, Job> = ConcurrentHashMap()
    )

    private val sessions = ConcurrentHashMap<Long, ChatSession>()

    private fun sessionFor(chatId: Long): ChatSession {
        return sessions.computeIfAbsent(chatId) {
            val sessionScope = CoroutineScope(SupervisorJob(botScope.coroutineContext[Job]) + Dispatchers.Default)
            val channel = Channel<Update>(capacity = Channel.BUFFERED)
            val state = SessionState()

            val session = ChatSession(sessionScope, channel, state)

            // Single consumer per chat: порядок сообщений в одном чате сохраняется.
            sessionScope.launch {
                channel.consumeEach { update ->
                    try {
                        handleUpdateInternal(session, update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // не падаем на одном апдейте
                    }
                }
            }

            session
        }
    }

    override fun onUpdateReceived(update: Update) {
        // Быстро роутим в актор конкретного чата. Разные чаты → параллельно.
        val chatId = extractChatId(update) ?: return
        val session = sessionFor(chatId)
        session.inbox.trySend(update)
    }

    override fun onClosing() {
        super.onClosing()
        botScope.cancel()
        sessions.values.forEach { it.scope.cancel() }
    }

    private fun extractChatId(update: Update): Long? {
        return when {
            update.hasPreCheckoutQuery() -> update.preCheckoutQuery?.from?.id // не всегда чат, но ок для ответа
            update.hasMessage() -> update.message.chatId
            update.hasCallbackQuery() -> update.callbackQuery.message.chatId
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Bot menu
    // ─────────────────────────────────────────────────────────────────────────────

    fun registerBotMenu() = runBlocking {
        val commands = listOf(
            BotCommand("/start", Strings.get("command.start")),
            BotCommand("/buy", Strings.get("command.buy")),
            BotCommand("/balance", Strings.get("command.balance")),
            BotCommand("/reset", Strings.get("command.reset")),
            BotCommand("/pic", Strings.get("command.pic"))
        )
        executeSafe(SetMyCommands(commands, BotCommandScopeDefault(), null))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Models / Prompts
    // ─────────────────────────────────────────────────────────────────────────────

    private val imageTag = "#pic"
    private val imagePromptPrefix =
        "rating:explicit, masterpiece, absurdres, highly detailed, very aesthetic, newest, recent,"
    private val imagePromptSystem = """
You generate prompts for a Stable Diffusion image model.

Rules:
- Output ONE line only
- Output ONLY comma-separated tags
- No sentences, no explanations, no instructions
- Use short visual tags (1–3 words)
- Prefer danbooru-style tags

Order:
rating, quality/style, subject count, appearance, clothing/nudity, accessories, pose/camera, environment, lighting/mood, action

Example format:
rating:general, masterpiece, absurdres, highly detailed, very aesthetic, newest, recent, 1girl, goth fashion, long hair, boots, alleyway, graffiti, dark lighting, from behind, looking back

Output ONLY the tags.
""".trimIndent()


    private val chatModel = "venice-uncensored"
    private val animeImageModelName = "wai-Illustrious"
    private val realisticImageModelName = "lustify-v7"

    private val defaultPersona = Strings.get("persona.default")

    private enum class ImageStyle { ANIME, REALISTIC }
    private val defaultImageStyle = ImageStyle.ANIME

    private object MenuBtn {
        const val BALANCE = "💰 Баланс"
        const val BUY = "🛍 Купить"
        const val PIC = "🖼 Картинка"
        const val RESET = "♻️ Сброс"
        const val HELP = "ℹ️ Помощь"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Keyboards
    // ─────────────────────────────────────────────────────────────────────────────

    private fun mainMenuKeyboard(): ReplyKeyboardMarkup {
        val row1 = KeyboardRow().apply {
            add(MenuBtn.BUY)
            add(MenuBtn.BALANCE)
        }
        val row2 = KeyboardRow().apply {
            add(MenuBtn.PIC)
            add(MenuBtn.RESET)
        }
        val row3 = KeyboardRow().apply {
            add(MenuBtn.HELP)
        }
        return ReplyKeyboardMarkup().apply {
            keyboard = listOf(row1, row2, row3)
            resizeKeyboard = true
            oneTimeKeyboard = true     // ✅ после нажатия кнопки свернётся
            selective = false
            isPersistent = false       // ✅ свайп/назад снова работает нормально
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Core update handling (inside per-chat actor)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun handleUpdateInternal(session: ChatSession, update: Update) {
        when {
            update.hasPreCheckoutQuery() -> {
                val answer = AnswerPreCheckoutQuery().apply {
                    preCheckoutQueryId = update.preCheckoutQuery.id
                    ok = true
                }
                executeSafe(answer)
            }

            update.hasMessage() && update.message.successfulPayment != null -> {
                onSuccessfulPayment(session, update.message)
            }

            update.hasMessage() && update.message.hasText() -> {
                handleTextMessage(session, update)
            }

            update.hasCallbackQuery() -> {
                handleCallback(session, update)
            }

            else -> Unit
        }
    }

    private suspend fun handleTextMessage(session: ChatSession, update: Update) {
        val chatId = update.message.chatId
        val textRaw = update.message.text.trim()
        val messageId = update.message.messageId

        // Ждём описание картинки после кнопки "🖼 Картинка"
        if (session.state.awaitingImagePrompt) {
            session.state.awaitingImagePrompt = false
            ensureUserBalance(chatId)
            memory.autoClean(chatId)
            handleImage(session, chatId, "$imageTag $textRaw")
            return
        }

        when {
            textRaw.equals(MenuBtn.BUY, true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                sendBuyMenu(session, chatId)
            }

            textRaw.equals(MenuBtn.BALANCE, true) -> {
                val balance = ensureUserBalance(chatId)
                memory.autoClean(chatId)
                sendBalance(session, chatId, balance)
            }

            textRaw.equals(MenuBtn.PIC, true) -> {
                session.state.awaitingImagePrompt = true
                sendEphemeral(session, chatId, "✅ Отлично! Введите описание изображения одним сообщением 🙂", ttlSeconds = 35)
            }

            textRaw.equals(MenuBtn.RESET, true) -> {
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)
                // Системное сообщение можно тоже подчистить (актуально: просто удаляем последнее)
                deleteLastSystemMessage(session, chatId)
                sendEphemeral(session, chatId, Strings.get("reset.success"), ttlSeconds = 10)
            }

            textRaw.equals(MenuBtn.HELP, true) -> {
                val help = buildString {
                    appendLine("🧭 Меню:")
                    appendLine("• ${MenuBtn.BUY} — купить план/пакет")
                    appendLine("• ${MenuBtn.BALANCE} — посмотреть баланс")
                    appendLine("• ${MenuBtn.PIC} — генерация картинки")
                    appendLine("• ${MenuBtn.RESET} — сбросить диалог")
                    appendLine()
                    appendLine("🖼 Можно так:")
                    appendLine("• нажми ${MenuBtn.PIC} и отправь описание")
                    appendLine("• или: $imageTag котёнок в дождь")
                    appendLine("• или: /pic котёнок в дождь")
                }
                sendEphemeral(session, chatId, help, ttlSeconds = 35)
            }

            textRaw.equals("/start", true) -> {
                memory.initIfNeeded(chatId)
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                sendWelcome(session, chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/buy", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                sendBuyMenu(session, chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/balance", true) -> {
                val balance = ensureUserBalance(chatId)
                memory.autoClean(chatId)
                sendBalance(session, chatId, balance)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/reset", true) -> {
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)
                deleteLastSystemMessage(session, chatId)
                sendEphemeral(session, chatId, Strings.get("reset.success"), ttlSeconds = 10)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/pic", true) -> {
                session.state.awaitingImagePrompt = true
                sendEphemeral(session, chatId, "✅ Отлично! Введите описание изображения одним сообщением 🙂", ttlSeconds = 35)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.startsWith(imageTag, true) ||
                    textRaw.startsWith("покажи мне", true) ||
                    textRaw.startsWith("/pic ", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleImage(session, chatId, textRaw)
            }

            else -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleChat(session, chatId, textRaw)
            }
        }
    }

    private suspend fun handleCallback(session: ChatSession, update: Update) {
        val chatId = update.callbackQuery.message.chatId
        val data = update.callbackQuery.data
        memory.autoClean(chatId)

        when {
            data.startsWith("buy:plan:") -> createPlanInvoice(session, chatId, data.removePrefix("buy:plan:"))
            data.startsWith("buy:pack:") -> createPackInvoice(session, chatId, data.removePrefix("buy:pack:"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI messages: системные / ephemeral
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * СИСТЕМНОЕ сообщение:
     * - перед отправкой удаляем только "последнее системное" (если оно не protected)
     * - сохраняем новый id как lastSystemMessageId
     */
    private suspend fun sendSystemText(session: ChatSession, chatId: Long, text: String, html: Boolean = false, replyMarkup: Any? = null): Message {
        deleteLastSystemMessage(session, chatId)

        val msg = SendMessage(chatId.toString(), text).apply {
            if (html) parseMode = "HTML"
            if (replyMarkup != null) {
                this.replyMarkup = replyMarkup as ReplyKeyboard?
            }

        }
        val sent = executeSafe(msg)
        session.state.lastSystemMessageId = sent.messageId
        return sent
    }

    private suspend fun deleteLastSystemMessage(session: ChatSession, chatId: Long) {
        val lastId = session.state.lastSystemMessageId ?: return
        if (session.state.protectedMessageIds.contains(lastId)) return
        try {
            executeSafe(DeleteMessage(chatId.toString(), lastId))
        } catch (_: Exception) {
        } finally {
            // даже если не удалилось (старое/прав нет) — не держим мусор
            if (session.state.lastSystemMessageId == lastId) session.state.lastSystemMessageId = null
        }
    }

    /**
     * EPHEMERAL:
     * - отправляем
     * - планируем удаление через ttl
     * - jobs храним, чтобы не конфликтовали (например, если захотим отменять/чистить)
     */
    private suspend fun sendEphemeral(
        session: ChatSession,
        chatId: Long,
        text: String,
        ttlSeconds: Long,
        html: Boolean = false
    ) {
        val message = SendMessage(chatId.toString(), text).apply {
            if (html) parseMode = "HTML"
        }
        val sent = executeSafe(message)

        val job = session.scope.launch {
            delay(ttlSeconds * 1000)
            try {
                executeSafe(DeleteMessage(chatId.toString(), sent.messageId))
            } catch (_: Exception) {}
            finally {
                session.state.ephemeralJobs.remove(sent.messageId)
            }
        }
        session.state.ephemeralJobs[sent.messageId] = job
    }
    private fun logTokens(tag: String, chatId: Long, tokens: Int, extra: String = "") {
        val ts = Instant.now().toString()
        println("[TOKENS][$ts][$tag] chatId=$chatId tokensUsed=$tokens $extra")
    }


    // ─────────────────────────────────────────────────────────────────────────────
    // Screens: welcome / balance / buy menu
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun sendWelcome(session: ChatSession, chatId: Long) {
        val text = Strings.get("welcome.text")
        sendSystemText(session, chatId, text, html = false, replyMarkup = mainMenuKeyboard())
    }

    private suspend fun sendBalance(session: ChatSession, chatId: Long, balance: UserBalance) {
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
        sendSystemText(session, chatId, text, html = true, replyMarkup = mainMenuKeyboard())
    }

    private suspend fun sendBuyMenu(session: ChatSession, chatId: Long) {
        val rows = mutableListOf<List<InlineKeyboardButton>>()

        Plan.entries.forEach { plan ->
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

        sendSystemText(session, chatId, Strings.get("buy.menu.text"), html = false, replyMarkup = markup)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Chat logic
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun handleChat(session: ChatSession, chatId: Long, text: String) {
        val isNewDialogue = memory.history(chatId).isEmpty()

        if (isNewDialogue) {
            memory.initIfNeeded(chatId)
            val lastTurns = chatHistoryRepository.getLast(chatId, limit = 20)
            if (lastTurns.isNotEmpty()) {
                lastTurns.forEach { turn ->
                    memory.append(chatId, turn.role, turn.text)
                }
            }
        }

        val balance = ensureUserBalance(chatId)
        if (balance.textTokensLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("text.tokens.not.enough"), ttlSeconds = 15)
            return
        }

        memory.initIfNeeded(chatId)

        memory.append(chatId, "user", text)
        chatHistoryRepository.append(chatId, "user", text)

        val history = memory.history(chatId)

        val result = withTyping(session, chatId) { chatService.generateReply(history) }

        logTokens(
            tag = "CHAT",
            chatId = chatId,
            tokens = result.tokensUsed,
            extra = "model=$chatModel historyTurns=${history.size} userTextChars=${text.length} replyChars=${result.text.length}"
        )


        memory.append(chatId, "assistant", result.text)
        chatHistoryRepository.append(chatId, "assistant", result.text)

        sendText(chatId, result.text)

        if (result.tokensUsed > 0) {
            val before = balance.textTokensLeft
            balance.textTokensLeft -= result.tokensUsed
            if (balance.textTokensLeft < 0) balance.textTokensLeft = 0
            repository.put(balance)

            println("[TOKENS][BALANCE] chatId=$chatId before=$before used=${result.tokensUsed} after=${balance.textTokensLeft}")
            repository.logUsage(chatId, result.tokensUsed, mapOf("type" to "chat", "model" to chatModel))
        }


        if (balance.plan == null && balance.textTokensLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Image logic
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun handleImage(session: ChatSession, chatId: Long, textRaw: String) {
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)

        if (balance.plan == null && balance.imageCreditsLeft < 1) {
            sendEphemeral(session, chatId, Strings.get("image.daily.limit", cap), ttlSeconds = 20)
            return
        }
        if (balance.imageCreditsLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("image.no.credits"), ttlSeconds = 20)
            return
        }

        val originalPrompt = textRaw
            .removePrefix(imageTag)
            .removePrefix("/pic")
            .removePrefix("покажи мне")
            .trim()

        if (originalPrompt.isBlank()) {
            sendEphemeral(session, chatId, Strings.get("image.empty.prompt"), ttlSeconds = 10)
            return
        }

        val finalPrompt = withUploadPhoto(session, chatId) { buildImagePrompt(chatId, originalPrompt) }

        val style = defaultImageStyle
        val (service, modelName) = when (style) {
            ImageStyle.ANIME -> animeImageService to animeImageModelName
            ImageStyle.REALISTIC -> realisticImageService to realisticImageModelName
        }

        val bytes: ByteArray? = try {
            withUploadPhoto(session, chatId) {
                withContext(Dispatchers.IO) { service.generateImage(finalPrompt, defaultPersona) }
            }
        } catch (_: Exception) {
            null
        }

        if (bytes == null) {
            sendEphemeral(session, chatId, "❌ Не удалось сгенерировать изображение (ошибка API/токена/сети).", ttlSeconds = 20)
            return
        }

        sendPhoto(chatId, bytes, caption = null)

        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)

        repository.logUsage(chatId, 0, mapOf("type" to "image", "model" to modelName, "credits_used" to 1))

        if (balance.plan == null && (balance.textTokensLeft <= 0 || balance.imageCreditsLeft <= 0)) {
            sendEphemeral(session, chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    private fun hasCyrillic(text: String): Boolean = Regex("[а-яА-ЯёЁ]").containsMatchIn(text)

    private suspend fun buildImagePrompt(chatId: Long, originalPrompt: String): String {
        val history = listOf(
            "system" to imagePromptSystem,
            "user" to originalPrompt
        )
        val result = chatService.generateReply(history)
        var prompt = normalizePrompt(result.text)
        if (prompt.isBlank() || prompt == Strings.get("chat.connection.issue")) {
            prompt = normalizePrompt(originalPrompt)
        }


        if (hasCyrillic(prompt)) {
            val translated = translateRuToEn(prompt)
            if (!translated.isNullOrBlank()) {
                prompt = normalizePrompt(translated)
            }
        }

        return limitPromptLength(prompt, 1000)
    }

    private fun normalizePrompt(text: String): String {
        return text
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^\"|\"$"), "")
            .trim()
            .trimEnd(',')
    }

    private fun limitPromptLength(prompt: String, maxChars: Int): String {
        if (prompt.length <= maxChars) return prompt
        return prompt.take(maxChars).trimEnd().trimEnd(',', ' ')
    }

    private suspend fun translateRuToEn(text: String): String? = withContext(Dispatchers.IO) {
        try {
            translator?.translate(text, "ru", "en")
        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Payments / invoices (protected messages)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun createPlanInvoice(session: ChatSession, chatId: Long, planCode: String) {
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
        safeExecuteInvoice(session, chatId, invoice)
    }

    private suspend fun createPackInvoice(session: ChatSession, chatId: Long, packCode: String) {
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
        safeExecuteInvoice(session, chatId, invoice)
    }

    private suspend fun safeExecuteInvoice(session: ChatSession, chatId: Long, invoice: SendInvoice) {
        try {
            val message = executeSafe(invoice)
            // счета защищаем от удаления "системного"
            session.state.protectedMessageIds.add(message.messageId)
        } catch (ex: TelegramApiRequestException) {
            val details = Strings.get("invoice.error.details", ex.message, ex.apiResponse, ex.parameters)
            sendEphemeral(session, chatId, "❌ $details", ttlSeconds = 20)
        } catch (ex: Exception) {
            sendEphemeral(session, chatId, Strings.get("invoice.error.unexpected", ex.message ?: ex.toString()), ttlSeconds = 20)
        }
    }

    private suspend fun onSuccessfulPayment(session: ChatSession, message: Message) {
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
                    session,
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
            }

            payload.startsWith("pack:") -> {
                val code = payload.split(":").getOrNull(1)
                val pack = ImagePack.byCode(code) ?: return
                balance.imageCreditsLeft += pack.images
                repository.put(balance)
                repository.addPayment(chatId, payload, totalRub)
                sendEphemeral(
                    session,
                    chatId,
                    Strings.get("payment.pack.activated", pack.images, pack.title),
                    ttlSeconds = 15
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Balance / limits
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun ensureUserBalance(userId: Long): UserBalance {
        val balance = repository.get(userId)
        val now = System.currentTimeMillis()
        if (balance.planExpiresAt?.let { now > it } == true) {
            balance.plan = null
            balance.planExpiresAt = null
        }
        val today = LocalDate.now().toString()
        if (balance.dayStamp != today) {
            balance.dayStamp = today
            balance.dayImageUsed = 0
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Command deletion
    // ─────────────────────────────────────────────────────────────────────────────

    private fun isDeletableCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "/start" || t == "/buy" || t == "/balance" || t == "/reset" || t == "/pic"
    }

    private suspend fun deleteUserCommand(chatId: Long, messageId: Int, text: String) {
        if (!isDeletableCommand(text)) return
        try {
            executeSafe(DeleteMessage(chatId.toString(), messageId))
        } catch (_: Exception) {
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Provider data / helpers
    // ─────────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────────
    // Chat actions (typing/uploadphoto)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun <T> withChatAction(session: ChatSession, chatId: Long, action: ActionType, block: suspend () -> T): T {
        val job = session.scope.launch {
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

    private suspend fun <T> withTyping(session: ChatSession, chatId: Long, block: suspend () -> T): T =
        withChatAction(session, chatId, ActionType.TYPING, block)

    private suspend fun <T> withUploadPhoto(session: ChatSession, chatId: Long, block: suspend () -> T): T =
        withChatAction(session, chatId, ActionType.UPLOADPHOTO, block)

    // ─────────────────────────────────────────────────────────────────────────────
    // "Normal" messages (не системные)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun sendText(chatId: Long, text: String, html: Boolean = false) {
        val message = SendMessage(chatId.toString(), text).apply {
            if (html) parseMode = "HTML"
        }
        executeSafe(message)
    }

    private suspend fun sendPhoto(chatId: Long, bytes: ByteArray, caption: String?) {
        val photo = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(ByteArrayInputStream(bytes), "image.png")
            this.caption = caption ?: Strings.get("photo.default.caption")
        }
        executeSafe(photo)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Telegram executeSafe
    // ─────────────────────────────────────────────────────────────────────────────

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
