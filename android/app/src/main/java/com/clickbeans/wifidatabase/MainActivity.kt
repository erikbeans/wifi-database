package com.clickbeans.wifidatabase

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.clickbeans.wifidatabase.ui.App
import com.clickbeans.wifidatabase.ui.theme.WiFiDatabaseTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WiFiDatabaseTheme {
                App()
            }
        }
    }
}
