package com.custom.mqttdashboard

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class WidgetType(val label: String) {
    SWITCH("Switch ON/OFF"),
    BUTTON("Pulsante Impulso"),
    SLIDER("Slider / Dimmer"),
    SENSOR("Valore Sensore"),
    GAUGE("Gauge Circolare"),
    RGB("Colore RGB")
}

data class BrokerConfig(
    val host: String = "192.168.1.100",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val devicePrefix: String = "esp32_casa"
)

data class MqttWidgetItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: WidgetType,
    val subTopic: String,
    val pubTopic: String,
    val payloadOn: String = "ON",
    val payloadOff: String = "OFF",
    val minVal: Float = 0f,
    val maxVal: Float = 100f,
    val unit: String = "",
    val jsonKey: String = "",
    val retain: Boolean = false
)

data class FullBackup(
    val broker: BrokerConfig,
    val widgets: List<MqttWidgetItem>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121418)) {
                    MqttDashboardApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MqttDashboardApp() {
    val context = LocalContext.current
    val gson = remember { Gson() }
    val prefs = remember { context.getSharedPreferences("mqtt_dash_prefs", Context.MODE_PRIVATE) }

    var broker by remember {
        mutableStateOf(
            gson.fromJson(prefs.getString("broker", null), BrokerConfig::class.java)
                ?: BrokerConfig()
        )
    }

    var widgets by remember {
        val json = prefs.getString("widgets", null)
        val list: List<MqttWidgetItem> = if (json != null) {
            gson.fromJson(json, object : TypeToken<List<MqttWidgetItem>>() {}.type)
        } else {
            listOf(
                MqttWidgetItem(
                    title = "Luce Soggiorno",
                    type = WidgetType.SWITCH,
                    subTopic = "esp32_casa/luce1/stat",
                    pubTopic = "esp32_casa/luce1/cmnd",
                    retain = true
                ),
                MqttWidgetItem(
                    title = "Temperatura",
                    type = WidgetType.GAUGE,
                    subTopic = "esp32_casa/temp",
                    pubTopic = "",
                    minVal = 0f,
                    maxVal = 50f,
                    unit = "°C"
                ),
                MqttWidgetItem(
                    title = "Dimmer LED",
                    type = WidgetType.SLIDER,
                    subTopic = "esp32_casa/dimmer/stat",
                    pubTopic = "esp32_casa/dimmer/set",
                    minVal = 0f,
                    maxVal = 100f,
                    unit = "%"
                ),
                MqttWidgetItem(
                    title = "Apri Cancello",
                    type = WidgetType.BUTTON,
                    subTopic = "",
                    pubTopic = "esp32_casa/cancello",
                    payloadOn = "OPEN"
                )
            )
        }
        mutableStateOf(list)
    }

    val liveValues = remember { mutableStateMapOf<String, String>() }
    var isConnected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Disconnesso") }
    var client by remember { mutableStateOf<Mqtt3AsyncClient?>(null) }

    var showBrokerDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingWidget by remember { mutableStateOf<MqttWidgetItem?>(null) }

    fun saveAll(newBroker: BrokerConfig = broker, newWidgets: List<MqttWidgetItem> = widgets) {
        prefs.edit()
            .putString("broker", gson.toJson(newBroker))
            .putString("widgets", gson.toJson(newWidgets))
            .apply()
    }

    fun subscribeAll(c: Mqtt3AsyncClient, list: List<MqttWidgetItem>) {
        list.map { it.subTopic.trim() }.filter { it.isNotEmpty() }.distinct().forEach { topic ->
            c.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { publish ->
                    val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                    liveValues[publish.topic.toString()] = payload
                }
                .send()
        }
    }

    fun connectMqtt() {
        try {
            client?.disconnect()
        } catch (_: Exception) {}

        statusText = "Connessione a ${broker.host}..."
        try {
            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier("MotoG57_${UUID.randomUUID().toString().take(6)}")
                .serverHost(broker.host.trim())
                .serverPort(broker.port)
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener {
                    isConnected = true
                    statusText = "Online (${broker.host})"
                }
                .addDisconnectedListener {
                    isConnected = false
                    statusText = "Offline / Riconnessione..."
                }

            val mqttClient = builder.buildAsync()
            val connectBuilder = mqttClient.connectWith().cleanSession(true)
            if (broker.username.isNotBlank()) {
                connectBuilder.simpleAuth()
                    .username(broker.username)
                    .password(broker.password.toByteArray(StandardCharsets.UTF_8))
                    .applySimpleAuth()
            }

            connectBuilder.send().whenComplete { _, throwable ->
                if (throwable != null) {
                    isConnected = false
                    statusText = "Errore: ${throwable.localizedMessage ?: "Connessione fallita"}"
                } else {
                    isConnected = true
                    statusText = "Online (${broker.host})"
                    subscribeAll(mqttClient, widgets)
                }
            }
            client = mqttClient
        } catch (e: Exception) {
            statusText = "Configurazione non valida"
        }
    }

    fun publishMessage(topic: String, payload: String, retain: Boolean = false) {
        if (topic.isBlank()) return
        liveValues[topic] = payload
        client?.publishWith()
            ?.topic(topic.trim())
            ?.payload(payload.toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.retain(retain)
            ?.send()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            val backupJson = gson.toJson(FullBackup(broker, widgets))
            context.contentResolver.openOutputStream(it)?.use { os ->
                os.write(backupJson.toByteArray())
            }
            Toast.makeText(context, "Backup esportato con successo!", Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            if (!content.isNullOrBlank()) {
                val restored = gson.fromJson(content, FullBackup::class.java)
                broker = restored.broker
                widgets = restored.widgets
                saveAll(broker, widgets)
                connectMqtt()
                Toast.makeText(context, "Configurazione ripristinata!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        connectMqtt()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MQTT Dashboard", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(statusText, fontSize = 12.sp, color = Color.LightGray)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { exportLauncher.launch("mqtt_dashboard_backup.json") }) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Esporta Backup")
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Default.Download, contentDescription = "Importa Backup")
                    }
                    IconButton(onClick = { showBrokerDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Broker")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1B1E24))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingWidget = null
                    showAddDialog = true
                },
                containerColor = Color(0xFF2979FF)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nuovo Widget", tint = Color.White)
            }
        },
        containerColor = Color(0xFF121418)
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            items(widgets, key = { it.id }) { item ->
                val rawValue = liveValues[item.subTopic] ?: liveValues[item.pubTopic] ?: ""
                val parsedValue = extractJsonValue(rawValue, item.jsonKey)

                WidgetCard(
                    item = item,
                    value = parsedValue,
                    onPublish = { payload -> publishMessage(item.pubTopic, payload, item.retain) },
                    onEdit = {
                        editingWidget = item
                        showAddDialog = true
                    },
                    onDelete = {
                        widgets = widgets.filterNot { it.id == item.id }
                        saveAll(newWidgets = widgets)
                    }
                )
            }
        }
    }

    if (showBrokerDialog) {
        BrokerDialog(
            current = broker,
            onDismiss = { showBrokerDialog = false },
            onSave = { updated ->
                broker = updated
                saveAll(newBroker = updated)
                showBrokerDialog = false
                connectMqtt()
            }
        )
    }

    if (showAddDialog) {
        WidgetEditorDialog(
            existing = editingWidget,
            defaultPrefix = broker.devicePrefix,
            onDismiss = { showAddDialog = false },
            onSave = { saved ->
                widgets = if (editingWidget == null) {
                    widgets + saved
                } else {
                    widgets.map { if (it.id == saved.id) saved else it }
                }
                saveAll(newWidgets = widgets)
                client?.let { subscribeAll(it, widgets) }
                showAddDialog = false
            }
        )
    }
}

fun extractJsonValue(raw: String, jsonKey: String): String {
    if (jsonKey.isBlank() || raw.isBlank()) return raw
    return try {
        val obj = JSONObject(raw)
        obj.optString(jsonKey, raw)
    } catch (_: Exception) {
        raw
    }
}

@Composable
fun WidgetCard(
    item: MqttWidgetItem,
    value: String,
    onPublish: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222A)),
        modifier = Modifier
            .fillMaxWidth()
            .height(175.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Opzioni",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { menuExpanded = true }
                    )
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Modifica") },
                            onClick = { menuExpanded = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Elimina", color = Color(0xFFFF5252)) },
                            onClick = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (item.type) {
                    WidgetType.SWITCH -> {
                        val isOn = value.equals(item.payloadOn, ignoreCase = true) || value == "1" || value.equals("true", true)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(
                                checked = isOn,
                                onCheckedChange = { checked ->
                                    onPublish(if (checked) item.payloadOn else item.payloadOff)
                                }
                            )
                            Text(if (isOn) item.payloadOn else item.payloadOff, fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    WidgetType.BUTTON -> {
                        Button(
                            onClick = { onPublish(item.payloadOn) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(item.payloadOn.ifBlank { "INVIA" }, color = Color.White)
                        }
                    }

                    WidgetType.SLIDER -> {
                        var sliderPos by remember(value) {
                            mutableFloatStateOf(value.toFloatOrNull()?.coerceIn(item.minVal, item.maxVal) ?: item.minVal)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${sliderPos.toInt()} ${item.unit}", fontWeight = FontWeight.Bold, color = Color(0xFF40C4FF))
                            Slider(
                                value = sliderPos,
                                onValueChange = { sliderPos = it },
                                onValueChangeFinished = { onPublish(sliderPos.toInt().toString()) },
                                valueRange = item.minVal..item.maxVal
                            )
                        }
                    }

                    WidgetType.SENSOR -> {
                        Text(
                            text = if (value.isBlank()) "-- ${item.unit}" else "$value ${item.unit}",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }

                    WidgetType.GAUGE -> {
                        val num = value.toFloatOrNull() ?: item.minVal
                        val sweep = (((num - item.minVal) / (item.maxVal - item.minVal)).coerceIn(0f, 1f)) * 240f
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(95.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawArc(
                                    color = Color(0xFF2C323D),
                                    startAngle = 150f,
                                    sweepAngle = 240f,
                                    useCenter = false,
                                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                                )
                                drawArc(
                                    color = Color(0xFF00E5FF),
                                    startAngle = 150f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                            Text(
                                text = if (value.isBlank()) "--" else "$value${item.unit}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    WidgetType.RGB -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val colors = listOf(
                                "#FF0000" to Color.Red,
                                "#00FF00" to Color.Green,
                                "#0000FF" to Color.Blue,
                                "#FFFFFF" to Color.White
                            )
                            colors.forEach { (hex, col) ->
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(col)
                                        .clickable { onPublish(hex) }
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = item.pubTopic.ifBlank { item.subTopic },
                fontSize = 10.sp,
                color = Color(0xFF788292),
                maxLines = 1
            )
        }
    }
}

@Composable
fun BrokerDialog(current: BrokerConfig, onDismiss: () -> Unit, onSave: (BrokerConfig) -> Unit) {
    var host by remember { mutableStateOf(current.host) }
    var port by remember { mutableStateOf(current.port.toString()) }
    var user by remember { mutableStateOf(current.username) }
    var pass by remember { mutableStateOf(current.password) }
    var prefix by remember { mutableStateOf(current.devicePrefix) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurazione Broker MQTT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Indirizzo IP / Host") })
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Porta TCP (es. 1883)") })
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username (opzionale)") })
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password (opzionale)") })
                OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Prefisso Dispositivo Base") })
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(BrokerConfig(host, port.toIntOrNull() ?: 1883, user, pass, prefix))
            }) { Text("Connetti e Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}

@Composable
fun WidgetEditorDialog(
    existing: MqttWidgetItem?,
    defaultPrefix: String,
    onDismiss: () -> Unit,
    onSave: (MqttWidgetItem) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var selectedType by remember { mutableStateOf(existing?.type ?: WidgetType.SWITCH) }
    var subTopic by remember { mutableStateOf(existing?.subTopic ?: "$defaultPrefix/stato") }
    var pubTopic by remember { mutableStateOf(existing?.pubTopic ?: "$defaultPrefix/comando") }
    var payloadOn by remember { mutableStateOf(existing?.payloadOn ?: "ON") }
    var payloadOff by remember { mutableStateOf(existing?.payloadOff ?: "OFF") }
    var minVal by remember { mutableStateOf((existing?.minVal ?: 0f).toString()) }
    var maxVal by remember { mutableStateOf((existing?.maxVal ?: 100f).toString()) }
    var unit by remember { mutableStateOf(existing?.unit ?: "") }
    var jsonKey by remember { mutableStateOf(existing?.jsonKey ?: "") }
    var retain by remember { mutableStateOf(existing?.retain ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuovo Widget" else "Modifica Widget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Nome Widget") })

                WidgetType.entries.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedType = type }
                    ) {
                        RadioButton(selected = selectedType == type, onClick = { selectedType = type })
                        Text(type.label, fontSize = 14.sp)
                    }
                }

                OutlinedTextField(value = subTopic, onValueChange = { subTopic = it }, label = { Text("Topic Lettura (Subscribe)") })
                if (selectedType != WidgetType.SENSOR && selectedType != WidgetType.GAUGE) {
                    OutlinedTextField(value = pubTopic, onValueChange = { pubTopic = it }, label = { Text("Topic Invio (Publish)") })
                }
                if (selectedType == WidgetType.SWITCH || selectedType == WidgetType.BUTTON) {
                    OutlinedTextField(value = payloadOn, onValueChange = { payloadOn = it }, label = { Text("Payload ON / Impulso") })
                }
                if (selectedType == WidgetType.SWITCH) {
                    OutlinedTextField(value = payloadOff, onValueChange = { payloadOff = it }, label = { Text("Payload OFF") })
                }
                if (selectedType == WidgetType.SLIDER || selectedType == WidgetType.GAUGE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = minVal, onValueChange = { minVal = it }, label = { Text("Min") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = maxVal, onValueChange = { maxVal = it }, label = { Text("Max") }, modifier = Modifier.weight(1f))
                    }
                }
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unità di misura (es. °C, %)") })
                OutlinedTextField(value = jsonKey, onValueChange = { jsonKey = it }, label = { Text("Chiave JSON opzionale (es. temp)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = retain, onCheckedChange = { retain = it })
                    Text("Messaggio Retain")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    MqttWidgetItem(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        title = title.ifBlank { selectedType.label },
                        type = selectedType,
                        subTopic = subTopic,
                        pubTopic = pubTopic,
                        payloadOn = payloadOn,
                        payloadOff = payloadOff,
                        minVal = minVal.toFloatOrNull() ?: 0f,
                        maxVal = maxVal.toFloatOrNull() ?: 100f,
                        unit = unit,
                        jsonKey = jsonKey,
                        retain = retain
                    )
                )
            }) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
