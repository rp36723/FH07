package com.example.seniordesignmobileapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.example.seniordesignmobileapp.ui.AggregatorApp
import com.example.seniordesignmobileapp.ui.theme.SeniorDesignMobileAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SeniorDesignMobileAppTheme {
                AggregatorApp(applicationContext = applicationContext)
            }
        }
    }
}
