package com.example.adaptivellm.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 7 — пользовательские настройки приложения.
 *
 * Persistent storage через SharedPreferences (синхронно для простоты — все
 * операции дешёвые, под однопоточный SP-кэш Android). StateFlow обёртки —
 * чтобы UI и движки могли реактивно подписаться на изменения.
 *
 * Инициализируется один раз через [init] из Application/ViewModel. Все
 * последующие чтения — через `value` или `collectAsState` в Compose.
 */
object SettingsRepository {

    private const val PREFS_NAME = "user_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_CROSS_CHAT_FACTS = "cross_chat_facts_enabled"
    private const val KEY_APP_LANGUAGE = "app_language"

    private lateinit var prefs: SharedPreferences

    /** System / Light / Dark — режим темы приложения. */
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    /**
     * Язык интерфейса приложения.
     *   - SYSTEM = следовать системной локали (если есть `res/values-XX/` —
     *     используется, иначе fallback на `res/values/` = English)
     *   - конкретный язык = app-level override через AppCompatDelegate
     *
     * Расширяемо: добавление нового языка = добавить enum + соответствующий
     * `res/values-XX/strings.xml`.
     */
    enum class AppLanguage(val tag: String) {
        SYSTEM(""),
        EN("en"),
        RU("ru"),
        ES("es"),
        FR("fr"),
        DE("de"),
        ZH("zh"),
    }

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _appLanguage = MutableStateFlow(AppLanguage.SYSTEM)
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    /**
     * Если ON — retrieval подмешивает факты из других чатов после фактов
     * текущего чата (tier-priority поведение). Если OFF — только факты с
     * `chat_id == currentChatId` (или legacy `chat_id IS NULL`) попадают
     * в prompt. Полезно для privacy и для бенчмарка где нужна изоляция.
     */
    private val _crossChatFactsEnabled = MutableStateFlow(true)
    val crossChatFactsEnabled: StateFlow<Boolean> = _crossChatFactsEnabled.asStateFlow()

    /**
     * Инициализирует repository из SharedPreferences. Идемпотентна — повторные
     * вызовы безопасны.
     */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Читаем сохранённые значения, иначе остаются defaults из StateFlow выше.
        val themeStr = prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        _themeMode.value = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.SYSTEM)

        _crossChatFactsEnabled.value = prefs.getBoolean(KEY_CROSS_CHAT_FACTS, true)

        val langStr = prefs.getString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.name) ?: AppLanguage.SYSTEM.name
        _appLanguage.value = runCatching { AppLanguage.valueOf(langStr) }.getOrDefault(AppLanguage.SYSTEM)
        // Применяем сохранённый язык при старте — иначе AppCompatDelegate сбросится
        // и Activity покажет системный язык вместо пользовательского выбора.
        applyLocale(_appLanguage.value)
    }

    fun setThemeMode(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setCrossChatFactsEnabled(enabled: Boolean) {
        if (_crossChatFactsEnabled.value == enabled) return
        _crossChatFactsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_CROSS_CHAT_FACTS, enabled).apply()
    }

    fun setAppLanguage(lang: AppLanguage) {
        if (_appLanguage.value == lang) return
        _appLanguage.value = lang
        prefs.edit().putString(KEY_APP_LANGUAGE, lang.name).apply()
        applyLocale(lang)
    }

    /**
     * Применяет локаль через AppCompatDelegate. SYSTEM → пустой LocaleListCompat,
     * Android берёт системную локаль. Конкретный язык → app-level override,
     * Activity recreate, ресурсы перечитываются из соответствующего res/values-XX/.
     */
    private fun applyLocale(lang: AppLanguage) {
        val locales = if (lang == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
