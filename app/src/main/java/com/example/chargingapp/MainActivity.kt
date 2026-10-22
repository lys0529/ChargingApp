@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.example.chargingapp

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    var statusMsg by remember { mutableStateOf("") }

    // 输入状态
    val dateFmt = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
    var inputDate by remember { mutableStateOf(dateFmt.format(Date())) }
    var inputAmount by remember { mutableStateOf("") }

    // 自定义键盘弹窗状态
    var showKeyboard by remember { mutableStateOf(false) }

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

    fun refreshAll() {
        records = db.getAll()
        for (t in statTabs) {
            statsMap[t] = db.getStats(t)
        }
        MyWidgetProvider.refreshAll(context)
    }

    LaunchedEffect(Unit) { refreshAll() }

    // --- 手动备份恢复的选择器 ---
    
    // 1. 导入 Excel
    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val list = ExcelHelper.importFromExcel(context, it)
                if (list.isNotEmpty()) db.insertBatch(list)
                withContext(Dispatchers.Main) {
                    refreshAll()
                    statusMsg = if (list.isEmpty()) "导入失败：未读取到数据" else "导入 Excel 成功: ${list.size} 条"
                }
            }
        }
    }

    // 2. 备份数据库 (导出)
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        val f = File(context.cacheDir, "temp_backup.txt")
                        db.exportTo(f)
                        f.inputStream().copyTo(outputStream)
                        f.delete()
                    }
                    withContext(Dispatchers.Main) { statusMsg = "数据库备份成功" }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { statusMsg = "备份失败: ${e.message}" }
                }
            }
        }
    }

    // 3. 恢复数据库 (导入)
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val f = File(context.cacheDir, "temp_restore.txt")
                        inputStream.copyTo(f.outputStream())
                        db.importFrom(f)
                        f.delete()
                    }
                    withContext(Dispatchers.Main) {
                        refreshAll()
                        statusMsg = "数据库恢复成功"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { statusMsg = "恢复失败: ${e.message}" }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("充电记账") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

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
                                        modifier = Modifier.weight(1f).height(56.dp),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Icon(Icons.Default.DateRange, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(inputDate)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    
                                    // 金额框：使用 Box 绘制假输入框，完美支持点击
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(56.dp)
                                            .border(
                                                width = 1.dp,
                                                color = Color.Gray,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .clickable { showKeyboard = true }
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (inputAmount.isEmpty()) {
                                            Text("金额", color = Color.Gray, fontSize = 16.sp)
                                        } else {
                                            Text(inputAmount, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                                Row(Modifier.padding(top = 8.dp)) {
                                    Button(onClick = {
                                        val amt = inputAmount.toDoubleOrNull()
                                        if (amt != null && inputDate.isNotBlank()) {
                                            db.insert(inputDate, amt)
                                            inputAmount = ""
                                            refreshAll()
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
                            Text("本地备份与恢复", style = MaterialTheme.typography.titleLarge)
                            Text("您的数据完全保存在本地，不会上传到任何服务器。", fontSize = 12.sp, color = Color.Gray)
                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    backupLauncher.launch("充电记账_备份_$time.txt")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("导出备份文件")
                            }

                            Spacer(Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    restoreLauncher.launch("*/*")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("导入备份文件 (覆盖当前数据)")
                            }

                            if (statusMsg.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text(statusMsg, color = MaterialTheme.colorScheme.primary)
                            }

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

    // ---- 自定义数字键盘弹窗 ----
    if (showKeyboard) {
        ModalBottomSheet(
            onDismissRequest = { showKeyboard = false },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            CustomNumberKeyboard(
                initialValue = inputAmount,
                onConfirm = { value ->
                    inputAmount = value
                    showKeyboard = false
                }
            )
        }
    }
}

@Composable
fun CustomNumberKeyboard(
    initialValue: String,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf(initialValue) }

    fun handleKey(key: String) {
        when (key) {
            "DEL" -> {
                if (input.isNotEmpty()) {
                    input = input.dropLast(1)
                }
            }
            "CLR" -> input = ""
            "." -> {
                if (!input.contains(".")) {
                    input = if (input.isEmpty()) "0." else "$input."
                }
            }
            else -> {
                if (input == "0") {
                    input = key
                } else {
                    if (input.length < 10) {
                        input += key
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = if (input.isEmpty()) "0" else input,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, end = 8.dp)
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("1", Modifier.weight(1f)) { handleKey("1") }
            KeyButton("2", Modifier.weight(1f)) { handleKey("2") }
            KeyButton("3", Modifier.weight(1f)) { handleKey("3") }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("4", Modifier.weight(1f)) { handleKey("4") }
            KeyButton("5", Modifier.weight(1f)) { handleKey("5") }
            KeyButton("6", Modifier.weight(1f)) { handleKey("6") }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("7", Modifier.weight(1f)) { handleKey("7") }
            KeyButton("8", Modifier.weight(1f)) { handleKey("8") }
            KeyButton("9", Modifier.weight(1f)) { handleKey("9") }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton(".", Modifier.weight(1f)) { handleKey(".") }
            KeyButton("0", Modifier.weight(1f)) { handleKey("0") }
            KeyButton("DEL", Modifier.weight(1f), isAction = true) { handleKey("DEL") }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("清空", Modifier.weight(1f), isAction = true) { handleKey("CLR") }
            KeyButton("确认", Modifier.weight(2f), isPrimary = true) {
                onConfirm(input)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    isAction: Boolean = false,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = when {
        isPrimary -> MaterialTheme.colorScheme.primary
        isAction -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        isPrimary -> MaterialTheme.colorScheme.onPrimary
        isAction -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = if (text == "DEL" || text == "清空" || text == "确认") 16.sp else 24.sp,
            color = textColor,
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun BarChartCard(stats: List<StatItem>, type: String) {
    val maxBars = when (type) {
        "周", "月" -> 12
        "季度" -> 8
        "年" -> Int.MAX_VALUE
        else -> 12
    }
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

            drawLine(
                color = Color.LightGray,
                start = Offset(0f, h - bottomPadding),
                end = Offset(w, h - bottomPadding),
                strokeWidth = 2f
            )

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
