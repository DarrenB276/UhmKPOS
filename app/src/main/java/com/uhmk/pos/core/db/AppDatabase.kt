package com.uhmk.pos.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ItemEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        UserEntity::class,
        NoticeEntity::class,
        AuditLogEntity::class,
        TicketEntity::class,
        TicketLineEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun saleDao(): SaleDao
    abstract fun userDao(): UserDao
    abstract fun noticeDao(): NoticeDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun ticketDao(): TicketDao

    companion object {

        /**
         * v1 held a small hand-built catalogue. v2 carries the full imported catalogue,
         * where the student price lives on the item instead of in a separate "STU" row.
         *
         * Sales are money records and are migrated in place, never dropped. Only the catalogue is
         * cleared, so the seeder can repopulate it — past sales keep their own snapshot of the
         * name, price and cost, so history stays readable even though the item rows changed.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN sku TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN trackStock INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_category ON items(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_sku ON items(sku)")

                db.execSQL("ALTER TABLE sale_lines ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sale_lines ADD COLUMN costKnown INTEGER NOT NULL DEFAULT 1")

                db.execSQL("ALTER TABLE sales ADD COLUMN receiptNo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN unknownCostCentavos INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'Cash'")
                db.execSQL("ALTER TABLE sales ADD COLUMN tenderedCentavos INTEGER NOT NULL DEFAULT 0")

                // Give existing sales receipt numbers in the order they happened.
                db.execSQL(
                    """
                    UPDATE sales SET receiptNo = (
                        SELECT COUNT(*) FROM sales AS s2 WHERE s2.soldAt <= sales.soldAt
                    )
                    """.trimIndent()
                )

                db.execSQL("DELETE FROM items")
            }
        }

        /**
         * v3 distinguishes a missing cost from a genuine zero-cost service. Without this flag a
         * cooking fee would incorrectly show as unknown profit instead of full take-home.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN costKnown INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE items SET costKnown = 1 " +
                        "WHERE costCentavos > 0 OR id = 'itm-cooking-fee'"
                )
                db.execSQL("ALTER TABLE sales ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN voidedAt INTEGER DEFAULT NULL")
                db.execSQL("UPDATE sales SET updatedAt = soldAt WHERE updatedAt = 0")
            }
        }

        /** v4 records whether each receipt was dine-in or takeout. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sales ADD COLUMN orderType TEXT NOT NULL DEFAULT 'UNSPECIFIED'"
                )
                db.execSQL("ALTER TABLE sales ADD COLUMN orderLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 keeps voided and returned receipts visible in the order-history audit trail. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN returnedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE sales ADD COLUMN returnReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notices ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notices ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("UPDATE notices SET updatedAt = sentAt WHERE updatedAt = 0")
            }
        }

        /** v6 distinguishes manually saved tallies and records tamper-evident edit summaries. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN source TEXT NOT NULL DEFAULT 'POS'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audit_logs (
                        id TEXT NOT NULL PRIMARY KEY,
                        action TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        businessDateEpochDay INTEGER NOT NULL,
                        actorId TEXT NOT NULL,
                        actorName TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        beforeSummary TEXT NOT NULL,
                        afterSummary TEXT NOT NULL,
                        dirty INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_occurredAt ON audit_logs(occurredAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_logs_businessDateEpochDay ON audit_logs(businessDateEpochDay)")
            }
        }

        /** v7 adds account-targeted notices and restart-safe held tickets. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notices ADD COLUMN targetUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notices ADD COLUMN targetName TEXT NOT NULL DEFAULT 'Everyone'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tickets (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        ownerId TEXT NOT NULL,
                        ownerName TEXT NOT NULL,
                        discountCentavos INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        tenderedCentavos INTEGER NOT NULL,
                        paymentMethod TEXT NOT NULL,
                        orderType TEXT NOT NULL,
                        orderLabel TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tickets_updatedAt ON tickets(updatedAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ticket_lines (
                        id TEXT NOT NULL PRIMARY KEY,
                        ticketId TEXT NOT NULL,
                        itemId TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        qty INTEGER NOT NULL,
                        FOREIGN KEY(ticketId) REFERENCES tickets(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ticket_lines_ticketId ON ticket_lines(ticketId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ticket_lines_itemId ON ticket_lines(itemId)")
            }
        }

        /**
         * v8 stamps the originating device onto each sale.
         *
         * Receipt numbers come from the local database, so a second phone restarts at 1 and every
         * number collides once both devices sync. Existing sales keep an empty code and keep
         * printing as plain numbers — they all came from one device, so nothing is ambiguous.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN deviceCode TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v9 lets the notices list hold the app's own alerts, not just staff messages.
         *
         * Missing-cost and passcode warnings were previously posted straight to the system tray
         * and nowhere else, so the in-app list read "No notices" while the phone was showing them.
         * Existing rows are all human-sent, hence the MESSAGE default.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notices ADD COLUMN kind TEXT NOT NULL DEFAULT 'MESSAGE'")
            }
        }

        /**
         * v10 replaces unsafe whole-row catalogue uploads with versioned, field-level changes.
         *
         * Older builds marked both starter rows and genuine edits with the same `dirty` flag, so
         * there is no trustworthy way to distinguish them during migration. Quarantine every
         * legacy item once, pull the cloud catalogue, and let only edits made by v10+ upload.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN cloudVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN pendingFields TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN pendingStockDelta INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN stockAbsolutePending INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE items SET dirty = 0, pendingFields = '', " +
                        "pendingStockDelta = 0, stockAbsolutePending = 0"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "uhmk-pos.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .build()
    }
}
