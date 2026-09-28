package com.example.roomexpensesplitter

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

data class Member(val id: Int = 0, val name: String = "")
data class DeletionNotice(
    val eventId: String,
    val expenseId: Long,
    val title: String,
    val amount: Double,
    val category: String,
    val payerId: Int,
    val deletedById: Int = 0,
    val deletedByName: String = ""
)
data class MessPayment(
    val id: Long = 0L,
    val date: String = "",
    val amount: Double = 0.0,
    val payerId: Int = 0
)
data class Expense(
    val id: Long = 0L,
    val title: String = "Expense",
    val amount: Double = 0.0,
    val payerId: Int = 0,
    val participants: Set<Int> = emptySet(),
    val month: String = "",
    val category: String = "Other"
)

private val categories = listOf("Rent", "Gas / Water", "Lottery", "Mess", "Car", "Other")
private val defaultMembers = listOf(
    Member(1, "maneesh"), Member(2, "vishnu"), Member(3, "aneesh"),
    Member(4, "githin"), Member(5, "binish"), Member(6, "shahul"), Member(7, "anoop")
)

/**
 * Shared cloud store. Every installed copy of the app reads/writes the same
 * household/default path, so an expense added on one phone appears on all
 * other phones in real time.
 */
class FirebaseExpenseStore(private val context: Context, private val roomCode: String) {
    // Direct Realtime Database REST sync. No Firebase Authentication call is
    // needed, so a DNS problem resolving identitytoolkit.googleapis.com cannot
    // block adding expenses. The database rules must permit read/write access
    // for this shared household path.
    // Try both Firebase Realtime Database hostnames. Some mobile networks/DNS
    // resolvers fail to resolve the legacy *.firebaseio.com hostname even though
    // the database itself is reachable. The active URL is remembered after a
    // successful request, so all reads/writes use the working endpoint.
    private val databaseUrls = listOf(
        "https://roomexpensesplitter-47254-default-rtdb.firebaseio.com",
        "https://roomexpensesplitter-47254-default-rtdb.firebasedatabase.app"
    )
    @Volatile private var activeDatabaseUrl: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = false
    private var onDataCallback: ((List<Member>, List<Expense>, List<DeletionNotice>, List<MessPayment>) -> Unit)? = null
    private var onReadyCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private val pendingExpenses = java.util.Collections.synchronizedList(mutableListOf<Expense>())
    private val pendingDeletes = java.util.Collections.synchronizedList(mutableListOf<Long>())
    private val pendingMembers = java.util.Collections.synchronizedList(mutableListOf<List<Member>>())
    private val pendingDeletionEvents = java.util.Collections.synchronizedList(mutableListOf<DeletionNotice>())
    // IDs currently being written/deleted. While a write is in flight, an older
    // cloud poll must not make the local UI briefly show stale data again.
    private val pendingExpenseIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val pendingMessPayments = java.util.Collections.synchronizedList(mutableListOf<MessPayment>())
    private val pendingDeleteIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    fun start(
        onData: (List<Member>, List<Expense>, List<DeletionNotice>, List<MessPayment>) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        running = true
        onDataCallback = onData
        onReadyCallback = onReady
        onErrorCallback = onError
        Thread { pollLoop() }.start()
    }

    private fun flushPendingWrites() {
        val messToSave = synchronized(pendingMessPayments) {
            val copy = pendingMessPayments.toList()
            pendingMessPayments.clear()
            copy
        }
        messToSave.forEach { p ->
            try { putMessPayment(p) } catch (_: Throwable) { pendingMessPayments.add(p) }
        }

        val expensesToSave = synchronized(pendingExpenses) {
            val copy = pendingExpenses.toList()
            pendingExpenses.clear()
            copy
        }
        expensesToSave.forEach { e ->
            try { putExpense(e); pendingExpenseIds.remove(e.id) } catch (_: Throwable) { pendingExpenses.add(e) }
        }

        val deletionEventsToSave = synchronized(pendingDeletionEvents) {
            val copy = pendingDeletionEvents.toList()
            pendingDeletionEvents.clear()
            copy
        }
        deletionEventsToSave.forEach { notice ->
            try { putDeletionEvent(notice) } catch (_: Throwable) { pendingDeletionEvents.add(notice) }
        }

        val deletesToApply = synchronized(pendingDeletes) {
            val copy = pendingDeletes.toList()
            pendingDeletes.clear()
            copy
        }
        deletesToApply.forEach { id ->
            try { httpDelete("${baseUrl()}/households/$roomCode/expenses/$id.json"); pendingDeleteIds.remove(id) }
            catch (_: Throwable) { pendingDeletes.add(id) }
        }

        val latestMembers = synchronized(pendingMembers) { pendingMembers.removeLastOrNull() }
        if (latestMembers != null) {
            try { putMembers(latestMembers) } catch (_: Throwable) { pendingMembers.add(latestMembers) }
        }
    }

    private fun pollLoop() {
        var announcedReady = false
        while (running) {
            try {
                flushPendingWrites()
                val json = httpGet("${baseUrl()}/households/$roomCode.json")
                val (members, cloudExpenses) = parseRoot(json)
                val messPayments = parseMessPayments(json)
                val deletionNotices = parseDeletionNotices(json)
                val expenses = cloudExpenses.filterNot { pendingDeleteIds.contains(it.id) }
                    .let { cloud ->
                        val byId = cloud.associateBy { it.id }.toMutableMap()
                        synchronized(pendingExpenses) { pendingExpenses.forEach { byId[it.id] = it } }
                        synchronized(pendingExpenseIds) { pendingExpenseIds.forEach { id ->
                            // Keep any locally pending record until its PUT succeeds.
                            synchronized(pendingExpenses) { pendingExpenses.firstOrNull { it.id == id }?.let { byId[id] = it } }
                        } }
                        byId.values.sortedBy { it.id }
                    }
                handler.post {
                    onDataCallback?.invoke(members, expenses, deletionNotices, messPayments)
                    if (!announcedReady) {
                        announcedReady = true
                        onReadyCallback?.invoke()
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    // Do not block the Add dialog with a Firebase error popup.
                    // Keep the app usable offline and retry automatically.
                    onErrorCallback?.invoke("Sync temporarily unavailable. Local changes will retry automatically.")
                }
            }
            try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
        }
    }

    private fun putExpense(e: Expense) {
        // Store Mess participants as an explicit JSON array.
        // This preserves an exact selection such as 3/7, and also preserves
        // an intentional empty selection without it being confused with
        // legacy records that had no participant field.
        val participants = org.json.JSONArray().apply {
            e.participants.sorted().forEach { put(it) }
        }
        val data = org.json.JSONObject().apply {
            put("id", e.id)
            put("title", e.title)
            put("amount", e.amount)
            put("payerId", e.payerId)
            put("participants", participants)
            put("month", e.month)
            put("category", e.category)
        }
        httpPut("${baseUrl()}/households/$roomCode/expenses/${e.id}.json", data.toString())
    }

    private fun putMessPayment(p: MessPayment) {
        val data = org.json.JSONObject().apply {
            put("id", p.id)
            put("date", p.date)
            put("amount", p.amount)
            put("payerId", p.payerId)
        }
        httpPut("${baseUrl()}/households/$roomCode/messPayments/${p.id}.json", data.toString())
    }

    private fun putMembers(members: List<Member>) {
        val data = org.json.JSONObject().apply {
            members.forEach { m ->
                put(m.id.toString(), org.json.JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                })
            }
        }
        httpPut("${baseUrl()}/households/$roomCode/members.json", data.toString())
    }

    private fun baseUrl(): String = activeDatabaseUrl ?: databaseUrls.first()

    private fun <T> withDatabaseFallback(request: (String) -> T): T {
        val preferred = activeDatabaseUrl
        val candidates = if (preferred != null) {
            listOf(preferred) + databaseUrls.filter { it != preferred }
        } else databaseUrls
        var last: Throwable? = null
        for (base in candidates) {
            try {
                val result = request(base)
                activeDatabaseUrl = base
                return result
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("No Firebase endpoint available")
    }

    private fun databasePath(url: String): String {
        for (base in databaseUrls) {
            if (url.startsWith(base)) return url.removePrefix(base)
        }
        return url.substringAfter(".com", url).substringAfter(".app", url)
    }

    private fun httpGet(url: String): String {
        val path = databasePath(url)
        return withDatabaseFallback { base ->
            val conn = (java.net.URL(base + path).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            readResponse(conn)
        }
    }

    private fun httpPut(url: String, body: String) {
        val path = databasePath(url)
        withDatabaseFallback { base ->
            val conn = (java.net.URL(base + path).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        }
    }

    private fun httpDelete(url: String) {
        val path = databasePath(url)
        withDatabaseFallback { base ->
            val conn = (java.net.URL(base + path).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 10000
                readTimeout = 10000
            }
            readResponse(conn)
        }
    }

    private fun readResponse(conn: java.net.HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Firebase HTTP $code")
        return text
    }

    private fun parseRoot(json: String): Pair<List<Member>, List<Expense>> {
        if (json == "null" || json.isBlank()) return defaultMembers to emptyList()
        val root = org.json.JSONObject(json)
        val members = mutableListOf<Member>()
        root.optJSONObject("members")?.let { obj ->
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key)
                if (item != null) {
                    val id = item.optInt("id", key.toIntOrNull() ?: 0)
                    val name = item.optString("name", "")
                    if (id != 0 && name.isNotBlank()) members += Member(id, name)
                }
            }
        }
        val finalMembers = members.sortedBy { it.id }.ifEmpty { defaultMembers }
        val expenses = mutableListOf<Expense>()
        root.optJSONObject("expenses")?.let { obj ->
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                val id = item.optLong("id", key.toLongOrNull() ?: 0L)
                if (id == 0L) return@forEach
                val participants = mutableSetOf<Int>()
                // V15 format: explicit JSON array. Also read the older object
                // format so existing shared expenses continue to work.
                item.optJSONArray("participants")?.let { pa ->
                    for (i in 0 until pa.length()) {
                        val pid = pa.optInt(i, 0)
                        if (pid != 0) participants += pid
                    }
                } ?: item.optJSONObject("participants")?.let { po ->
                    po.keys().forEach { pk ->
                        val v = po.opt(pk)
                        val pid = when (v) {
                            is Number -> v.toInt()
                            else -> pk.toIntOrNull()
                        }
                        if (pid != null && pid != 0) participants += pid
                    }
                }
                val amount = item.optDouble("amount", 0.0)
                val month = item.optString("month", LocalDate.now().toString().substring(0, 7))
                expenses += Expense(id, item.optString("title", "Expense"), amount,
                    item.optInt("payerId", 0), participants, month,
                    item.optString("category", "Other"))
            }
        }
        return finalMembers to expenses.sortedBy { it.id }
    }

    private fun parseMessPayments(json: String): List<MessPayment> {
        if (json == "null" || json.isBlank()) return emptyList()
        return try {
            val root = org.json.JSONObject(json)
            val obj = root.optJSONObject("messPayments") ?: return emptyList()
            val result = mutableListOf<MessPayment>()
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                val id = item.optLong("id", key.toLongOrNull() ?: 0L)
                val amount = item.optDouble("amount", 0.0)
                val payerId = item.optInt("payerId", 0)
                val date = item.optString("date", "")
                if (id != 0L && amount > 0.0 && payerId != 0) result += MessPayment(id, date, amount, payerId)
            }
            result.sortedBy { it.id }
        } catch (_: Throwable) { emptyList() }
    }

    private fun putDeletionEvent(notice: DeletionNotice) {
        val data = org.json.JSONObject().apply {
            put("eventId", notice.eventId)
            put("expenseId", notice.expenseId)
            put("title", notice.title)
            put("amount", notice.amount)
            put("category", notice.category)
            put("payerId", notice.payerId)
            put("deletedById", notice.deletedById)
            put("deletedByName", notice.deletedByName)
            put("deletedAt", System.currentTimeMillis())
        }
        httpPut("${baseUrl()}/households/$roomCode/deletionEvents/${notice.eventId}.json", data.toString())
    }

    private fun parseDeletionNotices(json: String): List<DeletionNotice> {
        if (json == "null" || json.isBlank()) return emptyList()
        return try {
            val root = org.json.JSONObject(json)
            val obj = root.optJSONObject("deletionEvents") ?: return emptyList()
            val result = mutableListOf<DeletionNotice>()
            obj.keys().forEach { key ->
                val item = obj.optJSONObject(key) ?: return@forEach
                val eventId = item.optString("eventId", key)
                val expenseId = item.optLong("expenseId", 0L)
                if (eventId.isNotBlank() && expenseId != 0L) {
                    result += DeletionNotice(
                        eventId, expenseId,
                        item.optString("title", "Expense"),
                        item.optDouble("amount", 0.0),
                        item.optString("category", "Other"),
                        item.optInt("payerId", 0),
                        item.optInt("deletedById", 0),
                        item.optString("deletedByName", "")
                    )
                }
            }
            result.sortedBy { it.eventId }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun deleteExpense(e: Expense, deletedById: Int, deletedByName: String) {
        pendingDeleteIds.add(e.id)
        val notice = DeletionNotice(
            eventId = "${System.currentTimeMillis()}_${e.id}",
            expenseId = e.id,
            title = e.title,
            amount = e.amount,
            category = e.category,
            payerId = e.payerId,
            deletedById = deletedById,
            deletedByName = deletedByName
        )
        pendingDeletes.add(e.id)
        Thread {
            try {
                httpDelete("${baseUrl()}/households/$roomCode/expenses/${e.id}.json")
                pendingDeletes.remove(e.id)
                pendingDeleteIds.remove(e.id)
                try {
                    putDeletionEvent(notice)
                } catch (_: Throwable) {
                    // The expense is already deleted; retry only the notification event.
                    pendingDeletionEvents.add(notice)
                }
            } catch (_: Throwable) {
                // pollLoop will retry the delete. No deletion notification is emitted
                // until the delete actually succeeds.
            }
        }.start()
    }

    fun saveExpense(e: Expense) {
        pendingExpenseIds.add(e.id)
        pendingExpenses.add(e)
        Thread {
            try { putExpense(e); pendingExpenses.removeIf { it.id == e.id }; pendingExpenseIds.remove(e.id) }
            catch (_: Throwable) { /* pollLoop will retry */ }
        }.start()
    }

    fun saveMessPayment(payment: MessPayment) {
        pendingMessPayments.add(payment)
        Thread {
            try { putMessPayment(payment); pendingMessPayments.removeIf { it.id == payment.id } }
            catch (_: Throwable) { /* pollLoop will retry */ }
        }.start()
    }

    fun saveMembers(members: List<Member>) {
        Thread {
            try { putMembers(members) }
            catch (_: Throwable) {
                synchronized(pendingMembers) {
                    pendingMembers.clear()
                    pendingMembers.add(members)
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        onDataCallback = null
        onReadyCallback = null
        onErrorCallback = null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoomExpenseApp(this) }
    }
}

private fun money(v: Double): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}.format(v)

private fun categoryNet(
    expenses: List<Expense>,
    memberId: Int,
    category: String,
    allMemberIds: Set<Int> = emptySet()
): Double {
    var value = 0.0
    expenses.filter { it.category == category }.forEach { e ->
        if (e.category == "Mess") {
            // Mess uses exactly the saved participant list. Empty means no one shares it.
            val participantIds = e.participants
            if (participantIds.isNotEmpty() && memberId in participantIds) {
                value += e.amount / participantIds.size.toDouble()
            }
            // Payer paid the full bill, so credit that payment back.
            if (memberId == e.payerId) value -= e.amount
        } else {
            // Fixed expenses (Rent, Gas/Water, Lottery, Car, Other) are per-person.
            // Older synced records may have an empty participant list; treat those
            // records as applying to every current member as the UI does for new
            // fixed expenses. This keeps existing expenses visible in Tracker.
            val participantIds = if (e.participants.isNotEmpty()) e.participants else allMemberIds
            if (memberId in participantIds) value += e.amount
        }
    }
    return value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomExpenseApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("room_expenses", Context.MODE_PRIVATE) }
    var roomCode by remember { mutableStateOf(prefs.getString("room_code", "") ?: "") }
    var codeInput by remember { mutableStateOf("") }
    var store by remember { mutableStateOf<FirebaseExpenseStore?>(null) }
    var members by remember { mutableStateOf(defaultMembers) }
    var expenses by remember { mutableStateOf(emptyList<Expense>()) }
    var messPayments by remember { mutableStateOf(emptyList<MessPayment>()) }
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var firebaseReady by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showRoomLogin by remember { mutableStateOf(roomCode.isBlank()) }
    var showRoomCode by remember { mutableStateOf(false) }
    var showCurrentUser by remember { mutableStateOf(false) }
    var currentUserId by remember { mutableIntStateOf(prefs.getInt("current_user_id_$roomCode", 0)) }
    val month = LocalDate.now().toString().substring(0, 7)
    val monthExpenses = expenses.filter { it.month == month }
    var showCar by remember { mutableStateOf(false) }
    var deletionNotification by remember { mutableStateOf<String?>(null) }
    var seenDeletionEvents by remember { mutableStateOf(prefs.getStringSet("seen_deletion_events", emptySet()) ?: emptySet()) }
    var hasInitialSync by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        showCar = prefs.getBoolean("show_car_column", false)
        onDispose { store?.stop() }
    }

    fun connectToRoom(code: String) {
        val clean = code.filter { it.isDigit() }.take(6)
        if (clean.length != 6) return
        store?.stop()
        store = FirebaseExpenseStore(context.applicationContext, clean)
        members = defaultMembers
        val savedUserId = prefs.getInt("current_user_id_$clean", 0)
        currentUserId = if (defaultMembers.any { it.id == savedUserId }) savedUserId else defaultMembers.firstOrNull()?.id ?: 0
        if (currentUserId != 0) prefs.edit().putInt("current_user_id_$clean", currentUserId).apply()
        expenses = emptyList()
        firebaseReady = false
        errorText = null
        hasInitialSync = false
        roomCode = clean
        prefs.edit().putString("room_code", clean).apply()
        showRoomLogin = false
        store?.start(
            onData = { cloudMembers, cloudExpenses, deletionEvents, cloudMessPayments ->
                members = cloudMembers
                if (cloudMembers.none { it.id == currentUserId }) {
                    currentUserId = cloudMembers.firstOrNull()?.id ?: 0
                    if (currentUserId != 0) prefs.edit().putInt("current_user_id_$roomCode", currentUserId).apply()
                }
                expenses = cloudExpenses
                messPayments = cloudMessPayments
                if (!hasInitialSync) {
                    val allIds = deletionEvents.map { it.eventId }.toSet()
                    seenDeletionEvents = allIds
                    prefs.edit().putStringSet("seen_deletion_events", allIds).apply()
                    hasInitialSync = true
                } else {
                    val fresh = deletionEvents.filter { it.eventId !in seenDeletionEvents }.maxByOrNull { it.eventId }
                    if (fresh != null) {
                        val payerName = members.firstOrNull { it.id == fresh.payerId }?.name
                        val deleterName = fresh.deletedByName.ifBlank { members.firstOrNull { it.id == fresh.deletedById }?.name ?: "Unknown" }
                        deletionNotification = if (payerName != null && fresh.payerId != 0) {
                            "🔔 $deleterName deleted ${fresh.category} ₹${String.format(Locale.US, "%.2f", fresh.amount)} (paid by $payerName)"
                        } else {
                            "🔔 $deleterName deleted ${fresh.category} ₹${String.format(Locale.US, "%.2f", fresh.amount)}"
                        }
                        val updatedSeen = (seenDeletionEvents + deletionEvents.map { it.eventId }).toList().takeLast(100).toSet()
                        seenDeletionEvents = updatedSeen
                        prefs.edit().putStringSet("seen_deletion_events", updatedSeen).apply()
                    }
                }
            },
            onReady = { firebaseReady = true },
            onError = { if (!firebaseReady) errorText = null }
        )
    }

    LaunchedEffect(roomCode) {
        if (roomCode.length == 6 && store == null && !showRoomLogin) connectToRoom(roomCode)
    }

    if (showRoomLogin) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🏠 HOUSEHOLD EXPENSE TRACKER 💰", fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("Create or join your household", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.filter(Char::isDigit).take(6) },
                        label = { Text("6-digit household code") },
                        placeholder = { Text("Example: 482731") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = codeInput.length == 6,
                        onClick = { connectToRoom(codeInput) }
                    ) { Text("Join Household") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        val newCode = (100000..999999).random().toString()
                        codeInput = newCode
                        connectToRoom(newCode)
                        showRoomCode = true
                    }) { Text("Create New Household") }
                    Spacer(Modifier.height(16.dp))
                    Text("Use the same code on every phone. Everyone with the same code shares the same expenses.", textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            }
        }
        return
    }

    MaterialTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("🏠 HOUSEHOLD EXPENSE TRACKER 💰", fontWeight = FontWeight.Bold)
                            Text(
                                if (firebaseReady) "Household $roomCode • Live Sync ON" else "Household $roomCode • Connecting / retrying…",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { showCurrentUser = true }) {
                            val youName = members.firstOrNull { it.id == currentUserId }?.name ?: "You"
                            Text("You: $youName")
                        }
                        TextButton(onClick = { showRoomCode = true }) { Text("CODE") }
                        TextButton(onClick = { firebaseReady = false; store?.stop(); store = null; connectToRoom(roomCode) }) {
                            Text(if (firebaseReady) "SYNC ON" else "SYNC…")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.TableChart, contentDescription = "Tracker") }, label = { Text("Tracker") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.ReceiptLong, contentDescription = "Expenses") }, label = { Text("Expenses") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.People, contentDescription = "People") }, label = { Text("People") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Text("🍽️") }, label = { Text("Mess") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> TrackerScreen(Modifier.padding(padding), members, monthExpenses, showCar, onToggleCar = {
                    showCar = !showCar
                    prefs.edit().putBoolean("show_car_column", showCar).apply()
                })
                1 -> ExpensesScreen(Modifier.padding(padding), members, monthExpenses) { deletedExpense ->
                    expenses = expenses.filterNot { it.id == deletedExpense.id }
                    val deletedByName = members.firstOrNull { it.id == currentUserId }?.name ?: "Unknown"
                    store?.deleteExpense(deletedExpense, currentUserId, deletedByName)
                }
                2 -> PeopleScreen(Modifier.padding(padding), members) { updated ->
                    members = updated
                    store?.saveMembers(updated)
                }
                else -> MessScreen(Modifier.padding(padding), members, messPayments, onAdd = { showAdd = true })
            }
        }

        LaunchedEffect(deletionNotification) {
        val message = deletionNotification ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        deletionNotification = null
    }

    if (showAdd) {
        if (tab == 3) {
            AddMessDialog(
                members = members,
                onDismiss = { showAdd = false },
                onSave = { date, amount, payerId ->
                    val payment = MessPayment(System.currentTimeMillis(), date, amount, payerId)
                    messPayments = (messPayments + payment).distinctBy { it.id }.sortedBy { it.id }
                    store?.saveMessPayment(payment)
                    showAdd = false
                }
            )
        } else {
            AddExpenseDialog(
                members = members,
                onDismiss = { showAdd = false },
                onSave = { title, amount, payer, participants, category ->
                    val finalParticipants = if (category == "Mess") participants else members.map { it.id }.toSet()
                    val e = Expense(System.currentTimeMillis(), title, amount, payer, finalParticipants, month, category)
                    expenses = (expenses + e).distinctBy { it.id }.sortedBy { it.id }
                    store?.saveExpense(e)
                    showAdd = false
                }
            )
        }
    }

        if (showCurrentUser) {
            AlertDialog(
                onDismissRequest = { showCurrentUser = false },
                title = { Text("👤 Who are you?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Select your name on this phone. Deletes from this phone will show this name to everyone.", fontSize = 13.sp)
                        members.forEach { member ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    currentUserId = member.id
                                    prefs.edit().putInt("current_user_id_$roomCode", member.id).apply()
                                    showCurrentUser = false
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = currentUserId == member.id, onClick = {
                                    currentUserId = member.id
                                    prefs.edit().putInt("current_user_id_$roomCode", member.id).apply()
                                    showCurrentUser = false
                                })
                                Text(member.name)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCurrentUser = false }) { Text("Close") } }
            )
        }

        if (showRoomCode) {
            AlertDialog(
                onDismissRequest = { showRoomCode = false },
                title = { Text("🏠 Household Code") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Use this exact 6-digit code on the other phones:", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(roomCode, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { showRoomCode = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = {
                    showRoomCode = false
                    store?.stop(); store = null
                    prefs.edit().remove("room_code").apply()
                    roomCode = ""
                    codeInput = ""
                    showRoomLogin = true
                }) { Text("Change Code") } }
            )
        }

        errorText?.let { message ->
            AlertDialog(onDismissRequest = { errorText = null }, title = { Text("Sync") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { errorText = null }) { Text("OK") } })
        }
    }
}

@Composable
private fun TrackerScreen(
    modifier: Modifier,
    members: List<Member>,
    expenses: List<Expense>,
    showCar: Boolean,
    onToggleCar: () -> Unit
) {
    val visibleCategories = categories.filter { it != "Car" || showCar }
    val nameWeight = 1.45f
    val cellWeight = 1f
    val totalWeight = nameWeight + (visibleCategories.size + 1) * cellWeight

    BoxWithConstraints(modifier.fillMaxSize()) {
        val nameWidth = maxWidth * (nameWeight / totalWeight)
        val cellWidth = maxWidth * (cellWeight / totalWeight)
        Column(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 1.dp)) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HOUSEHOLD EXPENSE TRACKER", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    Text("${expenses.size} expense(s) • current month", fontSize = 8.sp, maxLines = 1)
                }
                TextButton(onClick = onToggleCar, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                    Text(if (showCar) "Hide Car" else "Car: Hidden", fontSize = 9.sp, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                CompactHeaderCell("NAME", nameWidth, Color(0xFF123B70))
                for (category in visibleCategories) {
                    CompactHeaderCell(category.replace("Gas / Water", "GAS /\nWATER").uppercase(), cellWidth, Color(0xFF123B70))
                }
                CompactHeaderCell("TOTAL", cellWidth, Color(0xFF0B7A3A))
            }
            for (member in members) {
                Row(Modifier.fillMaxWidth().height(26.dp)) {
                    CompactNameCell(member.name, nameWidth)
                    for (category in visibleCategories) {
                        CompactValueCell(categoryNet(expenses, member.id, category, members.map { it.id }.toSet()), cellWidth)
                    }
                    CompactValueCell(visibleCategories.sumOf { categoryNet(expenses, member.id, it, members.map { it.id }.toSet()) }, cellWidth, total = true)
                }
            }
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                CompactHeaderCell("TOTAL", nameWidth, Color(0xFFDDEBD5), Color.Black)
                for (category in visibleCategories) {
                    CompactValueCell(members.sumOf { categoryNet(expenses, it.id, category, members.map { it.id }.toSet()) }, cellWidth, total = true)
                }
                CompactValueCell(members.sumOf { member -> visibleCategories.sumOf { categoryNet(expenses, member.id, it, members.map { it.id }.toSet()) } }, cellWidth, total = true)
            }
            Text("Mess: equal split • whoever pays the mess bill gets that payment deducted from their total • Rent/Water/Gas/Lottery: fixed amount per person", fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun CompactHeaderCell(text: String, width: androidx.compose.ui.unit.Dp, background: Color, textColor: Color = Color.White) {
    Box(Modifier.width(width).height(30.dp).background(background).padding(1.dp), contentAlignment = Alignment.Center) {
        Text(text, color = textColor, fontWeight = FontWeight.Bold, fontSize = 8.sp, lineHeight = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun CompactNameCell(name: String, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(width).height(26.dp).background(Color(0xFFEAF2F8)).padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun CompactValueCell(value: Double, width: androidx.compose.ui.unit.Dp, total: Boolean = false) {
    val positive = value > 0.005
    val negative = value < -0.005
    val textColor = when {
        negative -> Color(0xFFB3261E)
        positive && total -> Color(0xFF087A38)
        else -> Color.DarkGray
    }
    Box(Modifier.width(width).height(26.dp).padding(1.dp), contentAlignment = Alignment.Center) {
        Text(money(value), color = textColor, fontWeight = if (total) FontWeight.Bold else FontWeight.Normal, fontSize = 7.sp, maxLines = 1)
    }
}

@Composable
private fun ExpensesScreen(modifier: Modifier, members: List<Member>, expenses: List<Expense>, onDelete: (Expense) -> Unit) {
    val names = members.associateBy { it.id }
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expenses.isEmpty()) item { Text("No expenses this month. Tap + to add one.") }
        items(expenses) { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.category} • ${e.title}", fontWeight = FontWeight.Bold)
                        if (e.category == "Mess") {
                            Text("${money(e.amount)} • paid by ${names[e.payerId]?.name ?: "Unknown"}")
                            val participantIds = e.participants
                            if (participantIds.isEmpty()) {
                                Text("Split: no members selected")
                            } else {
                                val share = e.amount / participantIds.size
                                Text("Split: " + participantIds.joinToString { id ->
                                    "${names[id]?.name ?: "?"} ${money(share)}"
                                })
                            }
                        } else {
                            Text("${money(e.amount)} • fixed amount per person • no payer")
                            val participantIds = if (e.participants.isNotEmpty()) e.participants else members.map { it.id }.toSet()
                            Text("Split: " + participantIds.joinToString { id -> names[id]?.name ?: "?" })
                        }
                    }
                    TextButton(onClick = { onDelete(e) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun PeopleScreen(modifier: Modifier, members: List<Member>, onSave: (List<Member>) -> Unit) {
    var names by remember(members) { mutableStateOf(members.associate { it.id to it.name }) }
    var nextId by remember(members) { mutableIntStateOf((members.maxOfOrNull { it.id } ?: 0) + 1) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Room members", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(names.keys.toList()) { id ->
                OutlinedTextField(value = names[id] ?: "", onValueChange = { names = names.toMutableMap().apply { put(id, it) } }, label = { Text("Person $id") }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { names = names.toMutableMap().apply { put(nextId, "Person $nextId") }; nextId++ }) { Text("Add person") }
            Button(onClick = { onSave(names.map { Member(it.key, it.value.ifBlank { "Person ${it.key}" }) }) }) { Text("Save") }
        }
    }
}

@Composable
private fun MessScreen(
    modifier: Modifier,
    members: List<Member>,
    payments: List<MessPayment>,
    onAdd: () -> Unit
) {
    val names = members.associateBy { it.id }
    val total = payments.sumOf { it.amount }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("🍽️ Mess", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Text("+ Add Mess") }
        }
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Total Mess", style = MaterialTheme.typography.labelLarge)
                Text(money(total), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (payments.isEmpty()) {
            Text("No Mess payments yet. Tap + Add Mess.", modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(payments.reversed()) { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.date, modifier = Modifier.weight(1.1f), fontWeight = FontWeight.SemiBold)
                            Text(money(p.amount), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                            Text(names[p.payerId]?.name ?: "Unknown", modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMessDialog(
    members: List<Member>,
    onDismiss: () -> Unit,
    onSave: (String, Double, Int) -> Unit
) {
    var date by remember { mutableStateOf(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
    var amountText by remember { mutableStateOf("") }
    var payer by remember { mutableIntStateOf(members.firstOrNull()?.id ?: 1) }
    var payerExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ Add Mess") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (dd.MM.yyyy)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount (₹)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = payerExpanded, onExpandedChange = { payerExpanded = !payerExpanded }) {
                    OutlinedTextField(value = members.firstOrNull { it.id == payer }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Paid by") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = payerExpanded, onDismissRequest = { payerExpanded = false }) {
                        for (m in members) DropdownMenuItem(text = { Text(m.name) }, onClick = { payer = m.id; payerExpanded = false })
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = amountText.toDoubleOrNull()?.let { it > 0 } == true && payer != 0 && date.isNotBlank(), onClick = { onSave(date, amountText.toDouble(), payer) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(members: List<Member>, onDismiss: () -> Unit, onSave: (String, Double, Int, Set<Int>, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var payer by remember { mutableIntStateOf(members.firstOrNull()?.id ?: 1) }
    var selected by remember(members) { mutableStateOf(members.map { it.id }.toSet()) }
    var category by remember { mutableStateOf("Mess") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var payerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(category, members) {
        // Fixed expenses (Rent, Electricity, etc.) apply to everyone automatically.
        // Mess keeps manual member selection because it can be split among selected people.
        if (category != "Mess") {
            selected = members.map { it.id }.toSet()
        }
        // IMPORTANT: for Mess, do not auto-reselect when the set becomes empty.
        // This allows the user to unselect one, several, or all members.

    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ Add Expense") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
                    OutlinedTextField(value = category, onValueChange = {}, readOnly = true, label = { Text("Category") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        for (c in categories) {
                            DropdownMenuItem(text = { Text(c) }, onClick = { category = c; categoryExpanded = false })
                        }
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount (₹)") }, modifier = Modifier.fillMaxWidth())
                if (category == "Mess") {
                    ExposedDropdownMenuBox(expanded = payerExpanded, onExpandedChange = { payerExpanded = !payerExpanded }) {
                        OutlinedTextField(value = members.firstOrNull { it.id == payer }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Paid by") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = payerExpanded, onDismissRequest = { payerExpanded = false }) {
                            for (m in members) {
                                DropdownMenuItem(text = { Text(m.name) }, onClick = { payer = m.id; payerExpanded = false })
                            }
                        }
                    }
                    Text("Who shares this expense? (Mess is split equally)", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selected = members.map { it.id }.toSet() }) { Text("Select all") }
                        TextButton(onClick = { selected = emptySet() }) { Text("Clear all") }
                        Text("Selected: ${selected.size}/${members.size}", modifier = Modifier.padding(top = 12.dp))
                    }
                    for (m in members) {
                        val isSelected = selected.contains(m.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) {
                                        selected + m.id
                                    } else {
                                        selected - m.id
                                    }
                                }
                            )
                            Text(text = m.name, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    Text("Fixed amount per person • applies to all ${members.size} members", fontWeight = FontWeight.SemiBold)
                }
                val amount = amountText.toDoubleOrNull()
                if (amount != null && amount > 0) {
                    Text(
                        if (category == "Mess") {
                            if (selected.isNotEmpty()) "Each share: ${money(amount / selected.size)}" else "No members selected"
                        } else "Fixed amount per person: ${money(amount)}",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = amountText.toDoubleOrNull()?.let { it > 0 } == true,
                onClick = { onSave(title.ifBlank { category }, amountText.toDouble(), if (category == "Mess") payer else 0, selected, category) }
            ) { Text("Save Expense") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
