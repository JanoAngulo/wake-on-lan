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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jano.wake.ui.common.BodyText
import dev.jano.wake.ui.common.ScreenScaffold
import dev.jano.wake.ui.common.SectionLabel
import dev.jano.wake.ui.icons.WakeIcons
import dev.jano.wake.ui.main.KeyValueCard
import dev.jano.wake.ui.main.RowDivider
import dev.jano.wake.ui.theme.WakeColors
import dev.jano.wake.ui.theme.WakeType

private class Fact(val icon: ImageVector, val title: String, val body: String)

// Every claim here must stay true to the code: no analytics, allowBackup="false", SecretVault (Keystore),
// KeystoreUnlockPasswords (biometric-bound), SecretStore (user-only ACL), DPAPI sign-in key, logs without passwords.
private val sections = listOf(
    "What Wake sends" to listOf(
        Fact(WakeIcons.Wifi, "Only to your own PCs", "Wake talks to the PCs on your home network and nothing else. No account, no ads, no analytics, no crash reports."),
        Fact(WakeIcons.Globe, "Why it asks for Internet", "That's how Android names any network access, including to the PC down the hall. Wake never contacts a server."),
    ),
    "What's on this phone" to listOf(
        Fact(WakeIcons.Power, "Your PCs", "Names, IP addresses and network card IDs (MAC addresses), so Wake can find and wake them."),
        Fact(WakeIcons.Lock, "The pairing key", "Encrypted with a key that never leaves this phone's secure hardware (Android Keystore)."),
        Fact(WakeIcons.Fingerprint, "Your Windows password", "Only if you set up fingerprint unlock. It's locked behind your fingerprint or the phone's PIN, and opened for one unlock at a time."),
        Fact(WakeIcons.Cross, "No phone backup", "Android backup is off for Wake, so none of this goes to Google Drive."),
    ),
    "What's on your PC" to listOf(
        Fact(WakeIcons.Lock, "The pairing key", "Readable only by your Windows account. Your Windows password is never saved on the PC."),
        Fact(WakeIcons.Fingerprint, "The sign-in key", "Only with sign-in after shutdown on. It's a separate key that can unlock but can't send commands, encrypted by Windows."),
        Fact(WakeIcons.Folder, "Logs", "What the agent did, with local addresses and results. Never your password."),
    ),
    "How it's protected" to listOf(
        Fact(WakeIcons.Check, "Every command is signed", "The PC refuses anything not signed by your paired phone, and old or repeated requests."),
        Fact(WakeIcons.Fingerprint, "One try per tap", "Your password is encrypted when it's sent. If Windows rejects it, Wake deletes it and never retries, so it can't lock your account."),
        Fact(WakeIcons.Alert, "Turn on BitLocker", "On the PC: Settings → Privacy & security → Device encryption. Then a stolen drive gives nothing away."),
    ),
    "Removing everything" to listOf(
        Fact(WakeIcons.Cross, "Uninstall both sides", "Uninstall Wake on this phone. On the PC, run Uninstall Wake Agent.cmd: it removes the agent, its keys, logs and the sign-in option."),
    ),
)

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    ScreenScaffold("Privacy & security", onBack) {
        BodyText("Wake works only on your home network. Here's exactly what it keeps, and where.", Modifier.padding(horizontal = 4.dp))
        sections.forEach { (title, facts) ->
            SectionLabel(title)
            KeyValueCard(inset = 0.dp) {
                facts.forEachIndexed { index, fact ->
                    if (index > 0) RowDivider()
                    FactRow(fact)
                }
            }
        }
    }
}

@Composable
private fun FactRow(fact: Fact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(32.dp).background(WakeColors.HeroDisc.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fact.icon, contentDescription = null, tint = WakeColors.HeroDisc, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(fact.title, style = WakeType.RowValue, color = WakeColors.OnSurface)
            Text(fact.body, style = WakeType.Body, color = WakeColors.OnSurfaceMuted)
        }
    }
}
