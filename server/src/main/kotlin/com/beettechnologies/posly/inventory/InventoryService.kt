package com.beettechnologies.posly.inventory

import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.StoreService
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private data class SnapshotKey(val productId: String, val storeId: String)

sealed class AdjustStockResult {
    data class Success(val snapshot: InventorySnapshot, val transactionId: String) : AdjustStockResult()
    data object ProductNotFound : AdjustStockResult()
    data object StoreNotFound : AdjustStockResult()
    data object WouldGoNegative : AdjustStockResult()
}

sealed class ReserveResult {
    data class Success(val reservation: Reservation, val snapshot: InventorySnapshot) : ReserveResult()
    data object ProductNotFound : ReserveResult()
    data object StoreNotFound : ReserveResult()
    data object InsufficientStock : ReserveResult()
}

sealed class ReleaseResult {
    data class Success(val reservation: Reservation, val snapshot: InventorySnapshot) : ReleaseResult()
    data object NotFound : ReleaseResult()
    data object NotActive : ReleaseResult()
}

sealed class CommitReservationResult {
    data class Success(val reservation: Reservation, val snapshot: InventorySnapshot) : CommitReservationResult()
    data object NotFound : CommitReservationResult()
    data object NotActive : CommitReservationResult()
}

/**
 * In-memory inventory tracking per (productId, storeId). Snapshot updates go
 * through a compare-and-swap loop (see [casUpdate]) for real optimistic
 * locking under concurrent access, and reservation status transitions go
 * through ConcurrentHashMap.compute for atomic check-and-set - without both,
 * two concurrent releases/commits of the same reservation could each read
 * ACTIVE before either writes, double-applying the stock change.
 */
class InventoryService(
    private val productService: ProductService,
    private val storeService: StoreService
) {
    private val snapshots = ConcurrentHashMap<SnapshotKey, InventorySnapshot>()
    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val transactions = Collections.synchronizedList(mutableListOf<InventoryTransaction>())

    fun adjustStock(
        productId: String,
        storeId: String,
        delta: Int,
        reason: String,
        actorId: String?,
        referenceId: String? = null
    ): AdjustStockResult {
        if (productService.getProduct(productId) == null) return AdjustStockResult.ProductNotFound
        if (storeService.getStore(storeId) == null) return AdjustStockResult.StoreNotFound

        val key = SnapshotKey(productId, storeId)
        val updated = casUpdate(key) { current ->
            val newOnHand = current.onHand + delta
            if (newOnHand < current.reserved) null else current.copy(onHand = newOnHand)
        } ?: return AdjustStockResult.WouldGoNegative

        val transaction = recordTransaction(
            productId, storeId, InventoryTransactionType.ADJUSTMENT, delta, referenceId = referenceId, reason = reason, actorId = actorId
        )
        return AdjustStockResult.Success(updated, transaction.id)
    }

    fun reserve(productId: String, storeId: String, quantity: Int, referenceId: String): ReserveResult {
        if (productService.getProduct(productId) == null) return ReserveResult.ProductNotFound
        if (storeService.getStore(storeId) == null) return ReserveResult.StoreNotFound

        val key = SnapshotKey(productId, storeId)
        val updated = casUpdate(key) { current ->
            if (current.available < quantity) null else current.copy(reserved = current.reserved + quantity)
        } ?: return ReserveResult.InsufficientStock

        val reservation = Reservation(productId = productId, storeId = storeId, quantity = quantity, referenceId = referenceId)
        reservations[reservation.id] = reservation
        recordTransaction(productId, storeId, InventoryTransactionType.RESERVE, quantity, referenceId = referenceId)
        return ReserveResult.Success(reservation, updated)
    }

    fun release(reservationId: String): ReleaseResult {
        val transitionOutcome = transitionReservation(reservationId, ReservationStatus.RELEASED)
        val reservation = when (transitionOutcome) {
            is ReservationTransition.Success -> transitionOutcome.reservation
            ReservationTransition.NotFound -> return ReleaseResult.NotFound
            ReservationTransition.NotActive -> return ReleaseResult.NotActive
        }

        val key = SnapshotKey(reservation.productId, reservation.storeId)
        val updated = casUpdate(key) { current -> current.copy(reserved = current.reserved - reservation.quantity) }
            ?: error("release must never be rejected: reserved quantity can't exceed onHand")

        recordTransaction(reservation.productId, reservation.storeId, InventoryTransactionType.RELEASE, reservation.quantity, referenceId = reservation.referenceId)
        return ReleaseResult.Success(reservation, updated)
    }

    fun commit(reservationId: String): CommitReservationResult {
        val transitionOutcome = transitionReservation(reservationId, ReservationStatus.COMMITTED)
        val reservation = when (transitionOutcome) {
            is ReservationTransition.Success -> transitionOutcome.reservation
            ReservationTransition.NotFound -> return CommitReservationResult.NotFound
            ReservationTransition.NotActive -> return CommitReservationResult.NotActive
        }

        val key = SnapshotKey(reservation.productId, reservation.storeId)
        val updated = casUpdate(key) { current ->
            current.copy(onHand = current.onHand - reservation.quantity, reserved = current.reserved - reservation.quantity)
        } ?: error("commit must never be rejected: reserved quantity can't exceed onHand")

        recordTransaction(reservation.productId, reservation.storeId, InventoryTransactionType.COMMIT, reservation.quantity, referenceId = reservation.referenceId)
        return CommitReservationResult.Success(reservation, updated)
    }

    fun getSnapshot(productId: String, storeId: String): InventorySnapshot? = snapshots[SnapshotKey(productId, storeId)]

    fun listSnapshots(storeId: String? = null, productId: String? = null): List<InventorySnapshot> =
        snapshots.values.filter { (storeId == null || it.storeId == storeId) && (productId == null || it.productId == productId) }

    fun listTransactions(storeId: String? = null, productId: String? = null): List<InventoryTransaction> =
        synchronized(transactions) {
            transactions.filter { (storeId == null || it.storeId == storeId) && (productId == null || it.productId == productId) }
        }.sortedByDescending { it.createdAt }

    private sealed class ReservationTransition {
        data class Success(val reservation: Reservation) : ReservationTransition()
        data object NotFound : ReservationTransition()
        data object NotActive : ReservationTransition()
    }

    /** Atomically checks the reservation is ACTIVE and flips it to [newStatus] in one map operation. */
    private fun transitionReservation(reservationId: String, newStatus: ReservationStatus): ReservationTransition {
        var outcome: ReservationTransition? = null
        reservations.compute(reservationId) { _, existing ->
            when {
                existing == null -> {
                    outcome = ReservationTransition.NotFound
                    null
                }
                existing.status != ReservationStatus.ACTIVE -> {
                    outcome = ReservationTransition.NotActive
                    existing
                }
                else -> {
                    val updated = existing.copy(status = newStatus, updatedAt = System.currentTimeMillis())
                    outcome = ReservationTransition.Success(updated)
                    updated
                }
            }
        }
        return outcome!!
    }

    private fun recordTransaction(
        productId: String,
        storeId: String,
        type: InventoryTransactionType,
        quantity: Int,
        referenceId: String? = null,
        reason: String? = null,
        actorId: String? = null
    ): InventoryTransaction {
        val transaction = InventoryTransaction(
            productId = productId,
            storeId = storeId,
            type = type,
            quantity = quantity,
            referenceId = referenceId,
            reason = reason,
            actorId = actorId
        )
        transactions.add(transaction)
        return transaction
    }

    /**
     * Compare-and-swap update of a snapshot: reads the current value (or a
     * fresh zero-stock default), asks [mutate] to compute the next value,
     * and atomically swaps only if nothing else changed the entry in the
     * meantime - retrying otherwise. Returns null if [mutate] rejects the
     * update (e.g. insufficient stock).
     */
    private fun casUpdate(key: SnapshotKey, mutate: (InventorySnapshot) -> InventorySnapshot?): InventorySnapshot? {
        while (true) {
            val current = snapshots[key]
            val base = current ?: InventorySnapshot(key.productId, key.storeId, onHand = 0, reserved = 0)
            val mutated = mutate(base) ?: return null
            val updated = mutated.copy(version = base.version + 1)

            val applied = if (current == null) {
                snapshots.putIfAbsent(key, updated) == null
            } else {
                snapshots.replace(key, current, updated)
            }
            if (applied) return updated
        }
    }
}
