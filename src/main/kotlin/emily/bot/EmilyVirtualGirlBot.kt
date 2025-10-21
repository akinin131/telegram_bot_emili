package emily.bot

import com.deepl.api.Translator
import emily.app.BotConfig
import emily.data.BalanceRepository
import emily.data.DAILY_IMAGE_CAP_BASIC
import emily.data.DAILY_IMAGE_CAP_PRO
import emily.data.DAILY_IMAGE_CAP_ULTRA
import emily.data.ImagePack
import emily.data.Plan
import emily.data.UserBalance
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.api.objects.InputFile

class EmilyVirtualGirlBot(
    private val config: BotConfig,
    private val repository: BalanceRepository,
    private val chatService: ChatService,
    private val imageService: ImageService,
    private val memory: ConversationMemory,
    private val translator: Translator?
) : TelegramLongPollingBot() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val systemMessages = ConcurrentHashMap<Long, MutableList<Int>>()
    private val protectedMessages = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val imageTag = "#pic"
    private val chatModel = "venice-uncensored"
    private val imageModel = "wai-Illustrious"
    private val persona = """ Emily — petite yet curvy, with soft skin; short, straight silver 
        hair; green eyes; large, full, natural breasts (large, prominent, realistic, proportional); 
        enjoys being nude; age 20+; semi-realistic anime style with natural body proportions. IMPORTANT: 
        Carefully follow the user’s instructions regarding poses and the situation — make sure the pose, 
        hand placement, gaze direction, and overall composition strictly match the given description. """.trimIndent()

    override fun getBotUsername(): String = "EmilyVirtualGirlBot"
    override fun getBotToken(): String = config.telegramToken

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

    fun registerBotMenu() = runBlocking {
        val commands = listOf(
            BotCommand("/start", "Начать общение с Эмили"),
            BotCommand("/buy", "Купить подписку или пакет"),
            BotCommand("/balance", "Посмотреть баланс"),
            BotCommand("/reset", "Очистить память диалога"),
            BotCommand("/pic", "Сгенерировать изображение")
        )
        executeSafe(SetMyCommands(commands, BotCommandScopeDefault(), null))
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
            update.hasMessage() && update.message.successfulPayment != null ->
                onSuccessfulPayment(update.message)
            update.hasMessage() && update.message.hasText() ->
                handleTextMessage(update)
            update.hasCallbackQuery() ->
                handleCallback(update)
        }
    }

    private suspend fun handleTextMessage(update: Update) {
        val chatId = update.message.chatId
        val textRaw = update.message.text.trim()
        val messageId = update.message.messageId

        when {
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
                deleteOldSystemMessages(chatId)
                sendEphemeral(chatId, "Память диалога очищена 🙈", ttlSeconds = 10)
                deleteUserCommand(chatId, messageId, textRaw)
            }
            textRaw.equals("/pic", true) -> {
                sendEphemeral(chatId, "Формат: отправь сообщение вида:\n#pic описание сцены", ttlSeconds = 20)
                deleteUserCommand(chatId, messageId, textRaw)
            }
            textRaw.startsWith(imageTag, true) || textRaw.startsWith("/pic ", true) -> {
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleImage(chatId, textRaw)
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
        val text = """
Привет! Я Эмили 💕
Я умею разговаривать и создавать изображения.
Команды:
  /buy — оплатить подписку/пакет (с фото и чеком)
  /balance — показать текущий баланс
  /reset — очистить память диалога
  /pic — как генерировать картинку
Бесплатно: ~30 коротких сообщений и 1 изображение.
""".trimIndent()
        val message = executeSafe(SendMessage(chatId.toString(), text))
        rememberSystemMessage(chatId, message.messageId)
    }

    private suspend fun sendBalance(chatId: Long, balance: UserBalance) {
        val planTitle = when (balance.plan) {
            Plan.BASIC.code -> Plan.BASIC.title
            Plan.PRO.code -> Plan.PRO.title
            Plan.ULTRA.code -> Plan.ULTRA.title
            else -> "нет (Free)"
        }
        val until = balance.planExpiresAt?.let { Instant.ofEpochMilli(it).toString() } ?: "—"
        val text = """
<b>План:</b> $planTitle
<b>Действует до:</b> $until
<b>Текущие текстовые токены:</b> ${balance.textTokensLeft}
<b>Кредиты на изображения:</b> ${balance.imageCreditsLeft}
<b>Сегодня использовано изображений:</b> ${balance.dayImageUsed}
""".trimIndent()
        val message = SendMessage(chatId.toString(), text).apply { parseMode = "HTML" }
        rememberSystemMessage(chatId, executeSafe(message).messageId)
    }

    private suspend fun sendBuyMenu(chatId: Long) {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        Plan.values().forEach { plan ->
            rows += listOf(
                InlineKeyboardButton().apply {
                    text = "${plan.title} (${plan.priceRub}₽/мес)"
                    callbackData = "buy:plan:${plan.code}"
                }
            )
        }
        rows += listOf(
            InlineKeyboardButton().apply {
                text = "Фото для возбуждения (10)"
                callbackData = "buy:pack:${ImagePack.P10.code}"
            }
        )
        rows += listOf(
            InlineKeyboardButton().apply {
                text = "Порочный альбом (50)"
                callbackData = "buy:pack:${ImagePack.P50.code}"
            }
        )
        val markup = InlineKeyboardMarkup().apply { keyboard = rows }
        val msg = SendMessage(chatId.toString(), "Выбери пакет. После оплаты баланс пополнится автоматически.\n\nПодписка идёт без автопродления").apply {
            replyMarkup = markup
        }
        rememberSystemMessage(chatId, executeSafe(msg).messageId)
    }

    private suspend fun createPlanInvoice(chatId: Long, planCode: String) {
        val plan = Plan.byCode(planCode) ?: return
        val invoicePayload = "plan:${plan.code}:${UUID.randomUUID()}"
        val providerDataJson = makeProviderData(
            desc = "Пакет ${plan.title} — 30 дней. Текстовые токены + кредиты изображений.",
            rub = plan.priceRub,
            includeVat = true
        )
        val invoice = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = "Пакет: ${plan.title}"
            description = "30 дней: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений."
            payload = invoicePayload
            providerToken = config.providerToken
            currency = "RUB"
            startParameter = "plan-${plan.code}"
            prices = listOf(LabeledPrice("${plan.title} 30 дней", plan.priceRub * 100))
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
            desc = "${pack.title}. Дополнительные единицы генерации изображений.",
            rub = pack.priceRub,
            includeVat = true
        )
        val invoice = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = pack.title
            description = "Разовый пакет: ${pack.title}"
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
                sendEphemeral(chatId, "✅ Подписка «${plan.title}» активирована до ${Instant.ofEpochMilli(balance.planExpiresAt!!)}.\n" +
                    "Начислено: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений.", ttlSeconds = 20)
            }
            payload.startsWith("pack:") -> {
                val code = payload.split(":").getOrNull(1)
                val pack = ImagePack.byCode(code) ?: return
                balance.imageCreditsLeft += pack.images
                repository.put(balance)
                repository.addPayment(chatId, payload, totalRub)
                sendEphemeral(chatId, "✅ Начислено: ${pack.images} изображений по пакету «${pack.title}».", ttlSeconds = 15)
            }
        }
    }

    private suspend fun handleChat(chatId: Long, text: String) {
        val balance = ensureUserBalance(chatId)
        if (balance.textTokensLeft <= 0) {
            sendEphemeral(chatId, "⚠️ У тебя закончились текстовые токены.\nКупи подписку в /buy", ttlSeconds = 15)
            return
        }
        memory.initIfNeeded(chatId)
        memory.append(chatId, "user", text)
        val history = memory.history(chatId)
        val result = withTyping(chatId) { chatService.generateReply(history) }
        memory.append(chatId, "assistant", result.text)
        sendText(chatId, result.text)
        if (result.tokensUsed > 0) {
            balance.textTokensLeft -= result.tokensUsed
            if (balance.textTokensLeft < 0) balance.textTokensLeft = 0
            repository.put(balance)
            repository.logUsage(chatId, result.tokensUsed, mapOf("type" to "chat", "model" to chatModel))
        }
        if (balance.plan == null && balance.textTokensLeft <= 0) {
            sendEphemeral(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy", ttlSeconds = 15)
        }
    }

    private suspend fun handleImage(chatId: Long, textRaw: String) {
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)
        if (balance.plan == null && balance.imageCreditsLeft < 1) {
            sendEphemeral(chatId, "Дневной лимит изображений исчерпан ($cap). Попробуй завтра или купи пакет /buy.", ttlSeconds = 20)
            return
        }
        if (balance.imageCreditsLeft <= 0) {
            sendEphemeral(chatId, "У тебя нет кредитов на изображения. Купи подписку или пакет: /buy", ttlSeconds = 20)
            return
        }
        val originalPrompt = textRaw.removePrefix(imageTag).removePrefix("/pic").trim()
        if (originalPrompt.isBlank()) {
            sendEphemeral(chatId, "После #pic укажи описание 🙂", ttlSeconds = 10)
            return
        }
        if (!isPromptAllowed(originalPrompt)) {
            sendEphemeral(chatId, "❌ Нельзя темы про несовершеннолетних/насилие/принуждение.", ttlSeconds = 15)
            return
        }
        val containsCyrillic = originalPrompt.any { it.code in 0x0400..0x04FF }
        val finalPrompt = if (containsCyrillic) {
            withUploadPhoto(chatId) { translateRuToEn(originalPrompt) ?: originalPrompt }
        } else {
            originalPrompt
        }
        val bytes = withUploadPhoto(chatId) { imageService.generateImage(finalPrompt, persona) }
        if (bytes == null) {
            sendEphemeral(chatId, "Не удалось сгенерировать изображение. Попробуй ещё раз.", ttlSeconds = 12)
            return
        }
        sendPhoto(chatId, bytes, caption = null)
        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)
        repository.logUsage(chatId, 0, mapOf("type" to "image", "model" to imageModel, "credits_used" to 1))
        if (balance.plan == null && (balance.textTokensLeft <= 0 || balance.imageCreditsLeft <= 0)) {
            sendEphemeral(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy", ttlSeconds = 15)
        }
    }

    private suspend fun deleteOldSystemMessages(chatId: Long) {
        val ids = systemMessages[chatId] ?: return
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
        val message = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
        executeSafe(message)
    }

    private suspend fun sendPhoto(chatId: Long, bytes: ByteArray, caption: String?) {
        val photo = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(ByteArrayInputStream(bytes), "image.png")
            this.caption = caption ?: "Готово 💕"
        }
        executeSafe(photo)
    }

    private fun rememberSystemMessage(chatId: Long, messageId: Int) {
        val list = systemMessages.computeIfAbsent(chatId) { mutableListOf() }
        list += messageId
    }

    private fun markProtected(chatId: Long, messageId: Int) {
        val set = protectedMessages.computeIfAbsent(chatId) { mutableSetOf() }
        set += messageId
    }

    private suspend fun safeExecuteInvoice(chatId: Long, invoice: SendInvoice) {
        try {
            val message = executeSafe(invoice)
            markProtected(chatId, message.messageId)
        } catch (ex: TelegramApiRequestException) {
            val details = buildString {
                appendLine("Invoice error:")
                appendLine("message=${ex.message}")
                appendLine("apiResponse=${ex.apiResponse}")
                appendLine("parameters=${ex.parameters}")
            }
            sendEphemeral(chatId, "❌ $details", ttlSeconds = 20)
        } catch (ex: Exception) {
            sendEphemeral(chatId, "❌ Unexpected invoice error: ${ex.message ?: ex.toString()}", ttlSeconds = 20)
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

    private fun isPromptAllowed(text: String): Boolean {
        val lower = text.lowercase()
        val bad = listOf(
            "несовершеннолет", "школьник", "школьница", "подрост", "minor", "teen", "loli", "shota",
            "изнасил", "насилие", "принужд", "без согласи", "rape", "forced"
        )
        return bad.none { lower.contains(it) }
    }

    private suspend fun translateRuToEn(text: String): String? = withContext(Dispatchers.IO) {
        runCatching { translator?.translateText(text, "ru", "en-US")?.text }.getOrNull()
    }

    private fun isDeletableCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "/start" || t == "/buy" || t == "/balance" || t == "/reset" || t == "/pic"
    }

    private suspend fun deleteUserCommand(chatId: Long, messageId: Int, text: String) {
        if (isDeletableCommand(text)) {
            try {
                executeSafe(DeleteMessage(chatId.toString(), messageId))
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendEphemeral(chatId: Long, text: String, ttlSeconds: Long, html: Boolean = false) {
        val message = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
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
