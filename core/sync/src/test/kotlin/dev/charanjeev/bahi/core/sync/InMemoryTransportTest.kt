package dev.charanjeev.bahi.core.sync

/** Runs [SyncTransportContractTest] against the fake -- everywhere but M4b. */
class InMemoryTransportTest : SyncTransportContractTest() {
    override fun createTransport(): SyncTransport = InMemoryTransport()
}
