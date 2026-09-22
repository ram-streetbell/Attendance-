package com.streetbell.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdminHome() }
    }

    @Composable private fun AdminHome() {
        var tab by remember { mutableStateOf("Dashboard") }
        Scaffold(topBar = { TopAppBar(title = { Text("Attendance Admin") }) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    listOf("Dashboard", "Employees", "Devices", "Attendance", "Reports").forEach { item ->
                        NavigationRailItem(selected = tab == item, onClick = { tab = item }, icon = {}, label = { Text(item) })
                    }
                }
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(tab, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    when (tab) {
                        "Dashboard" -> Dashboard()
                        "Employees" -> EmptySection("Employees and face enrollment")
                        "Devices" -> EmptySection("Device pairing and business assignment")
                        "Attendance" -> EmptySection("Live and historical attendance")
                        "Reports" -> EmptySection("Daily, monthly and exportable reports")
                    }
                }
            }
        }
    }

    @Composable private fun Dashboard() {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Present", "0")
            Stat("Absent", "0")
            Stat("Devices online", "0")
            Stat("Events today", "0")
        }
    }
    @Composable private fun Stat(label: String, value: String) { Card(Modifier.width(180.dp)) { Column(Modifier.padding(18.dp)) { Text(label); Text(value, style = MaterialTheme.typography.headlineMedium) } } }
    @Composable private fun EmptySection(text: String) { Card { Text(text, Modifier.padding(20.dp)) } }
}
