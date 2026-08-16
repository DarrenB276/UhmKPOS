package com.uhmk.pos.core.repo

import androidx.room.withTransaction
import com.uhmk.pos.core.db.AppDatabase
import com.uhmk.pos.core.db.TicketEntity
import com.uhmk.pos.core.db.TicketLineEntity
import com.uhmk.pos.core.db.TicketWithLines
import com.uhmk.pos.core.model.Cart
import com.uhmk.pos.core.model.CartLine
import com.uhmk.pos.core.model.OrderType
import com.uhmk.pos.core.prefs.Session
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TicketRepository(private val db: AppDatabase) {

    private val dao get() = db.ticketDao()

    fun observeAll(): Flow<List<TicketWithLines>> = dao.observeAll()

    suspend fun hold(
        cart: Cart,
        title: String,
        session: Session,
        existingId: String? = null,
    ): String = db.withTransaction {
        require(cart.lines.isNotEmpty()) { "The current sale is empty" }
        val now = System.currentTimeMillis()
        val old = existingId?.let { dao.getWithLines(it)?.ticket }
        val id = old?.id ?: "tkt-${UUID.randomUUID()}"
        val ticket = TicketEntity(
            id = id,
            title = title.trim().ifBlank {
                cart.orderLabel.trim().ifBlank { "Ticket ${now.toString().takeLast(4)}" }
            },
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            ownerId = session.uid,
            ownerName = session.displayName.ifBlank { "Staff" },
            discountCentavos = cart.effectiveDiscount,
            note = cart.note,
            tenderedCentavos = cart.tendered,
            paymentMethod = cart.paymentMethod,
            orderType = cart.orderType.name,
            orderLabel = cart.orderLabel,
        )
        dao.upsert(ticket)
        dao.deleteLines(id)
        dao.insertLines(cart.lines.map { line ->
            TicketLineEntity(
                id = "$id-${line.item.id}",
                ticketId = id,
                itemId = line.item.id,
                tier = line.tier,
                qty = line.qty,
            )
        })
        id
    }

    suspend fun load(id: String): Cart? = db.withTransaction {
        val held = dao.getWithLines(id) ?: return@withTransaction null
        val lines = held.lines.mapNotNull { saved ->
            db.itemDao().getById(saved.itemId)?.takeIf { it.active }?.let { item ->
                CartLine(item = item, tier = saved.tier, qty = saved.qty.coerceAtLeast(1))
            }
        }
        if (lines.isEmpty()) return@withTransaction null
        held.ticket.let { ticket ->
            Cart(
                lines = lines,
                discount = ticket.discountCentavos,
                note = ticket.note,
                tendered = ticket.tenderedCentavos,
                paymentMethod = ticket.paymentMethod,
                orderType = OrderType.from(ticket.orderType),
                orderLabel = ticket.orderLabel,
            )
        }
    }

    suspend fun delete(id: String) = dao.delete(id)
}
