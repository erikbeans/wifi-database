package com.clickbeans.wifidatabase.ui.common

import android.content.Context
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

enum class CredentialFieldKind { Email, Password, NewPassword }

internal fun buildCredentialField(
    context: Context,
    kind: CredentialFieldKind,
    label: String,
    onValueChange: (String) -> Unit,
): TextInputLayout {
    val layout = TextInputLayout(context).apply {
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        hint = label
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val editText = TextInputEditText(layout.context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        imeOptions = EditorInfo.IME_ACTION_NEXT
        setSingleLine()
        inputType = when (kind) {
            CredentialFieldKind.Email ->
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            CredentialFieldKind.Password, CredentialFieldKind.NewPassword ->
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        }
        doOnTextChanged { text, _, _, _ ->
            onValueChange(text?.toString().orEmpty())
        }
    }
    layout.addView(editText)
    if (kind != CredentialFieldKind.Email) {
        layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    when (kind) {
        CredentialFieldKind.Email -> editText.setAutofillHints(android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS)
        CredentialFieldKind.Password -> editText.setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
        CredentialFieldKind.NewPassword -> editText.setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
    }
    return layout
}

@Composable
fun CredentialTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    kind: CredentialFieldKind,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            buildCredentialField(context, kind, label, onValueChange)
        },
        update = { layout ->
            val current = layout.editText?.text?.toString().orEmpty()
            if (current != value) {
                layout.editText?.setText(value)
            }
        },
    )
}
