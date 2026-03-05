package com.jonesmb.pulasmartagent.db.driver

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.jonesmb.pulasmartagent.db.SmartAgentDatabase

actual fun createSqlDriver(): SqlDriver = NativeSqliteDriver(
    SmartAgentDatabase.Schema, "smart_agent.db"
)