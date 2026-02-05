package app.s4h.fomovoi.core.data.local

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DatabaseDriverFactory): FomovoiDatabase {
    val driver = driverFactory.createDriver()
    return FomovoiDatabase(driver)
}
