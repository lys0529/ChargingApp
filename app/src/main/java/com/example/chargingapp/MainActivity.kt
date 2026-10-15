@file:OptIn(ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

    // 数据状态
    var records by remember { mutableStateOf(listOf<Record>()) }
    val statsMap = remember { mutableStateMapOf<String, List<StatItem>>() }

    // 主 Tab 分页
    val mainTabs = listOf("明细", "统计", "设置")
    val mainPagerState = rememberPagerState(pageCount = { mainTabs.size })

    // 统计页内部 周/月/季度/年 分页
    val statTabs = listOf("周", "月", "季度", "年")
    val statPagerState = rememberPagerState(pageCount = { statTabs.size })

    // WebDAV 配置
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

    // 刷新全部统计
    fun refreshAll() {
        records = db.getAll()
        for (t in statTabs) {
            statsMap[t] = db.getStats(t)
        }
        MyWidgetProvider.refreshAll(context)
    }

    LaunchedEffect(Unit) { refreshAll() }

    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val list = ExcelHelper.importFromExcel(context, it)
                if (list.isNotEmpty()) db.insertBatch(list)
                withContext(Dispatchers.Main) {
                    refreshAll()
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

            // 主 TabRow，和下方 HorizontalPager 联动
            TabRow(selectedTabIndex = mainPagerState.currentPage) {
                mainTabs.forEachIndexed { i, t ->
                    Tab(
                        selected = mainPagerState.currentPage == i,
                        onClick = {
                            scope.launch { mainPagerState.animateScrollToPage(i) }
                        },
                        text = { Text(t) }
                    )
                }
            }

            // 三大页面，支持左右滑动切换
            HorizontalPager(
                state = mainPagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    // ============ 页面 1：明细 ============
                    0 -> {
                        Column(Modifier.fillMaxSize()) {
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
                                            refreshAll()
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
                    }

                    // ============ 页面 2：统计 ============
                    1 -> {
                        Column(Modifier.fillMaxSize()) {
                            // 4 个统计类型 Tab，和下方 HorizontalPager 联动
                            TabRow(selectedTabIndex = statPagerState.currentPage) {
                                statTabs.forEachIndexed { i, t ->
                                    Tab(
                                        selected = statPagerState.currentPage == i,
                                        onClick = {
                                            scope.launch { statPagerState.animateScrollToPage(i) }
                                        },
                                        text = { Text(t) }
                                    )
                                }
                            }

                            // 周 / 月 / 季度 / 年 四个页面，支持左右滑动切换
                            HorizontalPager(
                                state = statPagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { statPage ->
                                val type = statTabs[statPage]
                                val stats = statsMap[type] ?: emptyList()

                                Column(Modifier.fillMaxSize().padding(12.dp)) {
                                    Text(
                                        when (type) {
                                            "周" -> "最近 12 周充电金额"
                                            "月" -> "最近 12 个月充电金额"
                                            "季度" -> "最近 8 个季度充电金额"
                                            "年" -> "各年充电金额"
                                            else -> ""
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    BarChartCard(stats = stats, type = type)
                                    Spacer(Modifier.height(12.dp))
                                    Divider()
                                    Spacer(Modifier.height(8.dp))

                                    // 下方列表
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
                            }
                        }
                    }

                    // ============ 页面 3：设置 ============
                    2 -> {
                        Column(
                            Modifier
                                .padding(16.dp)
                                .fillMaxHeight()
                        ) {
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
                                        if (ok) { refreshAll(); statusMsg = "恢复成功" } else statusMsg = "恢复失败"
                                    }
                                }
                            }) { Text("从 WebDAV 恢复") }

                            if (statusMsg.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(statusMsg)
                            }

                            // 页脚署名（推到底部居中，50% 透明度）
                            Spacer(Modifier.weight(1f))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "胖虎出品",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.alpha(0.5f)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 柱状图卡片：根据 type 自动决定显示多少个柱子
 * - 周、月：最近 12 个
 * - 季度：最近 8 个
 * - 年：全部
 */
@Composable
fun BarChartCard(stats: List<StatItem>, type: String) {
    val maxBars = when (type) {
        "周", "月" -> 12
        "季度" -> 8
        "年" -> Int.MAX_VALUE
        else -> 12
    }
    // stats 是按 period 倒序（最新在前），取前 maxBars 条再反转，让最新的在右边
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

            // 基线
            drawLine(
                color = Color.LightGray,
                start = Offset(0f, h - bottomPadding),
                end = Offset(w, h - bottomPadding),
                strokeWidth = 2f
            )

            // 柱子
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

                // 金额文字
                val amountText = "%.1f".format(item.total)
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = if (barCount > 16) 18f else 22f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    amountText,
                    left + barW / 2,
                    top - 6f,
                    textPaint
                )

                // 周期标签
                val label = simplifyPeriod(item.period)
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = if (barCount > 16) 16f else 20f
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
 * 把周期名简化，方便横轴显示：
 * - "2025年01月" → "01月"
 * - "2025年第03周" → "W03"
 * - "2025年第2季度" → "Q2"
 * - "2025年" → "2025"
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
        period.contains("季度") -> {
            val idx = period.indexOf("第")
            if (idx >= 0) "Q" + period.substring(idx + 1, period.length - 2) else period
        }
        else -> period
    }
}
