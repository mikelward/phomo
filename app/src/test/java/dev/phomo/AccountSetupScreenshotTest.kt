package dev.phomo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.phomo.sip.SipAccountError
import dev.phomo.sip.SipAccountField
import dev.phomo.sip.SipTransport
import dev.phomo.ui.AccountSetupContent
import dev.phomo.ui.PhomoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountSetupScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(state: AccountSetupUiState) {
        composeRule.setContent {
            PhomoTheme(dynamicColor = false) {
                StatelessSetup(state)
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun StatelessSetup(state: AccountSetupUiState) {
        AccountSetupContent(
            state = state,
            onUsernameChange = {}, onDomainChange = {}, onPasswordChange = {},
            onOutboundProxyChange = {}, onDisplayNameChange = {}, onTransportChange = {}, onSave = {},
        )
    }

    @Test
    fun empty() {
        render(AccountSetupUiState())
        save("account_setup_empty.png")
    }

    @Test
    fun filled() {
        render(
            AccountSetupUiState(
                username = "alice",
                domain = "sip.example.com",
                password = "s3cret",
                outboundProxy = "proxy.example.com:5061",
                displayName = "Alice",
                transport = SipTransport.Tls,
                saved = true,
            ),
        )
        save("account_setup_filled.png")
    }

    @Test
    fun withErrors() {
        render(
            AccountSetupUiState(
                username = "alice",
                domain = "bad domain",
                errors = mapOf(
                    SipAccountField.Domain to SipAccountError.DomainInvalid,
                    SipAccountField.Password to SipAccountError.PasswordRequired,
                ),
            ),
        )
        save("account_setup_errors.png")
    }

    @Test
    fun saveFailed() {
        render(
            AccountSetupUiState(
                username = "alice",
                domain = "sip.example.com",
                password = "s3cret",
                transport = SipTransport.Tls,
                saveFailed = true,
            ),
        )
        save("account_setup_save_failed.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun empty_darkTheme() {
        render(AccountSetupUiState())
        save("account_setup_empty_dark.png")
    }

    private fun save(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
