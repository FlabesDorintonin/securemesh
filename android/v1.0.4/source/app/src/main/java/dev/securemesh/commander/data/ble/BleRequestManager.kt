package dev.securemesh.commander.data.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class BleRequestManager(
    private val maxPending: Int = 16,
    private val timeoutMs: Long = 5_000L,
) {
    data class Handle(
        val requestId: Int,
        val opcode: BleOpcode,
        internal val deferred: CompletableDeferred<Result<SecureMeshBleFrame.Response>>,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<Int, Handle>()
    private var nextRequestId = 1

    fun allocate(opcode: BleOpcode): Result<Handle> = synchronized(lock) {
        if (pending.size >= maxPending) return@synchronized Result.failure(IllegalStateException("BLE pending request limit reached"))
        var candidate = nextRequestId
        repeat(0xFFFF) {
            if (candidate == 0) candidate = 1
            if (!pending.containsKey(candidate)) {
                val handle = Handle(candidate, opcode, CompletableDeferred())
                pending[candidate] = handle
                nextRequestId = if (candidate == 0xFFFF) 1 else candidate + 1
                return@synchronized Result.success(handle)
            }
            candidate = if (candidate == 0xFFFF) 1 else candidate + 1
        }
        Result.failure(IllegalStateException("No BLE requestId available"))
    }

    fun accept(frame: SecureMeshBleFrame): Boolean {
        if (frame !is SecureMeshBleFrame.Response) return false
        val handle = synchronized(lock) { pending[frame.requestId] } ?: return false
        if (frame.opcode != handle.opcode) {
            synchronized(lock) { pending.remove(frame.requestId) }
            handle.deferred.complete(Result.failure(IllegalStateException("BLE response opcode mismatch for request ${frame.requestId}")))
            return false
        }
        synchronized(lock) { pending.remove(frame.requestId) }
        handle.deferred.complete(Result.success(frame))
        return true
    }

    suspend fun await(handle: Handle): Result<SecureMeshBleFrame.Response> = try {
        withTimeout(timeoutMs) { handle.deferred.await() }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        synchronized(lock) { pending.remove(handle.requestId) }
        Result.failure(IllegalStateException("SecureMesh BLE command timeout (requestId=${handle.requestId})"))
    }

    fun cancel(handle: Handle, cause: Throwable) {
        val removed = synchronized(lock) { pending.remove(handle.requestId) }
        if (removed != null) removed.deferred.complete(Result.failure(cause))
    }

    fun failAll(cause: Throwable) {
        val copy = synchronized(lock) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
        copy.forEach { it.deferred.complete(Result.failure(cause)) }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }
}
