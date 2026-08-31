package dev.charanjeev.bahi.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * The op format is what one device writes and another device -- possibly a
 * different version of the app -- reads back out of an append-only log. Every
 * test here is about that gap: a batch this version cannot understand, a field
 * it has never heard of, a table it does not have.
 */
class SyncOpTest {

    private val json = Json

    private fun op(
        table: String = SyncTable.TRANSACTIONS.tableName,
        rowId: String = "h1:e69daf8267b11c3689db7a3e6d95f3fb#0",
        payload: kotlinx.serialization.json.JsonObject? = buildJsonObject {
            put("amount_minor", JsonPrimitive(-45_000))
            put("description", JsonPrimitive("Coffee Shop"))
        },
    ) = SyncOp(
        table = table,
        rowId = rowId,
        remoteRevision = 7,
        deviceId = "device-a",
        updatedAt = 1_700_000_000_000,
        payload = payload,
    )

    @Test
    fun `an op round-trips through json unchanged`() {
        val restored = json.decodeFromString<SyncOp>(json.encodeToString(op()))

        assertThat(restored).isEqualTo(op())
    }

    /**
     * The forward-compatibility claim in SyncOp's KDoc, made load-bearing.
     * A field written by a newer version has to survive being read and
     * re-encoded by this one, or an older device in a two-device pair quietly
     * strips it every time it syncs.
     */
    @Test
    fun `a field this version has never heard of survives a round trip`() {
        val fromTheFuture = """
            {"table":"transactions","rowId":"r1","remoteRevision":7,"deviceId":"device-a",
             "updatedAt":1700000000000,"payload":{"amount_minor":-45000,"exchange_rate":"1.09"}}
        """.trimIndent()

        val reEncoded = json.encodeToString(json.decodeFromString<SyncOp>(fromTheFuture))

        assertThat(reEncoded).contains("\"exchange_rate\":\"1.09\"")
    }

    @Test
    fun `an op with no payload is a tombstone`() {
        assertThat(op(payload = null).isTombstone).isTrue()
    }

    /**
     * An empty payload is not a tombstone. It is a row with no synced fields
     * left differing, which the resolver has to be able to tell from a
     * deletion -- applying the wrong one of those loses the row.
     */
    @Test
    fun `an op with an empty payload is not a tombstone`() {
        assertThat(op(payload = buildJsonObject { }).isTombstone).isFalse()
    }

    @Test
    fun `a batch defaults to the current format version`() {
        assertThat(OpBatch(deviceId = "device-a", seq = 1, ops = listOf(op())).version)
            .isEqualTo(OP_FORMAT_VERSION)
    }

    @Test
    fun `a batch from a future format version is not readable`() {
        val batch = OpBatch(deviceId = "device-a", seq = 1, ops = emptyList(), version = OP_FORMAT_VERSION + 1)

        assertThat(batch.isReadable).isFalse()
    }

    /**
     * A batch written before a format bump stays in the log forever -- the
     * remote is append-only (§8.3) -- so the older version has to stay
     * readable rather than merely parseable.
     */
    @Test
    fun `a batch from an older format version is still readable`() {
        val batch = OpBatch(deviceId = "device-a", seq = 1, ops = emptyList(), version = OP_FORMAT_VERSION - 1)

        assertThat(batch.isReadable).isTrue()
    }

    @Test
    fun `a batch round-trips its version rather than defaulting it back`() {
        val encoded = json.encodeToString(OpBatch("device-a", seq = 1, ops = listOf(op()), version = 99))

        assertThat(json.decodeFromString<OpBatch>(encoded).version).isEqualTo(99)
    }

    @Test
    fun `every syncing table is resolvable from its sql name`() {
        assertThat(SyncTable.entries.map { SyncTable.of(it.tableName) })
            .containsExactlyElementsIn(SyncTable.entries)
            .inOrder()
    }

    /**
     * The branch that keeps one unknown table from stopping sync for the other
     * three. It has to be a null, not a throw: the op still deserialises, and
     * the engine still has to be able to walk past it.
     */
    @Test
    fun `a table this version does not have resolves to null rather than throwing`() {
        assertThat(SyncTable.of("accounts")).isNull()
    }

    @Test
    fun `an op naming an unknown table still deserialises`() {
        val restored = json.decodeFromString<SyncOp>(json.encodeToString(op(table = "accounts")))

        assertThat(restored.table).isEqualTo("accounts")
        assertThat(SyncTable.of(restored.table)).isNull()
    }

    /**
     * Categories before transactions, and both before budgets and rules:
     * transactions.category_id, budgets.category_id and
     * category_rules.category_id are all foreign keys into categories, so a
     * batch applied in declaration order never inserts a child before its
     * parent.
     */
    @Test
    fun `tables are declared parents first`() {
        assertThat(SyncTable.entries.map(SyncTable::tableName))
            .containsExactly("categories", "transactions", "budgets", "category_rules")
            .inOrder()
    }
}
