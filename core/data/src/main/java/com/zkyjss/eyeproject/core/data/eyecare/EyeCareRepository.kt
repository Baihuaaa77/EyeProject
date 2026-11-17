package com.zkyjss.eyeproject.core.data.eyecare

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import com.zkyjss.eyeproject.core.domain.LiveEyeMonitor
import com.zkyjss.eyeproject.core.eyecare.EyeCareDays
import com.zkyjss.eyeproject.core.eyecare.EyeCareEngine
import com.zkyjss.eyeproject.core.eyecare.LedgerData
import com.zkyjss.eyeproject.core.eyecare.UsageDay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 当下的环境光与屏幕亮度；[lux] 为 null 表示暂无读数，是否支持传感器由 [EnvironmentReading.luxSupported] 区分。 */
data class EnvironmentReading(val lux: Float?, val brightness: Float?, val luxSupported: Boolean)

/** 系统用屏统计的授权状态与逐日数据（未授权时为空）。 */
data class SystemUsage(val access: String = EyeCareEngine.ACCESS_DENIED, val days: List<UsageDay> = emptyList())

/**
 * 主动护眼的本机数据：账本（实时检测会话、本应用使用时长、每日感受打卡）、
 * 系统用屏统计与环境光 / 亮度。全部只在本机，不上传；规则与报告在共用的 EyeCareEngine。
 */
interface EyeCareRepository {
    val ledger: Flow<LedgerData>
    val systemUsage: Flow<SystemUsage>
    /** 订阅期间监听光线传感器（离开页面即停止）。 */
    fun environment(): Flow<EnvironmentReading>
    /** 重新读取系统用屏统计（回到前台、从系统设置授权回来时调用）。 */
    suspend fun refreshUsage()
    suspend fun addLiveSession(session: LiveEyeMonitor.Session)
    suspend fun checkIn(symptoms: List<String>, note: String = "")
    suspend fun careCheckIn(key: String)
    suspend fun addAppSeconds(seconds: Long)
}

@Singleton
internal class OfflineFirstEyeCareRepository(
    directory: File,
    private val reader: ScreenUsageReader,
    private val sensors: SensorManager?,
    private val brightness: () -> Float?,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
) : EyeCareRepository {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.filesDir, SystemScreenUsageReader(context), context.getSystemService(SensorManager::class.java),
        { readBrightness(context) }, System::currentTimeMillis, ZoneId::systemDefault,
    )

    private val file = File(directory, FILE_NAME)
    private val lock = Mutex()
    private val state = MutableStateFlow(read())
    private val usage = MutableStateFlow(SystemUsage())

    override val ledger: Flow<LedgerData> = state.asStateFlow()
    override val systemUsage: Flow<SystemUsage> = usage.asStateFlow()

    private fun today(): String = EyeCareDays.dayOf(clock(), zone().rules.getOffset(java.time.Instant.ofEpochMilli(clock())).totalSeconds / 60)

    override fun environment(): Flow<EnvironmentReading> = callbackFlow {
        val manager = sensors
        val light = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val lux = AtomicReference<Float?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return
                lux.set(value)
                trySend(EnvironmentReading(value, brightness(), true))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val supported = manager != null && light != null && manager.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL)
        // 系统亮度可独立变化；无光线传感器或光照不变时也需要刷新。
        val ticker = launch {
            while (isActive) {
                trySend(EnvironmentReading(lux.get(), brightness(), supported))
                delay(1_000)
            }
        }
        awaitClose { ticker.cancel(); manager?.unregisterListener(listener) }
    }.conflate().flowOn(Dispatchers.Default)

    override suspend fun refreshUsage() {
        usage.value = withContext(Dispatchers.IO) {
            if (!reader.hasAccess()) SystemUsage(EyeCareEngine.ACCESS_DENIED)
            else SystemUsage(EyeCareEngine.ACCESS_GRANTED, reader.read(clock(), zone(), DAYS))
        }
    }

    override suspend fun addLiveSession(session: LiveEyeMonitor.Session) = update { it.withSession(today(), clock(), session) }
    override suspend fun checkIn(symptoms: List<String>, note: String) = update { it.withCheckIn(today(), symptoms, clock(),note) }
    override suspend fun careCheckIn(key: String) = update { it.withCareCheckIn(today(),key,clock()) }
    override suspend fun addAppSeconds(seconds: Long) = update { it.withAppSeconds(today(), seconds) }

    private suspend fun update(transform: (LedgerData) -> LedgerData) = lock.withLock {
        val next = transform(state.value).pruned(today())
        withContext(Dispatchers.IO) {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(next.toJson())
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
        state.value = next
    }

    private fun read(): LedgerData = LedgerData.fromJson(runCatching { file.takeIf(File::exists)?.readText() }.getOrNull())

    companion object {
        const val FILE_NAME = "eyecare.json"
        /** 读取最近 14 天：趋势页对比本周与上周。 */
        private const val DAYS = 14

        /** 系统亮度设置 0–255 换算成 0–1（个别机型上限不同，只作参考）。 */
        private fun readBrightness(context: Context): Float? = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrNull()?.coerceIn(0f, 1f)
    }
}
