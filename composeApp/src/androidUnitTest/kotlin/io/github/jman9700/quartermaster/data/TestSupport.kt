package io.github.jman9700.quartermaster.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jman9700.quartermaster.db.QuarterMasterDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

class FakeEnvironment(var now: Long = 1_000L, var date: LocalDate = LocalDate(2026, 10, 1), private val prefix: String = "id") : Environment {
    private var counter = 0
    override fun nowMillis() = now++
    override fun today() = date
    override fun newId() = "$prefix-${++counter}"
}

class FakeImageStore : ImageStore {
    val files = mutableMapOf<String, ByteArray>()
    private var counter = 0
    override suspend fun save(bytes: ByteArray) = "img-${++counter}.jpg".also { files[it] = bytes }
    override suspend fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    override suspend fun read(name: String) = files[name]
    override fun exists(name: String) = name in files
    override fun model(name: String): Any? = files[name]
}

fun inMemoryRepository(env: Environment = FakeEnvironment()): InventoryRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    QuarterMasterDatabase.Schema.create(driver)
    return InventoryRepository(driver, Dispatchers.Unconfined, env)
}
