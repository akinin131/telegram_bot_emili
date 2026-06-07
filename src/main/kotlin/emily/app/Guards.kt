package emily.app

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object BotRunGuard {
    private val registered = AtomicBoolean(false)

    fun tryLockOrExit() {
        if (!registered.compareAndSet(false, true)) {
            System.err.println("BotRunGuard: application is already registered in this process.")
            exitProcess(1)
        }
    }
}

object SingleInstance {
    private var lock: ServerSocket? = null

    fun acquire(port: Int) {
        try {
            lock = ServerSocket(port)
        } catch (e: Exception) {
            System.err.println(
                "SingleInstance: cannot acquire local port $port. " +
                    "Another bot instance is likely already running."
            )
            System.err.println("Cause: ${e.message}")
            exitProcess(1)
        }
    }
}
