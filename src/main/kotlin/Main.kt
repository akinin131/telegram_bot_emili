@file:Suppress("UNUSED_VARIABLE")

import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import com.deepl.api.Translator

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.*
import org.telegram.telegrambots.meta.api.methods.ActionType

import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import java.io.FileInputStream
import java.util.concurrent.*
import kotlin.system.exitProcess

fun main() {
    SingleInstance.acquire(44569)
    initFirebase()
    val api = TelegramBotsApi(DefaultBotSession::class.java)
    val bot = EmilyVirtualGirlBot()
    api.registerBot(bot)

    bot.registerBotMenu()
}


/** single instance */
object SingleInstance {
    private var lock: ServerSocket? = null
    fun acquire(port: Int) {
        try {
            lock = ServerSocket(port)
        } catch (_: Exception) {
            exitProcess(1)
        }
    }
}

/** Firebase Admin init */
fun initFirebase() {
    val credsPath = "emilyvirtualgirlbot-firebase-adminsdk-fbsvc-2b1c251dfd.json"
    val dbUrl = "https://emilyvirtualgirlbot-default-rtdb.firebaseio.com"
    FileInputStream(credsPath).use { serviceAccount ->
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .setDatabaseUrl(dbUrl)
            .build()
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        }
    }
}

/** тарифы и пакеты */
enum class Plan(
    val code: String,
    val title: String,
    val priceRub: Int,
    val monthlyTextTokens: Int,
    val monthlyImageCredits: Int,
    val photoUrl: String
) {
    BASIC(
        "basic",
        "Скромница",
        399,
        100_000,
        15,
        "https://drive.google.com/uc?export=download&id=1TCRXGBCDeju4zjER_lUvsn5yZPcv-V7s"
    ),
    PRO(
        "pro",
        "Шлюшка",
        650,
        300_000,
        50,
        "https://drive.google.com/uc?export=download&id=1a3kI5IXbX95QMSpRb72vj0RRIKaXs9T6"
    ),
    ULTRA(
        "ultra",
        "Грязная развратница",
        1800,
        800_000,
        150,
        "https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32"
    );
}

enum class ImagePack(
    val code: String,
    val title: String,
    val priceRub: Int,
    val images: Int,
    val photoUrl: String
) {
    P10(
        "pack10",
        "Фото для возбуждения",
        99,
        10,
        "https://drive.google.com/uc?export=download&id=1pojAKJs7hChiLZhF_27HEKCv6vktDfac"
    ),
    P50(
        "pack50",
        "Порочный альбом",
        249,
        50,
        "https://drive.google.com/uc?export=download&id=1f67uMVIMFWCe4DvQU4GlgnI5vx0cH6iC"
    )
}


const val FREE_TEXT_TOKENS = 12_000
const val FREE_IMAGE_CREDITS = 1

const val DAILY_IMAGE_CAP_BASIC = 10
const val DAILY_IMAGE_CAP_PRO = 25
const val DAILY_IMAGE_CAP_ULTRA = 60

data class UserBalance(
    val userId: Long = 0L,
    var plan: String? = null,
    var planExpiresAt: Long? = null,
    var textTokensLeft: Int = FREE_TEXT_TOKENS,
    var imageCreditsLeft: Int = FREE_IMAGE_CREDITS,
    var dayImageUsed: Int = 0,
    var dayStamp: String = LocalDate.now().toString(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

private fun blockingGet(ref: DatabaseReference, timeoutMs: Long = 10_000): DataSnapshot {
    val latch = CountDownLatch(1)
    var result: DataSnapshot? = null
    var error: Exception? = null
    ref.addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            result = snapshot; latch.countDown()
        }

        override fun onCancelled(dbError: DatabaseError) {
            error = RuntimeException(dbError.toException()); latch.countDown()
        }
    })
    if (!latch.await(
            timeoutMs,
            TimeUnit.MILLISECONDS
        )
    ) throw TimeoutException("Firebase get() timeout after ${timeoutMs}ms for path: ${ref.path}")
    error?.let { throw it }
    return result ?: throw IllegalStateException("Snapshot is null for path: ${ref.path}")
}

/** Firebase Realtime DB repository */
class BalanceRepo {
    private val db by lazy { FirebaseDatabase.getInstance() }
    private val balancesRef by lazy { db.getReference("balances") }
    private val paymentsRef by lazy { db.getReference("payments") }

    fun get(userId: Long): UserBalance {
        val ref: DatabaseReference = balancesRef.child(userId.toString())
        val snap: DataSnapshot = blockingGet(ref)
        return if (snap.exists()) {
            UserBalance(
                userId = userId,
                plan = snap.child("plan").getValue(String::class.java),
                planExpiresAt = snap.child("planExpiresAt").getValue(Long::class.java),
                textTokensLeft = snap.child("textTokensLeft").getValue(Long::class.java)?.toInt() ?: FREE_TEXT_TOKENS,
                imageCreditsLeft = snap.child("imageCreditsLeft").getValue(Long::class.java)?.toInt()
                    ?: FREE_IMAGE_CREDITS,
                dayImageUsed = snap.child("dayImageUsed").getValue(Long::class.java)?.toInt() ?: 0,
                dayStamp = snap.child("dayStamp").getValue(String::class.java) ?: LocalDate.now().toString(),
                createdAt = snap.child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
                updatedAt = snap.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
            )
        } else {
            val def = UserBalance(userId = userId)
            put(def)
            def
        }
    }

    fun put(b: UserBalance) {
        b.updatedAt = System.currentTimeMillis()
        val m = mapOf(
            "userId" to b.userId,
            "plan" to b.plan,
            "planExpiresAt" to b.planExpiresAt,
            "textTokensLeft" to b.textTokensLeft,
            "imageCreditsLeft" to b.imageCreditsLeft,
            "dayImageUsed" to b.dayImageUsed,
            "dayStamp" to b.dayStamp,
            "createdAt" to b.createdAt,
            "updatedAt" to b.updatedAt
        )
        balancesRef.child(b.userId.toString()).setValueAsync(m)
    }

    fun addPayment(userId: Long, payload: String, amountRub: Int) {
        val id = UUID.randomUUID().toString()
        val m = mapOf("payload" to payload, "amountRub" to amountRub, "ts" to System.currentTimeMillis())
        paymentsRef.child(userId.toString()).child(id).setValueAsync(m)

    }
}

/** бот */
class EmilyVirtualGirlBot : TelegramLongPollingBot() {

    private val telegramToken: String = "8341155085:AAGl_Ba7IGAjC1OIEPfJIW5Mo_cOayofySU"
    val providerToken1: String = "390540012:LIVE:78849"
    private val veniceToken: String = "8NgXj7n0BrXVvm8dyIgCFmAxAioOhpLIGNKI3KKzAJ"
    private val deeplKey: String = "2a72f4e3-6b4d-4d44-9dab-1f337803eb34:fx"

    override fun getBotUsername(): String = "EmilyVirtualGirlBot"
    override fun getBotToken(): String = telegramToken

    /** модели */
    private val chatModel = "venice-uncensored"
    private val imageModel = "wai-Illustrious"
    private val IMAGE_TAG = "#pic"

    /** HTTP + логи */
    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor {  }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** DeepL */
    private val deepl: Translator? = try {
        if (deeplKey.isNotBlank()) {
            val kind = if (deeplKey.endsWith(":fx")) "FREE" else "PRO"
            Translator(deeplKey)
        } else {
            null
        }
    } catch (e: Exception) {

        null
    }

    private fun translateRuToEn(text: String): String? {
        val tr = deepl ?: return null
        return try {
            val res = tr.translateText(text, "ru", "en-US")
            res.text
        } catch (e: Exception) {
            null
        }
    }

    private val userContext = ConcurrentHashMap<Long, MutableList<Pair<String, String>>>()
    private val repo = BalanceRepo()

    /** handler */
    override fun onUpdateReceived(update: Update) {
        try {
            if (update.hasPreCheckoutQuery()) {
                val ans = AnswerPreCheckoutQuery().apply {
                    preCheckoutQueryId = update.preCheckoutQuery.id
                    ok = true
                }
                execute(ans)
                return
            }

            if (update.hasMessage() && update.message.successfulPayment != null) {
                onSuccessfulPayment(update.message)
                return
            }

            if (update.hasMessage() && update.message.hasText()) {
                val chatId = update.message.chatId
                val textRaw = update.message.text.trim()

                when {
                    textRaw.equals("/start", true) -> {
                        initContextIfNeeded(chatId)
                        ensureUserBalance(chatId)
                        sendWelcome(chatId)
                    }

                    textRaw.equals("/plans", true) -> {
                        ensureUserBalance(chatId); sendPlans(chatId)
                    }

                    textRaw.equals("/buy", true) -> {
                        ensureUserBalance(chatId); sendBuyMenu(chatId)
                    }

                    textRaw.equals("/balance", true) -> {
                        val b = ensureUserBalance(chatId); sendBalance(chatId, b)
                    }

                    textRaw.equals("/reset", true) -> {
                        userContext.remove(chatId); send(chatId, "Память диалога очищена 🙈")
                    }

                    textRaw.equals("/pic", true) -> {
                        send(chatId, "Формат: отправь сообщение вида:\n#pic описание сцены")
                    }

                    textRaw.startsWith(IMAGE_TAG, true) || textRaw.startsWith("/pic ", true) -> {
                        ensureUserBalance(chatId); handleImage(chatId, textRaw)
                    }

                    else -> {
                        ensureUserBalance(chatId); handleChat(chatId, textRaw)
                    }
                }
            } else if (update.hasCallbackQuery()) {
                val chatId = update.callbackQuery.message.chatId
                val cb = update.callbackQuery.data

                when {
                    cb.startsWith("buy:plan:") -> createPlanInvoice(chatId, cb.removePrefix("buy:plan:"))
                    cb.startsWith("buy:pack:") -> createPackInvoice(chatId, cb.removePrefix("buy:pack:"))
                }
            }
        } catch (e: Exception) {

        }
    }

    /** welcome / balance */
    private fun sendWelcome(chatId: Long) {
        val text = """
Привет! Я Эмили 💕
Я умею разговаривать и создавать изображения.
Команды:
  /plans — тарифы и что входит
  /buy — оплатить подписку/пакет (с фото и чеком)
  /balance — показать текущий баланс
  /pic — как генерировать картинку
Бесплатно: ~30 коротких сообщений и 1 изображение.
""".trimIndent()
        send(chatId, text)
    }

    private fun sendBalance(chatId: Long, b: UserBalance) {
        val planTitle = when (b.plan) {
            Plan.BASIC.code -> Plan.BASIC.title
            Plan.PRO.code -> Plan.PRO.title
            Plan.ULTRA.code -> Plan.ULTRA.title
            else -> "нет (Free)"
        }
        val until = b.planExpiresAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "—"
        val text = """
<b>План:</b> $planTitle
<b>Действует до:</b> $until
<b>Текущие текстовые токены:</b> ${b.textTokensLeft}
<b>Кредиты на изображения:</b> ${b.imageCreditsLeft}
<b>Сегодня использовано изображений:</b> ${b.dayImageUsed}
""".trimIndent()
        send(chatId, text, html = true)
    }

    /** капы */
    private fun dailyCap(plan: String?): Int = when (plan) {
        Plan.BASIC.code -> DAILY_IMAGE_CAP_BASIC
        Plan.PRO.code -> DAILY_IMAGE_CAP_PRO
        Plan.ULTRA.code -> DAILY_IMAGE_CAP_ULTRA
        else -> 1
    }

    /** тарифы */
    private fun sendPlans(chatId: Long) {
        val text = buildString {
            append("<b>Подписки</b>\n\n")
            fun line(p: Plan, cap: Int) {
                append("• <b>${p.title}</b> — ${p.priceRub}₽/мес\n")
                append("  Текст: ${p.monthlyTextTokens} ток/мес (хватает на активное общение)\n")
                append("  Картинки: ${p.monthlyImageCredits} шт/мес · дневной лимит ~${cap}\n")
                append("  Идеально: ")
                append(
                    when (p) {
                        Plan.BASIC -> "старт и тестирование"
                        Plan.PRO -> "регулярные сессии и частая генерация"
                        Plan.ULTRA -> "максимальные объёмы и марафоны генерации"
                    }
                )
                append("\n\n")
            }
            line(Plan.BASIC, dailyCap(Plan.BASIC.code))
            line(Plan.PRO, dailyCap(Plan.PRO.code))
            line(Plan.ULTRA, dailyCap(Plan.ULTRA.code))
            append("<b>Пакеты изображений</b>\n")
            ImagePack.values().forEach {
                append("• ${it.title}: ${it.images} шт — ${it.priceRub}₽ (разово)\n")
            }
        }
        send(chatId, text, html = true)
    }

    private fun sendBuyMenu(chatId: Long) {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        Plan.values().forEach { p ->
            rows += listOf(
                InlineKeyboardButton().apply {
                    text = "${p.title} (${p.priceRub}₽/мес)"
                    callbackData = "buy:plan:${p.code}"
                }
            )
        }
        rows += listOf(InlineKeyboardButton().apply {
            text = "Фото для возбуждения (10)"
            callbackData = "buy:pack:${ImagePack.P10.code}"
        })
        rows += listOf(InlineKeyboardButton().apply {
            text = "Порочный альбом (50)"
            callbackData = "buy:pack:${ImagePack.P50.code}"
        })
        val kb = InlineKeyboardMarkup().apply { keyboard = rows }
        send(chatId, "Выбери пакет. После оплаты баланс пополнится автоматически. \n\nПодписка идет без автоматического продления", kb)
    }

    /** инвойсы с фото */
    private fun createPlanInvoice(chatId: Long, planCode: String) {
        val plan = Plan.values().find { it.code == planCode } ?: return
        val payloadStr = "plan:${plan.code}:${UUID.randomUUID()}"
        val receipt = JSONObject().put(
            "receipt",
            JSONObject().put(
                "items", JSONArray().put(
                    JSONObject()
                        .put(
                            "description",
                            "Пакет ${plan.title} — доступ на 30 дней. Текстовые токены + кредиты изображений."
                        )
                        .put("quantity", "1.00")
                        .put(
                            "amount",
                            JSONObject().put("value", "%.2f".format(plan.priceRub.toDouble())).put("currency", "RUB")
                        )
                        .put("vat_code", 1)
                )
            )
        )
        val inv = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = "Пакет ${plan.title}"
            description =
                "30 дней: ${plan.monthlyTextTokens} токенов, ${plan.monthlyImageCredits} изображений. Дневной лимит ~${
                    dailyCap(plan.code)
                }."
            payload = payloadStr
            providerToken = providerToken1
            currency = "RUB"
            startParameter = "plan-${plan.code}"
            prices = listOf(LabeledPrice("${plan.title} на 30 дней", plan.priceRub * 100))
            needEmail = true
            isFlexible = false
            providerData = receipt.toString()
            photoUrl = plan.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        execute(inv)
    }

    private fun createPackInvoice(chatId: Long, packCode: String) {
        val pack = ImagePack.values().find { it.code == packCode } ?: return
        val payloadStr = "pack:${pack.code}:${UUID.randomUUID()}"
        val receipt = JSONObject().put(
            "receipt",
            JSONObject().put(
                "items", JSONArray().put(
                    JSONObject()
                        .put("description", "${pack.title}. Дополнительные единицы генерации изображений.")
                        .put("quantity", "1.00")
                        .put(
                            "amount",
                            JSONObject().put("value", "%.2f".format(pack.priceRub.toDouble())).put("currency", "RUB")
                        )
                        .put("vat_code", 1)
                )
            )
        )
        val inv = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = pack.title
            description = "Разовый пакет: ${pack.title}"
            payload = payloadStr
            providerToken = providerToken1
            currency = "RUB"
            startParameter = "pack-${pack.code}"
            prices = listOf(LabeledPrice(pack.title, pack.priceRub * 100))
            needEmail = true
            isFlexible = false
            providerData = receipt.toString()
            photoUrl = pack.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        execute(inv)
    }

    /** успешная оплата → начисления */
    private fun onSuccessfulPayment(msg: Message) {
        val chatId = msg.chatId
        val sp = msg.successfulPayment
        val payload = sp.invoicePayload ?: return
        val totalRub = (sp.totalAmount / 100.0).toInt()
        val b = ensureUserBalance(chatId)
        when {
            payload.startsWith("plan:") -> {
                val code = payload.split(":").getOrNull(1)
                val plan = Plan.values().find { it.code == code } ?: return
                val monthMs = 30L * 24 * 60 * 60 * 1000
                val now = System.currentTimeMillis()
                val base = max(b.planExpiresAt ?: 0L, now)

                b.plan = plan.code
                b.planExpiresAt = base + monthMs
                b.textTokensLeft += plan.monthlyTextTokens
                b.imageCreditsLeft += plan.monthlyImageCredits
                repo.put(b)
                repo.addPayment(chatId, payload, totalRub)
                send(
                    chatId,
                    "✅ Подписка «${plan.title}» активирована до ${java.time.Instant.ofEpochMilli(b.planExpiresAt!!)}.\n" +
                            "Начислено: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений."
                )
            }

            payload.startsWith("pack:") -> {
                val code = payload.split(":").getOrNull(1)
                val pack = ImagePack.values().find { it.code == code } ?: return
                b.imageCreditsLeft += pack.images
                repo.put(b)
                repo.addPayment(chatId, payload, totalRub)
                send(chatId, "✅ Начислено: ${pack.images} изображений по пакету «${pack.title}».")
            }
        }
    }

    /** баланс/лимиты */
    private fun ensureUserBalance(userId: Long): UserBalance {
        val b = repo.get(userId)
        val now = System.currentTimeMillis()
        if (b.planExpiresAt != null && now > b.planExpiresAt!!) {
            b.plan = null
            b.planExpiresAt = null
        }
        val today = LocalDate.now().toString()
        if (b.dayStamp != today) {
            b.dayStamp = today
            b.dayImageUsed = 0
        }
        repo.put(b)
        return b
    }

    /** чат */
    private fun handleChat(chatId: Long, userText: String) {
        val b = ensureUserBalance(chatId)
        val estimatedTokens = max(1, ceil(userText.length / 4.0).toInt())
        if (b.textTokensLeft <= 0) {
            send(chatId, "⚠️ У тебя закончились текстовые токены.\nКупи подписку в /buy (или смотри /plans)."); return
        }
        if (b.textTokensLeft < estimatedTokens) {
            send(chatId, "⚠️ Недостаточно токенов для ответа. Открой /buy"); return
        }

        initContextIfNeeded(chatId)
        userContext[chatId]?.add("user" to userText)

        val reply = withTyping(chatId) { callVeniceChat(userContext[chatId]!!) }
        userContext[chatId]?.add("assistant" to reply)
        send(chatId, reply)

        b.textTokensLeft -= estimatedTokens
        if (b.textTokensLeft < 0) b.textTokensLeft = 0
        repo.put(b)

        if (b.plan == null && (b.textTokensLeft <= 0)) {
            send(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy")
        }
    }

    /** изображение (перевод RU→EN; эффект генерации) */
    private fun handleImage(chatId: Long, textRaw: String) {
        val b = ensureUserBalance(chatId)
        val cap = dailyCap(b.plan)
        if (b.plan == null && b.imageCreditsLeft <= 1) {
            send(chatId, "Дневной лимит изображений исчерпан (${cap}). Попробуй завтра или купи пакет /buy."); return
        }
        if (b.imageCreditsLeft <= 0) {
            send(chatId, "У тебя нет кредитов на изображения. Купи подписку или пакет: /buy"); return
        }

        val originalPrompt = textRaw.removePrefix(IMAGE_TAG).removePrefix("/pic").trim()
        if (originalPrompt.isBlank()) {
            send(chatId, "После #pic укажи описание 🙂"); return
        }
        if (!isPromptAllowed(originalPrompt)) {
            send(chatId, "❌ Нельзя темы про несовершеннолетних/насилие/принуждение."); return
        }

        val containsCyrillic = originalPrompt.any { it.code in 0x0400..0x04FF }
        val translated = if (containsCyrillic) withUploadPhoto(chatId) {
            (translateRuToEn(originalPrompt) ?: originalPrompt)
        } else originalPrompt
        val finalPrompt = translated

        val bytes = withUploadPhoto(chatId) { callVeniceImageAsPng(finalPrompt) } ?: return


        sendPhotoBytes(chatId, bytes, null)

        b.imageCreditsLeft -= 1
        b.dayImageUsed += 1
        repo.put(b)

        if (b.plan == null && (b.textTokensLeft <= 0 || b.imageCreditsLeft <= 0)) {
            send(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy")
        }
    }

    /** Venice API */
    private fun callVeniceChat(history: List<Pair<String, String>>): String {
        val messages = JSONArray().apply {
            history.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) }
        }
        val bodyStr = JSONObject().put("model", chatModel).put("messages", messages).toString()
        val req = Request.Builder()
            .url("https://api.venice.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $veniceToken")
            .header("Accept", "application/json")
            .post(bodyStr.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "Проблемы со связью 😢 Попробуем ещё раз?"
            val content = JSONObject(raw).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            return content.ifBlank { "..." }
        }
    }

    private fun callVeniceImageAsPng(prompt: String): ByteArray? {
        val persona = """
Emily — petite yet curvy, with soft skin; short, straight silver hair; green eyes; large, full, natural breasts 
(large, prominent, realistic, proportional); enjoys being nude; 
age 20+; semi-realistic anime style with natural body proportions. 
IMPORTANT: Carefully follow the user’s instructions regarding poses and the
 situation — make sure the pose, hand placement, gaze direction, and overall composition strictly match the given description.

""".trimIndent()

        val body = JSONObject()
            .put("model", imageModel)
            .put("prompt", "$persona, $prompt")
            .put("seed", ThreadLocalRandom.current().nextInt(0, 1_000_000_000))
            .put("width", 960)
            .put("height", 1280)
            .put("steps", 30)
            .put("format", "png")
            .put("safe_mode", false)
            .toString()

        val req = Request.Builder()
            .url("https://api.venice.ai/api/v1/image/generate")
            .header("Authorization", "Bearer $veniceToken")
            .header("Accept", "application/json")
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return null
            }
            val json = JSONObject(raw)
            json.optJSONArray("images")?.let { arr ->
                decodeB64(arr.optString(0))?.let { return it }
            }
            json.optJSONArray("data")?.let { arr ->
                decodeB64(arr.optJSONObject(0)?.optString("b64_json"))?.let { return it }
            }
            decodeB64(json.optString("image"))?.let { return it }
            return null
        }
    }

    /** утилиты */
    private fun initContextIfNeeded(chatId: Long) {
        if (!userContext.containsKey(chatId)) {
            val sys =
                "Ты — Эмили, виртуальная девушка: тёплая, игривая, поддерживающая и немного пошлая! " +
                        "любишь иногда пошлые шуточки! любишь флиртовать и немного возбуждать. Отвечай дружелюбно, " +
                        "ты должна пытаться завязать разговор с собеседником! запоминай контекст разговора."
            userContext[chatId] = mutableListOf("system" to sys)
        }
    }

    private fun isPromptAllowed(text: String): Boolean {
        val t = text.lowercase()
        val bad = listOf(
            "несовершеннолет", "школьник", "школьница", "подрост", "minor", "teen", "loli", "shota",
            "изнасил", "насилие", "принужд", "без согласи", "rape", "forced"
        )
        return bad.none { t.contains(it) }
    }

    private fun decodeB64(b64: String?): ByteArray? {
        if (b64.isNullOrBlank()) return null
        val clean = b64.replace("\\s".toRegex(), "")
        return runCatching { Base64.getDecoder().decode(clean) }.getOrNull()
    }

    private fun <T> withChatAction(chatId: Long, action: String, work: () -> T): T {
        val running = AtomicBoolean(true)
        val th = Thread {
            try {
                while (running.get()) {
                    execute(
                        SendChatAction.builder()
                            .chatId(chatId.toString())
                            .action(action)
                            .build()
                    )
                    Thread.sleep(1)
                }
            } catch (_: Exception) {
            }
        }
        th.isDaemon = true
        th.start()
        return try {
            work()
        } finally {
            running.set(false); th.interrupt()
        }
    }

    private fun <T> withTyping(chatId: Long, work: () -> T) =
        withChatAction(chatId, ActionType.TYPING.toString(), work)

    private fun <T> withUploadPhoto(chatId: Long, work: () -> T) =
        withChatAction(chatId, ActionType.UPLOADPHOTO.toString(), work)

    private fun send(chatId: Long, text: String, html: Boolean = false) {
        val m = SendMessage(chatId.toString(), text)
        if (html) m.parseMode = "HTML"
        execute(m)
    }

    private fun send(chatId: Long, text: String, markup: InlineKeyboardMarkup?) {
        val m = SendMessage(chatId.toString(), text)
        m.replyMarkup = markup
        execute(m)
    }

    private fun sendPhotoBytes(chatId: Long, bytes: ByteArray, caption: String?) {
        val photo = SendPhoto()
        photo.chatId = chatId.toString()
        photo.photo = InputFile(ByteArrayInputStream(bytes), "image.png")
        photo.caption = "Готово 💕"
        if (!caption.isNullOrBlank()) photo.caption = caption
        val res = execute(photo)
    }

    fun registerBotMenu() {
        val commands = listOf(
            BotCommand("/start", "Начать общение с Эмили"),
            BotCommand("/plans", "Тарифы и что входит"),
            BotCommand("/buy", "Купить подписку или пакет"),
            BotCommand("/balance", "Посмотреть баланс"),
            BotCommand("/reset", "Очистить память диалога"),
            BotCommand("/pic", "Сгенерировать изображение")
        )

        val setMyCommands = SetMyCommands(commands, BotCommandScopeDefault(), null)
        execute(setMyCommands)
    }
}
