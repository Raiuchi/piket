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
fun NativeTimetableScreen(
    data: NativeReferenceData,
    route: String,
    direction: String,
    overrides: Map<String, String>,
    updateTime: (String, String?) -> Unit,
    resetTrain: (String) -> Unit,
    close: () -> Unit
) {
    val matching = remember(data, route, direction) {
        data.trainsFor(route, direction)
    }
    var selected by remember(matching) { mutableStateOf(matching.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
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
            items((0 until train.stops.lastIndex).toList()) { index ->
                val from = train.stops[index]
                val to = train.stops[index + 1]
                val fromTime = effectiveTime(train, index, "dep", from.departure ?: from.arrival, overrides)
                val toTime = effectiveTime(train, index + 1, "arr", to.arrival ?: to.departure, overrides)
                val fromM = data.stationMeters(route, from.station, train.number)
                val toM = data.stationMeters(route, to.station, train.number)
                val seconds = secondsBetween(fromTime, toTime)
                val speed = if (fromM != null && toM != null && seconds != null && seconds > 0)
                    (kotlin.math.abs(toM - fromM) / seconds * 3.6).roundToInt() else null
                Card(colors = CardDefaults.cardColors(containerColor = PiketPanel)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${from.station} → ${to.station}", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScheduleTimeButton("отпр", fromTime) { editing = TimeEdit(train.number, index, "dep", fromTime) }
                            ScheduleTimeButton("приб", toTime) { editing = TimeEdit(train.number, index + 1, "arr", toTime) }
                        }
                        Text(
                            if (speed == null) "Километраж уточняется" else "Средняя перегонная скорость: $speed км/ч",
                            color = if (speed == null) PiketYellow else PiketBlue
                        )
                    }
                }
            }
            item {
                OutlinedButton({ resetTrain(train.number) }, Modifier.fillMaxWidth()) { Text("Сбросить правки времени поезда ${train.number}") }
            }
        }
    }
    editing?.let { edit ->
        PremiumTimeDialog(edit.value, { editing = null }, { value -> updateTime(edit.key, value); editing = null })
    }
}

private data class TimeEdit(val train: String, val index: Int, val kind: String, val value: String?) {
    val key get() = "$train:$index:$kind"
}

private fun effectiveTime(train: TimetableTrain, index: Int, kind: String, original: String?, overrides: Map<String, String>) =
    overrides["${train.number}:$index:$kind"] ?: overrides["${train.number}:$index"] ?: original

@Composable
private fun ScheduleTimeButton(label: String, value: String?, click: () -> Unit) {
    OutlinedButton(click, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, color = Color.Gray)
            Text(value ?: "—", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PremiumTimeDialog(initial: String?, dismiss: () -> Unit, save: (String?) -> Unit) {
    val parts = initial?.split(':').orEmpty()
    var hour by remember { mutableIntStateOf(parts.getOrNull(0)?.toIntOrNull() ?: 0) }
    var minute by remember { mutableIntStateOf(parts.getOrNull(1)?.toIntOrNull() ?: 0) }
    var half by remember { mutableStateOf(parts.getOrNull(2) == "30") }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = PiketPanel,
        title = { Text("Время станции", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeStepper(hour, { hour = (hour + 23) % 24 }, { hour = (hour + 1) % 24 })
                    Text(":", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    TimeStepper(minute, { minute = (minute + 59) % 60 }, { minute = (minute + 1) % 60 })
                }
                FilterChip(half, { half = !half }, { Text(if (half) "Полминуты · :30" else "Полминуты · :00") })
            }
        },
        confirmButton = { Button({ save("%02d:%02d%s".format(hour, minute, if (half) ":30" else "")) }, colors = ButtonDefaults.buttonColors(containerColor = PiketRed)) { Text("Установить") } },
        dismissButton = { Row { TextButton({ save(null) }) { Text("Сбросить") }; TextButton(dismiss) { Text("Отмена") } } }
    )
}

@Composable
private fun TimeStepper(value: Int, down: () -> Unit, up: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("＋", Modifier.clickable(onClick = up).padding(10.dp), color = PiketRed, fontSize = 25.sp)
        Text("%02d".format(value), fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
        Text("−", Modifier.clickable(onClick = down).padding(10.dp), color = PiketBlue, fontSize = 25.sp)
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
