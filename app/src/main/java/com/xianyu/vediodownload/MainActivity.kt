package com.xianyu.vediodownload

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.xianyu.vediodownload.ui.designsystem.theme.HyperOSTheme
import com.xianyu.vediodownload.ui.screens.MainScreen

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            HyperOSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        onNavigateToLogin = {
                            startActivity(Intent(this, DouyinLoginActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}
