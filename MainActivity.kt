package com.example.roomexpensesplitter

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

data class Member(val id: Int = 0, val name: String = "")
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
class FirebaseExpenseStore(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val root = FirebaseDatabase.getInstance().reference.child("household").child("default")
    private var listener: ValueEventListener? = null

    fun start(onData: (List<Member>, List<Expense>) -> Unit, onReady: () -> Unit, onError: (String) -> Unit) {
        fun attachAfterSignIn() {
            listener?.let { root.removeEventListener(it) }
            val valueListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val members = snapshot.child("members").children.mapNotNull { child ->
                        val id = child.child("id").getValue(Int::class.java) ?: child.key?.toIntOrNull()
                        val name = child.child("name").getValue(String::class.java)
                        if (id != null && name != null) Member(id, name) else null
                    }.sortedBy { it.id }

                    val finalMembers = if (members.isEmpty()) defaultMembers else members
                    val expenses = snapshot.child("expenses").children.mapNotNull { child ->
                        val id = child.child("id").getValue(Long::class.java) ?: child.key?.toLongOrNull()
                        val title = child.child("title").getValue(String::class.java) ?: "Expense"
                        val amount = child.child("amount").getValue(Double::class.java)
                            ?: child.child("amount").getValue(Long::class.java)?.toDouble()
                            ?: 0.0
                        val payerId = child.child("payerId").getValue(Int::class.java) ?: 0
                        val month = child.child("month").getValue(String::class.java)
                            ?: LocalDate.now().toString().substring(0, 7)
                        val category = child.child("category").getValue(String::class.java) ?: "Other"
                        val participants = child.child("participants").children.mapNotNull {
                            it.getValue(Int::class.java) ?: it.key?.toIntOrNull()
                        }.toSet()
                        if (id != null) Expense(id, title, amount, payerId, participants, month, category) else null
                    }.sortedBy { it.id }

                    onData(finalMembers, expenses)
                    onReady()
                }

                override fun onCancelled(error: DatabaseError) {
                    onError(error.message)
                }
            }
            listener = valueListener
            root.addValueEventListener(valueListener)
        }

        if (auth.currentUser != null) {
            attachAfterSignIn()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { attachAfterSignIn() }
                .addOnFailureListener { onError(it.message ?: "Firebase sign-in failed") }
        }
    }

    fun saveExpense(e: Expense) {
        val participants = e.participants.associate { it.toString() to true }
        val data = mapOf(
            "id" to e.id,
            "title" to e.title,
            "amount" to e.amount,
            "payerId" to e.payerId,
            "participants" to participants,
            "month" to e.month,
            "category" to e.category
        )
        root.child("expenses").child(e.id.toString()).setValue(data)
    }

    fun deleteExpense(id: Long) {
        root.child("expenses").child(id.toString()).removeValue()
    }

    fun saveMembers(members: List<Member>) {
        val data = members.associate { it.id.toString() to mapOf("id" to it.id, "name" to it.name) }
        root.child("members").setValue(data)
    }

    fun stop() {
        listener?.let { root.removeEventListener(it) }
        listener = null
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

private fun categoryNet(expenses: List<Expense>, memberId: Int, category: String): Double {
    var value = 0.0
    expenses.filter { it.category == category }.forEach { e ->
        if (e.category == "Mess") {
            if (e.participants.isNotEmpty() && memberId in e.participants) {
                value -= e.amount / e.participants.size.toDouble()
            }
            if (memberId == e.payerId) value += e.amount
        } else {
            if (memberId in e.participants) value += e.amount
        }
    }
    return value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomExpenseApp(context: Context) {
    val store = remember { FirebaseExpenseStore(context.applicationContext) }
    var members by remember { mutableStateOf(defaultMembers) }
    var expenses by remember { mutableStateOf(emptyList<Expense>()) }
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var firebaseReady by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val month = LocalDate.now().toString().substring(0, 7)
    val monthExpenses = expenses.filter { it.month == month }
    var showCar by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("room_expenses", Context.MODE_PRIVATE) }

    DisposableEffect(store) {
        showCar = prefs.getBoolean("show_car_column", false)
        store.start(
            onData = { cloudMembers, cloudExpenses ->
                members = cloudMembers
                expenses = cloudExpenses
            },
            onReady = { firebaseReady = true },
            onError = { errorText = it }
        )
        onDispose { store.stop() }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("🏠 HOUSEHOLD EXPENSE TRACKER 💰", fontWeight = FontWeight.Bold)
                        Text(
                            if (firebaseReady) "Shared / Live Sync ON" else "Connecting to shared database...",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                })
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { if (firebaseReady) showAdd = true }) { Text("+") }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▦") }, label = { Text("Tracker") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("≡") }, label = { Text("Expenses") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("👥") }, label = { Text("People") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> TrackerScreen(
                    Modifier.padding(padding), members, monthExpenses, showCar,
                    onToggleCar = {
                        showCar = !showCar
                        prefs.edit().putBoolean("show_car_column", showCar).apply()
                    }
                )
                1 -> ExpensesScreen(Modifier.padding(padding), members, monthExpenses) { id ->
                    store.deleteExpense(id)
                }
                else -> PeopleScreen(Modifier.padding(padding), members) { updated ->
                    store.saveMembers(updated)
                }
            }
        }

        if (showAdd && firebaseReady) {
            AddExpenseDialog(
                members = members,
                onDismiss = { showAdd = false },
                onSave = { title, amount, payer, participants, category ->
                    val e = Expense(System.currentTimeMillis(), title, amount, payer, participants, month, category)
                    store.saveExpense(e)
                    showAdd = false
                }
            )
        }

        errorText?.let { message ->
            AlertDialog(
                onDismissRequest = { errorText = null },
                title = { Text("Firebase connection") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { errorText = null }) { Text("OK") } }
            )
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
                visibleCategories.forEach { category ->
                    CompactHeaderCell(category.replace("Gas / Water", "GAS /\nWATER").uppercase(), cellWidth, Color(0xFF123B70))
                }
                CompactHeaderCell("TOTAL", cellWidth, Color(0xFF0B7A3A))
            }
            members.forEach { member ->
                Row(Modifier.fillMaxWidth().height(26.dp)) {
                    CompactNameCell(member.name, nameWidth)
                    visibleCategories.forEach { category -> CompactValueCell(categoryNet(expenses, member.id, category), cellWidth) }
                    CompactValueCell(visibleCategories.sumOf { categoryNet(expenses, member.id, it) }, cellWidth, total = true)
                }
            }
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                CompactHeaderCell("TOTAL", nameWidth, Color(0xFFDDEBD5), Color.Black)
                visibleCategories.forEach { category -> CompactValueCell(members.sumOf { categoryNet(expenses, it.id, category) }, cellWidth, total = true) }
                CompactValueCell(members.sumOf { member -> visibleCategories.sumOf { categoryNet(expenses, member.id, it) } }, cellWidth, total = true)
            }
            Text("Mess: equal split • Rent/Water/Gas/Lottery: fixed amount per person • no payer", fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
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
private fun ExpensesScreen(modifier: Modifier, members: List<Member>, expenses: List<Expense>, onDelete: (Long) -> Unit) {
    val names = members.associateBy { it.id }
    LazyColumn(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expenses.isEmpty()) item { Text("No expenses this month. Tap + to add one.") }
        items(expenses) { e ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.category} • ${e.title}", fontWeight = FontWeight.Bold)
                        if (e.category == "Mess") Text("${money(e.amount)} • paid by ${names[e.payerId]?.name ?: "Unknown"}")
                        else Text("${money(e.amount)} • fixed amount per person • no payer")
                        Text("Split: ${e.participants.joinToString { names[it]?.name ?: "?" }}")
                    }
                    IconButton(onClick = { onDelete(e.id) }) { Text("Delete") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(members: List<Member>, onDismiss: () -> Unit, onSave: (String, Double, Int, Set<Int>, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var payer by remember { mutableIntStateOf(members.firstOrNull()?.id ?: 1) }
    var selected by remember { mutableStateOf(members.map { it.id }.toSet()) }
    var category by remember { mutableStateOf("Mess") }
    var categoryExpanded by remember { mutableStateOf(false) }
    var payerExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ Add Expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = !categoryExpanded }) {
                    OutlinedTextField(value = category, onValueChange = {}, readOnly = true, label = { Text("Category") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        categories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; categoryExpanded = false }) }
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount (₹)") }, modifier = Modifier.fillMaxWidth())
                if (category == "Mess") {
                    ExposedDropdownMenuBox(expanded = payerExpanded, onExpandedChange = { payerExpanded = !payerExpanded }) {
                        OutlinedTextField(value = members.firstOrNull { it.id == payer }?.name ?: "", onValueChange = {}, readOnly = true, label = { Text("Paid by") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = payerExpanded, onDismissRequest = { payerExpanded = false }) {
                            members.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { payer = m.id; payerExpanded = false }) }
                        }
                    }
                    Text("Who shares this expense?", fontWeight = FontWeight.SemiBold)
                    members.forEach { m ->
                        Row(Modifier.fillMaxWidth().toggleable(selected.contains(m.id)) { checked -> selected = if (checked) selected + m.id else selected - m.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selected.contains(m.id), onCheckedChange = null)
                            Text(m.name)
                        }
                    }
                } else {
                    selected = members.map { it.id }.toSet()
                    Text("Fixed amount per person • no payer", fontWeight = FontWeight.SemiBold)
                }
                val amount = amountText.toDoubleOrNull()
                if (selected.isNotEmpty() && amount != null && amount > 0) {
                    Text(if (category == "Mess") "Each share: ${money(amount / selected.size)}" else "Fixed amount per person: ${money(amount)}", fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(enabled = amountText.toDoubleOrNull()?.let { it > 0 } == true && selected.isNotEmpty(), onClick = { onSave(title.ifBlank { category }, amountText.toDouble(), if (category == "Mess") payer else 0, selected, category) }) { Text("Save Expense") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
