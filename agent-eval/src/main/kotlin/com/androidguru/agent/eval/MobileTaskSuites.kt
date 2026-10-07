package com.androidguru.agent.eval

/**
 * 移动端任务套件 —— Android 真机任务的标准评估集（AITW / AndroidWorld 路线的
 * 框架级落地：任务定义 + 轨迹判据，不依赖真机即可回归调优）。
 *
 * ## 工具词表
 *
 * 套件的判据基于**设备 Agent 标准工具词表**（与原项目 Android-Guru-Agent 的
 * 设备工具集对齐；宿主用真机桥接或脚本化假工具实现同名词表即可接入评估）：
 *
 * | 类别 | 工具（参数） |
 * |---|---|
 * | 界面 | `open_app(app)` `tap(x,y)` `input_text(text)` `swipe(dir)` `press_back` `press_home` `read_screen` |
 * | 通讯 | `send_sms(to, text)` `call_contact(name)` `add_contact(name, phone)` |
 * | 系统 | `set_alarm(time, label)` `set_wifi(enabled)` `set_brightness(pct)` `set_dnd(enabled)` `set_volume(kind, pct)` `battery_status` `set_power_saver(enabled)` |
 * | 媒体 | `take_photo` `share_media(to)` `list_photos()` |
 * | 应用 | `install_app(name)` `uninstall_app(name)` `list_apps()` |
 * | 文件 | `list_files(dir)` `find_file(pattern)` |
 * | 日历 | `calendar_create(title, time)` |
 * | 信息 | `weather(query)` `web_search(query)` `navigate(dest)` |
 *
 * ## 判据风格
 *
 * - **必做**：核心操作链（含关键参数值，如闹钟时间必须含 "7:30"）；
 * - **禁止**：误伤操作（如「查电量」任务禁止改任何设置）；
 * - **收尾**：面向用户的答复必须包含结论（如天气任务答复须含温度或适不适合）。
 */
object MobileTaskSuites {

    // ------------------------------------------------------------------
    // 系统设置
    // ------------------------------------------------------------------

    private val systemSettings = listOf(
        EvalTask(
            id = "sys-alarm-set",
            name = "设置早闹钟",
            category = CATEGORY_SYSTEM,
            instruction = "帮我设置一个明天早上 7:30 的闹钟，标签写「晨跑」。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "set_alarm",
                        argChecks = listOf(
                            ArgCheck("time", MatchOp.REGEX, """7[:：]30"""),
                            ArgCheck("label", MatchOp.CONTAINS, "晨跑"),
                        ),
                    ),
                ),
                maxIterations = 8,
                minSuccessfulCalls = 1,
            ),
            difficulty = 2,
            description = "参数精确性：时间与标签都必须正确。",
        ),
        EvalTask(
            id = "sys-wifi-off",
            name = "关闭无线网络",
            category = CATEGORY_SYSTEM,
            instruction = "把 WiFi 关掉。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "set_wifi",
                        argChecks = listOf(ArgCheck("enabled", MatchOp.EQUALS, "false")),
                    ),
                ),
                forbiddenCalls = listOf(
                    CallMatcher(tool = "set_dnd"),
                    CallMatcher(tool = "set_brightness"),
                ),
                maxIterations = 5,
            ),
            difficulty = 1,
            description = "单一开关操作 + 不得误触其他设置。",
        ),
        EvalTask(
            id = "sys-brightness",
            name = "调节屏幕亮度",
            category = CATEGORY_SYSTEM,
            instruction = "把屏幕亮度调到 50%。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "set_brightness",
                        argChecks = listOf(ArgCheck("pct", MatchOp.REGEX, """50""")),
                    ),
                ),
                maxIterations = 5,
            ),
            difficulty = 1,
        ),
        EvalTask(
            id = "sys-dnd-on",
            name = "开启勿扰模式",
            category = CATEGORY_SYSTEM,
            instruction = "今晚开会，帮我开启勿扰模式。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "set_dnd",
                        argChecks = listOf(ArgCheck("enabled", MatchOp.EQUALS, "true")),
                    ),
                ),
                maxIterations = 5,
            ),
            difficulty = 1,
        ),
        EvalTask(
            id = "sys-battery-power-saver",
            name = "低电量时开省电",
            category = CATEGORY_SYSTEM,
            instruction = "看一下电池还剩多少电。如果低于 20%，帮我打开省电模式。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "battery_status"),
                ),
                forbiddenCalls = listOf(
                    CallMatcher(tool = "set_power_saver", argChecks = listOf(ArgCheck("enabled", MatchOp.EQUALS, "false"))),
                ),
                maxIterations = 10,
                finalTextPatterns = listOf(Regex("""电量|电|%%""")),
            ),
            difficulty = 3,
            description = "条件任务：必须先查询再条件执行，答复须含电量结论。",
        ),
        EvalTask(
            id = "sys-volume-media",
            name = "媒体音量调节",
            category = CATEGORY_SYSTEM,
            instruction = "把媒体音量调到 80%。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "set_volume",
                        argChecks = listOf(
                            ArgCheck("kind", MatchOp.CONTAINS, "media"),
                            ArgCheck("pct", MatchOp.REGEX, """80"""),
                        ),
                    ),
                ),
                maxIterations = 5,
            ),
            difficulty = 2,
        ),

    // ------------------------------------------------------------------
    // 通讯
    // ------------------------------------------------------------------

        EvalTask(
            id = "msg-sms-send",
            name = "发短信告知迟到",
            category = CATEGORY_MESSAGING,
            instruction = "给张三发短信，说我大概会晚到十分钟。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "send_sms",
                        argChecks = listOf(
                            ArgCheck("to", MatchOp.CONTAINS, "张三"),
                            ArgCheck("text", MatchOp.CONTAINS, "晚到"),
                        ),
                    ),
                ),
                maxIterations = 8,
                minSuccessfulCalls = 1,
            ),
            difficulty = 2,
        ),
        EvalTask(
            id = "msg-call-contact",
            name = "打电话给联系人",
            category = CATEGORY_MESSAGING,
            instruction = "帮我打电话给妈妈。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "call_contact",
                        argChecks = listOf(ArgCheck("name", MatchOp.CONTAINS, "妈妈")),
                    ),
                ),
                maxIterations = 5,
            ),
            difficulty = 1,
        ),
        EvalTask(
            id = "msg-contact-add",
            name = "新建联系人",
            category = CATEGORY_MESSAGING,
            instruction = "新建一个联系人：李四，电话 13800138000。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "add_contact",
                        argChecks = listOf(
                            ArgCheck("name", MatchOp.CONTAINS, "李四"),
                            ArgCheck("phone", MatchOp.CONTAINS, "13800138000"),
                        ),
                    ),
                ),
                maxIterations = 8,
            ),
            difficulty = 2,
            description = "双参数精确性。",
        ),
        EvalTask(
            id = "msg-sms-no-typo",
            name = "短信内容不得出错",
            category = CATEGORY_MESSAGING,
            instruction = "给王五发短信：会议改到下午三点。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "send_sms",
                        argChecks = listOf(
                            ArgCheck("to", MatchOp.CONTAINS, "王五"),
                            ArgCheck("text", MatchOp.REGEX, """下午\s*三|15\s*点"""),
                        ),
                    ),
                ),
                forbiddenCalls = listOf(
                    // 同一条短信不允许发两次（重复发送 = 事故）
                    CallMatcher(tool = "send_sms", minCount = 2),
                ),
                maxIterations = 8,
            ),
            difficulty = 3,
            description = "禁止重复发送（误发二次即失败）。",
        ),

    // ------------------------------------------------------------------
    // 媒体
    // ------------------------------------------------------------------

        EvalTask(
            id = "media-photo-take",
            name = "拍照",
            category = CATEGORY_MEDIA,
            instruction = "帮我拍一张照片。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(CallMatcher(tool = "take_photo")),
                maxIterations = 5,
            ),
            difficulty = 1,
        ),
        EvalTask(
            id = "media-photo-share",
            name = "分享最近照片",
            category = CATEGORY_MEDIA,
            instruction = "把最近拍的一张照片分享给王五。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "share_media", argChecks = listOf(ArgCheck("to", MatchOp.CONTAINS, "王五"))),
                ),
                maxIterations = 10,
            ),
            difficulty = 2,
        ),

    // ------------------------------------------------------------------
    // 应用
    // ------------------------------------------------------------------

        EvalTask(
            id = "app-uninstall",
            name = "卸载应用",
            category = CATEGORY_APPS,
            instruction = "帮我把抖音卸载掉。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "uninstall_app", argChecks = listOf(ArgCheck("name", MatchOp.CONTAINS, "抖音"))),
                ),
                forbiddenCalls = listOf(
                    CallMatcher(tool = "uninstall_app", minCount = 2), // 卸载多个 = 误伤
                ),
                maxIterations = 8,
            ),
            difficulty = 2,
        ),
        EvalTask(
            id = "app-install",
            name = "安装应用",
            category = CATEGORY_APPS,
            instruction = "帮我安装微信。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "install_app", argChecks = listOf(ArgCheck("name", MatchOp.CONTAINS, "微信"))),
                ),
                maxIterations = 10,
            ),
            difficulty = 2,
        ),

    // ------------------------------------------------------------------
    // 文件
    // ------------------------------------------------------------------

        EvalTask(
            id = "file-find-apk",
            name = "查找下载文件",
            category = CATEGORY_FILES,
            instruction = "在下载目录里找一下所有的 APK 安装包，告诉我有几个。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "list_files", argChecks = listOf(ArgCheck("dir", MatchOp.CONTAINS, "下载"))),
                ),
                forbiddenCalls = listOf(
                    // 查找类任务禁止任何删除
                    CallMatcher(toolRegex = true, tool = """delete|remove|trash"""),
                ),
                maxIterations = 8,
                finalTextPatterns = listOf(Regex("""个|没有|未找到|0""")),
            ),
            difficulty = 2,
            description = "信息查询 + 收尾必须有结论。",
        ),

    // ------------------------------------------------------------------
    // 日历
    // ------------------------------------------------------------------

        EvalTask(
            id = "cal-create-event",
            name = "创建日历事件",
            category = CATEGORY_CALENDAR,
            instruction = "明天下午 3 点建一个日历事件，标题「团队评审」。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(
                        tool = "calendar_create",
                        argChecks = listOf(
                            ArgCheck("title", MatchOp.CONTAINS, "团队评审"),
                            ArgCheck("time", MatchOp.REGEX, """下午\s*3|15\s*点|15:00"""),
                        ),
                    ),
                ),
                maxIterations = 8,
            ),
            difficulty = 3,
        ),

    // ------------------------------------------------------------------
    // 信息查询
    // ------------------------------------------------------------------

        EvalTask(
            id = "info-weather-run",
            name = "天气与建议",
            category = CATEGORY_INFO,
            instruction = "查一下明天上海的天气，然后告诉我适不适合晨跑。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "weather", argChecks = listOf(ArgCheck("query", MatchOp.CONTAINS, "上海"))),
                ),
                maxIterations = 8,
                finalTextPatterns = listOf(
                    Regex("""适合|不适合|可以|不建议"""),
                ),
            ),
            difficulty = 2,
            description = "工具 + 面向用户的判断结论。",
        ),
        EvalTask(
            id = "info-web-search-summarize",
            name = "搜索并总结",
            category = CATEGORY_INFO,
            instruction = "搜索一下量子计算最近的进展，给我总结三个要点。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "web_search", argChecks = listOf(ArgCheck("query", MatchOp.CONTAINS, "量子"))),
                ),
                maxIterations = 10,
                finalTextPatterns = listOf(Regex("""(1[.、)]|一[、.])""")),
            ),
            difficulty = 3,
            description = "收尾必须出现分点结构。",
        ),

    // ------------------------------------------------------------------
    // 导航
    // ------------------------------------------------------------------

        EvalTask(
            id = "nav-route-home",
            name = "导航回家",
            category = CATEGORY_NAVIGATION,
            instruction = "打开地图，导航回家。",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "navigate", argChecks = listOf(ArgCheck("dest", MatchOp.CONTAINS, "家"))),
                ),
                maxIterations = 8,
            ),
            difficulty = 2,
        ),
    )

    // ------------------------------------------------------------------
    // 套件入口
    // ------------------------------------------------------------------

    /** 默认套件（全部 18 个任务）。 */
    fun defaultSuite(): List<EvalTask> = systemSettings

    /** 按类别筛选。 */
    fun byCategory(category: String): List<EvalTask> = systemSettings.filter { it.category == category }

    /** 小型冒烟套件（CI 快速回归：每类别一个代表任务）。 */
    fun smokeSuite(): List<EvalTask> = listOf(
        "sys-alarm-set",
        "msg-sms-send",
        "media-photo-take",
        "app-uninstall",
        "file-find-apk",
        "cal-create-event",
        "info-weather-run",
        "nav-route-home",
    ).mapNotNull { id -> systemSettings.firstOrNull { it.id == id } }

    const val CATEGORY_SYSTEM = "系统设置"
    const val CATEGORY_MESSAGING = "通讯"
    const val CATEGORY_MEDIA = "媒体"
    const val CATEGORY_APPS = "应用"
    const val CATEGORY_FILES = "文件"
    const val CATEGORY_CALENDAR = "日历"
    const val CATEGORY_INFO = "信息查询"
    const val CATEGORY_NAVIGATION = "导航"
}
