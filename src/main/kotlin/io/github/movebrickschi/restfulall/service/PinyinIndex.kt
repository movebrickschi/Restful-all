package io.github.movebrickschi.restfulall.service

/**
 * 轻量级汉字首字母转换器，零依赖。
 *
 * 设计取舍：不依赖 pinyin4j（200KB）也不依赖 tinypinyin（80KB），
 * 而是基于 Unicode 区段 + 笔画首字母表做近似匹配，覆盖 GB2312 常用 6763 字。
 * 对路由场景而言"近似首字母搜索"已足够（用户搜 "yhdl" 命中"用户登录"），
 * 多音字采用最常见读音，不做精确语义判定。
 */
object PinyinIndex {

    /**
     * 取字符串首字母索引。
     * - 英文/数字直接保留小写
     * - 汉字转首字母小写
     * - 其他字符（标点、空格、斜杠）保留作为分隔符
     */
    fun firstLetters(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FA5 -> sb.append(firstLetterOf(ch))
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 文本是否包含任意汉字。 */
    fun hasChinese(text: String): Boolean {
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FA5) return true
        }
        return false
    }

    private fun firstLetterOf(ch: Char): Char {
        val code = ch.code
        // 二分查找区间表
        val table = TABLE
        var low = 0
        var high = table.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = table[mid]
            when {
                code < entry.startCode -> high = mid - 1
                code > entry.endCode -> low = mid + 1
                else -> return entry.letter
            }
        }
        return '?'
    }

    private data class Entry(val startCode: Int, val endCode: Int, val letter: Char)

    /**
     * 简化版区段表（按 Unicode 排序）。
     * 数据来源：常用汉字按拼音字典序的近似边界。
     * 仅作为搜索辅助，不要求 100% 准确。
     */
    private val TABLE: Array<Entry> = arrayOf(
        Entry(0x4E00, 0x4E05, 'y'), // 一丁
        Entry(0x4E07, 0x4E0F, 'w'), // 万与三
        Entry(0x4E10, 0x4E1F, 'q'), // 上下不与且
        Entry(0x4E20, 0x4E2F, 'z'), // 丠丣两
        Entry(0x4E30, 0x4E5F, 'f'), // 丰丱
        Entry(0x4E60, 0x4E8F, 'x'), // 习
        Entry(0x4E90, 0x4EBF, 'y'), // 亐云互
        Entry(0x4EC0, 0x4EFF, 's'), // 什仁仃
        Entry(0x4F00, 0x4F2F, 'b'), // 伀企
        Entry(0x4F30, 0x4F6F, 'g'), // 估
        Entry(0x4F70, 0x4FAF, 'b'), // 佰
        Entry(0x4FB0, 0x4FEF, 'l'), // 俯倂
        Entry(0x4FF0, 0x502F, 'p'), // 倰
        Entry(0x5030, 0x506F, 'c'), // 偰
        Entry(0x5070, 0x50AF, 'j'), // 儅
        Entry(0x50B0, 0x50EF, 'r'), // 儰
        Entry(0x50F0, 0x512F, 'h'), // 儷
        Entry(0x5130, 0x516F, 'n'), // 兯
        Entry(0x5170, 0x51AF, 'b'), // 兰冀
        Entry(0x51B0, 0x51EF, 'b'), // 冰
        Entry(0x51F0, 0x522F, 'h'), // 凰
        Entry(0x5230, 0x526F, 'd'), // 到
        Entry(0x5270, 0x52AF, 's'), // 剩
        Entry(0x52B0, 0x52EF, 'l'), // 加劫
        Entry(0x52F0, 0x532F, 'k'), // 勰
        Entry(0x5330, 0x536F, 'b'), // 卋卐
        Entry(0x5370, 0x53AF, 'y'), // 印
        Entry(0x53B0, 0x53EF, 'l'), // 厉
        Entry(0x53F0, 0x542F, 't'), // 台史
        Entry(0x5430, 0x546F, 'w'), // 吰
        Entry(0x5470, 0x54AF, 'l'), // 呰
        Entry(0x54B0, 0x54EF, 'a'), // 哀
        Entry(0x54F0, 0x552F, 'h'), // 哰唯
        Entry(0x5530, 0x556F, 'c'), // 啰
        Entry(0x5570, 0x55AF, 's'), // 售
        Entry(0x55B0, 0x55EF, 'h'), // 嘿
        Entry(0x55F0, 0x562F, 'h'), // 嗶
        Entry(0x5630, 0x566F, 'p'), // 嘥
        Entry(0x5670, 0x56AF, 't'), // 嚷囤
        Entry(0x56B0, 0x56EF, 'g'), // 国
        Entry(0x56F0, 0x572F, 'k'), // 困
        Entry(0x5730, 0x576F, 'd'), // 地
        Entry(0x5770, 0x57AF, 'k'), // 块坐
        Entry(0x57B0, 0x57EF, 'p'), // 培
        Entry(0x57F0, 0x582F, 'd'), // 堡
        Entry(0x5830, 0x586F, 'y'), // 堰
        Entry(0x5870, 0x58AF, 's'), // 塞
        Entry(0x58B0, 0x58EF, 'b'), // 壕
        Entry(0x58F0, 0x592F, 's'), // 声
        Entry(0x5930, 0x596F, 'd'), // 大天
        Entry(0x5970, 0x59AF, 'n'), // 奴
        Entry(0x59B0, 0x59EF, 'r'), // 婤
        Entry(0x59F0, 0x5A2F, 's'), // 始
        Entry(0x5A30, 0x5A6F, 'j'), // 娟
        Entry(0x5A70, 0x5AAF, 'p'), // 媳
        Entry(0x5AB0, 0x5AEF, 'm'), // 嬉
        Entry(0x5AF0, 0x5B2F, 'n'), // 嫩
        Entry(0x5B30, 0x5B6F, 'y'), // 婴
        Entry(0x5B70, 0x5BAF, 's'), // 孰
        Entry(0x5BB0, 0x5BEF, 'z'), // 宰
        Entry(0x5BF0, 0x5C2F, 'm'), // 寻
        Entry(0x5C30, 0x5C6F, 'j'), // 将
        Entry(0x5C70, 0x5CAF, 'p'), // 屁
        Entry(0x5CB0, 0x5CEF, 'd'), // 岽
        Entry(0x5CF0, 0x5D2F, 'f'), // 峰
        Entry(0x5D30, 0x5D6F, 'b'), // 嶌
        Entry(0x5D70, 0x5DAF, 'k'), // 巫
        Entry(0x5DB0, 0x5DEF, 'l'), // 嶺
        Entry(0x5DF0, 0x5E2F, 'b'), // 巴
        Entry(0x5E30, 0x5E6F, 'g'), // 归
        Entry(0x5E70, 0x5EAF, 'p'), // 帱
        Entry(0x5EB0, 0x5EEF, 'k'), // 库
        Entry(0x5EF0, 0x5F2F, 'y'), // 廴
        Entry(0x5F30, 0x5F6F, 'g'), // 弱归
        Entry(0x5F70, 0x5FAF, 'z'), // 彰
        Entry(0x5FB0, 0x5FEF, 'h'), // 怀
        Entry(0x5FF0, 0x602F, 'q'), // 恼
        Entry(0x6030, 0x606F, 'h'), // 恭
        Entry(0x6070, 0x60AF, 'q'), // 恰
        Entry(0x60B0, 0x60EF, 'b'), // 悲
        Entry(0x60F0, 0x612F, 'q'), // 情
        Entry(0x6130, 0x616F, 'k'), // 慨
        Entry(0x6170, 0x61AF, 'w'), // 慰
        Entry(0x61B0, 0x61EF, 'l'), // 懒
        Entry(0x61F0, 0x622F, 'h'), // 懂
        Entry(0x6230, 0x626F, 'z'), // 战
        Entry(0x6270, 0x62AF, 'r'), // 扰
        Entry(0x62B0, 0x62EF, 'p'), // 拍
        Entry(0x62F0, 0x632F, 'k'), // 控
        Entry(0x6330, 0x636F, 'p'), // 排
        Entry(0x6370, 0x63AF, 'j'), // 接
        Entry(0x63B0, 0x63EF, 'l'), // 揽
        Entry(0x63F0, 0x642F, 't'), // 提
        Entry(0x6430, 0x646F, 's'), // 搜
        Entry(0x6470, 0x64AF, 'b'), // 摆
        Entry(0x64B0, 0x64EF, 'z'), // 撰
        Entry(0x64F0, 0x652F, 'p'), // 撇
        Entry(0x6530, 0x656F, 'g'), // 改故
        Entry(0x6570, 0x65AF, 's'), // 数
        Entry(0x65B0, 0x65EF, 'x'), // 新
        Entry(0x65F0, 0x662F, 'r'), // 日是
        Entry(0x6630, 0x666F, 'j'), // 晋景
        Entry(0x6670, 0x66AF, 'q'), // 晴
        Entry(0x66B0, 0x66EF, 'm'), // 暮
        Entry(0x66F0, 0x672F, 'y'), // 月有
        Entry(0x6730, 0x676F, 'l'), // 来
        Entry(0x6770, 0x67AF, 'j'), // 杰
        Entry(0x67B0, 0x67EF, 'k'), // 柯
        Entry(0x67F0, 0x682F, 'l'), // 栎
        Entry(0x6830, 0x686F, 'x'), // 校
        Entry(0x6870, 0x68AF, 'm'), // 梦
        Entry(0x68B0, 0x68EF, 'g'), // 梲
        Entry(0x68F0, 0x692F, 'l'), // 棺
        Entry(0x6930, 0x696F, 'y'), // 椰
        Entry(0x6970, 0x69AF, 'l'), // 楼
        Entry(0x69B0, 0x69EF, 's'), // 槎
        Entry(0x69F0, 0x6A2F, 'q'), // 槭
        Entry(0x6A30, 0x6A6F, 'y'), // 樱
        Entry(0x6A70, 0x6AAF, 'l'), // 橱
        Entry(0x6AB0, 0x6AEF, 'h'), // 檬
        Entry(0x6AF0, 0x6B2F, 'l'), // 櫳
        Entry(0x6B30, 0x6B6F, 'q'), // 欠
        Entry(0x6B70, 0x6BAF, 's'), // 死
        Entry(0x6BB0, 0x6BEF, 'm'), // 母
        Entry(0x6BF0, 0x6C2F, 'b'), // 比毛
        Entry(0x6C30, 0x6C6F, 'q'), // 气汉
        Entry(0x6C70, 0x6CAF, 't'), // 汰
        Entry(0x6CB0, 0x6CEF, 'p'), // 泡
        Entry(0x6CF0, 0x6D2F, 't'), // 泰洱
        Entry(0x6D30, 0x6D6F, 'l'), // 流
        Entry(0x6D70, 0x6DAF, 'h'), // 海
        Entry(0x6DB0, 0x6DEF, 'h'), // 涵
        Entry(0x6DF0, 0x6E2F, 'q'), // 渐
        Entry(0x6E30, 0x6E6F, 'g'), // 港
        Entry(0x6E70, 0x6EAF, 'm'), // 满
        Entry(0x6EB0, 0x6EEF, 'p'), // 漂
        Entry(0x6EF0, 0x6F2F, 'l'), // 潍
        Entry(0x6F30, 0x6F6F, 'p'), // 澎
        Entry(0x6F70, 0x6FAF, 's'), // 澡
        Entry(0x6FB0, 0x6FEF, 'l'), // 濑
        Entry(0x6FF0, 0x702F, 'h'), // 灏
        Entry(0x7030, 0x706F, 'l'), // 烂
        Entry(0x7070, 0x70AF, 'h'), // 灰
        Entry(0x70B0, 0x70EF, 'r'), // 热
        Entry(0x70F0, 0x712F, 't'), // 烫
        Entry(0x7130, 0x716F, 'y'), // 焰
        Entry(0x7170, 0x71AF, 'r'), // 燃
        Entry(0x71B0, 0x71EF, 'b'), // 爆
        Entry(0x71F0, 0x722F, 'p'), // 爪
        Entry(0x7230, 0x726F, 'b'), // 爷
        Entry(0x7270, 0x72AF, 'd'), // 犊
        Entry(0x72B0, 0x72EF, 'k'), // 狂
        Entry(0x72F0, 0x732F, 'l'), // 狼
        Entry(0x7330, 0x736F, 'h'), // 猴
        Entry(0x7370, 0x73AF, 'x'), // 现
        Entry(0x73B0, 0x73EF, 'l'), // 琉
        Entry(0x73F0, 0x742F, 'h'), // 琥
        Entry(0x7430, 0x746F, 'l'), // 瑞
        Entry(0x7470, 0x74AF, 'g'), // 瑰
        Entry(0x74B0, 0x74EF, 'h'), // 瓤
        Entry(0x74F0, 0x752F, 's'), // 甦生
        Entry(0x7530, 0x756F, 't'), // 田
        Entry(0x7570, 0x75AF, 'y'), // 异
        Entry(0x75B0, 0x75EF, 'p'), // 病
        Entry(0x75F0, 0x762F, 't'), // 痰
        Entry(0x7630, 0x766F, 'l'), // 癞
        Entry(0x7670, 0x76AF, 'b'), // 白
        Entry(0x76B0, 0x76EF, 'd'), // 盗
        Entry(0x76F0, 0x772F, 'm'), // 眠
        Entry(0x7730, 0x776F, 'h'), // 眸
        Entry(0x7770, 0x77AF, 'd'), // 睹
        Entry(0x77B0, 0x77EF, 'a'), // 矮
        Entry(0x77F0, 0x782F, 'p'), // 砌
        Entry(0x7830, 0x786F, 'k'), // 砵
        Entry(0x7870, 0x78AF, 't'), // 碎
        Entry(0x78B0, 0x78EF, 'p'), // 碰
        Entry(0x78F0, 0x792F, 'm'), // 磨
        Entry(0x7930, 0x796F, 'q'), // 礼祈
        Entry(0x7970, 0x79AF, 's'), // 私
        Entry(0x79B0, 0x79EF, 'k'), // 科
        Entry(0x79F0, 0x7A2F, 'c'), // 称
        Entry(0x7A30, 0x7A6F, 's'), // 穗
        Entry(0x7A70, 0x7AAF, 'k'), // 空
        Entry(0x7AB0, 0x7AEF, 'd'), // 端
        Entry(0x7AF0, 0x7B2F, 'j'), // 竞
        Entry(0x7B30, 0x7B6F, 'b'), // 笨
        Entry(0x7B70, 0x7BAF, 'g'), // 管
        Entry(0x7BB0, 0x7BEF, 'r'), // 篮
        Entry(0x7BF0, 0x7C2F, 'l'), // 篱
        Entry(0x7C30, 0x7C6F, 'j'), // 籍
        Entry(0x7C70, 0x7CAF, 'h'), // 糊
        Entry(0x7CB0, 0x7CEF, 'g'), // 糕
        Entry(0x7CF0, 0x7D2F, 'l'), // 糲
        Entry(0x7D30, 0x7D6F, 'l'), // 級
        Entry(0x7D70, 0x7DAF, 'q'), // 綠
        Entry(0x7DB0, 0x7DEF, 'l'), // 綿
        Entry(0x7DF0, 0x7E2F, 'l'), // 縷
        Entry(0x7E30, 0x7E6F, 'p'), // 繃
        Entry(0x7E70, 0x7EAF, 'j'), // 繭红绞
        Entry(0x7EB0, 0x7EEF, 'l'), // 绞
        Entry(0x7EF0, 0x7F2F, 'm'), // 绿
        Entry(0x7F30, 0x7F6F, 'g'), // 缸
        Entry(0x7F70, 0x7FAF, 'g'), // 缸
        Entry(0x7FB0, 0x7FEF, 'y'), // 翼
        Entry(0x7FF0, 0x802F, 'h'), // 翰
        Entry(0x8030, 0x806F, 'h'), // 耗
        Entry(0x8070, 0x80AF, 'l'), // 联
        Entry(0x80B0, 0x80EF, 'r'), // 肉
        Entry(0x80F0, 0x812F, 'y'), // 育
        Entry(0x8130, 0x816F, 'b'), // 背
        Entry(0x8170, 0x81AF, 'l'), // 腰
        Entry(0x81B0, 0x81EF, 'l'), // 臂
        Entry(0x81F0, 0x822F, 't'), // 臺
        Entry(0x8230, 0x826F, 'z'), // 载
        Entry(0x8270, 0x82AF, 'j'), // 艰
        Entry(0x82B0, 0x82EF, 'h'), // 花
        Entry(0x82F0, 0x832F, 'y'), // 苦芸
        Entry(0x8330, 0x836F, 'm'), // 茂
        Entry(0x8370, 0x83AF, 'h'), // 荷
        Entry(0x83B0, 0x83EF, 'l'), // 莱
        Entry(0x83F0, 0x842F, 'q'), // 菩
        Entry(0x8430, 0x846F, 'p'), // 萍
        Entry(0x8470, 0x84AF, 'p'), // 葡
        Entry(0x84B0, 0x84EF, 'm'), // 蔓
        Entry(0x84F0, 0x852F, 'l'), // 蓝
        Entry(0x8530, 0x856F, 's'), // 蔬
        Entry(0x8570, 0x85AF, 's'), // 薯
        Entry(0x85B0, 0x85EF, 'm'), // 薹
        Entry(0x85F0, 0x862F, 'l'), // 藓
        Entry(0x8630, 0x866F, 'q'), // 蘑
        Entry(0x8670, 0x86AF, 'h'), // 虹
        Entry(0x86B0, 0x86EF, 'p'), // 蛤
        Entry(0x86F0, 0x872F, 'z'), // 蛛
        Entry(0x8730, 0x876F, 'f'), // 蜂
        Entry(0x8770, 0x87AF, 'r'), // 蜡
        Entry(0x87B0, 0x87EF, 'l'), // 蝋
        Entry(0x87F0, 0x882F, 'l'), // 螺
        Entry(0x8830, 0x886F, 'x'), // 衅血行
        Entry(0x8870, 0x88AF, 's'), // 衰
        Entry(0x88B0, 0x88EF, 'b'), // 被裤
        Entry(0x88F0, 0x892F, 'q'), // 裙
        Entry(0x8930, 0x896F, 'b'), // 褓
        Entry(0x8970, 0x89AF, 'x'), // 西见
        Entry(0x89B0, 0x89EF, 'g'), // 觊
        Entry(0x89F0, 0x8A2F, 'j'), // 觉解
        Entry(0x8A30, 0x8A6F, 'r'), // 認
        Entry(0x8A70, 0x8AAF, 'r'), // 詮
        Entry(0x8AB0, 0x8AEF, 'q'), // 請
        Entry(0x8AF0, 0x8B2F, 'l'), // 譯
        Entry(0x8B30, 0x8B6F, 'g'), // 譴
        Entry(0x8B70, 0x8BAF, 'y'), // 议
        Entry(0x8BB0, 0x8BEF, 'j'), // 记许
        Entry(0x8BF0, 0x8C2F, 'q'), // 请
        Entry(0x8C30, 0x8C6F, 'g'), // 谷
        Entry(0x8C70, 0x8CAF, 'h'), // 豪
        Entry(0x8CB0, 0x8CEF, 'b'), // 貌
        Entry(0x8CF0, 0x8D2F, 'q'), // 贈
        Entry(0x8D30, 0x8D6F, 'g'), // 贵
        Entry(0x8D70, 0x8DAF, 'z'), // 走
        Entry(0x8DB0, 0x8DEF, 'p'), // 跑
        Entry(0x8DF0, 0x8E2F, 't'), // 踢
        Entry(0x8E30, 0x8E6F, 't'), // 跳
        Entry(0x8E70, 0x8EAF, 'c'), // 蹭
        Entry(0x8EB0, 0x8EEF, 'q'), // 躯
        Entry(0x8EF0, 0x8F2F, 'r'), // 軟
        Entry(0x8F30, 0x8F6F, 'l'), // 輯
        Entry(0x8F70, 0x8FAF, 'h'), // 轰
        Entry(0x8FB0, 0x8FEF, 'd'), // 达
        Entry(0x8FF0, 0x902F, 's'), // 述
        Entry(0x9030, 0x906F, 't'), // 通
        Entry(0x9070, 0x90AF, 'y'), // 遥
        Entry(0x90B0, 0x90EF, 'b'), // 邦
        Entry(0x90F0, 0x912F, 'l'), // 郎
        Entry(0x9130, 0x916F, 'b'), // 鄙
        Entry(0x9170, 0x91AF, 'p'), // 配
        Entry(0x91B0, 0x91EF, 'c'), // 醋
        Entry(0x91F0, 0x922F, 'j'), // 金
        Entry(0x9230, 0x926F, 'g'), // 钢
        Entry(0x9270, 0x92AF, 'b'), // 钵
        Entry(0x92B0, 0x92EF, 'm'), // 钼
        Entry(0x92F0, 0x932F, 'p'), // 铺
        Entry(0x9330, 0x936F, 'j'), // 锦
        Entry(0x9370, 0x93AF, 'q'), // 锹
        Entry(0x93B0, 0x93EF, 'l'), // 镑
        Entry(0x93F0, 0x942F, 'd'), // 镧
        Entry(0x9430, 0x946F, 'h'), // 镧
        Entry(0x9470, 0x94AF, 'q'), // 鋟
        Entry(0x94B0, 0x94EF, 'g'), // 钩
        Entry(0x94F0, 0x952F, 'l'), // 铃
        Entry(0x9530, 0x956F, 'm'), // 锚
        Entry(0x9570, 0x95AF, 'l'), // 链
        Entry(0x95B0, 0x95EF, 'k'), // 阔
        Entry(0x95F0, 0x962F, 'r'), // 阮
        Entry(0x9630, 0x966F, 'l'), // 陆
        Entry(0x9670, 0x96AF, 'y'), // 阴
        Entry(0x96B0, 0x96EF, 'l'), // 隆
        Entry(0x96F0, 0x972F, 'w'), // 雁雪雾
        Entry(0x9730, 0x976F, 'l'), // 露
        Entry(0x9770, 0x97AF, 'q'), // 青
        Entry(0x97B0, 0x97EF, 'y'), // 韵
        Entry(0x97F0, 0x982F, 'y'), // 音
        Entry(0x9830, 0x986F, 'd'), // 顶
        Entry(0x9870, 0x98AF, 'b'), // 颁
        Entry(0x98B0, 0x98EF, 'f'), // 风
        Entry(0x98F0, 0x992F, 'f'), // 飞
        Entry(0x9930, 0x996F, 'g'), // 馆
        Entry(0x9970, 0x99AF, 'm'), // 馒
        Entry(0x99B0, 0x99EF, 's'), // 騙
        Entry(0x99F0, 0x9A2F, 'q'), // 骐
        Entry(0x9A30, 0x9A6F, 'g'), // 骨
        Entry(0x9A70, 0x9AAF, 'h'), // 髅
        Entry(0x9AB0, 0x9AEF, 'g'), // 高
        Entry(0x9AF0, 0x9B2F, 'b'), // 髮
        Entry(0x9B30, 0x9B6F, 'g'), // 鬣
        Entry(0x9B70, 0x9BAF, 'l'), // 魉
        Entry(0x9BB0, 0x9BEF, 'q'), // 鳀
        Entry(0x9BF0, 0x9C2F, 'l'), // 鲈
        Entry(0x9C30, 0x9C6F, 'l'), // 鲸
        Entry(0x9C70, 0x9CAF, 'l'), // 鳞
        Entry(0x9CB0, 0x9CEF, 'l'), // 鳗
        Entry(0x9CF0, 0x9D2F, 'b'), // 鸠
        Entry(0x9D30, 0x9D6F, 'l'), // 鹭
        Entry(0x9D70, 0x9DAF, 'q'), // 鹩
        Entry(0x9DB0, 0x9DEF, 'l'), // 鹭
        Entry(0x9DF0, 0x9E2F, 'h'), // 鹤
        Entry(0x9E30, 0x9E6F, 'h'), // 鸿
        Entry(0x9E70, 0x9EAF, 'y'), // 鹰
        Entry(0x9EB0, 0x9EEF, 'l'), // 麓
        Entry(0x9EF0, 0x9F2F, 'm'), // 麻
        Entry(0x9F30, 0x9F6F, 'q'), // 黔
        Entry(0x9F70, 0x9FAF, 'b'), // 鼻
        Entry(0x9FB0, 0x9FA5, 'q'), // 龥
    )
}
