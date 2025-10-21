package emily.data

import java.time.LocalDate

enum class Plan(
    val code: String,
    val title: String,
    val priceRub: Int,
    val monthlyTextTokens: Int,
    val monthlyImageCredits: Int,
    val photoUrl: String
) {
    BASIC(
        code = "basic",
        title = "Скромница",
        priceRub = 399,
        monthlyTextTokens = 100_000,
        monthlyImageCredits = 15,
        photoUrl = "https://drive.google.com/uc?export=download&id=1TCRXGBCDeju4zjER_lUvsn5yZPcv-V7s"
    ),
    PRO(
        code = "pro",
        title = "Шлюшка",
        priceRub = 650,
        monthlyTextTokens = 300_000,
        monthlyImageCredits = 50,
        photoUrl = "https://drive.google.com/uc?export=download&id=1a3kI5IXbX95QMSpRb72vj0RRIKaXs9T6"
    ),
    ULTRA(
        code = "ultra",
        title = "Грязная развратница",
        priceRub = 1800,
        monthlyTextTokens = 800_000,
        monthlyImageCredits = 150,
        photoUrl = "https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32"
    );

    companion object {
        fun byCode(code: String?): Plan? = values().firstOrNull { it.code == code }
    }
}

enum class ImagePack(
    val code: String,
    val title: String,
    val priceRub: Int,
    val images: Int,
    val photoUrl: String
) {
    P10(
        code = "pack10",
        title = "Фото для возбуждения",
        priceRub = 99,
        images = 10,
        photoUrl = "https://drive.google.com/uc?export=download&id=1pojAKJs7hChiLZhF_27HEKCv6vktDfac"
    ),
    P50(
        code = "pack50",
        title = "Порочный альбом",
        priceRub = 249,
        images = 50,
        photoUrl = "https://drive.google.com/uc?export=download&id=1f67uMVIMFWCe4DvQU4GlgnI5vx0cH6iC"
    );

    companion object {
        fun byCode(code: String?): ImagePack? = values().firstOrNull { it.code == code }
    }
}

const val FREE_TEXT_TOKENS = 50_000
const val FREE_IMAGE_CREDITS = 10

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

data class StorySelection(
    val userId: Long,
    val characterName: String,
    val characterAppearance: String?,
    val characterPersonality: String?,
    val storyTitle: String,
    val storyDescription: String?,
    val storyText: String?,
    val style: String?,
    val updatedAt: Long = System.currentTimeMillis()
)
