package net.raiuchi.piket

import android.app.Application
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class PiketTab(val title: String, val symbol: String) {
    TRIP("Поездка", "◉"), LIST("Список", "☰"), SETTINGS("Настройки", "⚙")
}

class PiketViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = PiketRepository(app)
    var restrictions by mutableStateOf(repository.loadRestrictions()); private set
    var settings by mutableStateOf(repository.loadSettings()); private set
    var scheduleOverrides by mutableStateOf(repository.loadScheduleOverrides()); private set
    var snapshot by mutableStateOf(repository.loadSnapshot()); private set
    var route by mutableStateOf(snapshot.route); private set
    var direction by mutableStateOf(snapshot.direction); private set
    var journey by mutableStateOf<String?>(null); private set
    var manualOfficialM by mutableStateOf<Double?>(null); private set
    val referenceData = NativeReferenceData(app)
    val routes: List<String> = runCatching {
        app.assets.open("data/routes.json").bufferedReader().use { NativeRouteEngine.fromJson(it.readText()).labels() }
    }.getOrDefault(listOf("СпбГл - Москва"))
    val routeChoices = NativeRouteCatalog.choices

    init { viewModelScope.launch { while (isActive) { snapshot = repository.loadSnapshot(); delay(500) } } }
    fun selectRoute(choice: NativeRouteCatalog.Choice) {
        choice.fixedDirection?.let { direction = it }
        val value = choice.start(direction)
        if (route != value) manualOfficialM = null
        route = value
        journey = choice.journey
    }
    fun selectDirection(value: String) {
        if (journey != null) return
        if (direction == value) return
        val choice = NativeRouteCatalog.forInternalRoute(route)
        manualOfficialM = null
        direction = value
        route = choice.start(value)
        journey = null
    }
    fun selectJourney(value: String?) {
        journey = value; manualOfficialM = null
        when (value) {
            "819" -> { route = "Волховстрой - Чудово"; direction = "obratno" }
            "820" -> { route = "Горы - Петрозаводск"; direction = "obratno" }
        }
    }
    fun setManualCalibration(value: Double) { manualOfficialM = value }
    fun add(item: RestrictionRecord): Boolean { val next = restrictions + item; return repository.saveRestrictions(next).also { if (it) restrictions = next } }
    fun remove(id: String): Boolean { val next = restrictions.filterNot { it.id == id }; return repository.saveRestrictions(next).also { if (it) restrictions = next } }
    fun updateSettings(value: PiketSettings) { if (repository.saveSettings(value)) settings = value }
    fun updateScheduleTime(key: String, value: String?) {
        val next = scheduleOverrides.toMutableMap().apply { if (value == null) remove(key) else put(key, value) }
        if (repository.saveScheduleOverrides(next)) scheduleOverrides = next
    }
    fun resetSchedule(trainNumber: String) {
        val next = scheduleOverrides.filterKeys { !it.startsWith("$trainNumber:") }
        if (repository.saveScheduleOverrides(next)) scheduleOverrides = next
    }
}

@Composable
fun PiketApp(
    onKeepScreen: (Boolean) -> Unit,
    onStart: (NativeUiConfig) -> Unit,
    onStop: () -> Unit,
    onRecalibrate: (NativeUiConfig) -> Unit,
    model: PiketViewModel = viewModel()
) {
    var tab by rememberSaveable { mutableStateOf(PiketTab.TRIP) }
    var calibrating by remember { mutableStateOf(false) }
    var referenceScreen by rememberSaveable { mutableStateOf<NativeReferenceScreen?>(null) }
    LaunchedEffect(model.settings.keepScreenOn) { onKeepScreen(model.settings.keepScreenOn) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (referenceScreen == null) PiketNavigation(tab) { tab = it } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(
            Brush.verticalGradient(listOf(Color(0xFF080A0E), Color(0xFF030405)))
        )) {
            when (referenceScreen) {
                NativeReferenceScreen.TIMETABLE -> NativeTimetableScreen(
                    model.referenceData, model.route, model.direction, model.scheduleOverrides,
                    model.snapshot.route, model.snapshot.officialM,
                    model::updateScheduleTime, model::resetSchedule
                ) { referenceScreen = null }
                NativeReferenceScreen.SPEEDS -> NativeSpeedReferenceScreen(model.referenceData) { referenceScreen = null }
                null -> when (tab) {
                    PiketTab.TRIP -> TripScreen(model, { calibrating = true }, onStart, onStop)
                    PiketTab.LIST -> RestrictionScreen(model)
                    PiketTab.SETTINGS -> SettingsScreen(model) { referenceScreen = it }
                }
            }
        }
    }
    if (calibrating) CalibrationDialog(model, { calibrating = false }) { official ->
        model.setManualCalibration(official)
        val config = NativeUiConfig(model.route, model.direction, official, model.restrictions, model.settings.leadM, model.settings.sound, model.settings.vibration, journey = model.journey)
        if (model.snapshot.active) onRecalibrate(config)
        calibrating = false
    }
}

@Composable
private fun TripScreen(model: PiketViewModel, calibrate: () -> Unit, onStart: (NativeUiConfig) -> Unit, onStop: () -> Unit) {
    val state = model.snapshot
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BrandHeader(state) }
        item { RouteSelector(model) }
        item { Speedometer(state.speedKmh) }
        item { PositionCard(state) }
        if (state.alertId != null) item { AlertCard(state, model.restrictions.firstOrNull { it.id == state.alertId }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (state.active) onStop()
                        else {
                            val manual = model.manualOfficialM
                            if (manual == null) calibrate()
                            else onStart(NativeUiConfig(model.route, model.direction, manual, model.restrictions, model.settings.leadM, model.settings.sound, model.settings.vibration, journey = model.journey))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.active) Color(0xFF761626) else PiketRed),
                    modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(17.dp)
                ) { Text(if (state.active) "■ Стоп" else "▶ Старт", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = calibrate, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(17.dp)) {
                    Text("⊕ Калибровка", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { NativeStatusCard(state) }
    }
}

@Composable
private fun BrandHeader(state: TripSnapshot) = Box(
    Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(2.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF090B10), Color(0xFF170B10), Color(0xFF25090F))))
        .border(width = 1.dp, color = Color(0xFF4B111C), shape = RoundedCornerShape(2.dp))
) {
    Canvas(Modifier.matchParentSize()) {
        drawLine(Color(0x332E91FF), Offset(size.width * .30f, 0f), Offset(size.width * .43f, size.height), 2f)
        drawLine(Color(0x44E71938), Offset(size.width * .62f, 0f), Offset(size.width * .51f, size.height), 3f)
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(54.dp).shadow(16.dp, RoundedCornerShape(14.dp))
                .background(Color(0xFF07090D), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF8E2031), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Image(painterResource(R.drawable.ic_launcher_art), null, Modifier.fillMaxSize().padding(4.dp)) }
        Column(Modifier.padding(start = 14.dp)) {
            Text("ПИКЕТ", fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("КОНТРОЛЬ ОГРАНИЧЕНИЙ", fontSize = 8.sp, letterSpacing = 2.4.sp, color = Color(0xFFB8C6D5))
        }
        Spacer(Modifier.weight(1f))
        val color = if (state.source == "native-gps") Color(0xFF31DB83) else Color(0xFF7E8B9B)
        Surface(color = Color(0xFF151A22), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF35404C))) {
            Text(if (state.active) "● GPS вкл" else "● GPS выкл", color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun RouteSelector(model: PiketViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(.82f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF151A22)).border(1.dp, Color(0xFF303844), RoundedCornerShape(16.dp))) {
            listOf("tuda" to "Туда", "obratno" to "Обратно").forEach { (value, title) ->
                Text(title, modifier = Modifier.weight(1f).clickable(enabled = model.journey == null) { model.selectDirection(value) }.background(if (model.direction == value) Brush.horizontalGradient(listOf(Color(0xFF7B0D20), PiketRed)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))).padding(vertical = 14.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
        }
        Box(Modifier.weight(1.18f)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (model.journey == null) "ПЕРЕГОН" else "СКВОЗНОЙ РЕЙС", fontSize = 8.sp, letterSpacing = 1.5.sp, color = PiketBlue)
                    Text(NativeRouteCatalog.forInternalRoute(model.route).let { choice -> model.routeChoices.firstOrNull { it.journey == model.journey && it.tudaStart == model.route }?.title ?: choice.title }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text("⌄")
            }
        }
    }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        containerColor = Color(0xFF101115),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).width(46.dp).height(4.dp).background(Color(0xFF55555E), RoundedCornerShape(4.dp))) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Выбери направление", fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 5.dp))
            model.routeChoices.forEach { choice ->
                val selected = choice.journey == model.journey && (model.route in choice.members || model.route == choice.tudaStart)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .background(if(selected) Brush.horizontalGradient(listOf(Color(0xFF55111A),Color(0xFF191218))) else Brush.horizontalGradient(listOf(Color(0xFF191A20),Color(0xFF0D0E11))))
                        .border(1.dp, if(selected) Color(0xFFFF5360) else Color(0xFF343740), RoundedCornerShape(15.dp))
                        .clickable { model.selectRoute(choice); expanded = false }.padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) { Text(choice.title, fontWeight = FontWeight.ExtraBold); Text(if(choice.journey == null) "Автоматические переходы участков" else "Сквозной маршрут поезда ${choice.journey}", color=if(selected)Color(0xFFFF9BA2)else Color(0xFF909AA7),fontSize=11.sp) }
                    Text(if(selected) "✓" else "›", color=if(selected)PiketRed else Color(0xFF7D8793),fontSize=22.sp)
                }
            }
        }
    }
}

@Composable
private fun Speedometer(speed: Float) {
    val value = speed.coerceIn(0f, 250f)
    Box(Modifier.fillMaxWidth().height(326.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(318.dp)) {
            val radius = size.minDimension * .44f; val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF19202A), Color(0xFF090B10), Color.Black)), radius, center)
            drawCircle(Color(0xFFB7C7DA), radius, center, style = Stroke(2.5f))
            drawCircle(Color(0xFF020304), radius - 5f, center, style = Stroke(7f))
            drawCircle(Color(0xFF3C4653), radius - 10f, center, style = Stroke(2f))
            val arcRadius = radius - 22f
            drawArc(Color(0xFF202936), 135f, 270f, false, topLeft = Offset(center.x-arcRadius,center.y-arcRadius), size=androidx.compose.ui.geometry.Size(arcRadius*2,arcRadius*2), style=Stroke(13f, cap=StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(Color(0xFFFF4057), PiketRed, Color(0xFFFF6A3D), Color(0xFFD13BC9))),135f,270f*(value/250f).coerceAtLeast(.012f),false,topLeft=Offset(center.x-arcRadius,center.y-arcRadius),size=androidx.compose.ui.geometry.Size(arcRadius*2,arcRadius*2),style=Stroke(14f,cap=StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(Color(0xFFFF3048), Color(0xFFFF743C), Color(0xFFD63CCA))), 372.6f, 32.4f, false, topLeft = Offset(center.x-arcRadius,center.y-arcRadius), size=androidx.compose.ui.geometry.Size(arcRadius*2,arcRadius*2), style=Stroke(9f, cap=StrokeCap.Round))
            for (i in 0..50) {
                val a=(135.0+i*5.4)*PI/180; val major=i%10==0; val medium=i%5==0
                val inner = radius - when { major -> 31f; medium -> 26f; else -> 22f }
                val p1=Offset(center.x+inner*cos(a).toFloat(),center.y+inner*sin(a).toFloat()); val p2=Offset(center.x+(radius-10)*cos(a).toFloat(),center.y+(radius-10)*sin(a).toFloat())
                drawLine(if(major) Color(0xFFEAF2F8) else if(medium) Color(0xFF8794A2) else Color(0xFF4A5663),p1,p2,if(major)4f else if(medium)2.4f else 1.4f,StrokeCap.Round)
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(220,228,235); textSize = 14f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            listOf(0,60,80,100,120,140,160,180,200,220,250).forEach { label ->
                val angle = (135.0 + 270.0 * label / 250.0) * PI / 180.0
                val lr = radius - 48f
                drawContext.canvas.nativeCanvas.drawText(label.toString(), center.x + lr*cos(angle).toFloat(), center.y + lr*sin(angle).toFloat() + 5f, labelPaint)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFF151C25), Color(0xFF06080C))), radius*.57f, center)
            drawCircle(Color(0xFF343D48), radius*.57f, center, style=Stroke(1.5f))
            val needle=(135.0+270.0*value/250.0)*PI/180; drawLine(Color(0xFFFF384F),center,Offset(center.x+radius*.72f*cos(needle).toFloat(),center.y+radius*.72f*sin(needle).toFloat()),5f,StrokeCap.Round); drawCircle(Color(0xFFE5EDF3),14f,center); drawCircle(Color(0xFF111820),10f,center); drawCircle(Color(0xFFFF384F),4f,center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.roundToInt().toString(), fontSize = 66.sp, fontWeight = FontWeight.Black); Text("К М / Ч", letterSpacing = 5.sp, color = Color(0xFF9EABB9), fontSize = 9.sp); Text(if(value == 0f) "GPS НЕ АКТИВЕН" else "НАТИВНАЯ ПОЗИЦИЯ", color=Color(0xFF566373),fontSize=8.sp,modifier=Modifier.padding(top=7.dp)); Row(horizontalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.padding(top=5.dp)){repeat(4){Box(Modifier.size(4.dp).background(Color(0xFF313943),CircleShape))}} }
    }
}

@Composable
private fun PositionCard(state: TripSnapshot) {
    val total = state.officialM?.roundToInt(); val km = total?.div(1000); val rest = total?.rem(1000); val pk = rest?.div(100); val m = rest?.rem(100)
    Row(Modifier.fillMaxWidth().shadow(14.dp,RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(Color(0xFF191A20),Color(0xFF0B0C0F))), RoundedCornerShape(20.dp)).border(1.dp, Color(0xFF343740), RoundedCornerShape(20.dp)).padding(vertical = 18.dp)) {
        listOf(km?.toString() ?: "—", pk?.toString() ?: "—", m?.toString() ?: "—").zip(listOf("КМ","ПК","М")).forEach { (v,l) -> Column(Modifier.weight(1f), horizontalAlignment=Alignment.CenterHorizontally) { Text(v,fontSize=35.sp,fontWeight=FontWeight.Bold);Text(l,fontSize=10.sp,letterSpacing=3.sp,color=PiketBlue) } }
    }
}

@Composable private fun NativeStatusCard(s: TripSnapshot) {
    val title=when { !s.active->"Путь свободен";s.recovering->"Восстановление позиции";s.source=="native-gps"->"Позиция подтверждена GPS";else->"Расчётная позиция" }
    val healthy = !s.active || s.source == "native-gps"
    Card(
        colors=CardDefaults.cardColors(containerColor=if (healthy) Color(0xFF0C241C) else Color(0xFF25200F)),
        shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp, if (healthy) Color(0xFF176943) else Color(0xFF67551C))
    ) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).shadow(10.dp, CircleShape).background(if (healthy) Color(0xFF31DB83) else PiketYellow, CircleShape))
        Column(Modifier.padding(start = 12.dp)) { Text(title.uppercase(),fontWeight=FontWeight.Bold,letterSpacing=1.7.sp,color=if(healthy)Color(0xFF8FE8BD)else PiketYellow);Text(if(!s.active)"Ограничений впереди нет" else "Спутники: ${s.satellites} · C/N₀: ${s.averageCn0.roundToInt()} · точность: ${s.accuracyM?.roundToInt() ?: 0} м",color=Color(0xFF9EABB9),fontSize=12.sp) }
    } }
}
@Composable private fun AlertCard(s: TripSnapshot,r:RestrictionRecord?){Card(colors=CardDefaults.cardColors(containerColor=if(s.alertInZone)Color(0xFF54121C)else Color(0xFF4A3510))){Column(Modifier.fillMaxWidth().padding(18.dp)){Text(if(s.alertInZone)"ОГРАНИЧЕНИЕ" else "ПОДЪЕЗЖАЕШЬ",fontWeight=FontWeight.Bold,color=if(s.alertInZone)PiketRed else PiketYellow);Text("${r?.speed ?: "—"} км/ч · ${r?.reason ?: "Ограничение"}",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Осталось ${s.alertDistanceM?.roundToInt() ?: 0} м")}}}

@Composable
private fun RestrictionScreen(model: PiketViewModel) {
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ограничения", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Хранятся только на телефоне", color = Color.Gray)
            }
            Button(onClick = { adding = true }, colors = ButtonDefaults.buttonColors(containerColor = PiketRed)) {
                Text("＋ Добавить")
            }
        }
        Spacer(Modifier.height(14.dp))
        if (model.restrictions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Список пока пуст", color = Color.Gray) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(model.restrictions, key = { it.id }) { restriction ->
                    Card(colors = CardDefaults.cardColors(containerColor = PiketPanel), shape = RoundedCornerShape(17.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(54.dp).background(PiketRedDark, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                Text(restriction.speed.toString(), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("${restriction.km} км ${restriction.pk} пк ${restriction.meter} м", fontWeight = FontWeight.Bold)
                                Text(restriction.reason, color = Color.LightGray)
                                Text(restriction.route, color = PiketBlue, fontSize = 11.sp)
                            }
                            Text("×", fontSize = 28.sp, modifier = Modifier.clickable {
                                if (!model.remove(restriction.id)) message = "Не удалось сохранить изменения"
                            })
                        }
                    }
                }
            }
        }
        message?.let { Text(it, color = PiketRed) }
    }
    if (adding) AddRestrictionDialog(model.routes, { adding = false }) {
        if (model.add(it)) adding = false else message = "Ошибка хранилища: ограничение не сохранено"
    }
}

@Composable
private fun AddRestrictionDialog(routes:List<String>,dismiss:()->Unit,save:(RestrictionRecord)->Unit){
    var km by remember{mutableStateOf("")};var pk by remember{mutableStateOf("")};var meter by remember{mutableStateOf("")};var speed by remember{mutableStateOf("60")};var reason by remember{mutableStateOf("Ремонт пути")};var route by remember{mutableStateOf(routes.firstOrNull()?:"Все участки")}
    Dialog(onDismissRequest=dismiss){
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Color(0xFF202027),Color(0xFF09090B)))).border(1.dp,Color(0x55FF5B66),RoundedCornerShape(24.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
            Box(Modifier.width(46.dp).height(4.dp).background(Color(0xFF55555E),RoundedCornerShape(4.dp)).align(Alignment.CenterHorizontally))
            Text("Новое ограничение",fontSize=24.sp,fontWeight=FontWeight.Black)
            NativeField("Маршрут",route){route=it}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){NativeField("КМ",km){km=it}};Box(Modifier.weight(1f)){NativeField("ПК",pk){pk=it}};Box(Modifier.weight(1f)){NativeField("М",meter){meter=it}}}
            NativeField("Скорость",speed){speed=it};NativeField("Причина",reason){reason=it}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){TextButton(dismiss,Modifier.weight(1f)){Text("Отмена")};Button({save(RestrictionRecord(UUID.randomUUID().toString(),route,"both",km.toIntOrNull()?:0,pk.toIntOrNull()?:0,meter.toIntOrNull()?:0,speed.toIntOrNull()?:60,reason))},Modifier.weight(1.25f),colors=ButtonDefaults.buttonColors(containerColor=PiketRed),shape=RoundedCornerShape(15.dp)){Text("Сохранить",fontWeight=FontWeight.Bold)}}
        }
    }
}
@Composable private fun NativeField(label:String,value:String,on:(String)->Unit){OutlinedTextField(value,on,singleLine=true,label={Text(label)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Color(0xFFFF5965),unfocusedBorderColor=Color(0xFF4A515C),focusedContainerColor=Color(0xFF111218),unfocusedContainerColor=Color(0xFF111218)))}

@Composable
private fun SettingsScreen(model:PiketViewModel, openReference:(NativeReferenceScreen)->Unit){val s=model.settings;LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Настройки",fontSize=28.sp,fontWeight=FontWeight.ExtraBold)};item{ReferenceCard("Расписание и время хода","Поезда, станции и расчёт средней скорости"){openReference(NativeReferenceScreen.TIMETABLE)}};item{ReferenceCard("Справочник скоростей","Главный путь — красный, боковой — жёлтый"){openReference(NativeReferenceScreen.SPEEDS)}};item{SettingSwitch("Звуковой сигнал","Голосовые и звуковые предупреждения",s.sound){model.updateSettings(s.copy(sound=it))}};item{SettingSwitch("Вибрация","Тактильное подтверждение",s.vibration){model.updateSettings(s.copy(vibration=it))}};item{SettingSwitch("Не гасить экран","Экран остаётся включённым во время поездки",s.keepScreenOn){model.updateSettings(s.copy(keepScreenOn=it))}};item{SettingSwitch("Демо-режим","Проверка интерфейса без движения",s.demoMode){model.updateSettings(s.copy(demoMode=it))}};item{Card(colors=CardDefaults.cardColors(containerColor=PiketPanel)){Column(Modifier.padding(18.dp)){Text("Дальность предупреждения",fontWeight=FontWeight.Bold);Text("${s.leadM/1000.0} км",color=PiketBlue);Slider(s.leadM.toFloat(),{model.updateSettings(s.copy(leadM=(it/100).roundToInt()*100))},valueRange=1000f..8000f)}}};item{Text("Работает офлайн, данные на телефоне.\nНативное Kotlin-ядро · интерфейс Jetpack Compose",color=Color.Gray,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(20.dp))}}}
@Composable private fun ReferenceCard(title:String,subtitle:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().shadow(12.dp,RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF1C1C21),Color(0xFF0A0A0C)))).border(1.dp,Color(0xFF34343B),RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(19.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.ExtraBold,fontSize=16.sp);Text(subtitle,color=Color(0xFF90949E),fontSize=12.sp,modifier=Modifier.padding(top=4.dp))};Text("›",fontSize=30.sp,color=PiketRed)}}
@Composable private fun SettingSwitch(title:String,subtitle:String,checked:Boolean,on:(Boolean)->Unit){Row(Modifier.fillMaxWidth().shadow(12.dp,RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF1C1C21),Color(0xFF0A0A0C)))).border(1.dp,Color(0xFF34343B),RoundedCornerShape(18.dp)).padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.ExtraBold,fontSize=16.sp);Text(subtitle,color=Color(0xFF90949E),fontSize=12.sp,modifier=Modifier.padding(top=4.dp))};Switch(checked,on,colors=SwitchDefaults.colors(checkedThumbColor=Color.White,checkedTrackColor=PiketRed,uncheckedTrackColor=Color(0xFF24262D),uncheckedBorderColor=Color(0xFF595B64)))}}

@Composable
private fun CalibrationDialog(model:PiketViewModel,dismiss:()->Unit,save:(Double)->Unit){val current=(model.manualOfficialM?:model.snapshot.officialM)?.roundToInt();var km by remember{mutableStateOf(current?.div(1000)?.toString()?:"")};var pk by remember{mutableStateOf(current?.rem(1000)?.div(100)?.toString()?:"")};var meter by remember{mutableStateOf(current?.rem(100)?.toString()?:"")};Dialog(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Color(0xFF202027),Color(0xFF09090B)))).border(1.dp,Color(0x55FF5B66),RoundedCornerShape(24.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){Box(Modifier.width(46.dp).height(4.dp).background(Color(0xFF55555E),RoundedCornerShape(4.dp)).align(Alignment.CenterHorizontally));Text("Калибровка по столбу",fontSize=23.sp,fontWeight=FontWeight.Black);Text("Впиши фактический километр, пикет и метр.",color=Color(0xFF9CA7B4));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){NativeField("КМ",km){km=it}};Box(Modifier.weight(1f)){NativeField("ПК",pk){pk=it}};Box(Modifier.weight(1f)){NativeField("М",meter){meter=it}}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){TextButton(dismiss,Modifier.weight(1f)){Text("Отмена")};Button({save((km.toIntOrNull()?:0)*1000.0+(pk.toIntOrNull()?:0)*100+(meter.toIntOrNull()?:0))},Modifier.weight(1.25f),colors=ButtonDefaults.buttonColors(containerColor=PiketRed),shape=RoundedCornerShape(15.dp)){Text("Установить",fontWeight=FontWeight.Bold)}}}}}

@Composable
private fun PiketNavigation(selected: PiketTab, on: (PiketTab) -> Unit) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 8.dp).height(72.dp)
            .shadow(18.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF171C24))
            .border(1.dp, Color(0xFF46505D), RoundedCornerShape(22.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PiketTab.entries.forEach { tab ->
            val active = selected == tab
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp))
                    .background(if (active) Brush.horizontalGradient(listOf(Color(0xFF821024), Color(0xFFC1162F))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { on(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(tab.symbol, fontSize = 19.sp, color = if (active) Color.White else Color(0xFFAAB3BE))
                Text(tab.title, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = if (active) Color.White else Color(0xFFD2D7DE))
            }
        }
    }
}
