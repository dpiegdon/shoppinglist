package org.p23q.shoppinglist.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.R
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.LocalAreaNote
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.LanguagePicker
import org.p23q.shoppinglist.core.AppLocale
import org.p23q.shoppinglist.data.deviceLocale

@Composable
fun LoginScreen(
    /** Null: go back to where the form was opened from (add and re-sign-in modes). */
    onLoginSuccess: (startDestination: String?) -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
    // Plain hoisted state rather than a second hiltViewModel() default (T-127): this screen is
    // rendered directly in unit tests against a fake LoginViewModel, and a Hilt-resolved default
    // fails there because the test host is a bare ComponentActivity, not a Hilt component. The
    // defaults keep those tests needing no new wiring; Nav supplies the real values.
    selectedLocale: AppLocale = deviceLocale(),
    onSelectLocale: (AppLocale) -> Unit = {},
    /** Opens the app package a server newer than this build offers (T-298). */
    onDownload: (downloadUrl: String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.loginSucceeded) {
        if (state.loginSucceeded) {
            onLoginSuccess(viewModel.startDestinationAfterLogin())
        }
    }
    // Without an account (T-293): the lists on this phone, with nothing behind them.
    LaunchedEffect(state.localAreaChosen) {
        if (state.localAreaChosen) onLoginSuccess(Routes.OVERVIEW)
    }
    if (state.localAreaNoteOpen) {
        LocalAreaNote(onDismiss = viewModel::dismissLocalAreaNote)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // The mark on the start screen only; adding or re-signing an account sits under a top
        // bar that already says what the form is for.
        if (state.mode == LoginMode.START) {
            Image(
                painter = painterResource(R.drawable.ic_brand_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            // The name in cuneiform, IM.DUB — read ṭuppu (T-225); About explains the signs. Smaller
            // here and without the transliteration caption: this is a mark, not the explanation.
            // Tinted to onSurface so it follows the text colour on both themes.
            Image(
                painter = painterResource(R.drawable.ic_cuneiform_tuppu),
                contentDescription = stringResource(R.string.about_transliteration),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                // Height only: the sign is about 3:1, so the width follows from it.
                modifier = Modifier.height(40.dp).align(Alignment.CenterHorizontally).testTag("login-cuneiform"),
            )
            Spacer(Modifier.height(24.dp))
        }
        if (state.mode == LoginMode.RESIGNIN) {
            Text(stringResource(R.string.login_resignin_help), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text(stringResource(R.string.login_server_url)) },
            singleLine = true,
            readOnly = state.serverUrlLocked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            // Enter on the password field submits, so a returning user never has to reach for the button.
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) stringResource(R.string.action_hide) else stringResource(R.string.action_show))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(text = message.asString(), color = MaterialTheme.colorScheme.error)
        }
        state.downloadUrl?.let { url ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onDownload(url) },
                modifier = Modifier.fillMaxWidth().testTag("login-download"),
            ) {
                Text(stringResource(R.string.action_update))
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = viewModel::submit,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isRegisterMode) stringResource(R.string.login_register) else stringResource(R.string.login_log_in))
        }

        // Disabled while the configured server is refusing new accounts (T-276), matching the web
        // login page — asked up front rather than after the user fills in the whole form. Not
        // offered to an account signing in again: it exists already.
        if (state.mode != LoginMode.RESIGNIN) {
            TextButton(onClick = viewModel::onToggleRegisterMode, enabled = state.registrationAllowed) {
                Text(if (state.isRegisterMode) stringResource(R.string.login_to_login) else stringResource(R.string.login_to_register))
            }
        }

        // Only on the start screen: from Accounts, the local area is added there (T-293).
        if (state.mode == LoginMode.START) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.useWithoutAccount() },
                enabled = !state.isLoading && !state.creatingLocalArea,
                modifier = Modifier.fillMaxWidth().testTag("login-use-local"),
            ) {
                Text(stringResource(R.string.login_use_without_account))
            }
        }

        if (state.mode == LoginMode.START) {
            Spacer(Modifier.height(24.dp))
            // Before login, deliberately: the chooser must be reachable without an account (T-127),
            // which is also why the preference is device-local. Past the start screen it is in
            // Settings.
            LanguagePicker(selected = selectedLocale, onSelect = onSelectLocale)
        }

        if (!state.registrationAllowed && state.mode != LoginMode.RESIGNIN) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.login_registration_disabled),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        // Debug-only self-signed-cert opt-in, mirrored from Settings so it's reachable before login —
        // Settings is post-auth, which would otherwise be a bootstrap deadlock for a self-signed
        // dev server. Absent from release builds (BuildConfig.DEBUG + no-op DevCertTrust). (T-38/T-46)
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.login_trust_self_signed), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.login_trust_self_signed_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.allowSelfSignedCerts,
                    onCheckedChange = { viewModel.setAllowSelfSignedCerts(it) },
                )
            }
        }
    }
}
