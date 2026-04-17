package com.example.seniordesignmobileapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.seniordesignmobileapp.ui.AggregatorApp
import com.example.seniordesignmobileapp.ui.theme.SeniorDesignMobileAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeniorDesignMobileAppTheme {
                AggregatorApp(applicationContext = applicationContext)
            }
        }
    }
}
