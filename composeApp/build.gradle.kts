import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

val ktorVersion = "3.2.3"
val coilVersion = "3.3.0"
val media3Version = "1.8.0"
val javacvVersion = "1.5.12"
val ffmpegVersion = "7.1.1-1.5.12"

val osName: String = System.getProperty("os.name").lowercase()
val nativePlatform = when {
    osName.contains("win") -> "windows-x86_64"
    osName.contains("mac") -> if (System.getProperty("os.arch") == "aarch64") "macosx-arm64" else "macosx-x86_64"
    else -> "linux-x86_64"
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Fichiers embarqués dans l'app (liste de chaînes par défaut).
            implementation(compose.components.resources)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.coil-kt.coil3:coil-compose:$coilVersion")
            implementation("io.coil-kt.coil3:coil-network-ktor3:$coilVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            implementation("androidx.media3:media3-exoplayer:$media3Version")
            implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
            implementation("androidx.media3:media3-ui:$media3Version")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            // Lecteur desktop : FFmpeg embarqué (aucune installation requise sur le PC).
            implementation("org.bytedeco:javacv:$javacvVersion") { isTransitive = false }
            implementation("org.bytedeco:javacpp:$javacvVersion")
            implementation("org.bytedeco:javacpp:$javacvVersion:$nativePlatform")
            implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
            implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativePlatform")
        }
    }
}

android {
    namespace = "com.yamking.iptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yamking.iptv"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Signature de la version de production : la clé et ses mots de passe sont lus dans
    // keystore.properties, à la racine du projet (fichier local, jamais versionné).
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // Pas de réduction du code pour l'instant : elle n'a pas été testée avec le lecteur.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

compose.resources {
    packageOfResClass = "com.yamking.iptv.resources"
}

compose.desktop {
    application {
        mainClass = "com.yamking.iptv.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "IPTV Player"
            packageVersion = "1.0.0"
            // Le lecteur (FFmpeg via JavaCPP) et le client HTTP ont besoin de modules Java que la
            // détection automatique ne voit pas : on embarque l'environnement Java complet.
            includeAllModules = true
            windows {
                menuGroup = "IPTV Player"
                shortcut = true
                // Même dessin que l'icône Android (carré dégradé et triangle « lecture »).
                iconFile.set(project.file("icons/app.ico"))
                // Identifiant fixe : permet à une nouvelle version de remplacer l'ancienne à l'installation.
                upgradeUuid = "6b0f3f0e-6c1f-4f3e-9a55-2d0a8c7c1b41"
            }
        }
    }
}
