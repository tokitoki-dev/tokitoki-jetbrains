package dev.tokitoki.jetbrains.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.tokitoki.jetbrains.TokitokiBundle
import dev.tokitoki.jetbrains.TokitokiService

class TokitokiConfigurable : BoundConfigurable(TokitokiBundle.message("settings.title")) {
    private val state get() = TokitokiSettings.getInstance().state

    override fun createPanel() = panel {
        row {
            checkBox(TokitokiBundle.message("settings.statusBar.enabled"))
                .bindSelected(state::statusBarEnabled)
        }
        row {
            checkBox(TokitokiBundle.message("settings.statusBar.showTime"))
                .bindSelected(state::statusBarShowTime)
                .comment(TokitokiBundle.message("settings.statusBar.showTime.comment"))
        }
        row {
            comment(TokitokiBundle.message("settings.note"))
        }
    }

    override fun apply() {
        super.apply()
        TokitokiService.getInstance().settingsChanged()
    }
}
