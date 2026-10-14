package com.example.chargingapp

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---- WebDAV 配置持久化 ----
object WebDavPrefs {
    private const val NAME = "webdav_prefs"
    private const val KEY_URL = "url"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"

    fun load(context: Context): Triple<String, String, String> {
        val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return Triple(
            sp.getString(KEY_URL, "") ?: "",
            sp.getString(KEY_USER, "") ?: "",
            sp.getString(KEY_PASS, "") ?: ""
        )
    }

    fun save(context: Context, url: String, user: String, pass: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url)
            .putString(KEY_USER, user)
            .putString(KEY_PASS, pass)
            .apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = DatabaseHelper(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(db)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(db: DatabaseHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var records by remember { mutableStateOf(listOf<Record>()) }
    var stats by remember { mutableStateOf(listOf<StatItem>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var statType by remember { mutableStateOf("月") }

    // ---- WebDAV 配置 ----
    val saved = remember { WebDavPrefs.load(context) }
    var webdavUrl by remember { mutableStateOf(saved.first) }
    var webdavUser by remember { mutableStateOf(saved.second) }
    var webdavPass by remember { mutableStateOf(saved.third) }
    var statusMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (webdavUrl.isNotBlank()) {
            WebDavHelper.configure(webdavUrl, webdavUser, webdavPass)
        }
    }

    // 输入状态
    val dateFmt = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
    var inputDate by remember { mutableStateOf(dateFmt.format(Date())) }
    var inputAmount by remember { mutableStateOf("") }

    fun showDatePicker() {
        val cal = Calendar.getInstance()
        try {
            cal.time = dateFmt.parse(inputDate) ?: Date()
        } catch (_: Exception) { }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val c = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                inputDate = dateFmt.format(c.time)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    fun refresh() {
        records = db.getAll()
        stats = db.getStats(statType)
        // 通知小部件刷新
        MyWidgetProvider.refreshAll(context)
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(statType) { stats = db.getStats(statType) }

    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val list = ExcelHelper.importFromExcel(context, it)
                if (list.isNotEmpty()) db.insertBatch(list)
                withContext(Dispatchers.Main) {
                    refresh()
                    statusMsg = if (list.isEmpty()) "导入失败：未读取到数据" else "导入成功: ${list.size} 条"
                    if (WebDavHelper.isConfigured() && list.isNotEmpty()) {
                        val f = File(context.cacheDir, "backup.db")
                        db.exportTo(f)
                        WebDavHelper.upload(f, "charging_backup.db")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("充电记账") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("明细", "统计", "设置").forEachIndexed { i, t ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(t) })
                }
            }

            when (selectedTab) {
                0 -> {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { showDatePicker() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(inputDate)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = inputAmount,
                                onValueChange = { inputAmount = it },
                                label = { Text("金额") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword
                                ),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(Modifier.padding(top = 8.dp)) {
                            Button(onClick = {
                                val amt = inputAmount.toDoubleOrNull()
                                if (amt != null && inputDate.isNotBlank()) {
                                    db.insert(inputDate, amt)
                                    inputAmount = ""
                                    refresh()
                                    if (WebDavHelper.isConfigured()) {
                                        scope.launch(Dispatchers.IO) {
                                            val f = File(context.cacheDir, "backup.db")
                                            db.exportTo(f)
                                            WebDavHelper.upload(f, "charging_backup.db")
                                        }
                                    }
                                }
                            }) { Text("添加") }

                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                excelPicker.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            }) { Text("导入 Excel") }
                        }
                        if (statusMsg.isNotEmpty()) {
                            Text(statusMsg, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Divider()
                    LazyColumn {
                        items(records) { r ->
                            ListItem(
                                headlineContent = { Text(r.date) },
                                trailingContent = { Text("¥${"%.2f".format(r.amount)}") }
                            )
                        }
                    }
                }
                1 -> {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            listOf("周", "月", "季度", "年").forEach { t ->
                                FilterChip(
                                    selected = statType == t,
                                    onClick = { statType = t },
                                    label = { Text(t) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // 柱状图：只在"周"或"月"时显示
                        if (statType == "周" || statType == "月") {
                            Text(
                                if (statType == "周") "最近 12 周充电金额" else "最近 12 个月充电金额",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            BarChartCard(stats = stats, maxBars = 12)
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    LazyColumn {
                        items(stats) { s ->
                            ListItem(
                                headlineContent = { Text(s.period) },
                                supportingContent = { Text("次数: ${s.count}") },
                                trailingContent = { Text("¥${"%.2f".format(s.total)}") }
                            )
                        }
                    }
                }
                2 -> {
                    Column(Modifier.padding(16.dp)) {
                        Text("WebDAV 配置", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = webdavUrl,
                            onValueChange = { webdavUrl = it },
                            label = { Text("服务器地址 (https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = webdavUser,
                            onValueChange = { webdavUser = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = webdavPass,
                            onValueChange = { webdavPass = it },
                            label = { Text("密码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            WebDavPrefs.save(context, webdavUrl, webdavUser, webdavPass)
                            WebDavHelper.configure(webdavUrl, webdavUser, webdavPass)
                            statusMsg = "已保存配置"
                        }) { Text("保存配置") }

                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val ok = WebDavHelper.isAvailable()
                                withContext(Dispatchers.Main) {
                                    statusMsg = if (ok) "WebDAV 可用" else "WebDAV 不可用"
                                }
                            }
                        }) { Text("测试连接") }

                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val f = File(context.cacheDir, "backup.db")
                                db.exportTo(f)
                                val ok = WebDavHelper.upload(f, "charging_backup.db")
                                withContext(Dispatchers.Main) {
                                    statusMsg = if (ok) "备份成功" else "备份失败"
                                }
                            }
                        }) { Text("立即备份到 WebDAV") }

                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val f = File(context.cacheDir, "restore.db")
                                val ok = WebDavHelper.download("charging_backup.db", f)
                                if (ok) {
                                    db.importFrom(f)
                                }
                                withContext(Dispatchers.Main) {
                                    if (ok) { refresh(); statusMsg = "恢复成功" } else statusMsg = "恢复失败"
                                }
                            }
                        }) { Text("从 WebDAV 恢复") }

                        if (statusMsg.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(statusMsg)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 柱状图卡片：取 stats 最后 maxBars 项（按时间倒序取最近），从左到右显示
 */
@Composable
fun BarChartCard(stats: List<StatItem>, maxBars: Int = 12) {
    // stats 是按 period 倒序（最新的在前），取前 maxBars 条再反转，让最新的在右边
    val shown = stats.take(maxBars).reversed()
    if (shown.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无数据", color = Color.Gray)
        }
        return
    }
    val maxValue = shown.maxOf { it.total }.coerceAtLeast(1.0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val bottomPadding = 40f
            val topPadding = 20f
            val chartH = h - bottomPadding - topPadding
            val barCount = shown.size
            val slotW = w / barCount
            val barW = slotW * 0.55f

            // 画基线
            drawLine(
                color = Color.LightGray,
                start = Offset(0f, h - bottomPadding),
                end = Offset(w, h - bottomPadding),
                strokeWidth = 2f
            )

            // 画每个柱子
            for (i in shown.indices) {
                val item = shown[i]
                val ratio = (item.total / maxValue).toFloat()
                val barH = chartH * ratio
                val left = i * slotW + (slotW - barW) / 2
                val top = h - bottomPadding - barH
                drawRect(
                    color = Color(0xFF2196F3),
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(barW, barH)
                )

                // 金额数字（小字）
                val amountText = "%.1f".format(item.total)
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 22f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    amountText,
                    left + barW / 2,
                    top - 6f,
                    textPaint
                )

                // 横轴标签（周期名，例如 "01月"、"W01"）
                val label = simplifyPeriod(item.period)
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 20f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    left + barW / 2,
                    h - bottomPadding + 24f,
                    labelPaint
                )
            }
        }
    }
}

/**
 * 把 "2025年01月" 简化成 "01月"；把 "2025年第01周" 简化成 "W01"
 */
private fun simplifyPeriod(period: String): String {
    return when {
        period.contains("月") -> {
            val idx = period.indexOf("年")
            if (idx >= 0) period.substring(idx + 1) else period
        }
        period.contains("周") -> {
            val idx = period.indexOf("第")
            if (idx >= 0) "W" + period.substring(idx + 1, period.length - 1) else period
        }
        else -> period
    }
}
