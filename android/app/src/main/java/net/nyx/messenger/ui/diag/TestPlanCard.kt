package net.nyx.messenger.ui.diag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import net.nyx.messenger.R
import net.nyx.messenger.diag.TestPlan

/**
 * v0.2.20 — CARTE « PLAN DE TESTS » (builds test/test2 uniquement — demande
 * opérateur : listing des tests à effectuer, mis à jour au fur et à mesure,
 * résultats journalisés nyx-test pour la validation de mise en production).
 */
@Composable
fun TestPlanCard() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val items = remember { TestPlan.items() }
    // v0.2.31 — REPLIÉ PAR DÉFAUT (demande opérateur : « l'écran principal ne
    // défile plus, je ne vois pas le bas ») : la carte plan (12 items ≈ 500 dp)
    // en pleine hauteur sous la bannière Tor écrasait la zone d'onglets dès
    // que l'espace restant était mesuré (fix weight(1f) de cet écran).
    // L'en-tête (titre + progression) reste visible ; la liste s'ouvre au clic.
    var expanded by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Text(
                    stringResource(R.string.testplan_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.testplan_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.testplan_progress, TestPlan.doneCount(context), items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            if (expanded) for (item in items) {
                val done = remember(tick) { TestPlan.doneAt(context, item.id) > 0L }
                // v0.2.26 : les items destructeurs (couper le réplica, cycle
                // sans serveur) sont BLOQUÉS sans redondance (≥ 2 réplicas ou
                // réplica temporaire servi) — demande opérateur : « je préfère
                // éviter ce genre de prise de risque tant que je n'ai pas de
                // redondance autre que les réplicas temporaires ».
                val blocked = item.requiresRedundancy && !TestPlan.redundancyAvailable()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !blocked) {
                            TestPlan.setDone(context, item.id, !done)
                            tick++
                            if (TestPlan.doneCount(context) == items.size) {
                                TestPlan.logSummary(context)
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.testplan_progress, items.size, items.size),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                ) {
                    Checkbox(checked = done, onCheckedChange = null, enabled = !blocked)
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = when {
                                done -> MaterialTheme.colorScheme.onSurfaceVariant
                                blocked -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (done) {
                            Text(
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(TestPlan.doneAt(context, item.id))),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (blocked) {
                            Text(
                                stringResource(R.string.testplan_redundancy_required),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                TestPlan.reset(context)
                tick++
            }) { Text(stringResource(R.string.testplan_reset)) }
        }
    }
}
