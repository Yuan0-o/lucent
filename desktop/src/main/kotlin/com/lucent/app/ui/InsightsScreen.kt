package com.lucent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucent.app.data.AppDatabase
import com.lucent.app.data.TaskInsights
import com.lucent.app.i18n.S

/**
 * The desktop Insights tab. On Android the same figures were folded into the completed-tasks page;
 * on the desktop, where Insights is its own sidebar destination, it gets a dedicated dashboard.
 *
 * All numbers come straight from [TaskInsights.summarize] over the live task list, so the panel
 * reacts the moment a task is added, completed, or its due date changes — the flow is the same one
 * the Tasks screen subscribes to. No Android APIs are involved; this is pure Compose over the shared
 * data layer.
 */
@Composable
fun InsightsScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val allTasks by remember { db.taskDao().getAll() }.collectAsState(initial = emptyList())

    val summary = remember(allTasks) { TaskInsights.summarize(allTasks) }
    val headline = remember(summary) { TaskInsights.headline(summary) }

    val onGradient = LocalOnGradient.current
    val onGradientMuted = LocalOnGradientMuted.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 28.dp)
    ) {
        Text(S.tabInsights, color = onGradient, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(20.dp))

        if (summary.isEmpty) {
            Box(modifier = Modifier.fillMaxWidth().frostedGlass().padding(28.dp)) {
                Text(S.insightsEmpty, color = onGradientMuted, fontSize = 15.sp)
            }
            return@Column
        }

        // A one-line "here's where things stand" summary, when there is anything active to report.
        // headline() returns null only when there are no active tasks, so guard on that.
        if (headline != null) {
            Box(modifier = Modifier.fillMaxWidth().frostedGlass().padding(24.dp)) {
                Text(headline, color = onGradient, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // The individual counts, as a responsive two-column grid of frosted stat cards. Overdue and
        // "needs attention" turn warm-red when non-zero so a real backlog stands out at a glance.
        val stats: List<StatCardData> = listOf(
            StatCardData(S.insightsActive, summary.active, alert = false),
            StatCardData(S.insightsNeedsAttention, summary.needsAttention, alert = true),
            StatCardData(S.searchChipOverdue, summary.overdue, alert = true),
            StatCardData(S.searchChipDueToday, summary.dueToday, alert = false),
            StatCardData(S.searchChipDueWeek, summary.dueThisWeek, alert = false),
            StatCardData(S.searchChipReminder, summary.withReminders, alert = false),
            StatCardData(S.insightsCompleted, summary.completed, alert = false)
        )

        stats.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { data ->
                    StatCard(
                        data = data,
                        onGradient = onGradient,
                        onGradientMuted = onGradientMuted,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Keep the last odd card at half width rather than stretching it across the row.
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private data class StatCardData(val label: String, val value: Int, val alert: Boolean)

@Composable
private fun StatCard(
    data: StatCardData,
    onGradient: Color,
    onGradientMuted: Color,
    modifier: Modifier = Modifier
) {
    val warm = Color(0xFFFF6B5E)
    Column(modifier = modifier.frostedGlass().padding(20.dp)) {
        Text(
            data.value.toString(),
            color = if (data.alert && data.value > 0) warm else onGradient,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(data.label, color = onGradientMuted, fontSize = 13.sp)
    }
}
