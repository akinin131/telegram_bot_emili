package emily.app

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object BotRunGuard {
    private val registered = AtomicBoolean(false)

    fun tryLockOrExit() {
        if (!registered.compareAndSet(false, true)) exitProcess(1)
    }
}

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
