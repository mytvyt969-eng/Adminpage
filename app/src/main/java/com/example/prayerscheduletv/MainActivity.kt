package com.example.prayerscheduletv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.store by preferencesDataStore("prayer_schedule")

data class TvTime(val total: Int) {
    val normalized: Int get() = ((total % 1440) + 1440) % 1440
    val hour24: Int get() = normalized / 60
    val minute: Int get() = normalized % 60
    val hour12: Int get() = when (hour24 % 12) { 0 -> 12; else -> hour24 % 12 }
    val am: Boolean get() = hour24 < 12
    fun addMinutes(n: Int) = TvTime(normalized + n)
    fun addHours(n: Int) = TvTime(normalized + n * 60)
    fun text() = "%02d:%02d %s".format(hour12, minute, if (am) "AM" else "PM")
}

data class Prayer(
    val name: String,
    val athan: TvTime,
    val offset: Int,
    val fixed: Boolean,
    val jamaat: TvTime
) {
    val calculatedJamaat: TvTime get() = athan.addMinutes(offset)
}

data class ScheduleState(
    val prayers: List<Prayer> = listOf(
        Prayer("Fajr", TvTime(5 * 60 + 8), 12, false, TvTime(5 * 60 + 20)),
        Prayer("Dhuhr", TvTime(12 * 60 + 15), 0, true, TvTime(13 * 60 + 30)),
        Prayer("Asr", TvTime(16 * 60 + 15), 30, false, TvTime(16 * 60 + 45)),
        Prayer("Maghrib", TvTime(17 * 60 + 58), 5, false, TvTime(18 * 60 + 3)),
        Prayer("Isha", TvTime(19 * 60 + 30), 15, false, TvTime(19 * 60 + 45))
    ),
    val jummahAthan: TvTime = TvTime(12 * 60 + 15),
    val jummahOffset: Int = 45,
    val jummahJamaat: TvTime = TvTime(13 * 60 + 15),
    val eid: TvTime = TvTime(7 * 60 + 30),
    val eidEnabled: Boolean = true,
    val eidAdha: TvTime = TvTime(7 * 60 + 30),
    val eidAdhaEnabled: Boolean = true,
    val taraweeh: TvTime = TvTime(20 * 60),
    val taraweehEnabled: Boolean = true,
    val ramzanEnabled: Boolean = true,
    val sehri: TvTime = TvTime(4 * 60 + 41),
    val iftar: TvTime = TvTime(17 * 60 + 58),
    val beforeAthan: Int = 10,
    val beforeJamaat: Int = 15
)

class ScheduleViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val raw = getApplication<ApplicationContext>().store.data.first()[KEY_STATE]
            if (!raw.isNullOrBlank()) decode(raw)?.let { _state.value = it }
        }
    }

    fun updatePrayer(index: Int, change: (Prayer) -> Prayer) {
        _state.value = _state.value.copy(prayers = _state.value.prayers.mapIndexed { i, p -> if (i == index) change(p) else p })
        _saved.value = false
    }

    fun updatePrayerAthan(i: Int, t: TvTime) = updatePrayer(i) { it.copy(athan = t, jamaat = if (it.fixed) it.jamaat else t.addMinutes(it.offset)) }
    fun updatePrayerOffset(i: Int, v: Int) = updatePrayer(i) { it.copy(offset = v.coerceIn(-120, 180), fixed = false, jamaat = it.athan.addMinutes(v)) }
    fun togglePrayerMode(i: Int) = updatePrayer(i) { if (it.fixed) it.copy(fixed = false, jamaat = it.athan.addMinutes(it.offset)) else it.copy(fixed = true) }
    fun updatePrayerJamaat(i: Int, t: TvTime) = updatePrayer(i) { it.copy(jamaat = t, fixed = true) }

    fun update(block: (ScheduleState) -> ScheduleState) { _state.value = block(_state.value); _saved.value = false }

    fun save() {
        viewModelScope.launch {
            getApplication<ApplicationContext>().store.edit { it[KEY_STATE] = encode(_state.value) }
            _saved.value = true
        }
    }

    private fun encode(s: ScheduleState): String = buildString {
        s.prayers.forEach { append(it.athan.normalized).append(',').append(it.offset).append(',').append(it.fixed).append(',').append(it.jamaat.normalized).append(';') }
        append("|").append(s.jummahAthan.normalized).append(',').append(s.jummahOffset).append(',').append(s.jummahJamaat.normalized)
        append("|").append(s.eid.normalized).append(',').append(s.eidEnabled)
        append("|").append(s.eidAdha.normalized).append(',').append(s.eidAdhaEnabled)
        append("|").append(s.taraweeh.normalized).append(',').append(s.taraweehEnabled)
        append("|").append(s.ramzanEnabled).append(',').append(s.sehri.normalized).append(',').append(s.iftar.normalized)
        append("|").append(s.beforeAthan).append(',').append(s.beforeJamaat)
    }

    private fun decode(raw: String): ScheduleState? = runCatching {
        val sections = raw.split("|")
        val ps = sections[0].split(";").filter { it.isNotBlank() }.mapIndexed { i, x ->
            val a = x.split(",")
            val defaults = ScheduleState().prayers[i]
            Prayer(defaults.name, TvTime(a[0].toInt()), a[1].toInt(), a[2].toBoolean(), TvTime(a[3].toInt()))
        }
        val j = sections[1].split(",")
        val e = sections[2].split(",")
        val ea = sections[3].split(",")
        val t = sections[4].split(",")
        val r = sections[5].split(",")
        val c = sections[6].split(",")
        ScheduleState(
            prayers = ps,
            jummahAthan = TvTime(j[0].toInt()), jummahOffset = j[1].toInt(), jummahJamaat = TvTime(j[2].toInt()),
            eid = TvTime(e[0].toInt()), eidEnabled = e[1].toBoolean(),
            eidAdha = TvTime(ea[0].toInt()), eidAdhaEnabled = ea[1].toBoolean(),
            taraweeh = TvTime(t[0].toInt()), taraweehEnabled = t[1].toBoolean(),
            ramzanEnabled = r[0].toBoolean(), sehri = TvTime(r[1].toInt()), iftar = TvTime(r[2].toInt()),
            beforeAthan = c[0].toInt(), beforeJamaat = c[1].toInt()
        )
    }.getOrNull()

    private companion object { val KEY_STATE = androidx.datastore.preferences.core.stringPreferencesKey("state") }
}
private typealias ApplicationContext = android.app.Application

private val Bg = Color(0xFF0A1211)
private val CardBg = Color(0xFF132220)
private val FieldBg = Color(0xFF071312)
private val Amber = Color(0xFFF5A623)
private val AmberBright = Color(0xFFFFC45E)
private val PrimaryText = Color(0xFFF2F5F3)
private val SecondaryText = Color(0xFF9FAEAA)
private val Green = Color(0xFF3EC7A0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { PrayerScheduleScreen(viewModel()) } }
    }
}

@Composable
fun PrayerScheduleScreen(vm: ScheduleViewModel) {
    val state by vm.state.collectAsState()
    val saved by vm.saved.collectAsState()
    val saveRequester = remember { FocusRequester() }
    val firstRequester = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Bg).padding(horizontal = 28.dp, vertical = 18.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prayer Schedule Time", color = PrimaryText, fontSize = 30.sp)
                    Text("TV Admin • DPAD editing enabled", color = SecondaryText, fontSize = 12.sp)
                }
                Button(
                    onClick = vm::save,
                    modifier = Modifier
                        .focusRequester(saveRequester)
                        .focusableWithGlow()
                        .focusProperties { down = firstRequester },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)
                ) { Text("SAVE ALL CHANGES", fontSize = 15.sp) }
            }

            LazyRow(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(state.prayers) { index, prayer ->
                    PrayerCard(
                        prayer = prayer,
                        modifier = Modifier.fillMaxHeight().width(270.dp),
                        firstRequester = if (index == 0) firstRequester else null,
                        saveRequester = if (index == 0) saveRequester else null,
                        onAthan = { vm.updatePrayerAthan(index, it) },
                        onOffset = { vm.updatePrayerOffset(index, it) },
                        onMode = { vm.togglePrayerMode(index) },
                        onJamaat = { vm.updatePrayerJamaat(index, it) }
                    )
                }
            }

            LazyRow(
                Modifier.fillMaxWidth().height(215.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { JummahCard(state, vm, Modifier.width(285.dp)) }
                item { SpecialCard(state, vm, Modifier.width(285.dp)) }
                item { RamzanCard(state, vm, Modifier.width(285.dp)) }
                item { CountdownCard(state, vm, Modifier.width(285.dp)) }
                item { SystemCard(saved, Modifier.width(285.dp)) }
            }

            Text(
                "Note: Activating a toggle switch will display that specific prayer or schedule on the main screen. Disabling it will hide it.",
                color = SecondaryText, fontSize = 11.sp
            )
        }

        if (saved) {
            Card(
                Modifier.align(Alignment.BottomEnd).padding(bottom = 26.dp),
                colors = CardDefaults.cardColors(containerColor = Green),
                shape = RoundedCornerShape(10.dp)
            ) { Text("✓  Changes saved", color = Color.Black, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun PrayerCard(
    prayer: Prayer, modifier: Modifier, firstRequester: FocusRequester?, saveRequester: FocusRequester?,
    onAthan: (TvTime) -> Unit, onOffset: (Int) -> Unit, onMode: () -> Unit, onJamaat: (TvTime) -> Unit
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(prayer.name, color = AmberBright, fontSize = 19.sp)
            Label("ATHAN TIME")
            TimeEditor(prayer.athan, onAthan, firstRequester, saveRequester)
            Label("ATHAN OFFSET / TYPE")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Stepper(if (prayer.fixed) 0 else prayer.offset, -120..180, { onOffset(it) }, Modifier.weight(1f), suffix = "m")
                FocusButton(if (prayer.fixed) "FIXED" else "AUTO", onMode, Modifier.width(78.dp))
            }
            Label("JAMA'AT TIME")
            TimeEditor(
                if (prayer.fixed) prayer.jamaat else prayer.calculatedJamaat,
                onJamaat, null, null, enabled = prayer.fixed
            )
        }
    }
}

@Composable
private fun JummahCard(s: ScheduleState, vm: ScheduleViewModel, modifier: Modifier) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("JUMU'AH", color = AmberBright, fontSize = 19.sp)
            Label("ATHAN"); TimeEditor(s.jummahAthan, { t -> vm.update { it.copy(jummahAthan = t, jummahJamaat = t.addMinutes(it.jummahOffset)) } }, null, null)
            Label("ATHAN OFFSET"); Stepper(s.jummahOffset, -60..180, { v -> vm.update { it.copy(jummahOffset = v, jummahJamaat = it.jummahAthan.addMinutes(v)) } }, Modifier.fillMaxWidth(), "m")
            Label("JAMA'AT"); TimeEditor(s.jummahJamaat, { t -> vm.update { it.copy(jummahJamaat = t) } }, null, null)
        }
    }
}
@Composable
private fun SpecialCard(s: ScheduleState, vm: ScheduleViewModel, modifier: Modifier) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SPECIAL SALAH", color = AmberBright, fontSize = 19.sp)
            ToggleTime("Eid", s.eidEnabled, s.eid, { vm.update { it.copy(eidEnabled = !it.eidEnabled) } }, { t -> vm.update { it.copy(eid = t) } })
            ToggleTime("Eid-ul-Adha", s.eidAdhaEnabled, s.eidAdha, { vm.update { it.copy(eidAdhaEnabled = !it.eidAdhaEnabled) } }, { t -> vm.update { it.copy(eidAdha = t) } })
            ToggleTime("Taraweeh", s.taraweehEnabled, s.taraweeh, { vm.update { it.copy(taraweehEnabled = !it.taraweehEnabled) } }, { t -> vm.update { it.copy(taraweeh = t) } })
        }
    }
}
@Composable
private fun RamzanCard(s: ScheduleState, vm: ScheduleViewModel, modifier: Modifier) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RAMZAN", color = AmberBright, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Switch(checked = s.ramzanEnabled, onCheckedChange = { vm.update { it.copy(ramzanEnabled = it.ramzanEnabled.not()) } })
            }
            Label("SEHRI"); TimeEditor(s.sehri, { t -> vm.update { it.copy(sehri = t) } }, null, null)
            Label("IFTAR"); TimeEditor(s.iftar, { t -> vm.update { it.copy(iftar = t) } }, null, null)
        }
    }
}

@Composable
private fun CountdownCard(s: ScheduleState, vm: ScheduleViewModel, modifier: Modifier) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("COUNTDOWN", color = AmberBright, fontSize = 19.sp)
            Label("BEFORE ATHAN"); Stepper(s.beforeAthan, 0..60, { v -> vm.update { it.copy(beforeAthan = v) } }, Modifier.fillMaxWidth(), "m")
            Label("BEFORE JAMA'AT"); Stepper(s.beforeJamaat, 0..60, { v -> vm.update { it.copy(beforeJamaat = v) } }, Modifier.fillMaxWidth(), "m")
        }
    }
}

@Composable
private fun SystemCard(saved: Boolean, modifier: Modifier) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("DISPLAY STATUS", color = AmberBright, fontSize = 19.sp)
            Text("Admin settings are stored locally.", color = SecondaryText, fontSize = 13.sp)
            Text(if (saved) "READY • ALL CHANGES SAVED" else "UNSAVED CHANGES", color = if (saved) Green else Amber, fontSize = 14.sp)
            Text("Use CENTER to edit. UP/DOWN changes values. BACK exits edit mode.", color = SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToggleTime(label: String, checked: Boolean, time: TvTime, onToggle: () -> Unit, onTime: (TvTime) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = PrimaryText, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
        TimeEditor(time, onTime, null, null, enabled = checked)
    }
}

@Composable
private fun TimeEditor(
    time: TvTime, onChange: (TvTime) -> Unit, requester: FocusRequester?, up: FocusRequester?, enabled: Boolean = true
) {
    var editing by remember { mutableStateOf(false) }
    FocusField(
        requester = requester,
        focusUp = up,
        enabled = enabled,
        onCenter = { editing = !editing },
        // UP/DOWN always adjust the selected time. This keeps editing discoverable
        // on a TV remote without requiring CENTER first.
        onUp = { onChange(time.addMinutes(1)) },
        onDown = { onChange(time.addMinutes(-1)) },
        onLeft = { onChange(time.addHours(-1)) },
        onRight = { onChange(time.addHours(1)) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                time.text(),
                color = if (enabled) PrimaryText else SecondaryText,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▲", color = AmberBright, fontSize = 12.sp)
                Text(if (editing) "EDIT" else "OK", color = Amber, fontSize = 8.sp)
                Text("▼", color = AmberBright, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier, suffix: String) {
    FocusField(onCenter = {}, onUp = { onChange((value + 1).coerceIn(range)) }, onDown = { onChange((value - 1).coerceIn(range)) }) {
        Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▲", color = Amber, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (value >= 0) "+$value$suffix" else "$value$suffix", color = PrimaryText, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("▼", color = Amber, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FocusButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.focusableWithGlow(), colors = ButtonDefaults.buttonColors(containerColor = FieldBg, contentColor = AmberBright)) {
        Text(text, fontSize = 10.sp)
    }
}

@Composable
private fun Label(text: String) { Text(text, color = SecondaryText, fontSize = 9.sp) }

@Composable
private fun FocusField(
    requester: FocusRequester? = null,
    focusUp: FocusRequester? = null,
    enabled: Boolean = true,
    onCenter: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties { if (focusUp != null) up = focusUp }
            .focusable(enabled)
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onLeft(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onRight(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onCenter(); true }
                    KeyEvent.KEYCODE_BACK -> false
                    else -> false
                }
            }
            .background(if (focused) FieldBg else FieldBg, RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.padding(2.dp) else Modifier)
    ) { content() }
}

@Composable
private fun Modifier.focusableWithGlow(): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .then(if (focused) Modifier.background(Amber.copy(alpha = .12f), RoundedCornerShape(8.dp)) else Modifier)
}

