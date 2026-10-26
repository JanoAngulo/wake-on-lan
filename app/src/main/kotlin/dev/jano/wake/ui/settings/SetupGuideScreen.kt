package dev.jano.wake.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.common.BodyText
import dev.jano.wake.ui.common.PrimaryButton
import dev.jano.wake.ui.common.ScreenScaffold
import dev.jano.wake.ui.common.SecondaryButton
import dev.jano.wake.ui.common.SectionLabel
import dev.jano.wake.ui.main.KeyValueCard
import dev.jano.wake.ui.main.RowDivider
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType

private data class Step(val title: String, val body: String)

private val pcSteps = listOf(
    Step(
        "Run the installer",
        "In the Wake project folder on your PC, double-click Install Wake Agent.cmd and choose Yes when Windows asks for permission.",
    ),
    Step(
        "Let it finish",
        "It checks the network card's wake settings, keeps Fast Startup off, allows Wake through the firewall on your home network, and starts the agent every time you log in. Every line in the table should say yes.",
    ),
    Step(
        "Find the green power icon",
        "It sits in the system tray at the bottom right. Click the ^ arrow if it's hidden. You only install once; it starts by itself after every login.",
    ),
)

private val phoneSteps = listOf(
    Step("Join your home Wi-Fi", "The phone and the PC need to be on the same network."),
    Step("Find your PC", "Tap Find my PC below. Wake lists every PC running the agent."),
    Step(
        "Get the code from the PC",
        "Right-click the green power icon and choose Pair phone. A 6-digit code appears for 2 minutes.",
    ),
    Step(
        "Type the code and pair",
        "Back on the phone, choose your PC, enter the code and tap Pair. When the PC agent shows Connected, you're done.",
    ),
)

@Composable
fun SetupGuideScreen(onFindPc: () -> Unit, onTroubleshoot: () -> Unit, onBack: () -> Unit) {
    ScreenScaffold("How to connect", onBack) {
        BodyText("Two parts: set up the PC once, then pair this phone with it.", Modifier.padding(horizontal = 4.dp))

        SectionLabel("On your PC")
        StepsCard(pcSteps)

        SectionLabel("On your phone")
        StepsCard(phoneSteps)

        BodyText(
            "Waking works as soon as the PC is set up. Shut down, reboot, sleep and lock need you logged in on the PC, because that's when the agent runs.",
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        PrimaryButton("Find my PC", onFindPc)
        SecondaryButton("Troubleshooting", onTroubleshoot)
    }
}

@Composable
private fun StepsCard(steps: List<Step>) {
    KeyValueCard(inset = 0.dp) {
        steps.forEachIndexed { index, step ->
            if (index > 0) RowDivider()
            StepRow(index + 1, step)
        }
    }
}

@Composable
private fun StepRow(number: Int, step: Step) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(28.dp).background(WakeColors.HeroDisc.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = WakeType.Pill, color = WakeColors.HeroDisc)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title, style = WakeType.RowValue, color = WakeColors.OnSurface)
            Text(step.body, style = WakeType.Body, color = WakeColors.OnSurfaceMuted)
        }
    }
}
