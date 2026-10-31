package com.joetr.mira.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.repository.SessionRepository
import com.joetr.mira.domain.usecase.MergeLocalIntoAccountUseCase
import com.joetr.mira.ui.components.MiraConfirmDialog
import com.joetr.mira.ui.icons.MiraAppMark
import com.joetr.mira.ui.icons.GoogleMark
import com.joetr.mira.ui.theme.MiraSpacing
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch

public class AuthViewModel(
    private val sessionRepository: SessionRepository,
    private val mergeUseCase: MergeLocalIntoAccountUseCase,
) {
    public suspend fun signIn(email: String, password: String) =
        sessionRepository.signInWithEmail(email, password)

    public suspend fun signUp(email: String, password: String) =
        sessionRepository.signUpWithEmail(email, password)

    public suspend fun resetPassword(email: String) =
        sessionRepository.resetPassword(email)

    public suspend fun signInGoogle() = sessionRepository.signInWithGoogle()

    public suspend fun mergePrompt(): Pair<Boolean, Int> = mergeUseCase.needsPrompt()

    public suspend fun acceptMerge(): Int = mergeUseCase.accept()

    public suspend fun declineMerge() = mergeUseCase.decline()
}

/**
 * A purpose-built auth template: brand mark hero above a floating, paper-like form sheet.
 * All providers and email recovery paths share this one visual rhythm.
 */
@Composable
public fun AuthScreen(
    viewModel: AuthViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var error by remember { mutableStateOf<String?>(null) }
    var showMerge by remember { mutableStateOf(false) }
    var mergeCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun finishAuth() {
        val (needsMerge, count) = viewModel.mergePrompt()
        if (needsMerge && count > 0) {
            mergeCount = count
            showMerge = true
        } else {
            onDone()
        }
    }

    fun show(modeToShow: AuthMode) {
        mode = modeToShow
        error = null
    }

    fun launchAuth(action: suspend () -> Unit) {
        if (isLoading) return
        scope.launch {
            isLoading = true
            error = null
            try {
                action()
            } catch (throwable: Throwable) {
                error = throwable.message ?: "Something went wrong. Please try again."
            }
            isLoading = false
        }
    }

    if (showMerge) {
        MergeRecipesDialog(
            mergeCount = mergeCount,
            onMerge = {
                scope.launch {
                    viewModel.acceptMerge()
                    showMerge = false
                    onDone()
                }
            },
            onSkip = {
                scope.launch {
                    viewModel.declineMerge()
                    showMerge = false
                    onDone()
                }
            },
        )
    }

    AuthScreenTemplate(modifier = modifier) {
        AuthSheet(
            title = mode.title,
            subtitle = mode.subtitle,
        ) {
            AuthUnderlineTextField(
                value = email,
                onValueChange = { email = it; error = null },
                placeholder = "Email address",
            )

            when (mode) {
                AuthMode.SIGN_IN, AuthMode.SIGN_UP -> {
                    AuthUnderlineTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        placeholder = "Password",
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.padding(top = MiraSpacing.md),
                    )
                }

                AuthMode.RESET -> Unit
            }

            if (mode == AuthMode.SIGN_IN) {
                TextButton(
                    onClick = { show(AuthMode.RESET) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Forgot password?")
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = MiraSpacing.xs),
                )
            }

            AuthPrimaryButton(
                text = mode.primaryLabel(isLoading),
                enabled = !isLoading,
                onClick = {
                    when (mode) {
                        AuthMode.SIGN_IN -> launchAuth {
                            viewModel.signIn(email, password)
                            finishAuth()
                        }

                        AuthMode.SIGN_UP -> launchAuth {
                            viewModel.signUp(email, password)
                            finishAuth()
                        }

                        AuthMode.RESET -> launchAuth {
                            viewModel.resetPassword(email)
                            mode = AuthMode.SIGN_IN
                            error = "Check your email for a reset link."
                        }
                    }
                },
                modifier = Modifier.padding(top = MiraSpacing.lg),
            )

            if (mode == AuthMode.SIGN_IN || mode == AuthMode.SIGN_UP) {
                AuthDivider(modifier = Modifier.padding(top = MiraSpacing.lg))
                GoogleButton(
                    enabled = !isLoading,
                    onClick = {
                        launchAuth {
                            viewModel.signInGoogle()
                            finishAuth()
                        }
                    },
                    modifier = Modifier.padding(top = MiraSpacing.lg),
                )
            }

            AuthModeFooter(
                mode = mode,
                onShowSignIn = { show(AuthMode.SIGN_IN) },
                onShowSignUp = { show(AuthMode.SIGN_UP) },
                modifier = Modifier.padding(top = MiraSpacing.lg),
            )
        }
    }
}

private val AuthCompactBreakpoint = 600.dp

@Composable
private fun AuthScreenTemplate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (maxWidth < AuthCompactBreakpoint) {
            AuthMobileTemplate(
                primary = primary,
                heroHeight = if (maxHeight < 640.dp) 210.dp else 272.dp,
                content = content,
            )
        } else {
            AuthDesktopTemplate(primary = primary, content = content)
        }
    }
}

@Composable
private fun AuthMobileTemplate(
    primary: Color,
    heroHeight: Dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            AuthBrandMark(primary = primary, markSize = 104.dp, circleSize = 148.dp)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(top = heroHeight - 42.dp, bottom = MiraSpacing.xxxl),
        ) {
            content()
        }
    }
}

@Composable
private fun AuthDesktopTemplate(
    primary: Color,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(0.46f)
                .fillMaxHeight()
                .background(primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = MiraSpacing.xxxl),
            ) {
                AuthBrandMark(primary = primary, markSize = 124.dp, circleSize = 180.dp)
                Spacer(Modifier.height(MiraSpacing.xxl))
                Text(
                    text = "Mira",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(MiraSpacing.sm))
                Text(
                    text = "Every day, kept.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }
        }

        Box(
            Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MiraSpacing.xxxl, vertical = MiraSpacing.xxxl),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun AuthBrandMark(
    primary: Color,
    markSize: Dp,
    circleSize: Dp,
) {
    Box(
        Modifier
            .size(circleSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        MiraAppMark(tint = primary, size = markSize)
    }
}

@Composable
private fun AuthSheet(
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = MiraSpacing.md)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(28.dp),
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                spotColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
            )
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = MiraSpacing.xl, vertical = MiraSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(MiraSpacing.xs),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = MiraSpacing.lg),
        )
        content()
    }
}

@Composable
private fun AuthUnderlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        HorizontalDivider(color = colors.outline)
    }
}

@Composable
private fun AuthPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AuthDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            text = "or",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = MiraSpacing.sm),
        )
    }
}

@Composable
private fun GoogleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        GoogleMark(size = 20.dp)
        Spacer(Modifier.width(MiraSpacing.sm))
        Text("Continue with Google", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AuthModeFooter(
    mode: AuthMode,
    onShowSignIn: () -> Unit,
    onShowSignUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        AuthMode.SIGN_IN -> AuthFooterLink(
            prompt = "New to Mira?",
            action = "Create an account",
            onClick = onShowSignUp,
            modifier = modifier,
        )

        AuthMode.SIGN_UP -> AuthFooterLink(
            prompt = "Already have an account?",
            action = "Sign in",
            onClick = onShowSignIn,
            modifier = modifier,
        )

        AuthMode.RESET -> AuthFooterLink(
            prompt = "Remembered your password?",
            action = "Sign in",
            onClick = onShowSignIn,
            modifier = modifier,
        )
    }
}

@Composable
private fun AuthFooterLink(
    prompt: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClick) {
            Text(action)
        }
    }
}

@Composable
private fun MergeRecipesDialog(
    mergeCount: Int,
    onMerge: () -> Unit,
    onSkip: () -> Unit,
) {
    MiraConfirmDialog(
        onDismissRequest = onSkip,
        title = "Merge local entries?",
        message = "You have $mergeCount entries on this device. Merge them into your account?",
        confirmText = "Merge",
        onConfirm = onMerge,
        dismissText = "Skip",
    )
}

private enum class AuthMode(
    val title: String,
    val subtitle: String,
) {
    SIGN_IN("Welcome back", "Sign in to pick up where you left off."),
    SIGN_UP("Create your account", "Keep every entry in sync across your devices."),
    RESET("Reset password", "We’ll send a secure reset link to your inbox."),
    ;

    fun primaryLabel(isLoading: Boolean): String = when {
        isLoading -> "Please wait…"
        this == SIGN_IN -> "Sign in"
        this == SIGN_UP -> "Create account"
        this == RESET -> "Send reset link"
        else -> error("Unhandled auth mode")
    }
}
