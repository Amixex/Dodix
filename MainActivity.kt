package com.dodix.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Оранжевый Додо
val DodoOrange = Color(0xFFFF6900)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    // Заглушка данных. В реальности здесь будет ViewModel и Flow из Room
    val newsItems = remember { listOf(
        NewsItem("Открытие новой школы в Фрязино", "Школа №4 открывает двери после ремонта", "Предложить пиццу классам на экскурсию", "Новость о школе напрямую касается родителей и детей."),
        NewsItem("Ярмарка выходного дня", "На площади пройдет фермерская ярмарка", "Сделать промо 'Пицца для пикника'", "Люди будут гулять и захотят перекусить.")
    )}

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "🍕 Додо Радар",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = DodoOrange
        )
        Text(text = "Фрязино • Сегодня", fontSize = 14.sp, color = Color.Gray)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(newsItems) { item ->
                NewsCard(item)
            }
        }
    }
}

@Composable
fun NewsCard(item: NewsItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = item.description, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(text = "💡 Идея: ${item.idea}", color = DodoOrange, fontWeight = FontWeight.Medium)
            Text(text = "Причина: ${item.reason}", fontSize = 12.sp, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { /* Открыть ссылку */ }, colors = ButtonDefaults.buttonColors(containerColor = DodoOrange)) {
                Text("Открыть новость")
            }
        }
    }
}

data class NewsItem(
    val title: String,
    val description: String,
    val idea: String,
    val reason: String
)
