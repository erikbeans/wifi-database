package com.clickbeans.wifidatabase

import android.app.Activity
import com.clickbeans.wifidatabase.ui.common.CredentialFieldKind
import com.clickbeans.wifidatabase.ui.common.buildCredentialField
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class CredentialFieldTest {

    private fun withActivity(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.setup()
        block(controller.get())
        controller.destroy()
    }

    @Test
    fun `password field inflates under the app theme`() {
        withActivity { activity ->
            val values = mutableListOf<String>()
            val layout = buildCredentialField(
                activity,
                CredentialFieldKind.Password,
                "Password",
            ) { values.add(it) }
            assertNotNull(layout.editText)
            assertEquals("Password", layout.hint)
            layout.editText?.setText("hello-world")
            assertEquals(listOf("hello-world"), values)
        }
    }

    @Test
    fun `email field inflates with email autofill hint`() {
        withActivity { activity ->
            val layout = buildCredentialField(activity, CredentialFieldKind.Email, "Email") {}
            assertNotNull(layout.editText)
            assertEquals(listOf("emailAddress"), layout.editText?.autofillHints?.toList())
        }
    }

    @Test
    fun `new password field exposes password toggle and hint`() {
        withActivity { activity ->
            val layout = buildCredentialField(activity, CredentialFieldKind.NewPassword, "New password") {}
            assertEquals(
                "endIconMode",
                TextInputLayout.END_ICON_PASSWORD_TOGGLE,
                layout.endIconMode,
            )
            assertTrue(
                "autofillHints must contain password, was: ${layout.editText?.autofillHints?.toList()}",
                layout.editText?.autofillHints?.contains("password") == true,
            )
        }
    }
}
