package com.example.chargingapp

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(db: DatabaseHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var records by remember { mutableStateOf(listOf<Record>()) }
    var stats by remember { mutableStateOf(listOf<StatItem>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var statType by remember { mutableStateOf("月") }

    // WebDAV 配置
    var webdavUrl by remember { mutableStateOf("") }
    var webdavUser by remember { mutableStateOf("") }
    var webdavPass by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("") }

    // 输入状态
    val dateFmt = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
    var inputDate by remember { mutableStateOf(dateFmt.format(Date())) }
    var inputAmount by remember { mutableStateOf("") }

    // 日期选择器对话框
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
    }

    LaunchedEffect(Unit) { refresh() }

    // Excel 选择器
    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val list = ExcelHelper.importFromExcel(context, it)
                if (list.isNotEmpty()) db.insertBatch(list)
                withContext(Dispatchers.Main) {
                    refresh()
                    statusMsg = "导入成功: ${list.size} 条"
                    if (WebDavHelper.isConfigured()) {
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
                        // 日期选择按钮 + 金额输入
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
                                    onClick = { statType = t; refresh() },
                                    label = { Text(t) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
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
                        OutlinedTextField(value = webdavUrl, onValueChange = { webdavUrl = it }, label = { Text("服务器地址 (https://...)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = webdavUser, onValueChange = { webdavUser = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = webdavPass, onValueChange = { webdavPass = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            WebDavHelper.configure(webdavUrl, webdavUser, webdavPass)
                            statusMsg = "已配置"
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
