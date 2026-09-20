package net.prok.proknet.core

/**
 * v0.13.3: the BLE control plane as a generation machine, pure.
 *
 * Phone evidence: after Bluetooth was toggled off and on, the OUKITEL still
 * reported `server ready, adv on, scan on` while every customer got
 * `peer has no ProkNet service (services=2)`. `GattServerNode.isReady` was a
 * Boolean set once by `onServiceAdded` and never invalidated, so the radio
 * recovery skipped the server rebuild (`if (server?.isReady != true)`) and
 * kept advertising a control plane that no longer existed.
 *
 * So a Boolean is not enough. Every asynchronous piece of the stack is
 * stamped with the generation it was built under, and a piece counts only
 * while its stamp equals the current generation. An adapter that comes back
 * bumps the generation, which invalidates the server, the service, the
 * advertiser, the scanner and every callback still in flight, all at once.
 *
 * The invariant: **ProkNet never advertises while its GATT service is
 * missing.**
 */
object BleLifecycle {

    enum class Phase {
        /** No usable stack: Bluetooth off, or torn down waiting for a rebuild. */
        DOWN,
        /** openGattServer succeeded, addService accepted, waiting for onServiceAdded. */
        SERVICE_PENDING,
        /** onServiceAdded confirmed SUCCESS under the current generation. */
        READY,
        /** The service could not be added; bounded retry. */
        FAILED,
    }

    /** Bounded retry when Android refuses to add the service. */
    val SERVICE_RETRY_MS = listOf(1_000L, 3_000L, 10_000L, 30_000L)
    const val SERVICE_MAX_ATTEMPTS = 6

    data class State(
        /** Bumped whenever the stack must be rebuilt from nothing. Everything else is stamped with it. */
        val generation: Int = 1,
        val bluetoothOn: Boolean = true,
        val phase: Phase = Phase.DOWN,
        /** The generation whose onServiceAdded reported SUCCESS; 0 = never. */
        val serviceGeneration: Int = 0,
        val advertisingGeneration: Int = 0,
        val scanGeneration: Int = 0,
        val serviceAttempts: Int = 0,
        val lastAttemptAt: Long = 0,
        val rebuilds: Int = 0,
        val lastRebuildWhy: String = "",
    )

    /** The service exists AND was confirmed under the generation running now. */
    fun serviceReady(s: State): Boolean = s.phase == Phase.READY && s.serviceGeneration == s.generation

    /** The one rule that was missing: no service, no advertising. */
    fun mayAdvertise(s: State): Boolean = s.bluetoothOn && serviceReady(s)

    fun advertising(s: State): Boolean = s.advertisingGeneration == s.generation && s.generation > 0
    fun scanning(s: State): Boolean = s.scanGeneration == s.generation && s.generation > 0

    /** What a customer can actually use: a live service and a live advert under the same generation. */
    fun controlPlaneHealthy(s: State): Boolean = serviceReady(s) && advertising(s)

    /** The line the diagnostic prints when the invariant is broken, or null when it holds. */
    fun unhealthyReason(s: State): String? = when {
        !s.bluetoothOn -> "Bluetooth is off"
        advertising(s) && !serviceReady(s) -> "CONTROL PLANE UNHEALTHY: advertising without ProkNet GATT service"
        s.phase == Phase.FAILED -> "the ProkNet GATT service could not be added (attempt " + s.serviceAttempts + ")"
        s.phase == Phase.SERVICE_PENDING -> "waiting for the GATT service to be added"
        !serviceReady(s) -> "no ProkNet GATT service"
        !advertising(s) -> "not advertising"
        else -> null
    }

    /** A callback from an older generation must never touch the new stack. */
    fun isStale(s: State, callbackGeneration: Int): Boolean = callbackGeneration != s.generation

    // ---- transitions ------------------------------------------------------------------------------------

    /** The adapter went off: everything Android owned is gone, whatever our objects still say. */
    fun bluetoothOff(s: State): State =
        s.copy(bluetoothOn = false, phase = Phase.DOWN, serviceGeneration = 0, advertisingGeneration = 0, scanGeneration = 0, serviceAttempts = 0)

    /** The adapter came back: a NEW generation, so nothing built before can be mistaken for live. */
    fun bluetoothOn(s: State, now: Long): State = rebuild(s.copy(bluetoothOn = true), now, "Bluetooth returned")

    /** Any other reason to rebuild the whole stack (a wedged radio, a missing service). */
    fun rebuild(s: State, now: Long, why: String): State = s.copy(
        generation = s.generation + 1, phase = Phase.DOWN, serviceGeneration = 0, advertisingGeneration = 0, scanGeneration = 0,
        serviceAttempts = 0, lastAttemptAt = now, rebuilds = s.rebuilds + 1, lastRebuildWhy = why)

    /** openGattServer + addService were accepted; onServiceAdded has not answered yet. */
    fun serverOpened(s: State, now: Long): State =
        s.copy(phase = Phase.SERVICE_PENDING, serviceAttempts = s.serviceAttempts + 1, lastAttemptAt = now)

    /** onServiceAdded answered. A late answer from an old generation is ignored. */
    fun serviceAdded(s: State, generation: Int, ok: Boolean, now: Long): State = when {
        isStale(s, generation) -> s
        ok -> s.copy(phase = Phase.READY, serviceGeneration = generation, serviceAttempts = 0)
        else -> s.copy(phase = Phase.FAILED, lastAttemptAt = now)
    }

    fun advertisingStarted(s: State, generation: Int, ok: Boolean): State =
        if (isStale(s, generation) || !ok) s else s.copy(advertisingGeneration = generation)

    fun advertisingStopped(s: State): State = s.copy(advertisingGeneration = 0)

    fun scanStarted(s: State, generation: Int, ok: Boolean): State =
        if (isStale(s, generation) || !ok) s else s.copy(scanGeneration = generation)

    fun scanStopped(s: State): State = s.copy(scanGeneration = 0)

    /** May the stack try to (re)add the service now? Bounded, with a backoff. */
    fun mayTryService(s: State, now: Long): Boolean = when {
        !s.bluetoothOn -> false
        s.phase == Phase.READY -> false
        s.phase == Phase.SERVICE_PENDING -> now - s.lastAttemptAt > 10_000L     // Android never answered
        s.phase == Phase.FAILED -> s.serviceAttempts < SERVICE_MAX_ATTEMPTS &&
            now - s.lastAttemptAt >= SERVICE_RETRY_MS[minOf(s.serviceAttempts, SERVICE_RETRY_MS.size - 1)]
        else -> true
    }

    fun describe(s: State): String =
        "generation: " + s.generation + "\n" +
            "  GATT server: " + (if (s.phase == Phase.DOWN) "CLOSED" else "OPEN") + "\n" +
            "  ProkNet service: " + (when {
                serviceReady(s) -> "ADDED"
                s.phase == Phase.SERVICE_PENDING -> "PENDING"
                s.phase == Phase.FAILED -> "FAILED (attempt " + s.serviceAttempts + ")"
                else -> "MISSING"
            }) + "\n" +
            "  advertising generation: " + s.advertisingGeneration + (if (advertising(s)) "" else " (not advertising)") + "\n" +
            "  scan generation: " + s.scanGeneration + (if (scanning(s)) "" else " (not scanning)") + "\n" +
            "  rebuilds: " + s.rebuilds + (if (s.lastRebuildWhy.isEmpty()) "" else " (last: " + s.lastRebuildWhy + ")")
}
