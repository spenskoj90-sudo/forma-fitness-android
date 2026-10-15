plugins {alias(libs.plugins.android.application);alias(libs.plugins.kotlin.android);alias(libs.plugins.kotlin.compose);alias(libs.plugins.kotlin.serialization);alias(libs.plugins.ksp)}
android {
 namespace="ru.forma.app";compileSdk=35
 defaultConfig {applicationId="ru.forma.fitness";minSdk=26;targetSdk=35;versionCode=providers.gradleProperty("formaVersionCode").orNull?.toInt()?:2;versionName="0.2.0";testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner";manifestPlaceholders["formaAppLabel"]="FORMA"}
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlinOptions {jvmTarget="17"}
 signingConfigs {
  getByName("debug") { storeFile=rootProject.file(".forma-signing/debug.keystore") }
  create("formaRelease") {
   System.getenv("FORMA_RELEASE_KEYSTORE_PATH")?.let { storeFile=file(it) }
   storePassword=System.getenv("FORMA_RELEASE_STORE_PASSWORD")
   keyAlias=System.getenv("FORMA_RELEASE_KEY_ALIAS")
   keyPassword=System.getenv("FORMA_RELEASE_KEY_PASSWORD")
  }
 }
 buildTypes {
  getByName("debug"){
   // Opt-in isolated package: leaves all existing Owner installations untouched.
   if(providers.gradleProperty("formaAcceptance").orNull=="true"){
    applicationIdSuffix=".acceptance"
    versionNameSuffix="-acceptance"
    manifestPlaceholders["formaAppLabel"]="FORMA Проверка"
   }
  }
  getByName("release"){signingConfig=signingConfigs.getByName("formaRelease")}
 }
 buildFeatures {compose=true}
 testOptions {unitTests.isIncludeAndroidResources=true;unitTests.all {it.systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2")}}
}
dependencies {
 implementation(project(":domain"))
 implementation(libs.androidx.core.ktx);implementation(libs.androidx.lifecycle.runtime.ktx);implementation(libs.androidx.lifecycle.viewmodel.compose);implementation(libs.androidx.activity.compose)
 implementation(platform(libs.androidx.compose.bom));implementation(libs.androidx.ui);implementation(libs.androidx.material3);implementation(libs.androidx.material.icons.extended)
 implementation(libs.androidx.room.runtime);implementation(libs.androidx.room.ktx);ksp(libs.androidx.room.compiler);implementation(libs.androidx.datastore.preferences);implementation(libs.kotlinx.serialization.json)
 testImplementation(libs.junit);testImplementation(libs.robolectric)
 androidTestImplementation(libs.androidx.junit);androidTestImplementation(libs.androidx.espresso.core);androidTestImplementation(libs.androidx.test.rules);androidTestImplementation(platform(libs.androidx.compose.bom));androidTestImplementation(libs.androidx.ui.test.junit4)
 debugImplementation(libs.androidx.ui.test.manifest)
}

