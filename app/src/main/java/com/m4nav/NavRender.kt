package com.m4nav

/**
 * 解析 + 渲染，规则与 nav_final.py 逐条对齐。
 *
 * 状态机（转向类方向且已知距离时）：
 *   d > NEAR          -> "↑500"    现在直行，数字=到路口的距离
 *   FINAL < d <= NEAR -> "↑200→"   先直行、200 米后右转（距离夹中间，03 排法）
 *   d <= FINAL        -> "→"       就是现在转
 */
object NavRender {

    const val NEAR_M = 200
    const val FINAL_M = 40

    private val RE_DIST = Regex("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|KM|米|m|M)")

    private val RULES: List<Pair<String, List<String>>> = listOf(
        "U" to listOf("掉头", "调头", "u-turn", "u turn"),
        "O" to listOf("环岛", "roundabout"),
        "L" to listOf("左转", "左拐", "向左", "靠左", "左前方", "左侧", "left"),
        "R" to listOf("右转", "右拐", "向右", "靠右", "右前方", "右侧", "right"),
        "S" to listOf("直行", "直走", "向前", "继续直", "沿当前", "straight", "continue"),
        "E" to listOf("到达", "已到达", "终点", "目的地", "arrive", "destination")
    )

    private val ARROW = mapOf(
        "L" to "←", "R" to "→", "S" to "↑", "U" to "U-T",
        "O" to "(((", "E" to "★", "?" to "?"
    )

    private val ASCII = mapOf(
        "L" to "<<<", "R" to ">>>", "S" to "^^^", "U" to "U-T",
        "O" to "(((", "E" to "***", "?" to "???"
    )

    data class Parsed(val dir: String, val meters: Int?)

    fun parse(raw: String?): Parsed {
        val t = (raw ?: "").replace(Regex("\\s+"), "").lowercase()
        var dir = "?"
        for ((code, kws) in RULES) {
            if (kws.any { t.contains(it.lowercase()) }) { dir = code; break }
        }
        var meters: Int? = null
        val m = RE_DIST.find(raw ?: "")
        if (m != null) {
            val v = m.groupValues[1].toDoubleOrNull()
            val unit = m.groupValues[2]
            if (v != null) {
                meters = if (unit in listOf("公里", "千米", "km", "KM")) (v * 1000).toInt() else v.toInt()
            }
        }
        return Parsed(dir, meters)
    }

    private fun dist(meters: Int?): String {
        if (meters == null) return ""
        return if (meters >= 1000) {
            val k = meters / 1000.0
            val s = "%.1f".format(k).trimEnd('0').trimEnd('.')
            s + "K"
        } else meters.toString()
    }

    /** mode: "arrow"（默认，真箭头）/ "text"（ASCII 兜底） */
    fun render(dir: String, meters: Int?, mode: String = "arrow"): String {
        if (mode != "arrow") {
            return (ASCII[dir] ?: ASCII["?"]!!) + dist(meters)
        }
        val g = ARROW[dir] ?: ARROW["?"]!!
        val d = dist(meters)
        return if (dir in listOf("L", "R", "U", "O") && meters != null) {
            when {
                meters > NEAR_M -> "↑$d"
                meters <= FINAL_M -> g
                else -> "↑$d$g"          // 03 排法：距离夹在两个箭头中间
            }
        } else g + d
    }
}
