package my.fumui.sairitsu.ui

import androidx.compose.runtime.Composable
import my.fumui.sairitsu.gameplay.AppSettings

private val VOLUME = StepScale(0.0, 1.0, 0.01)
private val OFFSET = StepScale(-0.5, 0.5, 0.001)

/** The four [AppSettings] sliders, shared by Settings and "Level options". */
@Composable
fun AppSettingsSliders(s: AppSettings, onSettings: (AppSettings) -> Unit) {
    val d = AppSettings()
    SettingSlider("Effect volume", s.effectVolume, d.effectVolume, VOLUME, ValueFormat.PERCENT) {
        onSettings(s.copy(effectVolume = it))
    }
    SettingSlider("BGM volume", s.bgmVolume, d.bgmVolume, VOLUME, ValueFormat.PERCENT) {
        onSettings(s.copy(bgmVolume = it))
    }
    SettingSlider("Audio offset", s.audioOffset, d.audioOffset, OFFSET, ValueFormat.SECONDS_AS_MS) {
        onSettings(s.copy(audioOffset = it))
    }
    SettingSlider("Input offset", s.inputOffset, d.inputOffset, OFFSET, ValueFormat.SECONDS_AS_MS) {
        onSettings(s.copy(inputOffset = it))
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    default: Float,
    scale: StepScale,
    format: ValueFormat,
    onChange: (Float) -> Unit,
) = StepperSlider(label, value.toString().toDouble(), scale, format, default.toString().toDouble(), { onChange(it.toFloat()) })
