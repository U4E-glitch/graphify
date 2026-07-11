package com.dheyab.qiyas.integration

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.entry.AddGlucoseScreen
import com.dheyab.qiyas.ui.entry.GlucoseEntryViewModel
import com.dheyab.qiyas.ui.theme.QiyasTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real AddGlucose screen through Compose UI testing (Robolectric):
 * types like a user, taps chips and buttons, and checks the blocking alert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlainTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddGlucoseScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private lateinit var viewModel: GlucoseEntryViewModel
    private var doneZone: Zone? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = TestGraph(ApplicationProvider.getApplicationContext<Context>())
        viewModel = GlucoseEntryViewModel(
            graph.readingRepository, graph.settingsRepository, graph.classifier,
            graph.photoStore, graph.scanner, SavedStateHandle(),
        )
    }

    private fun setScreen() {
        compose.setContent {
            QiyasTheme {
                AddGlucoseScreen(
                    onDone = { doneZone = it },
                    onBack = {},
                    viewModel = viewModel,
                )
            }
        }
    }

    /** The value field is the first editable field on the screen (the note field is second). */
    private fun valueField() = compose.onAllNodes(hasSetTextAction()).onFirst()

    /** Saves finish on Room's background executor — wait in real time, then flush composition. */
    private fun awaitState(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            compose.waitForIdle()
        }
        assertThat(condition()).isTrue()
        compose.waitForIdle()
    }

    @Test
    fun saveStaysDisabledUntilContextChosen_thenNormalSaveCompletes() {
        setScreen()

        compose.onNodeWithText("Save").assertIsNotEnabled()
        valueField().performTextInput("100")
        compose.onNodeWithText("Save").assertIsNotEnabled() // Hard Rule 2

        compose.onNodeWithText("Fasting").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        awaitState { viewModel.state.value.closeWithZone != null }

        assertThat(doneZone).isEqualTo(Zone.IN_RANGE)
    }

    @Test
    fun emergencyValueShowsBlockingAlertWithVerbatimCopy() {
        setScreen()

        valueField().performTextInput("58")
        compose.onNodeWithText("Random").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        awaitState { viewModel.state.value.alert != null }

        // Blocking dialog with the exact Appendix B copy; navigation held back.
        compose.onNodeWithText("Low blood sugar (58 mg/dL). Take about 15 g of fast-acting carbohydrates (such as juice or glucose tablets) and re-check in 15 minutes. If it stays low or symptoms worsen, seek medical help.")
            .assertIsDisplayed()
        assertThat(doneZone).isNull()

        compose.onNodeWithText("I understand").performClick()
        awaitState { viewModel.state.value.closeWithZone != null }
        assertThat(doneZone).isEqualTo(Zone.EMERGENCY_LOW)
    }

    @Test
    fun invalidValueShowsInlineErrorAndBlocksSave() {
        setScreen()

        valueField().performTextInput("700") // above 600 mg/dL cap
        compose.onNodeWithText("Fasting").performClick()
        compose.onNodeWithText("Enter a value between 20 and 600 mg/dL").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }
}
