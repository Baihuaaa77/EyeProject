package com.zkyjss.eyeproject.core.data

import android.content.Context
import com.zkyjss.eyeproject.core.model.MonitoringSample
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sin

/** 监测数据仓库：滚动保留最近 2 天的采样。 */
interface MonitoringRepository {
    val samples: StateFlow<List<MonitoringSample>>
    fun addSample(sample: MonitoringSample)
    fun flush()
    fun clear()
}

/**
 * 基于 JSON 文件的内存 + 落盘实现：每采样一条立即写入文件，滚动保留最近 2 天。
 */
@Singleton
class JsonMonitoringRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : MonitoringRepository {

    private val file = File(context.filesDir, FILE_NAME)
    private val _samples = MutableStateFlow(load())
    override val samples: StateFlow<List<MonitoringSample>> = _samples.asStateFlow()

    init {
        pruneToRetention()
    }

    @Synchronized
    override fun addSample(sample: MonitoringSample) {
        _samples.value = _samples.value + sample
        pruneToRetention()
        persist(_samples.value)
    }

    @Synchronized
    override fun flush() {
        pruneToRetention()
        persist(_samples.value)
    }

    @Synchronized
    override fun clear() {
        _samples.value = emptyList()
        if (file.exists()) file.delete()
    }

    private fun pruneToRetention() {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        val kept = _samples.value.filter { it.timestampMillis >= cutoff }
        if (kept.size != _samples.value.size) {
            _samples.value = kept
        }
    }

    private fun load(): List<MonitoringSample> {
        return try {
            if (!file.exists()) return emptyList()
            val arr = JSONObject(file.readText()).optJSONArray("samples")
                ?: return emptyList()
            val list = ArrayList<MonitoringSample>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list += MonitoringSample(
                    timestampMillis = o.optLong("ts"),
                    ambientLux = o.optDouble("lux").toFloat(),
                    screenBrightness = o.optInt("brightness"),
                    distanceCm = o.optDouble("distance").toFloat(),
                    durationMin = o.optDouble("duration").toFloat(),
                    blinkCount = o.optInt("blink")
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(samples: List<MonitoringSample>) {
        try {
            val arr = JSONArray()
            for (s in samples) {
                arr.put(
                    JSONObject()
                        .put("ts", s.timestampMillis)
                        .put("lux", s.ambientLux)
                        .put("brightness", s.screenBrightness)
                        .put("distance", s.distanceCm)
                        .put("duration", s.durationMin)
                        .put("blink", s.blinkCount)
                )
            }
            file.writeText(JSONObject().put("version", 1).put("samples", arr).toString())
        } catch (_: Exception) {
            // 落盘失败不致命，内存中仍保留数据
        }
    }

    companion object {
        private const val FILE_NAME = "monitoring_samples.json"
        private const val RETENTION_MILLIS = 2L * 24 * 60 * 60 * 1000L
    }
}