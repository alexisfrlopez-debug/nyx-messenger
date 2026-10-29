package net.nyx.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nyx.messenger.diag.TestPlan
import java.text.DateFormat
import java.util.Date

/**
 * v0.3.4 (session 74 — « parité Android ») — CARTE « PLAN DE TESTS » desktop,
 * portage miroir de l'Android `ui/diag/TestPlanCard.kt` (v0.2.20) : listing
 * des tests à effectuer, mis à jour au fur et à mesure, résultats journalisés
 * nyx-test (partent dans l'export de logs du bouton rouge).
 *
 * Leçons terrain Android reprises à l'identique :
 *  - v0.2.31 : REPLIÉE PAR DÉFAUT (l'en-tête titre + progression reste
 *    visible, la liste s'ouvre au clic — l'écran principal reste lisible) ;
 *  - v0.2.32 : liste déployée CONFINÉE (hauteur max 260 dp + défilement
 *    interne) — toutes les cases et « Tout réinitialiser » restent
 *    atteignables, l'en-tête reste visible au-dessus.
 */
@Composable
fun TestPlanCard() {
    var tick by remember { mutableIntStateOf(0) }
    val items = remember { TestPlan.items() }
    // v0.2.31 (miroir) : repliée par défaut.
    var expanded by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Text(
                    "Plan de tests v0.3.4",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "▼" else "▶", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            // v0.2.38 (miroir) : consigne affichée SEULEMENT dépliée.
            if (expanded) {
                Text(
                    "Appuyez sur un test une fois effectué : le résultat est journalisé (nyx-test) et part dans l'export de logs — il sert à valider la mise en production.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                "${TestPlan.doneCount()} / ${items.size} test(s) fait(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            // v0.2.32 (miroir) : liste CONFINÉE défilante quand déployée.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (expanded) for (item in items) {
                    // v0.3.14 — VALIDÉ PAR LOGS : la preuve est dans l'export
                    // terrain du 29/10 — l'item s'affiche COCHÉ avec son badge.
                    val logValid = item.logValidated
                    val done = remember(tick) { TestPlan.doneAt(item.id) > 0L || logValid != null }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                TestPlan.setDone(item.id, !done)
                                tick++
                                if (TestPlan.doneCount() == items.size) {
                                    TestPlan.logSummary()
                                }
                            }
                    ) {
                        Checkbox(checked = done, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                textDecoration = if (done) TextDecoration.LineThrough else null,
                                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (done) {
                                if (logValid != null && TestPlan.doneAt(item.id) == 0L) {
                                    Text(
                                        "✓ VALIDÉ PAR LOGS (29/10)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        logValid,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(TestPlan.doneAt(item.id))),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            } // fin liste confinée (v0.2.32)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                TestPlan.reset()
                tick++
            }) { Text("Tout réinitialiser") }
        }
    }
}
