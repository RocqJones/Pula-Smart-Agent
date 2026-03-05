package com.jonesmb.pulasmartagent.db.adapters

import app.cash.sqldelight.ColumnAdapter
import com.jonesmb.pulasmartagent.domain.model.status.SyncStatus
import com.jonesmb.pulasmartagent.domain.model.status.AttachmentUploadStatus

val syncStatusAdapter = object : ColumnAdapter<SyncStatus, String> {
    override fun decode(databaseValue: String): SyncStatus =
        SyncStatus.valueOf(databaseValue)

    override fun encode(value: SyncStatus): String =
        value.name
}

val attachmentUploadStatusAdapter = object : ColumnAdapter<AttachmentUploadStatus, String> {
    override fun decode(databaseValue: String): AttachmentUploadStatus =
        AttachmentUploadStatus.valueOf(databaseValue)

    override fun encode(value: AttachmentUploadStatus): String =
        value.name
}

