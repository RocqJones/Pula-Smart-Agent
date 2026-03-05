package com.jonesmb.pulasmartagent.db.driver

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.jonesmb.pulasmartagent.db.SmartAgentDatabase

lateinit var applicationContext: Context

actual fun createSqlDriver(): SqlDriver = AndroidSqliteDriver(
    SmartAgentDatabase.Schema, applicationContext, "smart_agent.db"
)