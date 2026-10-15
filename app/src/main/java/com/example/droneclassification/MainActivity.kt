package com.example.droneclassification

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.droneclassification.ui.ClassificationScreen
import com.example.droneclassification.ui.theme.DroneClassificationTheme
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val viewModel: DroneClassificationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroneClassificationTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ClassificationScreen(viewModel)
                }
            }
        }
    }

    // NOTE: We intentionally do NOT override onStop() to stop classification.
    // The foreground service (ClassificationService) keeps inference alive
    // when the screen turns off or the app goes to background.
    // Classification only stops when the user explicitly presses "ABORT"
    // or the ViewModel is cleared (activity destroyed for real).
}
