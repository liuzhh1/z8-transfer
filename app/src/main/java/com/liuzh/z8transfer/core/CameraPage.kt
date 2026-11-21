package com.liuzh.z8transfer.core

/** A connection does not imply a request to enumerate the camera's memory cards. */
enum class CameraPage(val label: String) { HOME("首页"), REMOTE("遥控拍摄"), PHOTOS("下载照片") }
