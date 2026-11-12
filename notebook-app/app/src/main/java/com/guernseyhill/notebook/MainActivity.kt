package com.guernseyhill.notebook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.guernseyhill.notebook.ui.NotebookScreen
import com.guernseyhill.notebook.ui.NotebookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NotebookTheme { NotebookScreen() } }
    }
}
