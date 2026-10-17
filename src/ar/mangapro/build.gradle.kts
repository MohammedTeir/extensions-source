import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProChan"
    versionCode = 69
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        name = "ProChan"
        lang = "ar"
        baseUrl = "https://procomic.pro"
        versionId = 5
    }
}
