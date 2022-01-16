package tv.glimesh.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Streamer(
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)
