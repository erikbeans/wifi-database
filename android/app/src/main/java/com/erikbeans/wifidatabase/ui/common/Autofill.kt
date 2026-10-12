package com.erikbeans.wifidatabase.ui.common

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

fun Modifier.emailAutofill(): Modifier = semantics {
    contentType = ContentType.EmailAddress
}

fun Modifier.passwordAutofill(newPassword: Boolean): Modifier = semantics {
    contentType = if (newPassword) ContentType.NewPassword else ContentType.Password
}
