package dev.jano.wake.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.common.BodyText
import dev.jano.wake.ui.common.ScreenScaffold
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.main.KeyValueCard
import dev.jano.wake.ui.main.RowDivider
import dev.jano.wake.ui.main.rememberReducedMotion
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType

private class Problem(val icon: ImageVector, val title: String, val hint: String, val steps: List<Step>)

private const val CHECK_COMMAND = "powershell -ExecutionPolicy Bypass -File pc-setup\\setup.ps1 -Check"

private val problems = listOf(
    Problem(
        WakeIcons.Power, "The PC doesn't wake", "Tapping the power button does nothing",
        listOf(
            Step("Check the cable and power", "The PC must be plugged in, with a network cable connected. Most Wi-Fi cards can't wake a PC that's off."),
            Step("Run the check on the PC", "Open PowerShell in the folder where you unzipped Wake and run this. Every row should say yes.", CHECK_COMMAND),
            Step("Turn on Wake on LAN in the BIOS", "Restart and press Delete or F2. Turn on Wake on LAN (sometimes Resume by PCI-E device) and turn off ErP Ready or Deep Sleep."),
            Step("Shut down, don't unplug", "Wake can't start a PC that lost power. If you changed Fast Startup, run Install Wake Agent.cmd again to turn it off."),
        ),
    ),
    Problem(
        WakeIcons.Pulse, "It says On, waiting for agent", "On, but the buttons are greyed out",
        listOf(
            Step("Sign in on the PC", "The agent starts when you sign in. With sign-in after shutdown turned on, tap Unlock instead."),
            Step("Look for the green power icon", "It's in the system tray at the bottom right; click ^ if it's hidden. If it's missing, run Install Wake Agent.cmd again."),
            Step("Check the network", "The phone and the PC must be on the same Wi-Fi network. A VPN on either one can hide the PC."),
        ),
    ),
    Problem(
        WakeIcons.Fingerprint, "No Unlock after a shutdown", "Signing in with your fingerprint",
        listOf(
            Step("Turn it on in the installer", "Run Install Wake Agent.cmd and answer y to both fingerprint questions."),
            Step("Turn off Windows Hello-only sign-in", "On the PC: Settings → Accounts → Sign-in options → turn off \"only allow Windows Hello sign-in\"."),
            Step("Give the PC a fixed address", "If the PC gets a new IP address when it starts, the phone can't find it before you sign in. Reserve its address in your router's DHCP settings."),
        ),
    ),
    Problem(
        WakeIcons.Lock, "Unlock says wrong password", "After changing your Windows password",
        listOf(
            Step("Enter the password again", "Wake deleted the old one so it can't lock your account. Go to Settings → Devices → your PC → Fingerprint unlock and enter your Windows password, not the PIN."),
        ),
    ),
    Problem(
        WakeIcons.Wifi, "Phone isn't paired any more", "After reinstalling the app or the agent",
        listOf(
            Step("Pair again", "On the PC, click the green power icon and choose Pair a phone. Then in Wake: Settings → Devices → your PC → Pair, and type the code."),
        ),
    ),
    Problem(
        WakeIcons.Alert, "Still stuck?", "Where the PC keeps its logs",
        listOf(
            Step("Open the agent's log", "On the PC, click the green power icon and choose Open log folder. agent.log says what the agent saw."),
            Step("Sign-in after shutdown", "Its log is here; it lists every unlock attempt and its result.", "C:\\ProgramData\\Wake\\logs\\signin.log"),
        ),
    ),
)

@Composable
fun PcSetupHelpScreen(onBack: () -> Unit) {
    ScreenScaffold("PC setup help", onBack) {
        BodyText("Tap what's going wrong.", Modifier.padding(horizontal = 4.dp))
        problems.forEach { ProblemCard(it) }
    }
}

@Composable
private fun ProblemCard(problem: Problem) {
    var open by rememberSaveable(problem.title) { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    KeyValueCard(inset = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide steps" else "Show steps") { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(WakeColors.HeroDisc.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(problem.icon, contentDescription = null, tint = WakeColors.HeroDisc, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(problem.title, style = WakeType.RowValue, color = WakeColors.OnSurface)
                Text(problem.hint, style = WakeType.RowSecondary, color = WakeColors.OnSurfaceMuted)
            }
            Icon(
                WakeIcons.ChevronDown,
                contentDescription = null,
                tint = WakeColors.OnSurfaceMuted,
                modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reducedMotion) fadeIn(tween(0)) else expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = if (reducedMotion) fadeOut(tween(0)) else shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) {
            Column {
                problem.steps.forEachIndexed { index, step ->
                    RowDivider()
                    StepRow(index + 1, step)
                }
            }
        }
    }
}
