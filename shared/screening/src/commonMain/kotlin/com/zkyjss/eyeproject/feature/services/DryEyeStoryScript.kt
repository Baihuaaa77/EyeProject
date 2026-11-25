package com.zkyjss.eyeproject.feature.services

/**
 * 干眼自测「护眼科普小剧场」的双端共用剧本与时间轴。
 * 每幕 10 秒，前 6 幕（60 秒）完整讲述泪膜结构、盯屏眨眼危机与日常护眼方法；
 * 延长至 3 分钟（180 秒）时续播第 7–18 幕进阶百科，全程配同步中文语音讲解与电影字幕。
 */
data class DryEyeStoryScene(
    val index: Int,
    val sceneKey: String,
    val title: String,
    val factTag: String,
    val narration: String,
)

object DryEyeStoryScript {
    const val SCENE_DURATION_SEC = 10

    val scenes: List<DryEyeStoryScene> = listOf(
        DryEyeStoryScene(
            index = 0,
            sceneKey = "WATER_WORLD",
            title = "欢迎来到眼球水世界",
            factTag = "隐形保湿水膜",
            narration = "欢迎来到眼睛的水世界。每一次轻眨眼，都会为清澈的角膜铺上一层隐形的保护水膜。",
        ),
        DryEyeStoryScene(
            index = 1,
            sceneKey = "TEAR_LAYERS",
            title = "神奇的三层泪膜护盾",
            factTag = "油脂 · 水液 · 黏蛋白",
            narration = "别看泪膜很薄，它其实有三层。外层油脂锁住水分，中层水液滋润角膜，内层让水膜稳稳贴合。",
        ),
        DryEyeStoryScene(
            index = 2,
            sceneKey = "SCREEN_VS_TALK",
            title = "盯屏幕时的眨眼危机",
            factTag = "正常 10–16 次/分",
            narration = "平时聊天时，我们每分钟大约眨眼十五次。可一旦专注看屏幕，眨眼会悄悄减少一半，水膜更容易变干。",
        ),
        DryEyeStoryScene(
            index = 3,
            sceneKey = "TEAR_BREAKUP",
            title = "眼睑就像温柔的小雨刷",
            factTag = "眨眼抹平干斑",
            narration = "如果太久不眨眼，泪膜表面会出现干斑缺口，看东西容易发雾发涩。轻轻一眨眼，就能把水膜重新抹平。",
        ),
        DryEyeStoryScene(
            index = 4,
            sceneKey = "RULE_20_20_20",
            title = "20-20-20 远眺放松法",
            factTag = "远眺 6 米 · 20 秒",
            narration = "每用眼二十分钟，记得抬头看看六米外的远处二十秒。远眺能让紧绷的眼肌松口气，眨眼也会自然恢复。",
        ),
        DryEyeStoryScene(
            index = 5,
            sceneKey = "WARM_COMPRESS",
            title = "40°C 暖雾热敷魔法",
            factTag = "疏通睑板腺油脂",
            narration = "眼皮里藏着分泌油脂的小管道。睡前用温热毛巾敷一敷，能让油脂顺畅流出，给眼睛一整晚的水润保护。",
        ),
        DryEyeStoryScene(
            index = 6,
            sceneKey = "AC_WIND",
            title = "别让空调风直吹眼睛",
            factTag = "适宜湿度 40%–60%",
            narration = "空调和风扇直吹脸部会加速泪液蒸发。调偏出风口方向，在干燥房间放一台加湿器，眼睛会舒服很多。",
        ),
        DryEyeStoryScene(
            index = 7,
            sceneKey = "SCREEN_ANGLE",
            title = "把屏幕放低一点点",
            factTag = "视线略向下 15°",
            narration = "屏幕略低于视线约十五度时，上眼皮会自然垂下遮住一部分眼球，能明显减少眼表暴露和水分流失。",
        ),
        DryEyeStoryScene(
            index = 8,
            sceneKey = "NIGHT_LIGHT",
            title = "别在全黑环境刷手机",
            factTag = "开启柔和背景光",
            narration = "关灯后只看发亮的屏幕，明暗反差太强容易眼酸发干。晚上用手机时，记得在身旁留一盏柔和的小夜灯。",
        ),
        DryEyeStoryScene(
            index = 9,
            sceneKey = "COMPLETE_BLINK",
            title = "学会完整轻闭眨眼",
            factTag = "上下眼睑轻触",
            narration = "盯屏幕时很多人只是半眨眼，上下眼皮没有碰上。有意识地完整轻闭再睁开，才能真正把润眼油脂挤出来。",
        ),
        DryEyeStoryScene(
            index = 10,
            sceneKey = "CONTACT_LENS",
            title = "给角膜留出透气时间",
            factTag = "减少长时间戴隐形",
            narration = "长时间佩戴隐形眼镜会打断泪膜连续性、减少角膜透气。回到家尽早换回框架眼镜，让眼睛大口深呼吸。",
        ),
        DryEyeStoryScene(
            index = 11,
            sceneKey = "HYDRATION_NUTRITION",
            title = "多喝水与均衡护眼营养",
            factTag = "水分与 Omega-3",
            narration = "保持充足饮水，日常适量吃些深海鱼、坚果和深色蔬菜，能从身体内部为健康泪膜提供源源不断的原料。",
        ),
        DryEyeStoryScene(
            index = 12,
            sceneKey = "TEAR_LAYERS",
            title = "复盘：护住油脂锁水层",
            factTag = "锁水防蒸发关键",
            narration = "干眼常常不只是缺水，更是最外层的油脂不够稳定。保护好睑板腺，水液才不会很快蒸发掉。",
        ),
        DryEyeStoryScene(
            index = 13,
            sceneKey = "SCREEN_VS_TALK",
            title = "复盘：看屏别忘多眨眼",
            factTag = "每隔片刻主动轻眨",
            narration = "工作和刷视频时，可以在屏幕旁贴个小提示，提醒自己偶尔完整轻眨几次眼睛，给眼球补一层水膜。",
        ),
        DryEyeStoryScene(
            index = 14,
            sceneKey = "RULE_20_20_20",
            title = "复盘：抬头看看远方",
            factTag = "放松睫状肌",
            narration = "连续近距离用眼超过半小时，眼睛就容易又干又胀。哪怕站起来倒杯水、望望窗外，也是极好的护眼休息。",
        ),
        DryEyeStoryScene(
            index = 15,
            sceneKey = "WARM_COMPRESS",
            title = "复盘：温热敷别太烫",
            factTag = "约 40°C · 10 分钟",
            narration = "热敷眼睛时先用手背试温，温热不烫手最合适，每次敷五到十分钟，感觉发烫或不舒服要立刻取下。",
        ),
        DryEyeStoryScene(
            index = 16,
            sceneKey = "AC_WIND",
            title = "复盘：打造润眼小环境",
            factTag = "避风 · 柔光 · 湿润",
            narration = "避开直吹冷风、保持柔和光线与合适湿度，再配合规律作息，就能帮眼睛稳稳度过一整天。",
        ),
        DryEyeStoryScene(
            index = 17,
            sceneKey = "WATER_WORLD",
            title = "准备进入稳住水膜挑战",
            factTag = "即将切换下一阶段",
            narration = "自然眨眼观影即将结束，接下来我们会一起挑战稳住水膜二十秒，祝你轻松完成。",
        ),
    )

    fun sceneAt(elapsedSeconds: Int): DryEyeStoryScene {
        val idx = (elapsedSeconds.coerceAtLeast(0) / SCENE_DURATION_SEC) % scenes.size
        return scenes[idx]
    }

    fun totalScenesFor(totalSeconds: Int): Int =
        ((totalSeconds.coerceAtLeast(SCENE_DURATION_SEC) + SCENE_DURATION_SEC - 1) / SCENE_DURATION_SEC)
            .coerceAtMost(scenes.size)

    fun openNarration(elapsedSeconds: Int): String = when {
        elapsedSeconds < 10 -> "现在尽量保持睁眼，稳住晶莹水膜护盾。"
        else -> "坚持过半啦，水膜护盾正在升级，再稳住几秒。"
    }
}
