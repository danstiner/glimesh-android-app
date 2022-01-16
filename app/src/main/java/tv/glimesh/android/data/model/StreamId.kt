package tv.glimesh.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StreamId(val id: Long) {
    override fun toString(): String = "StreamId($id)"
}
