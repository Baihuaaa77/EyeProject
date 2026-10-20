package com.zkyjss.eyeproject.feature.services

/** 健康档案补充项：沿用成员字段表，可选填写，旧资料无需迁移或补填。 */
internal object HealthProfileFields {
    private fun decimal(key: String, title: String, range: ClosedFloatingPointRange<Double>, example: String) = Question(
        key, title, optional = true, inputKind = QuestionInput.DECIMAL, placeholder = example, maxLength = 8,
        validate = { it.toDoubleOrNull()?.let { n -> n.isFinite() && n in range } == true },
    )
    private fun date(key: String, visible: (Map<String, String>) -> Boolean = { true }) = Question(
        key, optional = true, inputKind = QuestionInput.DATE, placeholder = "如：2026-10-20", maxLength = 10,
        visibleWhen = visible, validate = ::validDate,
    )
    private val medication: (Map<String, String>) -> Boolean = { it["近期眼部用药"] in setOf("人工泪液", "其他眼药水") }
    private val contacts: (Map<String, String>) -> Boolean = { it["佩戴隐形眼镜史"] in setOf("以前戴过", "现在在戴") }

    val measurements = ProfileSection("视力与验光记录（选填，照检查单填写）", buildList {
        add(date("验光日期"))
        for (eye in listOf("右眼", "左眼")) {
            add(decimal("${eye}裸眼视力", "${eye}裸眼视力（小数视力）", 0.01..3.0, "如：0.5；不是 4.7"))
            add(decimal("${eye}矫正视力", "${eye}戴镜矫正视力（小数视力）", 0.01..3.0, "如：1.0，不清楚可留空"))
            add(decimal("${eye}球镜", "${eye}球镜 SPH（D，保留正负号）", -30.0..30.0, "如：-2.50"))
            add(decimal("${eye}柱镜", "${eye}柱镜 CYL（D，保留正负号）", -15.0..15.0, "如：-0.75"))
            add(Question("${eye}轴位", "${eye}散光轴位 AX（0–180°）", numeric = true, optional = true,
                placeholder = "如：90", maxLength = 3, validate = { it.toIntOrNull() in 0..180 }))
        }
    })
    val histories = ProfileSection("用药、隐形眼镜与家族史（选填）", listOf(
        Question("眼药水名称", optional = true, visibleWhen = medication, placeholder = "照药盒填写，不清楚可留空", maxLength = 100),
        Question("眼药水使用频次", optional = true, visibleWhen = medication, placeholder = "如：每日 3 次", maxLength = 100),
        date("眼药水开始日期", medication), date("眼药水结束日期", medication),
        Question("隐形眼镜类型", options = listOf("软性镜片", "硬性透气镜片（RGP）", "角膜塑形镜（OK 镜）", "不清楚"), optional = true, visibleWhen = contacts),
        Question("隐形眼镜更换周期", options = listOf("日抛", "双周抛", "月抛", "季抛", "年抛", "其他", "不清楚"), optional = true, visibleWhen = contacts),
        Question("隐形眼镜每日佩戴时长", "隐形眼镜每天戴多久？", options = listOf("不到 4 小时", "4–8 小时", "8 小时以上", "仅夜间佩戴", "不清楚"), optional = true, visibleWhen = contacts),
        Question("家族眼病史", "近亲属有医生确诊的眼病吗？", options = listOf("近视", "青光眼", "白内障", "斜视", "弱视", "眼底病", "其他", "没有", "不清楚"), multiple = true, optional = true),
        Question("过敏史", "已知过敏史（药物或其他）", optional = true, placeholder = "如：某种眼药水过敏；不知道可留空", maxLength = 100),
        Question("眼病与手术补充", "眼病与手术补充说明", optional = true, placeholder = "如：左眼、手术名称及大致时间", maxLength = 200),
    ))

    fun validDate(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        val (year, month, day) = value.split('-').map { it.toInt() }
        if (year !in 1900..2100 || month !in 1..12) return false
        val leap = year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)
        val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day in 1..days[month - 1]
    }
}
