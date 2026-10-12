package com.erikbeans.wifidatabase.ui.common

import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

enum class CredentialFieldKind { Email, Password, NewPassword }

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
            val layout = TextInputLayout(context).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                hint = label
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val editText = TextInputEditText(layout.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setSingleLine()
                when (kind) {
                    CredentialFieldKind.Email -> {
                        inputType = EditorInfo.TYPE_CLASS_TEXT or
                            EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        setAutofillHints(android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS)
                    }
                    CredentialFieldKind.Password -> {
                        inputType = EditorInfo.TYPE_CLASS_TEXT or
                            EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
                    }
                    CredentialFieldKind.NewPassword -> {
                        inputType = EditorInfo.TYPE_CLASS_TEXT or
                            EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
                    }
                }
                if (kind != CredentialFieldKind.Email) {
                    layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                doOnTextChanged { text, _, _, _ ->
                    onValueChange(text?.toString().orEmpty())
                }
            }
            layout.addView(editText)
            layout
        },
        update = { layout ->
            val current = layout.editText?.text?.toString().orEmpty()
            if (current != value) {
                layout.editText?.setText(value)
            }
        },
    )
}
