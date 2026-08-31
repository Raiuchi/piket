package net.raiuchi.piket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class NativeReferenceScreen { TIMETABLE, SPEEDS }

@Composable
fun NativeTimetableScreen(data: NativeReferenceData, route: String, direction: String, close: () -> Unit) {
    val matching = remember(data, route, direction) {
        data.trainsFor(route, direction)
    }
    var selected by remember(matching) { mutableStateOf(matching.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    NativeReferenceScaffold("Расписание и время хода", close) {
        item {
            Box {
                OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
                    Text(selected?.let { "Поезд ${it.number} · ${it.title}" } ?: "Для участка поездов нет", Modifier.weight(1f))
                    Text("⌄")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    matching.forEach { train ->
                        DropdownMenuItem({ Text("${train.number} · ${train.title}") }, { selected = train; expanded = false })
                    }
                }
            }
        }
        selected?.let { train ->
            items(train.stops.windowed(2)) { pair ->
                val from = pair[0]
                val to = pair[1]
                val fromM = data.stationMeters(route, from.station, train.number)
                val toM = data.stationMeters(route, to.station, train.number)
                val seconds = secondsBetween(from.departure ?: from.arrival, to.arrival ?: to.departure)
                val speed = if (fromM != null && toM != null && seconds != null && seconds > 0)
                    (kotlin.math.abs(toM - fromM) / seconds * 3.6).roundToInt() else null
                Card(colors = CardDefaults.cardColors(containerColor = PiketPanel)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${from.station} → ${to.station}", fontWeight = FontWeight.Bold)
                        Text("${from.departure ?: from.arrival ?: "—"} → ${to.arrival ?: to.departure ?: "—"}", color = Color.LightGray)
                        Text(
                            if (speed == null) "Километраж уточняется" else "Средняя перегонная скорость: $speed км/ч",
                            color = if (speed == null) PiketYellow else PiketBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NativeSpeedReferenceScreen(data: NativeReferenceData, close: () -> Unit) {
    var selected by remember { mutableStateOf(data.speedRoutes.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    NativeReferenceScaffold("Справочник скоростей", close) {
        item {
            Box {
                OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
                    Text(selected?.let { "${it.train} · ${it.route}" } ?: "Нет данных", Modifier.weight(1f))
                    Text("⌄")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    data.speedRoutes.forEach { route -> DropdownMenuItem({ Text("${route.train} · ${route.route}") }, { selected = route; expanded = false }) }
                }
            }
        }
        selected?.let { route ->
            if (route.note.isNotBlank()) item { Text(route.note, color = Color.LightGray, fontSize = 12.sp) }
            route.groups.forEach { group ->
                item { Text(group.title, color = PiketRed, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                items(group.rows) { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = PiketPanel)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, Modifier.weight(1f))
                            row.mainSpeed?.let { SpeedBadge(it, PiketRedDark) }
                            row.sideSpeed?.let { Spacer(Modifier.width(7.dp)); SpeedBadge(it, Color(0xFF735411)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedBadge(value: Int, color: Color) = Box(Modifier.background(color, MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = 9.dp)) {
    Text(value.toString(), fontWeight = FontWeight.ExtraBold)
}

@Composable
private fun NativeReferenceScaffold(title: String, close: () -> Unit, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 34.sp, modifier = Modifier.clickable(onClick = close).padding(end = 14.dp))
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

private fun secondsBetween(from: String?, to: String?): Int? {
    fun parse(value: String?): Int? {
        if (value == null) return null
        val parts = value.split(':').mapNotNull(String::toIntOrNull)
        if (parts.size !in 2..3) return null
        return parts[0] * 3600 + parts[1] * 60 + parts.getOrElse(2) { 0 }
    }
    val start = parse(from) ?: return null
    var end = parse(to) ?: return null
    if (end < start) end += 24 * 3600
    return end - start
}
