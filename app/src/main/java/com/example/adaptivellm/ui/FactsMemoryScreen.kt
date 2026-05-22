package com.example.adaptivellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.storage.FactsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 7 — экран «Память». Просмотр всех активных фактов (valid_to IS NULL)
 * с фильтрацией по категории. MVP read-only: tap по факту → детали в диалоге.
 *
 * Restoration/deletion инвалидированных фактов отложены на post-конкурс scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactsMemoryScreen(viewModel: MainViewModel) {
    val facts by viewModel.facts.collectAsState()
    val filter by viewModel.factsCategoryFilter.collectAsState()
    val loading by viewModel.factsLoading.collectAsState()

    var detail by remember { mutableStateOf<FactsRepository.Fact?>(null) }
    detail?.let { f ->
        FactDetailDialog(fact = f, onDismiss = { detail = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(AppScreen.ChatList) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            stringResource(R.string.memory_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            pluralStringResource(R.plurals.memory_facts_count, facts.size, facts.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            FilterChipRow(
                selected = filter,
                onSelected = { viewModel.setFactsFilter(it) },
            )

            if (loading && facts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (facts.isEmpty()) {
                EmptyState(hasFilter = filter != null)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(facts, key = { it.id }) { fact ->
                        FactCard(fact = fact, onClick = { detail = fact })
                    }
                }
            }
        }
    }
}

/** Горизонтальный ряд chip'ов: «Все» + по одной на категорию. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.memory_filter_all)) },
        )
        for (cat in CATEGORY_ORDER) {
            FilterChip(
                selected = selected == cat,
                onClick = { onSelected(cat) },
                label = { Text(categoryLabel(cat)) },
            )
        }
    }
}

@Composable
private fun FactCard(fact: FactsRepository.Fact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(fact.category)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = importanceStars(fact.importance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatRelativeShort(fact.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = fact.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: String) {
    val (bg, fg) = categoryColors(category)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = categoryLabel(category),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FactDetailDialog(fact: FactsRepository.Fact, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(fact.category)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.memory_fact_id, fact.id),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "«${fact.content}»",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                DetailRow(stringResource(R.string.memory_importance), "${fact.importance}/10")
                DetailRow(stringResource(R.string.memory_access_count), fact.accessCount.toString())
                fact.context?.takeIf { it.isNotBlank() }?.let {
                    DetailRow(stringResource(R.string.memory_context), it)
                }
                if (fact.keywords.isNotEmpty()) {
                    DetailRow(stringResource(R.string.memory_keywords), fact.keywords.joinToString(", "))
                }
                DetailRow(stringResource(R.string.memory_created), formatAbsolute(fact.createdAt))
                fact.eventDate?.let {
                    DetailRow(stringResource(R.string.memory_event_date), formatAbsoluteDate(it))
                }
                fact.chatId?.let {
                    DetailRow(
                        stringResource(R.string.memory_source),
                        stringResource(R.string.memory_source_local, it),
                    )
                } ?: DetailRow(
                    stringResource(R.string.memory_source),
                    stringResource(R.string.memory_source_global),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(hasFilter: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(
                    if (hasFilter) R.string.memory_filter_empty_title
                    else R.string.memory_empty_title
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (hasFilter) R.string.memory_filter_empty_subtitle
                    else R.string.memory_empty_subtitle
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────

/** Порядок категорий в filter row (от частого к редкому). */
private val CATEGORY_ORDER = listOf(
    "preference", "goal", "personal_info", "instruction", "event", "relationship"
)

/**
 * UI-label для категории факта. БД-идентификатор остаётся английским
 * ("preference"/"goal"/etc); локализованный label через res/strings.xml.
 */
@Composable
private fun categoryLabel(category: String): String = when (category) {
    "preference" -> stringResource(R.string.cat_preference)
    "goal" -> stringResource(R.string.cat_goal)
    "personal_info" -> stringResource(R.string.cat_personal_info)
    "instruction" -> stringResource(R.string.cat_instruction)
    "event" -> stringResource(R.string.cat_event)
    "relationship" -> stringResource(R.string.cat_relationship)
    else -> category
}

/** Возвращает (background, foreground) для category badge. */
@Composable
private fun categoryColors(category: String): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (category) {
        "preference"    -> cs.tertiaryContainer to cs.onTertiaryContainer
        "goal"          -> cs.secondaryContainer to cs.onSecondaryContainer
        "personal_info" -> cs.primaryContainer to cs.onPrimaryContainer
        "instruction"   -> cs.errorContainer to cs.onErrorContainer
        "event"         -> cs.surfaceTint.copy(alpha = 0.2f) to cs.onSurface
        "relationship"  -> cs.inversePrimary to cs.onPrimaryContainer
        else            -> cs.surfaceVariant to cs.onSurfaceVariant
    }
}

private fun importanceStars(importance: Int): String {
    val n = importance.coerceIn(1, 10)
    val full = n / 2
    val half = n % 2
    return "★".repeat(full) + (if (half == 1) "·" else "") + " $n/10"
}

/**
 * Локализованный «N мин назад» / «X дн назад» через Android plurals.
 * @Composable нужен для `pluralStringResource` / `stringResource`.
 */
@Composable
private fun formatRelativeShort(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000L
    val diff = now - unixSec
    return when {
        diff < 60 -> stringResource(R.string.time_just_now)
        diff < 3600 -> {
            val n = (diff / 60).toInt()
            pluralStringResource(R.plurals.time_minutes_ago, n, n)
        }
        diff < 86400 -> {
            val n = (diff / 3600).toInt()
            pluralStringResource(R.plurals.time_hours_ago, n, n)
        }
        diff < 7 * 86400 -> {
            val n = (diff / 86400).toInt()
            pluralStringResource(R.plurals.time_days_ago, n, n)
        }
        else -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(unixSec * 1000L))
    }
}

private fun formatAbsolute(unixSec: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(unixSec * 1000L))

private fun formatAbsoluteDate(unixSec: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(unixSec * 1000L))

/**
 * Material Design «library_books» icon (стопка книг с линиями страниц) —
 * inline ImageVector чтобы не подтягивать material-icons-extended зависимость.
 * Семантически: архив сохранённых фактов / памяти.
 */
internal val MemoryLibraryIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LibraryBooks",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Back book (peeks out behind, with bottom-left tab)
            moveTo(4f, 6f)
            horizontalLineTo(2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            horizontalLineTo(4f)
            verticalLineTo(6f)
            close()
            // Front book (main rectangle)
            moveTo(20f, 2f)
            horizontalLineTo(8f)
            curveTo(6.9f, 2f, 6f, 2.9f, 6f, 4f)
            verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(12f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            close()
            moveTo(20f, 16f)
            horizontalLineTo(8f)
            verticalLineTo(4f)
            horizontalLineToRelative(12f)
            verticalLineTo(16f)
            close()
            // Line 1 (top)
            moveTo(10f, 9f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-8f)
            verticalLineTo(9f)
            close()
            // Line 2 (middle)
            moveTo(10f, 6f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-8f)
            verticalLineTo(6f)
            close()
            // Line 3 (bottom, shorter)
            moveTo(10f, 12f)
            horizontalLineToRelative(5f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-5f)
            verticalLineTo(12f)
            close()
        }
    }.build()
}
