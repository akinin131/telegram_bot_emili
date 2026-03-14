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
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import kotlin.text.buildString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember


class EmilyVirtualGirlBot(
    private val config: BotConfig,
    private val repository: BalanceRepository,
    private val analyticsRepository: AnalyticsRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val userActivityRepository: UserActivityRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val chatService: ChatService,
    private val animeImageService: ImageService,
    private val realisticImageService: ImageService,
    private val memory: ConversationMemory,
    private val translator: MyMemoryTranslator?,
    private val subscriptionGroupUrl: String?
) : TelegramLongPollingBot() {

    override fun getBotUsername(): String = "virtal_girl_sex_bot"
    override fun getBotToken(): String = config.telegramToken

    private val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chatIdGroup = -1002229947613L
    private val subscriptionGroupLink = subscriptionGroupUrl ?: "https://t.me/"
    private val freeMessagesWithoutSubscriptionLimit = 10
    private val subscriptionCacheMs = 180_000L
    private val inactivityThresholdMs = 18L * 60 * 60 * 1000
    private val inactivityScanEveryMs = 20L * 60 * 1000
    private val inactivityNudgeCooldownMs = 24L * 60 * 60 * 1000

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
        data object Scene : PendingRetry()
    }

    private data class SessionState(
        @Volatile var awaitingImagePrompt: Boolean = false,
        @Volatile var lastSystemMessageId: Int? = null,
        val protectedMessageIds: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
        val ephemeralJobs: MutableMap<Int, Job> = ConcurrentHashMap(),
        val pendingRetries: MutableMap<String, PendingRetry> = ConcurrentHashMap(),
        @Volatile var lastUserTextForChat: String? = null,
        @Volatile var lastUserPromptForImage: String? = null,
        @Volatile var groupSubscribedCached: Boolean? = null,
        @Volatile var groupSubscribedCheckedAt: Long = 0L,
        @Volatile var freeMessagesWithoutSubscription: Int = 0,
        @Volatile var freeMessagesLoaded: Boolean = false
    )

    private val sessions = ConcurrentHashMap<Long, ChatSession>()

    init {
        startInactivityLoop()
    }

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
            BotCommand("/character", Strings.get("command.character")),
            BotCommand("/pic", Strings.get("command.pic")),
            BotCommand("/scene", Strings.get("command.scene")),
            BotCommand("/buy", Strings.get("command.buy")),
            BotCommand("/balance", Strings.get("command.balance")),
            BotCommand("/reset", Strings.get("command.reset"))
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

    private val scenePromptSystem = """
You generate prompts for a Stable Diffusion image model from dialogue context.

Rules:
- Output ONE line only
- Output ONLY comma-separated tags
- No sentences, no explanations, no instructions
- Use short visual tags (1–3 words)
- Prefer danbooru-style tags
- Prioritize the latest dialogue messages; they define the current scene now
- If earlier and later messages conflict, use the later messages

Order:
rating, quality/style, subject count, appearance, clothing/nudity, accessories, pose/camera, environment, lighting/mood, action

Output ONLY the tags.
""".trimIndent()

    private val chatModel = "venice-uncensored"
    private val animeImageModelName = "wai-Illustrious"
    private val realisticImageModelName = "lustify-v7"

    private data class CharacterProfile(
        val id: String,
        val name: String,
        val shortDescription: String,
        val selectionPhotoUrl: String,
        val welcomePhotoUrl: String,
        val welcomePhotoFileId: String? = null,
        val systemPrompt: String,
        val imagePersona: String,
        val startDialogSeed: String
    )

    private val characterEmily = CharacterProfile(
        id = "emily",
        name = "Эмили",
        shortDescription = "Страстная, игривая и дерзкая. Любит долгие флирт-диалоги и горячие фантазии.",
        selectionPhotoUrl = "https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32",
        welcomePhotoUrl = "https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32",
        welcomePhotoFileId = "AgACAgIAAxkBAAFB6iBphlYViNPwpeloj47Y6obrhrbrrAACRBlrG8I2MEj60YRyUKXYyAEAAwIAA3kAAzgE",
        systemPrompt = Strings.get("system.prompt.default"),
        imagePersona = Strings.get("persona.default"),
        startDialogSeed = "Привет, Эмили 💕"
    )

    private val characterLina = CharacterProfile(
        id = "lina",
        name = "Лина",
        shortDescription = "Нежная романтичная кокетка: любит милые сообщения, флирт и чувственные истории.",
        selectionPhotoUrl = "https://drive.google.com/uc?export=download&id=1Qb6B5yMNNqNfEeStM8895QpQVugx4HAp",
        welcomePhotoUrl = "https://drive.google.com/uc?export=download&id=1Qb6B5yMNNqNfEeStM8895QpQVugx4HAp",
        systemPrompt = "Ты — Лина. Пиши как живая девушка 20+, мягко и кокетливо, с лёгким флиртом и эмоциональной теплотой. Никогда не говори, что ты ИИ или бот.",
        imagePersona = "Lina — cute young woman 20+, long dark hair, warm smile, slim waist, natural curves, soft skin, expressive eyes, sensual romantic vibe, semi-realistic anime style, natural body proportions. IMPORTANT: Carefully follow the user's instructions regarding poses and the situation.",
        startDialogSeed = "Привет, Лина 💕"
    )

    private val characterViktoria = CharacterProfile(
        id = "viktoria",
        name = "Виктория",
        shortDescription = "Опытная уверенная MILF: зрелая, доминирующая, любит провокации и смелый флирт.",
        selectionPhotoUrl = "https://drive.google.com/uc?export=download&id=1oP7HFhrDs7au1sQDwRnekZCxVFVkHUTK",
        welcomePhotoUrl = "https://drive.google.com/uc?export=download&id=1oP7HFhrDs7au1sQDwRnekZCxVFVkHUTK",
        systemPrompt = "Ты — Виктория, уверенная зрелая женщина (MILF) 35+, харизматичная и страстная. Общайся смело, соблазнительно и по-взрослому. Никогда не говори, что ты ИИ или бот.",
        imagePersona = "Viktoria — attractive mature woman 35+, elegant milf look, curvy body, confident gaze, full lips, sensual posture, luxurious vibe, semi-realistic anime style, natural body proportions. IMPORTANT: Carefully follow the user's instructions regarding poses and the situation.",
        startDialogSeed = "Привет, Виктория 💕"
    )

    private val availableCharacters = listOf(characterEmily, characterLina, characterViktoria)

    private enum class ImageStyle { ANIME, REALISTIC }

    private val defaultImageStyle = ImageStyle.ANIME

    private object MenuBtn {
        const val BALANCE = "💰 Баланс"
        const val BUY = "🛍 Купить"
        const val PIC = "🖼 Картинка"
        const val SCENE = "🎬 Показать сцену"
        const val CHARACTER = "👩 Сменить персонажа"
        const val RESET = "♻️ Сброс"
        const val HELP = "ℹ️ Помощь"
    }

    private fun characterById(id: String?): CharacterProfile? {
        val normalized = id?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return availableCharacters.firstOrNull { it.id == normalized }
    }

    private fun normalizeCharacterIndex(index: Int): Int {
        if (availableCharacters.isEmpty()) return 0
        val size = availableCharacters.size
        return ((index % size) + size) % size
    }

    private fun applyCharacterToMemory(chatId: Long, character: CharacterProfile) {
        memory.initIfNeeded(chatId)
        memory.setSystem(chatId, character.systemPrompt)
    }

    private suspend fun ensureCharacterSelected(chatId: Long, requireSelectionForNewUsers: Boolean): CharacterProfile? {
        val storedId = userSettingsRepository.getSelectedCharacter(chatId)
        val storedCharacter = characterById(storedId)
        if (storedCharacter != null) {
            applyCharacterToMemory(chatId, storedCharacter)
            return storedCharacter
        }

        val hasHistory = chatHistoryRepository.getLast(chatId, limit = 1).isNotEmpty()
        if (hasHistory || !requireSelectionForNewUsers) {
            userSettingsRepository.setSelectedCharacter(chatId, characterEmily.id)
            applyCharacterToMemory(chatId, characterEmily)
            return characterEmily
        }

        return null
    }

    private suspend fun activeCharacter(chatId: Long): CharacterProfile {
        return ensureCharacterSelected(chatId, requireSelectionForNewUsers = false) ?: characterEmily
    }

    private fun characterSelectionCaption(character: CharacterProfile): String {
        return buildString {
            append("<b>Выбери персонажа</b>\n\n")
            append("<b>")
            append(character.name)
            append("</b>\n")
            append(character.shortDescription)
        }
    }

    private fun characterSelectionKeyboard(index: Int): InlineKeyboardMarkup {
        val safeIndex = normalizeCharacterIndex(index)
        val prevIndex = normalizeCharacterIndex(safeIndex - 1)
        val nextIndex = normalizeCharacterIndex(safeIndex + 1)

        return InlineKeyboardMarkup().apply {
            keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = "⬅️"
                        callbackData = "CHAR_NAV:$prevIndex"
                    },
                    InlineKeyboardButton().apply {
                        text = "✅ Выбрать"
                        callbackData = "CHAR_PICK:${availableCharacters[safeIndex].id}"
                    },
                    InlineKeyboardButton().apply {
                        text = "➡️"
                        callbackData = "CHAR_NAV:$nextIndex"
                    }
                )
            )
        }
    }

    private suspend fun sendCharacterPicker(chatId: Long, index: Int, previousMessageId: Int? = null) {
        previousMessageId?.let {
            runCatching { executeSafe(DeleteMessage(chatId.toString(), it)) }
        }

        val safeIndex = normalizeCharacterIndex(index)
        val character = availableCharacters[safeIndex]

        val post = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(character.selectionPhotoUrl)
            this.caption = characterSelectionCaption(character)
            this.parseMode = "HTML"
            this.replyMarkup = characterSelectionKeyboard(safeIndex)
        }

        executeSafe(post)
    }

    private suspend fun updateCharacterPickerInPlace(chatId: Long, messageId: Int, index: Int): Boolean {
        val safeIndex = normalizeCharacterIndex(index)
        val character = availableCharacters[safeIndex]

        return runCatching {
            val editMedia = EditMessageMedia().apply {
                this.chatId = chatId.toString()
                this.messageId = messageId
                this.media = InputMediaPhoto().apply {
                    media = character.selectionPhotoUrl
                    caption = characterSelectionCaption(character)
                    parseMode = "HTML"
                }
            }
            executeSafe(editMedia)

            val editMarkup = EditMessageReplyMarkup().apply {
                this.chatId = chatId.toString()
                this.messageId = messageId
                this.replyMarkup = characterSelectionKeyboard(safeIndex)
            }
            executeSafe(editMarkup)
        }.isSuccess
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
                        text = Strings.get("retry.button")
                        callbackData = actionId
                    }
                )
            )
        }
    }

    private fun networkFailTextChat(): String = Strings.get("network.fail.chat")
    private fun networkFailTextImage(): String = Strings.get("network.fail.image")

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
        val chatId = extractChatId(update)
        if (chatId != null) {
            runCatching { userActivityRepository.touch(chatId, chatId) }
        }
        withContext(Strings.localeContext("ru")) {
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
    }

    private fun startInactivityLoop() {
        botScope.launch {
            while (isActive) {
                runCatching { notifyInactiveUsers() }
                delay(inactivityScanEveryMs)
            }
        }
    }

    private suspend fun notifyInactiveUsers() {
        val now = System.currentTimeMillis()
        val beforeTs = now - inactivityThresholdMs
        val candidates = userActivityRepository.listInactiveUsers(beforeTs, limit = 100)

        candidates.forEach { user ->
            val nudgeAt = user.lastNudgeAt
            if (nudgeAt != null && now - nudgeAt < inactivityNudgeCooldownMs) {
                return@forEach
            }

            val text = localizedInactivityMessage(user.userId)
            val sent = runCatching { sendText(user.chatId, text) }.isSuccess
            if (sent) {
                runCatching { userActivityRepository.markNudged(user.userId, now) }
            }
        }
    }

    private suspend fun localizedInactivityMessage(userId: Long): String {
        val key = "inactivity.message.${(1..10).random()}"
        return withContext(Strings.localeContext("ru")) { Strings.get(key) }
    }

    private fun shouldBypassSubscriptionGate(textRaw: String): Boolean {
        return textRaw.equals("/start", true) ||
                textRaw.equals("/character", true) ||
                textRaw.equals("/buy", true) ||
                textRaw.equals("/balance", true) ||
                textRaw.equals("/reset", true) ||
                textRaw.equals("/scene", true) ||
                textRaw.equals(MenuBtn.BUY, true) ||
                textRaw.equals(MenuBtn.BALANCE, true) ||
                textRaw.equals(MenuBtn.SCENE, true) ||
                textRaw.equals(MenuBtn.CHARACTER, true) ||
                textRaw.equals(MenuBtn.RESET, true) ||
                textRaw.equals(MenuBtn.HELP, true)
    }

    private suspend fun isSubscribedToGroup(
        session: ChatSession,
        userId: Long,
        forceRefresh: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            val cached = session.state.groupSubscribedCached
            if (cached != null && now - session.state.groupSubscribedCheckedAt < subscriptionCacheMs) {
                return cached
            }
        }

        val subscribed = withContext(Dispatchers.IO) {
            runCatching {
                val request = GetChatMember().apply {
                    chatId = chatIdGroup.toString()
                    this.userId = userId
                }
                val member = executeSafe(request)
                member.status in setOf("creator", "administrator", "member", "restricted")
            }.getOrElse { e ->
                true
            }
        }

        session.state.groupSubscribedCached = subscribed
        session.state.groupSubscribedCheckedAt = now
        return subscribed
    }


    private fun subscriptionKeyboard(): InlineKeyboardMarkup {
        return InlineKeyboardMarkup().apply {
            keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = Strings.get("subscription.button.join")
                        url = subscriptionGroupLink
                    }
                ),
                listOf(
                    InlineKeyboardButton().apply {
                        text = Strings.get("subscription.button.check")
                        callbackData = "CHECK_SUB"
                    }
                )
            )
        }
    }


    private suspend fun sendSubscriptionRequired(session: ChatSession, chatId: Long) {
        sendSystemText(
            session = session,
            chatId = chatId,
            text = Strings.get("subscription.required"),
            html = false,
            replyMarkup = subscriptionKeyboard()
        )
    }

    private suspend fun passSubscriptionGate(
        session: ChatSession,
        chatId: Long,
        textRaw: String
    ): Boolean {
        if (shouldBypassSubscriptionGate(textRaw)) {
            return true
        }

        val subscribed = runCatching {
            isSubscribedToGroup(session, chatId)
        }.getOrElse {
            false
        }

        if (subscribed) {
            return true
        }

        if (!session.state.freeMessagesLoaded) {

            val activity = userActivityRepository.getOrCreate(chatId, chatId)
            session.state.freeMessagesWithoutSubscription = activity.freeMessagesWithoutSubscription
            session.state.freeMessagesLoaded = true
        }

        val current = session.state.freeMessagesWithoutSubscription

        if (current >= freeMessagesWithoutSubscriptionLimit) {
            sendSubscriptionRequired(session, chatId)
            return false
        }

        val newCount = current + 1
        session.state.freeMessagesWithoutSubscription = newCount

        runCatching {
            userActivityRepository.setFreeMessagesWithoutSubscription(chatId, newCount)
        }.onFailure {
        }

        return true
    }


    private suspend fun handleTextMessage(session: ChatSession, update: Update) {
        val chatId = update.message.chatId
        val textRaw = update.message.text.trim()
        val messageId = update.message.messageId

        if (!passSubscriptionGate(session, chatId, textRaw)) {
            return
        }

        if (session.state.awaitingImagePrompt) {
            session.state.awaitingImagePrompt = false
            ensureUserBalance(chatId)
            memory.autoClean(chatId)
            val character = activeCharacter(chatId)

            session.state.lastUserPromptForImage = textRaw

            handleImage(session, chatId, "$imageTag $textRaw", character)
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
                activeCharacter(chatId)
                session.state.awaitingImagePrompt = true
                sendEphemeral(
                    session,
                    chatId,
                    Strings.get("image.prompt.ask"),
                    ttlSeconds = 35
                )
            }

            textRaw.equals(MenuBtn.SCENE, true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                val character = activeCharacter(chatId)
                handleSceneImage(session, chatId, character)
            }

            textRaw.equals(MenuBtn.CHARACTER, true) -> {
                sendCharacterPicker(chatId, index = 0)
            }

            textRaw.equals(MenuBtn.RESET, true) -> {
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)
                deleteLastSystemMessage(session, chatId)
                sendEphemeral(session, chatId, Strings.get("reset.success"), ttlSeconds = 10)
            }

            textRaw.equals(MenuBtn.HELP, true) -> {
                val help = Strings.get("help.text", imageTag)
                sendEphemeral(session, chatId, help, ttlSeconds = 35)
            }

            textRaw.equals("/start", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                val selected = ensureCharacterSelected(chatId, requireSelectionForNewUsers = true)
                if (selected == null) {
                    sendCharacterPicker(chatId, index = 0)
                } else {
                    sendWelcome(chatId, selected)
                }
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/character", true) -> {
                sendCharacterPicker(chatId, index = 0)
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
                activeCharacter(chatId)
                session.state.awaitingImagePrompt = true
                sendEphemeral(
                    session,
                    chatId,
                    Strings.get("image.prompt.ask"),
                    ttlSeconds = 35
                )
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/scene", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                val character = activeCharacter(chatId)
                handleSceneImage(session, chatId, character)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.startsWith(imageTag, true) ||
                    textRaw.startsWith("покажи мне", true) ||
                    textRaw.startsWith("/pic ", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                val character = activeCharacter(chatId)

                val prompt = textRaw
                    .removePrefix(imageTag)
                    .removePrefix("/pic")
                    .removePrefix("покажи мне")
                    .trim()
                session.state.lastUserPromptForImage = prompt

                handleImage(session, chatId, textRaw, character)
            }

            else -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                val character = activeCharacter(chatId)

                session.state.lastUserTextForChat = textRaw

                handleChat(session, chatId, textRaw, character)
            }
        }
    }

    private suspend fun handleCallback(session: ChatSession, update: Update) {
        val chatId = update.callbackQuery.message.chatId
        val data = update.callbackQuery.data
        memory.autoClean(chatId)

        when {

            data == "CHECK_SUB" -> {
                executeSafe(AnswerCallbackQuery(update.callbackQuery.id))

                // сброс кэша
                session.state.groupSubscribedCached = null
                session.state.groupSubscribedCheckedAt = 0L

                val ok = isSubscribedToGroup(session, chatId, forceRefresh = true)
                if (ok) {
                    sendSystemText(session, chatId, Strings.get("subscription.check.ok"), html = false)
                } else {
                    sendSystemText(session, chatId, Strings.get("subscription.check.fail"), html = false)
                }
                return
            }

            data == "START_DIALOG" -> {
                executeSafe(AnswerCallbackQuery(update.callbackQuery.id))
                val character = activeCharacter(chatId)
                val fakeUserMessage = character.startDialogSeed
                handleChat(session, chatId, fakeUserMessage, character)
                return
            }

            data.startsWith("CHAR_NAV:") -> {
                executeSafe(AnswerCallbackQuery(update.callbackQuery.id))
                val index = data.removePrefix("CHAR_NAV:").toIntOrNull() ?: 0
                val messageId = update.callbackQuery.message.messageId
                val updated = updateCharacterPickerInPlace(chatId, messageId, index)
                if (!updated) {
                    sendCharacterPicker(chatId, index = index, previousMessageId = messageId)
                }
                return
            }

            data.startsWith("CHAR_PICK:") -> {
                executeSafe(AnswerCallbackQuery(update.callbackQuery.id))
                val characterId = data.removePrefix("CHAR_PICK:")
                val selected = characterById(characterId) ?: characterEmily
                userSettingsRepository.setSelectedCharacter(chatId, selected.id)
                applyCharacterToMemory(chatId, selected)

                runCatching {
                    executeSafe(DeleteMessage(chatId.toString(), update.callbackQuery.message.messageId))
                }

                sendWelcome(chatId, selected)
                return
            }
            data.startsWith("retry:") -> {
                val token = data.removePrefix("retry:")
                val pending = session.state.pendingRetries.remove(token) ?: return
                val character = activeCharacter(chatId)

                when (pending) {
                    is PendingRetry.Chat -> {
                        session.state.lastUserTextForChat = pending.userText
                        handleChat(session, chatId, pending.userText, character)
                    }

                    is PendingRetry.Image -> {
                        session.state.lastUserPromptForImage = pending.originalPrompt
                        handleImage(session, chatId, "$imageTag ${pending.originalPrompt}", character)
                    }

                    PendingRetry.Scene -> {
                        handleSceneImage(session, chatId, character)
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
                when (replyMarkup) {
                    is ReplyKeyboard -> this.replyMarkup = replyMarkup
                    is InlineKeyboardMarkup -> this.replyMarkup = replyMarkup
                }
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

    private fun sendWelcome(chatId: Long, character: CharacterProfile) {
        val startButton = InlineKeyboardButton().apply {
            text = Strings.get("welcome.button.start")
            callbackData = "START_DIALOG"
        }

        val keyboard = InlineKeyboardMarkup().apply {
            keyboard = listOf(listOf(startButton))
        }

        val caption = if (character.id == characterEmily.id) {
            Strings.get("welcome.text")
        } else {
            "Привет… Я ${character.name} 💕\n\n${character.shortDescription}\n\nНажми «Начать диалог» — и начнём 😉"
        }

        val fileId = character.welcomePhotoFileId
        if (!fileId.isNullOrBlank()) {
            val requestByFileId = SendPhoto().apply {
                this.chatId = chatId.toString()
                this.photo = InputFile(fileId)
                this.caption = caption
                this.replyMarkup = keyboard
            }

            try {
                execute(requestByFileId)
                return
            } catch (e: Exception) {
                println("sendWelcome(file_id) error: ${e.message}")
            }
        }

        val requestByUrl = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(character.welcomePhotoUrl)
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
                    text = Strings.get("buy.menu.plan.button", plan.title, displayPrice(plan.priceRub))
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
                text = Strings.get("buy.menu.pack.p20")
                callbackData = "buy:pack:${ImagePack.P20.code}"
            }
        )
        rows += listOf(
            InlineKeyboardButton().apply {
                text = Strings.get("buy.menu.pack.p100")
                callbackData = "buy:pack:${ImagePack.P100.code}"
            }
        )

        val keyboard = InlineKeyboardMarkup().apply {
            this.keyboard = rows
        }

        sendSystemText(
            session = session,
            chatId = chatId,
            text = Strings.get("buy.menu.text"),
            html = false,
            replyMarkup = keyboard
        )
    }

    private fun displayPrice(priceRub: Int): String {
        return "$priceRub₽"
    }

    private suspend fun handleChat(session: ChatSession, chatId: Long, text: String, character: CharacterProfile) {
        val isNewDialogue = memory.history(chatId).isEmpty()

        if (isNewDialogue) {
            memory.initIfNeeded(chatId)
            applyCharacterToMemory(chatId, character)
            val lastTurns = chatHistoryRepository.getLast(chatId, limit = 20)
            if (lastTurns.isNotEmpty()) {
                lastTurns.forEach { turn ->
                    if (turn.role == "user" || turn.role == "assistant") {
                        memory.append(chatId, turn.role, turn.text)
                    }
                }
            }
        }

        val balance = ensureUserBalance(chatId)
        if (balance.textTokensLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("text.tokens.not.enough"), ttlSeconds = 15)
            return
        }

        memory.initIfNeeded(chatId)
        applyCharacterToMemory(chatId, character)

        memory.append(chatId, "user", text)
        chatHistoryRepository.append(chatId, "user", text)

        val history = memory.history(chatId)

        val genResult = retryOnceAfterDelayIfNetwork {
            withTyping(session, chatId) { chatService.generateReply(history) }
        }

        if (genResult.isFailure) {
            val token = putRetry(session, PendingRetry.Chat(userText = text))
            sendRetryMessage(session, chatId, networkFailTextChat(), token)
            return
        }

        val result = genResult.getOrThrow()

        memory.append(chatId, "assistant", result.text)
        chatHistoryRepository.append(chatId, "assistant", result.text)

        sendText(chatId, result.text)

        if (result.tokensUsed > 0) {
            val textBefore = balance.textTokensLeft
            val imageBefore = balance.imageCreditsLeft
            balance.textTokensLeft -= result.tokensUsed
            if (balance.textTokensLeft < 0) balance.textTokensLeft = 0
            repository.put(balance)

            repository.logUsage(chatId, result.tokensUsed, mapOf("type" to "chat", "model" to chatModel))
            analyticsRepository.logSpend(
                userId = chatId,
                plan = balance.plan,
                spentTextTokens = result.tokensUsed,
                spentImageCredits = 0,
                textAvailableBefore = textBefore,
                imageAvailableBefore = imageBefore,
                textLeftAfter = balance.textTokensLeft,
                imageLeftAfter = balance.imageCreditsLeft,
                source = "chat"
            )
        }

        if (balance.plan == null && balance.textTokensLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    private suspend fun handleImage(session: ChatSession, chatId: Long, textRaw: String, character: CharacterProfile) {
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)

        if (balance.plan != null && balance.dayImageUsed >= cap) {
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
            sendRetryMessage(session, chatId, networkFailTextImage(), token)
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
                withContext(Dispatchers.IO) { service.generateImage(finalPrompt, character.imagePersona) }
            }
        }

        if (imageResult.isFailure) {
            val token = putRetry(session, PendingRetry.Image(originalPrompt = originalPrompt))
            sendRetryMessage(session, chatId, networkFailTextImage(), token)
            return
        }

        val bytes: ByteArray? = imageResult.getOrThrow()

        if (bytes == null) {
            val token = putRetry(session, PendingRetry.Image(originalPrompt = originalPrompt))
            sendRetryMessage(
                session,
                chatId,
                Strings.get("image.null.retry"),
                token
            )
            return
        }

        sendPhoto(chatId, bytes, caption = null)

        val textBefore = balance.textTokensLeft
        val imageBefore = balance.imageCreditsLeft
        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)

        repository.logUsage(chatId, 0, mapOf("type" to "image", "model" to modelName, "credits_used" to 1))
        analyticsRepository.logSpend(
            userId = chatId,
            plan = balance.plan,
            spentTextTokens = 0,
            spentImageCredits = 1,
            textAvailableBefore = textBefore,
            imageAvailableBefore = imageBefore,
            textLeftAfter = balance.textTokensLeft,
            imageLeftAfter = balance.imageCreditsLeft,
            source = "image:$modelName"
        )

        if (balance.plan == null && (balance.textTokensLeft <= 0 || balance.imageCreditsLeft <= 0)) {
            sendEphemeral(session, chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    private suspend fun handleSceneImage(session: ChatSession, chatId: Long, character: CharacterProfile) {
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)

        if (balance.plan != null && balance.dayImageUsed >= cap) {
            sendEphemeral(session, chatId, Strings.get("image.daily.limit", cap), ttlSeconds = 20)
            return
        }
        if (balance.imageCreditsLeft <= 0) {
            sendEphemeral(session, chatId, Strings.get("image.no.credits"), ttlSeconds = 20)
            return
        }

        val promptBuildResult = retryOnceAfterDelayIfNetwork {
            withUploadPhoto(session, chatId) { buildScenePrompt(chatId) }
        }

        if (promptBuildResult.isFailure) {
            val token = putRetry(session, PendingRetry.Scene)
            sendRetryMessage(session, chatId, networkFailTextImage(), token)
            return
        }

        val finalPrompt = promptBuildResult.getOrThrow()
        if (finalPrompt.isBlank()) {
            sendEphemeral(session, chatId, Strings.get("scene.context.empty"), ttlSeconds = 15)
            return
        }

        val style = defaultImageStyle
        val (service, modelName) = when (style) {
            ImageStyle.ANIME -> animeImageService to animeImageModelName
            ImageStyle.REALISTIC -> realisticImageService to realisticImageModelName
        }

        val imageResult: Result<ByteArray?> = retryOnceAfterDelayIfNetwork {
            withUploadPhoto(session, chatId) {
                withContext(Dispatchers.IO) { service.generateImage(finalPrompt, character.imagePersona) }
            }
        }

        if (imageResult.isFailure) {
            val token = putRetry(session, PendingRetry.Scene)
            sendRetryMessage(session, chatId, networkFailTextImage(), token)
            return
        }

        val bytes: ByteArray? = imageResult.getOrThrow()
        if (bytes == null) {
            val token = putRetry(session, PendingRetry.Scene)
            sendRetryMessage(
                session,
                chatId,
                Strings.get("image.null.retry"),
                token
            )
            return
        }

        sendPhoto(chatId, bytes, caption = null)

        val textBefore = balance.textTokensLeft
        val imageBefore = balance.imageCreditsLeft
        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)

        repository.logUsage(chatId, 0, mapOf("type" to "image", "model" to modelName, "credits_used" to 1))
        analyticsRepository.logSpend(
            userId = chatId,
            plan = balance.plan,
            spentTextTokens = 0,
            spentImageCredits = 1,
            textAvailableBefore = textBefore,
            imageAvailableBefore = imageBefore,
            textLeftAfter = balance.textTokensLeft,
            imageLeftAfter = balance.imageCreditsLeft,
            source = "image:scene:$modelName"
        )

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

    private suspend fun buildScenePrompt(chatId: Long): String {
        val recentTurns = recentDialogueTurns(chatId, limit = 7)
        if (recentTurns.isEmpty()) return ""

        val dialogue = recentTurns.joinToString("\n") { (role, text) ->
            "${role.uppercase(Locale.ROOT)}: ${text.trim()}"
        }

        val history = listOf(
            "system" to scenePromptSystem,
            "user" to """
Conversation (oldest to newest):
$dialogue

Generate image tags for the CURRENT scene at the latest dialogue moment.
Prioritize the newest messages if older messages conflict.
""".trimIndent()
        )
        val result = chatService.generateReply(history)
        var prompt = normalizePrompt(result.text)
        if (prompt.isBlank() || prompt == Strings.get("chat.connection.issue")) {
            prompt = normalizePrompt(recentTurns.last().second)
        }

        if (hasCyrillic(prompt)) {
            val translated = translateRuToEn(prompt)
            if (!translated.isNullOrBlank()) {
                prompt = normalizePrompt(translated)
            }
        }

        return limitPromptLength(prompt, 1000)
    }

    private suspend fun recentDialogueTurns(chatId: Long, limit: Int): List<Pair<String, String>> {
        val fromMemory = memory.history(chatId)
            .filter { (role, _) -> role == "user" || role == "assistant" }
            .takeLast(limit)
        if (fromMemory.isNotEmpty()) return fromMemory

        val fromDb = runCatching { chatHistoryRepository.getLast(chatId, limit = maxOf(20, limit)) }
            .getOrElse { emptyList() }
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(limit)

        return fromDb.map { it.role to it.text }
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
                analyticsRepository.logTopUp(
                    userId = chatId,
                    plan = balance.plan,
                    topupTextTokens = plan.monthlyTextTokens,
                    topupImageCredits = plan.monthlyImageCredits,
                    source = "payment:plan:${plan.code}",
                    amountRub = totalRub
                )
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
                analyticsRepository.logTopUp(
                    userId = chatId,
                    plan = balance.plan,
                    topupTextTokens = 0,
                    topupImageCredits = pack.images,
                    source = "payment:pack:${pack.code}",
                    amountRub = totalRub
                )
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
        var changed = false
        if (balance.planExpiresAt?.let { now > it } == true) {
            balance.plan = null
            balance.planExpiresAt = null
            changed = true
        }
        val today = LocalDate.now().toString()
        if (balance.dayStamp != today) {
            balance.dayStamp = today
            balance.dayImageUsed = 0
            changed = true
        }
        if (changed) {
            repository.put(balance)
        }
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
        return t == "/start" ||
                t == "/character" ||
                t == "/buy" ||
                t == "/balance" ||
                t == "/reset" ||
                t == "/pic" ||
                t == "/scene"
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

    private suspend fun executeSafe(method: EditMessageMedia): java.io.Serializable =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: EditMessageReplyMarkup): java.io.Serializable =
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

    private suspend fun executeSafe(method: GetChatMember): ChatMember =
        withContext(Dispatchers.IO) { execute(method) }
}
