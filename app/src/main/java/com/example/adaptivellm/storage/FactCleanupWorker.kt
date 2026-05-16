package com.example.adaptivellm.storage

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Stage 7 — periodic cleanup для facts таблицы (architecture.md § Periodic cleanup).
 *
 * Запускается раз в сутки (примерно — WorkManager имеет jitter ±15 мин для
 * экономии батареи). Прогоняет 4 правила инвалидации через
 * [FactsRepository.invalidateExpired]:
 *   - Rule 1: trash facts (low importance + 0 access + old)
 *   - Rule 2: low-priority idle (medium importance + rarely accessed + idle 90d)
 *   - Rule 3: not-very-important very idle (180d)
 *   - Rule 4: expired events (event_date > 30d назад)
 *
 * Не зависит от модели / engine / прочей runtime инфраструктуры — только DB.
 * Может работать в фоне без активной Activity.
 *
 * Constraints: только при заряде ≥ ~15% (default ENERGY_STARVED protection)
 * чтобы не сожрать батарею при низком заряде. NetworkType.NOT_REQUIRED — DB
 * локальная, сети не нужны.
 */
class FactCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        // Ensure DB is initialized — может быть холодный запуск из WorkManager'а
        // без открытого приложения. Initialize идемпотентна, если DB уже открыта —
        // мгновенный return.
        MemoryDatabaseHelper.initialize(applicationContext)
        val stats = FactsRepository.invalidateExpired()
        Log.i(TAG, "Cleanup complete: invalidated ${stats.total} facts " +
                   "(rule1=${stats.rule1}, rule2=${stats.rule2}, " +
                   "rule3=${stats.rule3}, rule4=${stats.rule4})")
        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "Cleanup failed", e)
        // Retry с экспоненциальным backoff — WorkManager сам разрулит
        Result.retry()
    }

    companion object {
        private const val TAG = "FactCleanupWorker"
        private const val UNIQUE_NAME = "fact_cleanup_daily"

        /**
         * Регистрирует periodic worker. Идемпотентно — повторный вызов с
         * KEEP policy не перезапустит уже запланированный job.
         *
         * Период: 24 часа (минимум WorkManager'а — 15 минут, но cleanup
         * раз в сутки достаточно). Constraints: device не должен быть в
         * battery-saver mode + заряд ≥ ~15%.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<FactCleanupWorker>(
                repeatInterval = 24, repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1, flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "Periodic cleanup scheduled (every 24h, +/- 1h flex)")
        }

        /** Только для debug/dev: запустить cleanup немедленно один раз. */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<FactCleanupWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "One-time cleanup enqueued")
        }
    }
}
