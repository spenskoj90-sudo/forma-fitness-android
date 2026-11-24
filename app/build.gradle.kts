plugins {alias(libs.plugins.android.application);alias(libs.plugins.kotlin.android);alias(libs.plugins.kotlin.compose);alias(libs.plugins.kotlin.serialization);alias(libs.plugins.ksp)}
val cloudReleaseFlag = providers.environmentVariable("FORMA_CLOUD_RELEASE_ENABLED").orNull.orEmpty()
require(cloudReleaseFlag in setOf("", "false", "true")) {
 "FORMA_CLOUD_RELEASE_ENABLED must be true or false"
}
val cloudRelease = cloudReleaseFlag == "true"
val cloudDebug = providers.gradleProperty("formaCloudSyncEnabled").orNull == "true"

android {
 namespace="ru.forma.app";compileSdk=35
 defaultConfig {applicationId="ru.forma.fitness";minSdk=26;targetSdk=35;versionCode=providers.gradleProperty("formaVersionCode").orNull?.toInt()?:2;versionName=providers.gradleProperty("formaVersionName").orNull?:"0.2.0";testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner";manifestPlaceholders["formaAppLabel"]="FORMA";resValue("string","forma_update_feed_url",providers.gradleProperty("formaUpdateFeedUrl").orNull?:"");resValue("string","forma_update_manifest_public_key",providers.gradleProperty("formaUpdateManifestPubKey").orNull?:"")}
 defaultConfig.resValue("bool", "forma_cloud_sync_enabled", "false")
 defaultConfig.resValue("bool", "forma_use_play_integrity_app_check", "false")
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlinOptions {jvmTarget="17"}
 signingConfigs {
  getByName("debug") { storeFile=rootProject.file(".forma-signing/debug.keystore") }
  create("formaRelease") {
   storeType="pkcs12"
   System.getenv("FORMA_RELEASE_KEYSTORE_PATH")?.let { storeFile=file(it) }
   storePassword=System.getenv("FORMA_RELEASE_STORE_PASSWORD")
   keyAlias=System.getenv("FORMA_RELEASE_KEY_ALIAS")
   keyPassword=System.getenv("FORMA_RELEASE_KEY_PASSWORD")
  }
 }
 buildTypes {
  // Never mint a different applicationId for an Owner-facing FORMA update.
  // Debug APKs are CI evidence only; never install them over existing data
  // unless the exact certificate is known to be compatible.
  getByName("debug") {
   resValue("bool", "forma_cloud_sync_enabled", cloudDebug.toString())
  }
  getByName("release") {
   signingConfig = signingConfigs.getByName("formaRelease")
   if (cloudRelease) {
    val projectId = providers.environmentVariable("FORMA_FIREBASE_PROJECT_ID").orNull.orEmpty()
    val appId = providers.environmentVariable("FORMA_FIREBASE_ANDROID_APP_ID").orNull.orEmpty()
    val apiKey = providers.environmentVariable("FORMA_FIREBASE_ANDROID_API_KEY").orNull.orEmpty()
    require(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]").matches(projectId)) {
     "Cloud release requires a valid Firebase project ID"
    }
    require(Regex("1:[0-9]+:android:[0-9a-fA-F]+").matches(appId)) {
     "Cloud release requires a valid Firebase Android app ID"
    }
    require(Regex("AIza[A-Za-z0-9_-]{35}").matches(apiKey)) {
     "Cloud release requires a valid Firebase Android client API key"
    }
    resValue("string", "project_id", projectId)
    resValue("string", "google_app_id", appId)
    resValue("string", "google_api_key", apiKey)
    resValue("bool", "forma_cloud_sync_enabled", "true")
    resValue("bool", "forma_use_play_integrity_app_check", "true")
   }
  }
 }
 buildFeatures {compose=true}
 testOptions {unitTests.isIncludeAndroidResources=true;unitTests.all {it.systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2")}}
}
// Persist compiler-generated Room schemas for non-destructive future migrations.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(project(":domain"))
 implementation(libs.androidx.core.ktx);implementation(libs.androidx.lifecycle.runtime.ktx);implementation(libs.androidx.lifecycle.viewmodel.compose);implementation(libs.androidx.activity.compose)
 implementation(platform(libs.androidx.compose.bom));implementation(libs.androidx.ui);implementation(libs.androidx.material3);implementation(libs.androidx.material.icons.extended)
 implementation(libs.androidx.room.runtime);implementation(libs.androidx.room.ktx);ksp(libs.androidx.room.compiler);implementation(libs.androidx.datastore.preferences);implementation(libs.kotlinx.serialization.json)
 implementation(platform(libs.firebase.bom))
 implementation(libs.firebase.auth)
 implementation(libs.firebase.firestore)
 implementation(libs.firebase.appcheck.playintegrity)
 implementation(libs.androidx.work.runtime)
 implementation(libs.kotlinx.coroutines.play.services)
 testImplementation(libs.junit);testImplementation(libs.robolectric);testImplementation(libs.androidx.work.testing)
 androidTestImplementation(libs.androidx.junit);androidTestImplementation(libs.androidx.espresso.core);androidTestImplementation(libs.androidx.test.rules);androidTestImplementation(platform(libs.androidx.compose.bom));androidTestImplementation(libs.androidx.ui.test.junit4)
 androidTestImplementation(libs.androidx.work.testing)
 debugImplementation(libs.androidx.ui.test.manifest)
}

