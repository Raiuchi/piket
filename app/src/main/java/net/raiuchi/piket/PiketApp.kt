package net.raiuchi.piket

import android.app.Application
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var snapshot by mutableStateOf(repository.loadSnapshot()); private set
    var route by mutableStateOf(snapshot.route); private set
    var direction by mutableStateOf(snapshot.direction); private set
    val routes: List<String> = runCatching {
        app.assets.open("assets/piket-core.js").bufferedReader().use { NativeRouteEngine.fromCoreJs(it.readText()).labels() }
    }.getOrDefault(listOf("СпбГл - Москва"))

    init { viewModelScope.launch { while (isActive) { snapshot = repository.loadSnapshot(); delay(500) } } }
    fun setRoute(value: String) { route = value }
    fun setDirection(value: String) { direction = value }
    fun add(item: RestrictionRecord): Boolean { val next = restrictions + item; return repository.saveRestrictions(next).also { if (it) restrictions = next } }
    fun remove(id: String): Boolean { val next = restrictions.filterNot { it.id == id }; return repository.saveRestrictions(next).also { if (it) restrictions = next } }
    fun updateSettings(value: PiketSettings) { if (repository.saveSettings(value)) settings = value }
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
    LaunchedEffect(model.settings.keepScreenOn) { onKeepScreen(model.settings.keepScreenOn) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { PiketNavigation(tab) { tab = it } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(
            Brush.verticalGradient(listOf(Color(0xFF080A0E), Color(0xFF030405)))
        )) {
            when (tab) {
                PiketTab.TRIP -> TripScreen(model, { calibrating = true }, onStart, onStop)
                PiketTab.LIST -> RestrictionScreen(model)
                PiketTab.SETTINGS -> SettingsScreen(model)
            }
        }
    }
    if (calibrating) CalibrationDialog(model, { calibrating = false }) { official ->
        val config = NativeUiConfig(model.route, model.direction, official, model.restrictions, model.settings.leadM)
        if (model.snapshot.active) onRecalibrate(config)
        calibrating = false
    }
}

@Composable
private fun TripScreen(model: PiketViewModel, calibrate: () -> Unit, onStart: (NativeUiConfig) -> Unit, onStop: () -> Unit) {
    val state = model.snapshot
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                        else onStart(NativeUiConfig(model.route, model.direction, state.officialM ?: 0.0, model.restrictions, model.settings.leadM))
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
private fun BrandHeader(state: TripSnapshot) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(42.dp).background(PiketRedDark, CircleShape).border(1.dp, PiketRed, CircleShape), contentAlignment = Alignment.Center) { Text("🚄") }
    Column(Modifier.padding(start = 11.dp)) { Text("ПИКЕТ", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold); Text("КОНТРОЛЬ ОГРАНИЧЕНИЙ", fontSize = 9.sp, letterSpacing = 2.sp, color = PiketBlue) }
    Spacer(Modifier.weight(1f))
    val color = if (state.source == "native-gps") Color(0xFF31DB83) else PiketYellow
    Text(if (state.active) "● GPS" else "● GPS выкл", color = color, fontSize = 12.sp)
}

@Composable
private fun RouteSelector(model: PiketViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().background(PiketPanel, RoundedCornerShape(15.dp))) {
            listOf("tuda" to "Туда", "obratno" to "Обратно").forEach { (value, title) ->
                Text(title, modifier = Modifier.weight(1f).clickable { model.setDirection(value) }.background(if (model.direction == value) PiketRedDark else Color.Transparent).padding(14.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            }
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) { Text(model.route, Modifier.weight(1f), textAlign = TextAlign.Start); Text("⌄") }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.fillMaxWidth(.9f).background(PiketPanel)) {
                model.routes.forEach { route -> DropdownMenuItem(text = { Text(route) }, onClick = { model.setRoute(route); expanded = false }) }
            }
        }
    }
}

@Composable
private fun Speedometer(speed: Float) {
    val value = speed.coerceIn(0f, 250f)
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            val radius = size.minDimension * .42f; val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0xFF10151D), radius, center)
            drawArc(Color(0xFF35404D), 135f, 270f, false, topLeft = Offset(center.x-radius,center.y-radius), size=androidx.compose.ui.geometry.Size(radius*2,radius*2), style=Stroke(18f, cap=StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(PiketBlue,PiketRed,PiketYellow)),135f,270f*(value/250f),false,topLeft=Offset(center.x-radius,center.y-radius),size=androidx.compose.ui.geometry.Size(radius*2,radius*2),style=Stroke(20f,cap=StrokeCap.Round))
            for (i in 0..25) { val a=(135.0+i*10.8)*PI/180; val p1=Offset(center.x+(radius-24)*cos(a).toFloat(),center.y+(radius-24)*sin(a).toFloat()); val p2=Offset(center.x+(radius-8)*cos(a).toFloat(),center.y+(radius-8)*sin(a).toFloat()); drawLine(if(i%5==0) Color.White else Color(0xFF77808B),p1,p2,if(i%5==0)5f else 2f,StrokeCap.Round) }
            val needle=(135.0+270.0*value/250.0)*PI/180; drawLine(PiketRed,center,Offset(center.x+radius*.72f*cos(needle).toFloat(),center.y+radius*.72f*sin(needle).toFloat()),6f,StrokeCap.Round); drawCircle(PiketRed,13f,center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.roundToInt().toString(), fontSize = 68.sp, fontWeight = FontWeight.ExtraBold); Text("К М / Ч", letterSpacing = 5.sp, color = Color.LightGray, fontSize = 11.sp) }
    }
}

@Composable
private fun PositionCard(state: TripSnapshot) {
    val total = state.officialM?.roundToInt(); val km = total?.div(1000); val rest = total?.rem(1000); val pk = rest?.div(100); val m = rest?.rem(100)
    Row(Modifier.fillMaxWidth().background(PiketPanel, RoundedCornerShape(20.dp)).border(1.dp, Color(0xFF303844), RoundedCornerShape(20.dp)).padding(vertical = 20.dp)) {
        listOf(km?.toString() ?: "—", pk?.toString() ?: "—", m?.toString() ?: "—").zip(listOf("КМ","ПК","М")).forEach { (v,l) -> Column(Modifier.weight(1f), horizontalAlignment=Alignment.CenterHorizontally) { Text(v,fontSize=35.sp,fontWeight=FontWeight.Bold);Text(l,fontSize=10.sp,letterSpacing=3.sp,color=PiketBlue) } }
    }
}

@Composable private fun NativeStatusCard(s: TripSnapshot) { val title=when { !s.active->"Нет активной поездки";s.recovering->"Восстановление после потери сигнала";s.source=="native-gps"->"Нативная GPS-позиция";else->"Нативное счисление координаты" }; Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF10141A)),shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(18.dp)){Text(title,fontWeight=FontWeight.Bold);Text("Спутники: ${s.satellites} · C/N₀: ${s.averageCn0.roundToInt()} · точность: ${s.accuracyM?.roundToInt() ?: 0} м",color=Color.Gray,fontSize=12.sp)}} }
@Composable private fun AlertCard(s: TripSnapshot,r:RestrictionRecord?){Card(colors=CardDefaults.cardColors(containerColor=if(s.alertInZone)Color(0xFF54121C)else Color(0xFF4A3510))){Column(Modifier.fillMaxWidth().padding(18.dp)){Text(if(s.alertInZone)"ОГРАНИЧЕНИЕ" else "ПОДЪЕЗЖАЕШЬ",fontWeight=FontWeight.Bold,color=if(s.alertInZone)PiketRed else PiketYellow);Text("${r?.speed ?: "—"} км/ч · ${r?.reason ?: "Ограничение"}",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Осталось ${s.alertDistanceM?.roundToInt() ?: 0} м")}}}

@Composable
private fun RestrictionScreen(model:PiketViewModel){var adding by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)};Column(Modifier.fillMaxSize().padding(18.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Ограничения",fontSize=28.sp,fontWeight=FontWeight.ExtraBold);Text("Хранятся только на телефоне",color=Color.Gray)};Button({adding=true},colors=ButtonDefaults.buttonColors(containerColor=PiketRed)){Text("＋ Добавить")}};Spacer(Modifier.height(14.dp));if(model.restrictions.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Список пока пуст",color=Color.Gray)}else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(model.restrictions,key={it.id}){r->Card(colors=CardDefaults.cardColors(containerColor=PiketPanel),shape=RoundedCornerShape(17.dp)){Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(54.dp).background(PiketRedDark,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Text(r.speed.toString(),fontSize=21.sp,fontWeight=FontWeight.Bold)};Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text("${r.km} км ${r.pk} пк ${r.meter} м",fontWeight=FontWeight.Bold);Text(r.reason,color=Color.LightGray);Text(r.route,color=PiketBlue,fontSize=11.sp)};Text("×",fontSize=28.sp,modifier=Modifier.clickable{if(!model.remove(r.id))message="Не удалось сохранить изменения"})}}}}};message?.let{Text(it,color=PiketRed)} };if(adding)AddRestrictionDialog(model.routes,{adding=false}){if(model.add(it)){adding=false}else message="Ошибка хранилища: ограничение не сохранено"}}

@Composable
private fun AddRestrictionDialog(routes:List<String>,dismiss:()->Unit,save:(RestrictionRecord)->Unit){var km by remember{mutableStateOf("")};var pk by remember{mutableStateOf("")};var meter by remember{mutableStateOf("")};var speed by remember{mutableStateOf("60")};var reason by remember{mutableStateOf("Ремонт пути")};var route by remember{mutableStateOf(routes.firstOrNull()?:"Все участки")};AlertDialog(onDismissRequest=dismiss,containerColor=PiketPanel,title={Text("Новое ограничение",fontWeight=FontWeight.Bold)},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){item{NativeField("Маршрут",route){route=it}};item{Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.weight(1f)){NativeField("КМ",km){km=it}};Box(Modifier.weight(1f)){NativeField("ПК",pk){pk=it}};Box(Modifier.weight(1f)){NativeField("М",meter){meter=it}}}};item{NativeField("Скорость",speed){speed=it}};item{NativeField("Причина",reason){reason=it}}}},confirmButton={Button({save(RestrictionRecord(UUID.randomUUID().toString(),route,"both",km.toIntOrNull()?:0,pk.toIntOrNull()?:0,meter.toIntOrNull()?:0,speed.toIntOrNull()?:60,reason))},colors=ButtonDefaults.buttonColors(containerColor=PiketRed)){Text("Сохранить")}},dismissButton={TextButton(dismiss){Text("Отмена")}})}
@Composable private fun NativeField(label:String,value:String,on:(String)->Unit){OutlinedTextField(value,on,singleLine=true,label={Text(label)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))}

@Composable
private fun SettingsScreen(model:PiketViewModel){val s=model.settings;LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Настройки",fontSize=28.sp,fontWeight=FontWeight.ExtraBold)};item{SettingSwitch("Звуковой сигнал","Голосовые и звуковые предупреждения",s.sound){model.updateSettings(s.copy(sound=it))}};item{SettingSwitch("Вибрация","Тактильное подтверждение",s.vibration){model.updateSettings(s.copy(vibration=it))}};item{SettingSwitch("Не гасить экран","Экран остаётся включённым во время поездки",s.keepScreenOn){model.updateSettings(s.copy(keepScreenOn=it))}};item{SettingSwitch("Демо-режим","Проверка интерфейса без движения",s.demoMode){model.updateSettings(s.copy(demoMode=it))}};item{Card(colors=CardDefaults.cardColors(containerColor=PiketPanel)){Column(Modifier.padding(18.dp)){Text("Дальность предупреждения",fontWeight=FontWeight.Bold);Text("${s.leadM/1000.0} км",color=PiketBlue);Slider(s.leadM.toFloat(),{model.updateSettings(s.copy(leadM=(it/100).roundToInt()*100))},valueRange=1000f..8000f)}}};item{Text("Работает офлайн, данные на телефоне.\nНативное Kotlin-ядро · интерфейс Jetpack Compose",color=Color.Gray,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(20.dp))}}}
@Composable private fun SettingSwitch(title:String,subtitle:String,checked:Boolean,on:(Boolean)->Unit){Card(colors=CardDefaults.cardColors(containerColor=PiketPanel),shape=RoundedCornerShape(17.dp)){Row(Modifier.fillMaxWidth().padding(17.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Bold);Text(subtitle,color=Color.Gray,fontSize=12.sp)};Switch(checked,on,colors=SwitchDefaults.colors(checkedThumbColor=Color.White,checkedTrackColor=PiketRed))}}}

@Composable
private fun CalibrationDialog(model:PiketViewModel,dismiss:()->Unit,save:(Double)->Unit){val current=model.snapshot.officialM?.roundToInt();var km by remember{mutableStateOf(current?.div(1000)?.toString()?:"")};var pk by remember{mutableStateOf(current?.rem(1000)?.div(100)?.toString()?:"")};var meter by remember{mutableStateOf(current?.rem(100)?.toString()?:"")};AlertDialog(onDismissRequest=dismiss,containerColor=PiketPanel,title={Text("Калибровка по столбу",fontWeight=FontWeight.Bold)},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Впиши фактический километр, пикет и метр.",color=Color.LightGray);Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.weight(1f)){NativeField("КМ",km){km=it}};Box(Modifier.weight(1f)){NativeField("ПК",pk){pk=it}};Box(Modifier.weight(1f)){NativeField("М",meter){meter=it}}}}},confirmButton={Button({save((km.toIntOrNull()?:0)*1000.0+(pk.toIntOrNull()?:0)*100+(meter.toIntOrNull()?:0))},colors=ButtonDefaults.buttonColors(containerColor=PiketRed)){Text("Установить")}},dismissButton={TextButton(dismiss){Text("Отмена")}})}

@Composable
private fun PiketNavigation(selected:PiketTab,on:(PiketTab)->Unit){NavigationBar(containerColor=Color(0xFF20232B),tonalElevation=0.dp,modifier=Modifier.padding(horizontal=14.dp,vertical=8.dp).border(1.dp,Color(0xFF565A64),RoundedCornerShape(20.dp))){PiketTab.entries.forEach{tab->NavigationBarItem(selected==tab,{on(tab)},icon={Text(tab.symbol,fontSize=20.sp)},label={Text(tab.title,fontWeight=FontWeight.Bold)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Color.White,selectedTextColor=Color.White,indicatorColor=PiketRedDark,unselectedIconColor=Color.LightGray,unselectedTextColor=Color.LightGray))}} 
