package app.s4h.fomovoi.core.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = FomovoiDatabase.Schema,
            name = "fomovoi.db"
        )
    }
}
