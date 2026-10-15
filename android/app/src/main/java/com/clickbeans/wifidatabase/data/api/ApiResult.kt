package com.clickbeans.wifidatabase.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val message: String, val code: Int? = null) : ApiResult<Nothing>
}

const val EMAIL_UNVERIFIED = "email_unverified"

private val json = Json { ignoreUnknownKeys = true }

@PublishedApi
internal fun HttpException.detailMessage(): String? {
    return try {
        val body = response()?.errorBody()?.string() ?: return null
        val fields = json.parseToJsonElement(body).jsonObject
        fields["message"]?.jsonPrimitive?.contentOrNull
            ?: fields["detail"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
}

@Suppress("TooGenericExceptionCaught")
inline fun <T> apiCall(block: () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: HttpException) {
    ApiResult.Failure(e.detailMessage() ?: genericMessage(e.code()), e.code())
} catch (e: IOException) {
    ApiResult.Failure("Network error — check your connection")
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult.Failure("Something went wrong")
}

fun genericMessage(code: Int): String = when (code) {
    400 -> "That request was rejected"
    401 -> "Please sign in again"
    403 -> "You don't have permission to do that"
    404 -> "Not found — it may have been deleted"
    422 -> "Some of that input was invalid"
    429 -> "You're going too fast — try again later"
    else -> "Something went wrong (code $code)"
}
