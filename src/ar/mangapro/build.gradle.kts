import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProChan"
    versionCode = 64
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    source {
        name = "ProChan"
        lang = "ar"
        baseUrl = "https://procomic.pro"
        versionId = 5
    }
}
