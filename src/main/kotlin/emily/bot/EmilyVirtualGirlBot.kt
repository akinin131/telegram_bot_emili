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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    private val awaitingImagePrompt = ConcurrentHashMap<Long, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val systemMessages = ConcurrentHashMap<Long, ConcurrentLinkedQueue<Int>>()
    private val protectedMessages = ConcurrentHashMap<Long, MutableSet<Int>>()

    private val imageTag = "#pic"
    private val imagePromptPrefix =
        "rating:explicit, masterpiece, absurdres, highly detailed, very aesthetic, newest, recent,"
    private val imagePromptSuffix = "fully clothed"
    private val imagePromptSystem = """
        You are a prompt engineer for image generation.
        Convert the user's request into a single-line, comma-separated English prompt.
        Start the prompt with: $imagePromptPrefix
        Add clear subject, scene, lighting, mood, and action tags based on the request.
        End with: $imagePromptSuffix
        Output only the final prompt. No quotes, no explanations, no dialogue.
        The final prompt must be 300 characters or less.
    """.trimIndent()
    private val chatModel = "venice-uncensored"

    private val animeImageModelName = "wai-Illustrious"
    private val realisticImageModelName = "lustify-v7"

    private val defaultPersona = Strings.get("persona.default")
    override fun getBotUsername(): String = "virtal_girl_sex_bot"

    private enum class ImageStyle { ANIME, REALISTIC }

    private val defaultImageStyle = ImageStyle.ANIME

    private object MenuBtn {
        const val BALANCE = "💰 Баланс"
        const val BUY = "🛍 Купить"
        const val PIC = "🖼 Картинка"
        const val RESET = "♻️ Сброс"
        const val HELP = "ℹ️ Помощь"
    }

    override fun getBotToken(): String = config.telegramToken

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

    override fun onUpdateReceived(update: Update) {
        scope.launch {
            try {
                handleUpdate(update)
            } catch (_: Exception) {
            }
        }
    }

    override fun onClosing() {
        super.onClosing()
        scope.cancel()
    }

    private suspend fun handleUpdate(update: Update) {
        when {
            update.hasPreCheckoutQuery() -> {
                val answer = AnswerPreCheckoutQuery().apply {
                    preCheckoutQueryId = update.preCheckoutQuery.id
                    ok = true
                }
                executeSafe(answer)
            }

            update.hasMessage() && update.message.successfulPayment != null -> {
                onSuccessfulPayment(update.message)
            }

            update.hasMessage() && update.message.hasText() -> {
                handleTextMessage(update)
            }

            update.hasCallbackQuery() -> {
                handleCallback(update)
            }

            else -> {
            }
        }
    }

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
            oneTimeKeyboard = false
            selective = false
        }
    }

    private suspend fun handleTextMessage(update: Update) {
        val chatId = update.message.chatId
        val textRaw = update.message.text.trim()
        val messageId = update.message.messageId

        if (awaitingImagePrompt.remove(chatId) == true) {
            ensureUserBalance(chatId)
            memory.autoClean(chatId)
            handleImage(chatId, "$imageTag $textRaw")
            sendText(chatId, "✅ Отлично! Введите описание следующего изображения 🙂")
            return
        }

        when {
            textRaw.equals(MenuBtn.BUY, true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBuyMenu(chatId)
            }

            textRaw.equals(MenuBtn.BALANCE, true) -> {
                val balance = ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBalance(chatId, balance)
            }

            textRaw.equals(MenuBtn.PIC, true) -> {
                awaitingImagePrompt[chatId] = true
                sendText(chatId, "✅ Отлично! Введите описание изображения одним сообщением 🙂")
            }

            textRaw.equals(MenuBtn.RESET, true) -> {
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)
                deleteOldSystemMessages(chatId)
                sendEphemeral(chatId, Strings.get("reset.success"), ttlSeconds = 10)
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
                sendEphemeral(chatId, help, ttlSeconds = 35)
            }

            textRaw.equals("/start", true) -> {
                memory.initIfNeeded(chatId)
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendWelcome(chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/buy", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBuyMenu(chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/balance", true) -> {
                val balance = ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBalance(chatId, balance)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/reset", true) -> {
                memory.reset(chatId)
                chatHistoryRepository.clear(chatId)
                deleteOldSystemMessages(chatId)
                sendEphemeral(chatId, Strings.get("reset.success"), ttlSeconds = 10)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/pic", true) -> {
                awaitingImagePrompt[chatId] = true
                sendText(chatId, "✅ Отлично! Введите описание изображения одним сообщением 🙂")
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.startsWith(imageTag, true) ||
                    textRaw.startsWith("покажи мне", true) ||
                    textRaw.startsWith("/pic ", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleImage(chatId, textRaw)
                sendText(chatId, "✅ Отлично! Введите описание следующего изображения 🙂")
            }

            else -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleChat(chatId, textRaw)
            }
        }
    }

    private suspend fun handleCallback(update: Update) {
        val chatId = update.callbackQuery.message.chatId
        val data = update.callbackQuery.data
        memory.autoClean(chatId)
        deleteOldSystemMessages(chatId)

        when {
            data.startsWith("buy:plan:") -> createPlanInvoice(chatId, data.removePrefix("buy:plan:"))
            data.startsWith("buy:pack:") -> createPackInvoice(chatId, data.removePrefix("buy:pack:"))
        }
    }

    private suspend fun sendWelcome(chatId: Long) {
        val text = Strings.get("welcome.text")
        val message = SendMessage(chatId.toString(), text).apply {
            replyMarkup = mainMenuKeyboard()
        }
        val sent = executeSafe(message)
        rememberSystemMessage(chatId, sent.messageId)
    }

    private suspend fun sendBalance(chatId: Long, balance: UserBalance) {
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
        val message = SendMessage(chatId.toString(), text).apply {
            parseMode = "HTML"
            replyMarkup = mainMenuKeyboard()
        }
        rememberSystemMessage(chatId, executeSafe(message).messageId)
    }

    private suspend fun sendBuyMenu(chatId: Long) {
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

        val msg = SendMessage(chatId.toString(), Strings.get("buy.menu.text")).apply {
            replyMarkup = markup
        }
        rememberSystemMessage(chatId, executeSafe(msg).messageId)
    }

    private suspend fun createPlanInvoice(chatId: Long, planCode: String) {
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
            }
        }
    }

    private suspend fun handleChat(chatId: Long, text: String) {
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
            sendEphemeral(chatId, Strings.get("text.tokens.not.enough"), ttlSeconds = 15)
            return
        }

        memory.initIfNeeded(chatId)

        memory.append(chatId, "user", text)
        chatHistoryRepository.append(chatId, "user", text)

        val history = memory.history(chatId)
        val result = withTyping(chatId) { chatService.generateReply(history) }

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
            sendEphemeral(chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    private suspend fun handleImage(chatId: Long, textRaw: String) {
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)

        if (balance.plan == null && balance.imageCreditsLeft < 1) {
            sendEphemeral(chatId, Strings.get("image.daily.limit", cap), ttlSeconds = 20)
            return
        }
        if (balance.imageCreditsLeft <= 0) {
            sendEphemeral(chatId, Strings.get("image.no.credits"), ttlSeconds = 20)
            return
        }

        val originalPrompt = textRaw
            .removePrefix(imageTag)
            .removePrefix("/pic")
            .removePrefix("покажи мне")
            .trim()

        if (originalPrompt.isBlank()) {
            sendEphemeral(chatId, Strings.get("image.empty.prompt"), ttlSeconds = 10)
            return
        }

        val finalPrompt = withUploadPhoto(chatId) { buildImagePrompt(chatId, originalPrompt) }

        val style = defaultImageStyle
        val (service, modelName) = when (style) {
            ImageStyle.ANIME -> animeImageService to animeImageModelName
            ImageStyle.REALISTIC -> realisticImageService to realisticImageModelName
        }

        val bytes: ByteArray? = try {
            withUploadPhoto(chatId) {
                withContext(Dispatchers.IO) { service.generateImage(finalPrompt, defaultPersona) }
            }
        } catch (_: Exception) {
            null
        }

        if (bytes == null) {
            sendEphemeral(
                chatId,
                "❌ Не удалось сгенерировать изображение (ошибка API/токена/сети).",
                ttlSeconds = 20
            )
            return
        }

        sendPhoto(chatId, bytes, caption = null)

        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)

        repository.logUsage(chatId, 0, mapOf("type" to "image", "model" to modelName, "credits_used" to 1))

        if (balance.plan == null && (balance.textTokensLeft <= 0 || balance.imageCreditsLeft <= 0)) {
            sendEphemeral(chatId, Strings.get("free.limit.reached"), ttlSeconds = 15)
        }
    }

    private fun hasCyrillic(text: String): Boolean = Regex("[а-яА-ЯёЁ]").containsMatchIn(text)

    private suspend fun buildImagePrompt(chatId: Long, originalPrompt: String): String {
        println("🧩 Генерация промпта для изображения: chatId=$chatId")
        val history = listOf(
            "system" to imagePromptSystem,
            "user" to originalPrompt
        )
        val result = chatService.generateReply(history)
        var prompt = normalizePrompt(result.text)
        if (prompt.isBlank() || prompt == Strings.get("chat.connection.issue")) {
            println("⚠️ Не удалось получить промпт от модели, использую оригинал")
            prompt = normalizePrompt(originalPrompt)
        }

        if (!prompt.lowercase().startsWith("rating:explicit")) {
            prompt = "$imagePromptPrefix ${prompt}".trim()
        }
        if (!prompt.lowercase().contains(imagePromptSuffix)) {
            prompt = "$prompt, $imagePromptSuffix"
        }

        if (hasCyrillic(prompt)) {
            println("🔤 Перевод промпта на английский: chatId=$chatId")
            val translated = translateRuToEn(prompt)
            if (!translated.isNullOrBlank()) {
                println("✅ Переведено: '${preview(translated, 30)}'")
                prompt = normalizePrompt(translated)
            } else {
                println("❌ Перевод не удался, оставляю как есть")
            }
        }

        prompt = limitPromptLength(prompt, 300)
        println("🧾 Финальный промпт: '${preview(prompt, 80)}' (len=${prompt.length})")
        return prompt
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

    private suspend fun deleteOldSystemMessages(chatId: Long) {
        val queue = systemMessages[chatId] ?: return
        while (true) {
            val id = queue.poll() ?: break
            if (protectedMessages[chatId]?.contains(id) == true) continue
            try {
                executeSafe(DeleteMessage(chatId.toString(), id))
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendText(chatId: Long, text: String, html: Boolean = false) {
        val message = SendMessage(chatId.toString(), text).apply {
            if (html) parseMode = "HTML"
            replyMarkup = mainMenuKeyboard()
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

    private fun rememberSystemMessage(chatId: Long, messageId: Int) {
        val queue = systemMessages.computeIfAbsent(chatId) { ConcurrentLinkedQueue() }
        queue.add(messageId)
    }

    private fun markProtected(chatId: Long, messageId: Int) {
        val set = protectedMessages.computeIfAbsent(chatId) { ConcurrentHashMap.newKeySet() }
        set.add(messageId)
    }

    private suspend fun safeExecuteInvoice(chatId: Long, invoice: SendInvoice) {
        try {
            val message = executeSafe(invoice)
            markProtected(chatId, message.messageId)
        } catch (ex: TelegramApiRequestException) {
            val details = Strings.get("invoice.error.details", ex.message, ex.apiResponse, ex.parameters)
            sendEphemeral(chatId, "❌ $details", ttlSeconds = 20)
        } catch (ex: Exception) {
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

    private suspend fun sendEphemeral(chatId: Long, text: String, ttlSeconds: Long, html: Boolean = false) {
        val message = SendMessage(chatId.toString(), text).apply {
            if (html) parseMode = "HTML"
            replyMarkup = mainMenuKeyboard()
        }
        val sent = executeSafe(message)
        scope.launch {
            delay(ttlSeconds * 1000)
            try {
                executeSafe(DeleteMessage(chatId.toString(), sent.messageId))
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun <T> withChatAction(chatId: Long, action: ActionType, block: suspend () -> T): T {
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