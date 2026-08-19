package emily.data

import emily.resources.Strings
import java.time.LocalDate
import kotlin.math.ceil

object TelegramStarsPricing {
    // Telegram assigns a $0.013 developer reward to each earned Star.
    const val REWARD_USD_PER_STAR = 0.013

    // Official Bank of Russia USD rate effective 2026-08-18.
    const val USD_RUB_REFERENCE_RATE = 85.0136

    val rewardRubPerStar: Double
        get() = REWARD_USD_PER_STAR * USD_RUB_REFERENCE_RATE

    fun forTargetRub(targetRub: Int): Int = ceil(targetRub / rewardRubPerStar).toInt()
}

enum class Plan(
    val code: String,
    private val titleKey: String,
    val targetRevenueRub: Int,
    val monthlyTextTokens: Int,
    val monthlyImageCredits: Int,
    val monthlyGifCredits: Int,
    val photoUrl: String
) {
    BASIC(
        code = "basic",
        titleKey = "plan.title.basic",
        targetRevenueRub = 399,
        monthlyTextTokens = 2_400_000,
        monthlyImageCredits = 25,
        monthlyGifCredits = 0,
        photoUrl = "https://drive.google.com/uc?export=download&id=1TCRXGBCDeju4zjER_lUvsn5yZPcv-V7s"
    ),

    PRO(
        code = "pro",
        titleKey = "plan.title.pro",
        targetRevenueRub = 999,
        monthlyTextTokens = 6_000_000,
        monthlyImageCredits = 80,
        monthlyGifCredits = 0,
        photoUrl = "https://drive.google.com/uc?export=download&id=1a3kI5IXbX95QMSpRb72vj0RRIKaXs9T6"
    ),

    ULTRA(
        code = "ultra",
        titleKey = "plan.title.ultra",
        targetRevenueRub = 1890,
        monthlyTextTokens = 12_000_000,
        monthlyImageCredits = 180,
        monthlyGifCredits = 0,
        photoUrl = "https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32"
    );

    companion object {
        fun byCode(code: String?): Plan? = entries.firstOrNull { it.code == code }
    }

    val title: String
        get() = Strings.get(titleKey)

    val priceStars: Int
        get() = TelegramStarsPricing.forTargetRub(targetRevenueRub)
}

enum class ImagePack(
    val code: String,
    private val titleKey: String,
    val targetRevenueRub: Int,
    val images: Int,
    val photoUrl: String
) {
    P10(
        code = "pack10",
        titleKey = "pack.title.p10",
        targetRevenueRub = 99,
        images = 20,
        photoUrl = "https://drive.google.com/uc?export=download&id=1pojAKJs7hChiLZhF_27HEKCv6vktDfac"
    ),
    P20(
        code = "pack20",
        titleKey = "pack.title.p20",
        targetRevenueRub = 149,
        images = 50,
        photoUrl = "https://drive.google.com/uc?export=download&id=1pojAKJs7hChiLZhF_27HEKCv6vktDfac"
    ),
    P100(
        code = "pack100",
        titleKey = "pack.title.p100",
        targetRevenueRub = 349,
        images = 150,
        photoUrl = "https://drive.google.com/uc?export=download&id=1f67uMVIMFWCe4DvQU4GlgnI5vx0cH6iC"
    );

    companion object {
        fun byCode(code: String?): ImagePack? = entries.firstOrNull { it.code == code }
    }

    val title: String
        get() = Strings.get(titleKey)

    val priceStars: Int
        get() = TelegramStarsPricing.forTargetRub(targetRevenueRub)
}

enum class GifPack(
    val code: String,
    private val titleKey: String,
    val targetRevenueRub: Int,
    val gifs: Int
) {
    G3(
        code = "gif3",
        titleKey = "gifpack.title.g3",
        targetRevenueRub = 269,
        gifs = 3
    ),
    G7(
        code = "gif7",
        titleKey = "gifpack.title.g7",
        targetRevenueRub = 619,
        gifs = 7
    ),
    G15(
        code = "gif15",
        titleKey = "gifpack.title.g15",
        targetRevenueRub = 1309,
        gifs = 15
    );

    companion object {
        fun byCode(code: String?): GifPack? = entries.firstOrNull { it.code == code }
    }

    val title: String
        get() = Strings.get(titleKey)

    val priceStars: Int
        get() = TelegramStarsPricing.forTargetRub(targetRevenueRub)
}
const val FREE_TEXT_TOKENS = 100_000
const val FREE_IMAGE_CREDITS = 3
const val FREE_GIF_CREDITS = 0

object CustomStoryPack {
    const val code = "custom_story_3_stories"
    const val targetRevenueRub = 150
    val priceStars: Int
        get() = TelegramStarsPricing.forTargetRub(targetRevenueRub)
    const val storySlots = 3
    const val title = "Своя история"
    const val description = "Открой создание своих сценариев и добавь до 3 историй."
}

data class UserBalance(
    val userId: Long = 0L,
    var plan: String? = null,
    var planExpiresAt: Long? = null,
    var textTokensLeft: Int = FREE_TEXT_TOKENS,
    var imageCreditsLeft: Int = FREE_IMAGE_CREDITS,
    var gifCreditsLeft: Int = FREE_GIF_CREDITS,
    var dayImageUsed: Int = 0,
    var dayGifUsed: Int = 0,
    var dayStamp: String = LocalDate.now().toString(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)
