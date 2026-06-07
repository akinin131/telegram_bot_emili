package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReferralActivationBonus(
    val referrerId: Long,
    val invitedUserId: Long,
    val referrerBonusTokens: Int,
    val invitedBonusTokens: Int
)

data class ReferralPaymentBonus(
    val referrerId: Long,
    val invitedUserId: Long,
    val ratePercent: Int,
    val paidReferralCount: Int,
    val bonusTextTokens: Int,
    val bonusImageCredits: Int
)

data class ReferralPartnerStats(
    val registeredCount: Int,
    val activatedCount: Int,
    val paidCount: Int,
    val earnedTextTokens: Int,
    val earnedImageCredits: Int
)

data class ReferralTopPartner(
    val referrerId: Long,
    val registeredCount: Int,
    val activatedCount: Int,
    val paidCount: Int,
    val earnedTextTokens: Int,
    val earnedImageCredits: Int
)

class ReferralRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val rootRef by lazy { database.reference }
    private val usersRef by lazy { database.getReference("users") }
    private val balancesRef by lazy { database.getReference("balances") }
    private val referralsRef by lazy { database.getReference("referrals") }

    suspend fun ensureUserProfile(userId: Long): Any? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val snapshot = usersRef.child(userId.toString()).awaitSingle()
        val payload = mutableMapOf<String, Any>(
            "userId" to userId,
            "referralCode" to userId.toString(),
            "updatedAt" to now
        )
        if (!snapshot.child("createdAt").exists()) {
            payload["createdAt"] = now
        }
        usersRef.child(userId.toString()).updateChildrenAsync(payload)
    }

    suspend fun registerReferral(referrerId: Long, invitedUserId: Long): Boolean = withContext(Dispatchers.IO) {
        if (referrerId == invitedUserId) return@withContext false

        val now = System.currentTimeMillis()
        val invitedSnapshot = usersRef.child(invitedUserId.toString()).awaitSingle()
        val existingReferrer = invitedSnapshot.child("invitedBy").longValue()
        if (existingReferrer != null) {
            ensureUserProfile(referrerId)
            ensureUserProfile(invitedUserId)
            return@withContext false
        }

        val alreadyKnownUser = invitedSnapshot.child("createdAt").exists() ||
            balancesRef.child(invitedUserId.toString()).awaitSingle().exists()

        if (alreadyKnownUser) {
            ensureUserProfile(referrerId)
            ensureUserProfile(invitedUserId)
            return@withContext false
        }

        val updates = mutableMapOf<String, Any>(
            "users/$referrerId/userId" to referrerId,
            "users/$referrerId/referralCode" to referrerId.toString(),
            "users/$referrerId/updatedAt" to now,
            "users/$invitedUserId/userId" to invitedUserId,
            "users/$invitedUserId/referralCode" to invitedUserId.toString(),
            "users/$invitedUserId/invitedBy" to referrerId,
            "users/$invitedUserId/referralActivated" to false,
            "users/$invitedUserId/referralRegisteredAt" to now,
            "users/$invitedUserId/createdAt" to now,
            "users/$invitedUserId/updatedAt" to now,
            "referrals/$referrerId/$invitedUserId/referrerId" to referrerId,
            "referrals/$referrerId/$invitedUserId/invitedUserId" to invitedUserId,
            "referrals/$referrerId/$invitedUserId/status" to STATUS_REGISTERED,
            "referrals/$referrerId/$invitedUserId/createdAt" to now,
            "referrals/$referrerId/$invitedUserId/activityMessages" to 0,
            "referrals/$referrerId/$invitedUserId/earnedTokens" to 0,
            "referrals/$referrerId/$invitedUserId/paidBonusTokens" to 0,
            "referrals/$referrerId/$invitedUserId/paidBonusImageCredits" to 0
        )
        if (!usersRef.child(referrerId.toString()).awaitSingle().child("createdAt").exists()) {
            updates["users/$referrerId/createdAt"] = now
        }

        rootRef.updateChildrenAsync(updates)
        true
    }

    suspend fun recordChatMessage(invitedUserId: Long): ReferralActivationBonus? = withContext(Dispatchers.IO) {
        val referrerId = referrerForPendingReferral(invitedUserId) ?: return@withContext null
        val referralRef = referralsRef.child(referrerId.toString()).child(invitedUserId.toString())
        val snapshot = referralRef.awaitSingle()
        val nextMessages = snapshot.child("activityMessages").intValue() + 1
        val now = System.currentTimeMillis()

        referralRef.updateChildrenAsync(
            mapOf(
                "activityMessages" to nextMessages,
                "lastActivityAt" to now
            )
        )

        if (nextMessages >= REQUIRED_ACTIVITY_MESSAGES) {
            markActivated(referrerId, invitedUserId, "chat_messages", now)
        } else {
            null
        }
    }

    suspend fun activateByGeneration(invitedUserId: Long, source: String): ReferralActivationBonus? =
        withContext(Dispatchers.IO) {
            val referrerId = referrerForPendingReferral(invitedUserId) ?: return@withContext null
            markActivated(referrerId, invitedUserId, source, System.currentTimeMillis())
        }

    suspend fun registerPayment(
        invitedUserId: Long,
        purchasedTextTokens: Int,
        purchasedImageCredits: Int,
        paymentPayload: String
    ): ReferralPaymentBonus? = withContext(Dispatchers.IO) {
        val userSnapshot = usersRef.child(invitedUserId.toString()).awaitSingle()
        val referrerId = userSnapshot.child("invitedBy").longValue() ?: return@withContext null
        if (referrerId == invitedUserId) return@withContext null

        val referrerRef = referralsRef.child(referrerId.toString())
        val referralRef = referrerRef.child(invitedUserId.toString())
        val referralSnapshot = referralRef.awaitSingle()
        val wasPaid = referralSnapshot.isPaidReferral()
        val allReferrals = referrerRef.awaitSingle()
        val paidBefore = allReferrals.children.count { it.isPaidReferral() }
        val paidReferralCount = if (wasPaid) paidBefore else paidBefore + 1
        val ratePercent = partnerRatePercent()
        val bonusTextTokens = percentBonus(purchasedTextTokens, ratePercent)
        val bonusImageCredits = percentBonus(purchasedImageCredits, ratePercent)
        val now = System.currentTimeMillis()

        val updates = mutableMapOf<String, Any>(
            "users/$invitedUserId/referralPaid" to true,
            "users/$invitedUserId/updatedAt" to now,
            "referrals/$referrerId/$invitedUserId/status" to STATUS_PAID,
            "referrals/$referrerId/$invitedUserId/lastPaidAt" to now,
            "referrals/$referrerId/$invitedUserId/paidEvents" to referralSnapshot.child("paidEvents").intValue() + 1,
            "referrals/$referrerId/$invitedUserId/paidBonusTokens" to referralSnapshot.child("paidBonusTokens").intValue() + bonusTextTokens,
            "referrals/$referrerId/$invitedUserId/paidBonusImageCredits" to referralSnapshot.child("paidBonusImageCredits").intValue() + bonusImageCredits,
            "referrals/$referrerId/$invitedUserId/lastPartnerRatePercent" to ratePercent,
            "referrals/$referrerId/$invitedUserId/paidReferralCountAtBonus" to paidReferralCount,
            "referrals/$referrerId/$invitedUserId/lastPaymentPayload" to paymentPayload
        )
        if (!wasPaid) {
            updates["referrals/$referrerId/$invitedUserId/firstPaidAt"] = now
        }

        rootRef.updateChildrenAsync(updates)

        if (bonusTextTokens <= 0 && bonusImageCredits <= 0) {
            null
        } else {
            ReferralPaymentBonus(
                referrerId = referrerId,
                invitedUserId = invitedUserId,
                ratePercent = ratePercent,
                paidReferralCount = paidReferralCount,
                bonusTextTokens = bonusTextTokens,
                bonusImageCredits = bonusImageCredits
            )
        }
    }

    suspend fun partnerStats(referrerId: Long): ReferralPartnerStats = withContext(Dispatchers.IO) {
        val snapshot = referralsRef.child(referrerId.toString()).awaitSingle()
        snapshot.toPartnerStats()
    }

    suspend fun topPartners(limit: Int = 10): List<ReferralTopPartner> = withContext(Dispatchers.IO) {
        val snapshot = referralsRef.awaitSingle()
        snapshot.children
            .mapNotNull { referrerNode ->
                val referrerId = referrerNode.key?.toLongOrNull() ?: return@mapNotNull null
                val stats = referrerNode.toPartnerStats()
                ReferralTopPartner(
                    referrerId = referrerId,
                    registeredCount = stats.registeredCount,
                    activatedCount = stats.activatedCount,
                    paidCount = stats.paidCount,
                    earnedTextTokens = stats.earnedTextTokens,
                    earnedImageCredits = stats.earnedImageCredits
                )
            }
            .sortedWith(
                compareByDescending<ReferralTopPartner> { it.paidCount }
                    .thenByDescending { it.activatedCount }
                    .thenByDescending { it.earnedTextTokens }
            )
            .take(limit)
    }

    private suspend fun referrerForPendingReferral(invitedUserId: Long): Long? {
        val userSnapshot = usersRef.child(invitedUserId.toString()).awaitSingle()
        if (userSnapshot.child("referralActivated").booleanValue()) return null
        val referrerId = userSnapshot.child("invitedBy").longValue() ?: return null
        return referrerId.takeUnless { it == invitedUserId }
    }

    private fun markActivated(
        referrerId: Long,
        invitedUserId: Long,
        source: String,
        now: Long
    ): ReferralActivationBonus? {
        val updates = mutableMapOf<String, Any>(
            "users/$invitedUserId/referralActivated" to true,
            "users/$invitedUserId/updatedAt" to now,
            "referrals/$referrerId/$invitedUserId/activatedAt" to now,
            "referrals/$referrerId/$invitedUserId/activationSource" to source,
            "referrals/$referrerId/$invitedUserId/earnedTokens" to REFERRER_ACTIVATION_BONUS_TOKENS
        )
        if (source != "payment") {
            updates["referrals/$referrerId/$invitedUserId/status"] = STATUS_ACTIVATED
        }
        rootRef.updateChildrenAsync(updates)
        return ReferralActivationBonus(
            referrerId = referrerId,
            invitedUserId = invitedUserId,
            referrerBonusTokens = REFERRER_ACTIVATION_BONUS_TOKENS,
            invitedBonusTokens = INVITED_ACTIVATION_BONUS_TOKENS
        )
    }

    private fun DataSnapshot.toPartnerStats(): ReferralPartnerStats {
        var registered = 0
        var activated = 0
        var paid = 0
        var earnedText = 0
        var earnedImages = 0

        children.forEach { referral ->
            registered += 1
            val status = referral.child("status").getValue(String::class.java).orEmpty()
            if (status == STATUS_ACTIVATED || status == STATUS_PAID || referral.child("activatedAt").exists()) {
                activated += 1
            }
            if (status == STATUS_PAID || referral.child("paidAt").exists() || referral.child("firstPaidAt").exists()) {
                paid += 1
            }
            earnedText += referral.child("earnedTokens").intValue()
            earnedText += referral.child("paidBonusTokens").intValue()
            earnedImages += referral.child("paidBonusImageCredits").intValue()
        }

        return ReferralPartnerStats(
            registeredCount = registered,
            activatedCount = activated,
            paidCount = paid,
            earnedTextTokens = earnedText,
            earnedImageCredits = earnedImages
        )
    }

    private fun DataSnapshot.isPaidReferral(): Boolean {
        val status = child("status").getValue(String::class.java).orEmpty()
        return status == STATUS_PAID || child("paidAt").exists() || child("firstPaidAt").exists()
    }

    private fun DataSnapshot.longValue(): Long? {
        return when (val raw = value) {
            is Long -> raw
            is Int -> raw.toLong()
            is Double -> raw.toLong()
            is Float -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> getValue(Long::class.java)
        }
    }

    private fun DataSnapshot.intValue(): Int {
        return when (val raw = value) {
            is Long -> raw.toInt()
            is Int -> raw
            is Double -> raw.toInt()
            is Float -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> getValue(Long::class.java)?.toInt() ?: 0
        }
    }

    private fun DataSnapshot.booleanValue(): Boolean {
        return when (val raw = value) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun partnerRatePercent(): Int = PAYMENT_BONUS_PERCENT

    private fun percentBonus(amount: Int, percent: Int): Int {
        if (amount <= 0 || percent <= 0) return 0
        val raw = amount * percent / 100
        return if (raw == 0) 1 else raw
    }

    companion object {
        const val INVITED_ACTIVATION_BONUS_TOKENS = 10_000
        const val REFERRER_ACTIVATION_BONUS_TOKENS = 0
        const val REQUIRED_ACTIVITY_MESSAGES = 3
        const val PAYMENT_BONUS_PERCENT = 50

        private const val STATUS_REGISTERED = "registered"
        private const val STATUS_ACTIVATED = "activated"
        private const val STATUS_PAID = "paid"
    }
}
