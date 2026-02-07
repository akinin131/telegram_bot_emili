package emily.bot

import emily.app.BotConfig
import emily.data.*
import emily.resources.Strings
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery


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

    private data class ChatSession(
        val scope: CoroutineScope,
        val inbox: Channel<Update>,
        val state: SessionState
    )
    private suspend fun executeSafe(method: AnswerCallbackQuery): Boolean =
        withContext(Dispatchers.IO) {
            try {
                execute(method)
            } catch (e: Exception) {
                // если у тебя есть логгер — лучше логировать
                false
            }
        }
    private sealed class PendingRetry {
        data class Chat(val userText: String) : PendingRetry()
        data class Image(val originalPrompt: String) : PendingRetry()
    }

    private data class SessionState(
        @Volatile var awaitingImagePrompt: Boolean = false,
        @Volatile var lastSystemMessageId: Int? = null,
        val protectedMessageIds: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
        val ephemeralJobs: MutableMap<Int, Job> = ConcurrentHashMap(),
        val pendingRetries: MutableMap<String, PendingRetry> = ConcurrentHashMap(),
        @Volatile var lastUserTextForChat: String? = null,
        @Volatile var lastUserPromptForImage: String? = null
    )

    private val sessions = ConcurrentHashMap<Long, ChatSession>()

    private fun sessionFor(chatId: Long): ChatSession {
        return sessions.computeIfAbsent(chatId) {
            val sessionScope = CoroutineScope(SupervisorJob(botScope.coroutineContext[Job]) + Dispatchers.Default)
            val channel = Channel<Update>(capacity = Channel.BUFFERED)
            val state = SessionState()

            val session = ChatSession(sessionScope, channel, state)

            sessionScope.launch {
                channel.consumeEach { update ->
                    try {
                        handleUpdateInternal(session, update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }

            session
        }
    }

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasPhoto()) {
            val fileId = update.message.photo.last().fileId
            println("FILE_ID = $fileId")
        }
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

    private val imageTag = "#pic"
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

    private fun isNetworkIssue(e: Throwable): Boolean {
        if (e is SocketTimeoutException) return true
        if (e is UnknownHostException) return true
        if (e is ConnectException) return true
        if (e is IOException) return true

        val msg = (e.message ?: "").lowercase()
        val markers = listOf(
            "timeout", "timed out", "connect", "connection", "reset", "refused",
            "network", "unreachable", "unknownhost", "eof", "broken pipe",
            "502", "503", "504", "bad gateway", "service unavailable", "gateway timeout"
        )
        return markers.any { it in msg }
    }

    private suspend fun <T> retryOnceAfterDelayIfNetwork(
        delayMs: Long = 1000,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: Throwable) {
            if (!isNetworkIssue(e)) return Result.failure(e)
            delay(delayMs)
            try {
                Result.success(block())
            } catch (e2: Throwable) {
                Result.failure(e2)
            }
        }
    }

    private fun retryKeyboard(actionId: String): InlineKeyboardMarkup {
        return InlineKeyboardMarkup().apply {
            keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = "🔁 Попробовать ещё"
                        callbackData = actionId
                    }
                )
            )
        }
    }

    private val NETWORK_FAIL_TEXT_CHAT =
        "😏 Ну за***сь… связь снова решила полежать.\n" +
                "Я честно пыталась, но интернет сегодня как бывшая — игнорит.\n\n" +
                "Жмякни ниже, и я попробую ещё раз."

    private val NETWORK_FAIL_TEXT_IMAGE =
        "😈 Картинка не родилась.\n" +
                "Сервер пыхтел, стонал — и сдох.\n\n" +
                "Нажми кнопку, и я попробую ещё раз."

    private fun putRetry(session: ChatSession, pending: PendingRetry): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        session.state.pendingRetries[token] = pending
        return "retry:$token"
    }

    private suspend fun sendRetryMessage(session: ChatSession, chatId: Long, text: String, token: String) {
        sendSystemText(
            session = session,
            chatId = chatId,
            text = text,
            html = false,
            replyMarkup = retryKeyboard(token)
        )
    }

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

        if (session.state.awaitingImagePrompt) {
            session.state.awaitingImagePrompt = false
            ensureUserBalance(chatId)
            memory.autoClean(chatId)

            session.state.lastUserPromptForImage = textRaw

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
                sendEphemeral(
                    session,
                    chatId,
                    "✅ Отлично! Введите описание изображения одним сообщением 🙂",
                    ttlSeconds = 35
                )
            }

            textRaw.equals(MenuBtn.RESET, true) -> {
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)
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
                sendWelcome(chatId)
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
                sendEphemeral(
                    session,
                    chatId,
                    "✅ Отлично! Введите описание изображения одним сообщением 🙂",
                    ttlSeconds = 35
                )
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.startsWith(imageTag, true) ||
                    textRaw.startsWith("покажи мне", true) ||
                    textRaw.startsWith("/pic ", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)

                val prompt = textRaw
                    .removePrefix(imageTag)
                    .removePrefix("/pic")
                    .removePrefix("покажи мне")
                    .trim()
                session.state.lastUserPromptForImage = prompt

                handleImage(session, chatId, textRaw)
            }

            else -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)

                session.state.lastUserTextForChat = textRaw

                handleChat(session, chatId, textRaw)
            }
        }
    }

    private suspend fun handleCallback(session: ChatSession, update: Update) {
        val chatId = update.callbackQuery.message.chatId
        val data = update.callbackQuery.data
        memory.autoClean(chatId)

        when {
            data == "START_DIALOG" -> {
                executeSafe(AnswerCallbackQuery(update.callbackQuery.id))
                val fakeUserMessage = "Привет, Эмили 💕"
                handleChat(session, chatId, fakeUserMessage)
                return
            }
            data.startsWith("retry:") -> {
                val token = data.removePrefix("retry:")
                val pending = session.state.pendingRetries.remove(token) ?: return

                when (pending) {
                    is PendingRetry.Chat -> {
                        session.state.lastUserTextForChat = pending.userText
                        handleChat(session, chatId, pending.userText)
                    }

                    is PendingRetry.Image -> {
                        session.state.lastUserPromptForImage = pending.originalPrompt
                        handleImage(session, chatId, "$imageTag ${pending.originalPrompt}")
                    }
                }
            }

            data.startsWith("buy:plan:") -> createPlanInvoice(session, chatId, data.removePrefix("buy:plan:"))
            data.startsWith("buy:pack:") -> createPackInvoice(session, chatId, data.removePrefix("buy:pack:"))
        }
    }

    private suspend fun sendSystemText(
        session: ChatSession,
        chatId: Long,
        text: String,
        html: Boolean = false,
        replyMarkup: Any? = null
    ): Message {
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
            if (session.state.lastSystemMessageId == lastId) session.state.lastSystemMessageId = null
        }
    }

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
            } catch (_: Exception) {
            } finally {
                session.state.ephemeralJobs.remove(sent.messageId)
            }
        }
        session.state.ephemeralJobs[sent.messageId] = job
    }

    fun sendWelcome(chatId: Long) {
        val startButton = InlineKeyboardButton().apply {
            text = "💬 Начать диалог"
            callbackData = "START_DIALOG"
        }

        val keyboard = InlineKeyboardMarkup().apply {
            keyboard = listOf(listOf(startButton))
        }

        val caption = Strings.get("welcome.text")

        // 1) пробуем через file_id
        val requestByFileId = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(
                "AgACAgIAAxkBAAFB6iBphlYViNPwpeloj47Y6obrhrbrrAACRBlrG8I2MEj60YRyUKXYyAEAAwIAA3kAAzgE"
            )
            this.caption = caption
            this.replyMarkup = keyboard
        }

        try {
            execute(requestByFileId)
            return
        } catch (e: Exception) {
            // Важно увидеть причину, иначе кажется "ничего не приходит"
            println("sendWelcome(file_id) error: ${e.message}")
        }

        // 2) fallback на URL (твой рабочий вариант)
        val requestByUrl = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile("https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32")
            this.caption = caption
            this.replyMarkup = keyboard
        }

        execute(requestByUrl)
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
        sendSystemText(session, chatId, text, html = true)
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

        sendSystemText(session, chatId, Strings.get("buy.menu.text"), html = false)
    }

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

        val genResult = retryOnceAfterDelayIfNetwork {
            withTyping(session, chatId) { chatService.generateReply(history) }
        }

        if (genResult.isFailure) {
            val token = putRetry(session, PendingRetry.Chat(userText = text))
            sendRetryMessage(session, chatId, NETWORK_FAIL_TEXT_CHAT, token)
            return
        }

        val result = genResult.getOrThrow()

        memory.append(chatId, "assistant", result.text)
        chatHistoryRepository.append(chatId, "assistant", result.text)

        sendText(chatId, result.text)

        if (result.tokensUsed > 0) {
            balance.textTokensLeft -= result.tokensUsed
            if (balance.textTokensLeft < 0) balance.textTokensLeft = 0
            repository.put(balance)

            repository.logUsage(chatId, result.tokensUsed, mapOf("type" to "chat", "model" to chatModel))
        }

        if (balance.plan == null && balance.textTokensLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

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

        session.state.lastUserPromptForImage = originalPrompt

        val promptBuildResult = retryOnceAfterDelayIfNetwork {
            withUploadPhoto(session, chatId) { buildImagePrompt(chatId, originalPrompt) }
        }

        if (promptBuildResult.isFailure) {
            val token = putRetry(session, PendingRetry.Image(originalPrompt = originalPrompt))
            sendRetryMessage(session, chatId, NETWORK_FAIL_TEXT_IMAGE, token)
            return
        }

        val finalPrompt = promptBuildResult.getOrThrow()

        val style = defaultImageStyle
        val (service, modelName) = when (style) {
            ImageStyle.ANIME -> animeImageService to animeImageModelName
            ImageStyle.REALISTIC -> realisticImageService to realisticImageModelName
        }

        val imageResult: Result<ByteArray?> = retryOnceAfterDelayIfNetwork {
            withUploadPhoto(session, chatId) {
                withContext(Dispatchers.IO) { service.generateImage(finalPrompt, defaultPersona) }
            }
        }

        if (imageResult.isFailure) {
            val token = putRetry(session, PendingRetry.Image(originalPrompt = originalPrompt))
            sendRetryMessage(session, chatId, NETWORK_FAIL_TEXT_IMAGE, token)
            return
        }

        val bytes: ByteArray? = imageResult.getOrThrow()

        if (bytes == null) {
            val token = putRetry(session, PendingRetry.Image(originalPrompt = originalPrompt))
            sendRetryMessage(
                session,
                chatId,
                "😈 Ну вооооот… картинка не вылезла.\n" +
                        "Похоже, сервер решил сделать вид, что он занят.\n\n" +
                        "Жми кнопку — попробуем ещё раз.",
                token
            )
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
            session.state.protectedMessageIds.add(message.messageId)
        } catch (ex: TelegramApiRequestException) {
            val details = Strings.get("invoice.error.details", ex.message, ex.apiResponse, ex.parameters)
            sendEphemeral(session, chatId, "❌ $details", ttlSeconds = 20)
        } catch (ex: Exception) {
            sendEphemeral(
                session,
                chatId,
                Strings.get("invoice.error.unexpected", ex.message ?: ex.toString()),
                ttlSeconds = 20
            )
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

    private suspend fun <T> withChatAction(
        session: ChatSession,
        chatId: Long,
        action: ActionType,
        block: suspend () -> T
    ): T {
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
