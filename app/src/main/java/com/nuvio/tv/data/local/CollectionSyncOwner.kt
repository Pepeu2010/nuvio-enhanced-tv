package com.nuvio.tv.data.local

import kotlinx.serialization.json.JsonArray

internal data class CollectionSyncOwner(val userId: String, val profileId: Int, val backendUrl: String)
internal data class CollectionSyncApplication(val changed: Boolean, val upload: Pair<Long, JsonArray>?)
