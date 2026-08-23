package dev.charanjeev.bahi.core.importer

/** Shared by every test class reading a fixture from src/test/resources/csv/. */
internal fun loadCsvFixture(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/csv/$name")) { "Missing fixture: $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
