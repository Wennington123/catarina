package dev.catarina.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.catarina.assistant.core.RemoteBrain
import dev.catarina.assistant.service.CatarinaService
import dev.catarina.assistant.state.AppSettings
import dev.catarina.assistant.state.AssistantStateBus
import dev.catarina.assistant.state.Phase
import dev.catarina.assistant.ui.mascot.BabyMascot
import dev.catarina.assistant.ui.overlay.MascotOverlayService
import dev.catarina.assistant.util.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela principal: a bebezinha reage ao estado do assistente; botões de turno
 * e modo contínuo; configurações (bolha flutuante e recursos root).
 */
@Composable
fun MainScreen(
    onListen: () -> Unit,
    onToggleService: () -> Unit,
    onGrantPermissions: () -> Unit,
) {
    val phase by AssistantStateBus.phase.collectAsState()
    val progress by AssistantStateBus.progress.collectAsState()
    val message by AssistantStateBus.message.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var overlayOn by remember { mutableStateOf(AppSettings.overlayEnabled) }
    var rootOn by remember { mutableStateOf(AppSettings.rootEnabled) }
    var rootChecking by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }

    var providerRemote by remember { mutableStateOf(AppSettings.providerRemote) }
    var baseUrl by remember { mutableStateOf(AppSettings.providerBaseUrl) }
    var model by remember { mutableStateOf(AppSettings.providerModel) }
    var apiKey by remember { mutableStateOf(AppSettings.providerApiKey) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showCapabilities by remember { mutableStateOf(!AppSettings.capabilitiesSeen) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        if (showCapabilities) {
            CapabilitiesBanner {
                AppSettings.capabilitiesSeen = true
                showCapabilities = false
            }
            Spacer(Modifier.height(16.dp))
        }

        BabyMascot(phase, progress, Modifier.size(240.dp))

        Spacer(Modifier.height(12.dp))
        Text(
            text = message ?: phaseLabel(phase),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        AnimatedVisibility(visible = phase == Phase.BOOTING) {
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        val busy = phase in setOf(Phase.HEARING, Phase.THINKING, Phase.SPEAKING, Phase.BOOTING)
        Button(onClick = onListen, enabled = !busy, modifier = Modifier.widthIn(min = 220.dp)) {
            Text("Ouvir agora")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onToggleService, modifier = Modifier.widthIn(min = 220.dp)) {
            Text(if (phase == Phase.LISTENING) "Parar modo contínuo" else "Sempre ouvindo")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGrantPermissions, modifier = Modifier.widthIn(min = 220.dp)) {
            Text("Dar permissões")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showCapabilities = true },
            modifier = Modifier.widthIn(min = 220.dp),
        ) {
            Text("O que ela faz")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Configurações", style = MaterialTheme.typography.titleSmall)

        SettingRow(
            title = "Bolha flutuante",
            subtitle = "Mascote sobre outros apps; toque pede um turno rápido.",
            checked = overlayOn,
            onCheckedChange = { on ->
                if (on && !Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                    return@SettingRow
                }
                AppSettings.overlayEnabled = on
                overlayOn = on
                if (on) MascotOverlayService.start(context) else MascotOverlayService.stop(context)
            },
        )

        SettingRow(
            title = "Extras (root)",
            subtitle = when {
                rootChecking -> "Verificando root…"
                rootAvailable == false -> "Root não detectado neste aparelho"
                rootAvailable == true && rootOn -> "Root ativo: Wi-Fi/BT/brilho via svc + shell"
                else -> "Usa root p/ Wi-Fi, Bluetooth, brilho e comandos (pede autorização no 1º uso)"
            },
            checked = rootOn,
            onCheckedChange = { on ->
                if (!on) {
                    AppSettings.rootEnabled = false
                    rootOn = false
                    return@SettingRow
                }
                if (rootAvailable == true) {
                    AppSettings.rootEnabled = true
                    rootOn = true
                    return@SettingRow
                }
                rootChecking = true
                scope.launch(Dispatchers.IO) {
                    val has = ShellRunner.hasRoot
                    withContext(Dispatchers.Main) {
                        rootChecking = false
                        rootAvailable = has
                        if (has) {
                            AppSettings.rootEnabled = true
                            rootOn = true
                        }
                    }
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        SettingRow(
            title = "Cérebro na nuvem (API)",
            subtitle = when {
                !providerRemote ->
                    "Local (Needle), 100% offline. Ligue para conversar e ter mais funções"
                AppSettings.providerConfigured -> "Provedor: ${AppSettings.providerModel}"
                else -> "Preencha URL e modelo abaixo"
            },
            checked = providerRemote,
            onCheckedChange = { on ->
                AppSettings.provider =
                    if (on) AppSettings.PROVIDER_REMOTE else AppSettings.PROVIDER_LOCAL
                providerRemote = on
                testResult = null
            },
        )

        AnimatedVisibility(visible = providerRemote) {
            Column(Modifier.fillMaxWidth()) {
                ProviderField("URL base (ex.: …/v1)", baseUrl) {
                    baseUrl = it
                    AppSettings.providerBaseUrl = it
                }
                ProviderField("Modelo", model) {
                    model = it
                    AppSettings.providerModel = it
                }
                ProviderField("API key", apiKey, password = true) {
                    apiKey = it
                    AppSettings.providerApiKey = it
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        testResult = "Testando…"
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) { RemoteBrain.testConnection() }
                        }
                    },
                    modifier = Modifier.widthIn(min = 220.dp),
                ) {
                    Text("Testar conexão")
                }
                testResult?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Banner de abertura: o que ela sabe fazer hoje, em linguagem simples. Some
 * quando o usuario toca em "Entendi" e pode ser reaberto pelo botao
 * "O que ela faz".
 */
@Composable
private fun CapabilitiesBanner(onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("O que a Catarina faz", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            CAPABILITIES.forEach { item ->
                Text("• $item", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Ela faz na hora, sem pedir confirmação. Chame falando \"Catarina\" " +
                    "(modo Sempre ouvindo) ou toque em \"Ouvir agora\". Para conversa e " +
                    "perguntas abertas, ligue o \"Cérebro na nuvem\" nas Configurações.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.widthIn(min = 160.dp)) {
                Text("Entendi")
            }
        }
    }
}

private val CAPABILITIES = listOf(
    "Liga e desliga o Wi-Fi e o Bluetooth (precisa de root)",
    "Lanterna, brilho da tela e volume",
    "Abre apps pelo nome",
    "Manda SMS e cria notificações",
    "Liga o ar-condicionado (se o aparelho tiver emissor IR)",
    "Roda comandos do sistema (com root ativado)",
)

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProviderField(
    label: String,
    value: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

private fun phaseLabel(p: Phase): String = when (p) {
    Phase.IDLE -> "Prontinha pra te ouvir"
    Phase.BOOTING -> "Carregando modelos…"
    Phase.LISTENING -> "Ouvindo por \"Catarina\"…"
    Phase.HEARING -> "Te ouvindo…"
    Phase.THINKING -> "Pensando…"
    Phase.SPEAKING -> "Fazendo sons…"
    Phase.CELEBRATING -> "Feito!"
    Phase.SAD -> "Ops…"
}
