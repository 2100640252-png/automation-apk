package com.automation.work

/**
 * 随机内容池（v1.2 新增）：
 * 随机发视频、随机评论到自己已发布的视频时用的文案，
 * 不是每次都发同一句，避免被平台判定为重复刷屏。
 * 用户也可以在评论页自己改，想发什么就发什么。
 */
object RandomContent {
    val comments = listOf(
        "这个画面太治愈了，阳光刚刚好。",
        "看完心情都变好了，谢谢分享。",
        "这个转场好自然，越看越喜欢。",
        "音乐和画面好搭，循环了好几遍。",
        "拍得真好，构图和光都绝了。",
        "好喜欢这个氛围，收藏了。",
        "舞蹈好轻盈，看着很舒服。",
        "这个地方好美，想去打卡。",
        "剪辑节奏很好，一点都不拖。",
        "笑容好治愈，今天也被暖到了。",
        "这条视频质量好高，支持一下。",
        "画面好干净，色彩也好鲜艳。"
    )

    val captions = listOf(
        "今日份治愈，海边散步 #治愈 #日常",
        "阳光正好，微风不燥 #日常 #治愈",
        "随歌起舞的一天 #舞蹈 #治愈",
        "街头走走，记录好心情 #街拍 #日常"
    )

    fun randomComment(): String = comments.random()

    fun randomCaption(): String = captions.random()

    /** 从视频库里随机挑一个视频，测试随机发布用。 */
    fun randomVideo(videos: List<StoredVideo>): StoredVideo? {
        if (videos.isEmpty()) return null
        return videos.random()
    }
}
