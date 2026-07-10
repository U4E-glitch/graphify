package com.dheyab.qiyas.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.appcompat.app.AppCompatDelegate
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.settings.LanguageChoice
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.settings.toLocaleList
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val step: Int = 0, // 0 = language, 1 = unit, 2 = disclaimer
    val language: LanguageChoice = LanguageChoice.SYSTEM,
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun selectLanguage(language: LanguageChoice) {
        _state.value = _state.value.copy(language = language)
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
            AppCompatDelegate.setApplicationLocales(language.toLocaleList())
        }
    }

    fun selectUnit(unit: GlucoseUnit) {
        _state.value = _state.value.copy(unit = unit)
        viewModelScope.launch { settingsRepository.setGlucoseUnit(unit) }
    }

    fun next() {
        _state.value = _state.value.copy(step = _state.value.step + 1)
    }

    /** The app is unusable until the disclaimer is accepted (spec §9). */
    fun accept() {
        viewModelScope.launch { settingsRepository.setDisclaimerAccepted(true) }
    }
}

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(width = if (index == state.step) 22.dp else 8.dp, height = 8.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(
                                if (index == state.step) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                    )
                }
            }
        }
        when (state.step) {
            0 -> {
                Text(stringResource(R.string.onboarding_language_title), style = MaterialTheme.typography.titleLarge)
                RadioGroup(
                    options = LanguageChoice.entries.toList(),
                    selected = state.language,
                    labelOf = {
                        when (it) {
                            LanguageChoice.SYSTEM -> stringResource(R.string.language_system)
                            LanguageChoice.EN -> stringResource(R.string.language_english)
                            LanguageChoice.AR -> stringResource(R.string.language_arabic)
                        }
                    },
                    onSelect = viewModel::selectLanguage,
                )
                Button(onClick = viewModel::next, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_continue))
                }
            }
            1 -> {
                Text(stringResource(R.string.onboarding_unit_title), style = MaterialTheme.typography.titleLarge)
                RadioGroup(
                    options = GlucoseUnit.entries.toList(),
                    selected = state.unit,
                    labelOf = { stringResource(it.labelRes()) },
                    onSelect = viewModel::selectUnit,
                )
                Button(onClick = viewModel::next, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_continue))
                }
            }
            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.disclaimer_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Button(onClick = viewModel::accept, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_accept))
                }
            }
        }
    }
}

@Composable
private fun <T> RadioGroup(
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(selected = option == selected, onClick = { onSelect(option) })
                Text(labelOf(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
