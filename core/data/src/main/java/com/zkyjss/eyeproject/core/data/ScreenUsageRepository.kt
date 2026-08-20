package com.zkyjss.eyeproject.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.zkyjss.eyeproject.core.model.AppUsageInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenUsageRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = context.packageManager

    fun getTodayUsage(): List<AppUsageInfo> {
        val calendar = Calendar.getInstance()
        val endMillis = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return getUsage(calendar.timeInMillis, endMillis)
    }

    fun getUsage(startMillis: Long, endMillis: Long): List<AppUsageInfo> {
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startMillis,
            endMillis
        )

        return stats
            .asSequence()
            .filter { it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .map { (packageName, packageStats) ->
                AppUsageInfo(
                    packageName = packageName,
                    appName = resolveAppName(packageName),
                    totalTimeInForegroundMillis = packageStats.sumOf { it.totalTimeInForeground },
                    lastTimeUsedMillis = packageStats.maxOf { it.lastTimeUsed }
                )
            }
            .sortedByDescending { it.totalTimeInForegroundMillis }
            .toList()
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: SecurityException) {
            packageName
        }
    }
}
