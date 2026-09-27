@Composable
private fun MessScreen(
    modifier: Modifier,
    members: List<Member>,
    expenses: List<Expense>
) {

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    val messExpenses =
        expenses.filter {
            it.category == "Mess"
        }

    val totalMess =
        messExpenses.sumOf {
            it.amount
        }

    LazyColumn(
        modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        // -----------------------------
        // TOTAL MESS EXPENSE
        // -----------------------------

        item {

            Card(
                Modifier.fillMaxWidth()
            ) {

                Column(
                    Modifier.padding(16.dp)
                ) {

                    Text(
                        "🍴 MESS SUMMARY",
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        "Total Mess Expense",
                        fontSize = 13.sp
                    )

                    Text(
                        money(totalMess),
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 24.sp
                    )

                    Spacer(
                        Modifier.height(6.dp)
                    )

                    Text(
                        "${messExpenses.size} mess payment(s)",
                        fontSize = 12.sp
                    )
                }
            }
        }

        // -----------------------------
        // PERSON-WISE SUMMARY
        // -----------------------------

        items(members) { member ->

            val paid =
                messExpenses
                    .filter {
                        it.payerId == member.id
                    }
                    .sumOf {
                        it.amount
                    }

            val share =
                messExpenses.sumOf { expense ->

                    if (
                        member.id in
                        expense.participants
                    ) {

                        if (
                            expense.participants.isNotEmpty()
                        ) {

                            expense.amount /
                                    expense.participants.size
                                        .toDouble()

                        } else {

                            0.0
                        }

                    } else {

                        0.0
                    }
                }

            val balance =
                paid - share

            Card(
                Modifier.fillMaxWidth()
            ) {

                Column(
                    Modifier.padding(14.dp)
                ) {

                    Text(
                        member.name,
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 17.sp
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    // I PAID
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        Text(
                            "💵 I Paid",
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            money(paid),
                            fontWeight =
                                FontWeight.Bold
                        )
                    }

                    Spacer(
                        Modifier.height(4.dp)
                    )

                    // MY SHARE
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        Text(
                            "📊 My Share",
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            money(share)
                        )
                    }

                    Spacer(
                        Modifier.height(4.dp)
                    )

                    HorizontalDivider()

                    Spacer(
                        Modifier.height(4.dp)
                    )

                    // BALANCE
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        Text(
                            "⚖️ Balance",
                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(
                            money(balance),
                            fontWeight =
                                FontWeight.Bold,
                            color =
                                if (balance >= 0)
                                    Color(0xFF087A38)
                                else
                                    Color(0xFFB3261E)
                        )
                    }

                    Spacer(
                        Modifier.height(10.dp)
                    )

                    // -----------------------------
                    // PAYMENT HISTORY
                    // -----------------------------

                    val entries =
                        messExpenses
                            .filter {
                                it.payerId == member.id
                            }
                            .sortedBy {
                                it.id
                            }

                    if (entries.isNotEmpty()) {

                        Text(
                            "Payment History",
                            fontWeight =
                                FontWeight.SemiBold,
                            fontSize = 13.sp
                        )

                        Spacer(
                            Modifier.height(5.dp)
                        )

                        var runningTotal = 0.0

                        entries.forEach { expense ->

                            runningTotal +=
                                expense.amount

                            val date =
                                Instant
                                    .ofEpochMilli(
                                        expense.id
                                    )
                                    .atZone(
                                        ZoneId.systemDefault()
                                    )
                                    .toLocalDate()
                                    .format(
                                        dateFormatter
                                    )

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        vertical = 3.dp
                                    ),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {

                                Text(
                                    "$date"
                                )

                                Text(
                                    "+ ${
                                        money(
                                            expense.amount
                                        )
                                    }"
                                )
                            }
                        }

                        Spacer(
                            Modifier.height(4.dp)
                        )

                        Text(
                            "Total Paid: ${
                                money(runningTotal)
                            }",
                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
