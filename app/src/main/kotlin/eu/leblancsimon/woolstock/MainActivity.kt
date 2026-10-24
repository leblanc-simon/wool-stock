package eu.leblancsimon.woolstock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.leblancsimon.woolstock.ui.WoolStockNavHost
import eu.leblancsimon.woolstock.ui.WoolStockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WoolStockTheme {
                WoolStockNavHost()
            }
        }
    }
}
