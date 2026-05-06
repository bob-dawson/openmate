package com.openmate.core.common

fun guessMimeForAttachment(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "text/plain"
    }
}

fun guessMimeForOpening(ext: String): String {
    return when (ext.lowercase()) {
        "txt", "log", "cfg", "conf", "ini", "properties", "env", "gitignore", "dockerignore" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "ts" -> "application/typescript"
        "md", "markdown", "mdx" -> "text/markdown"
        "yaml", "yml" -> "application/yaml"
        "toml" -> "application/toml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "tar" -> "application/x-tar"
        "gz", "tgz" -> "application/gzip"
        "bz2" -> "application/x-bzip2"
        "xz" -> "application/x-xz"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
