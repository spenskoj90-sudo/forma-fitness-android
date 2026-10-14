package ru.forma.app
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class UpdateTest{
 @get:Rule val rule=createAndroidComposeRule<MainActivity>()
 @Test fun legacyDatabaseSurvivesCompatibleCertificateUpdate()=runBlocking(Dispatchers.IO){
  val app=rule.activity.application as FormaApp
  val p=app.repository.load("legacy-update")!!
  assertEquals(88.0,p.weightKg,0.0);assertEquals(1,p.history.size);assertEquals("old-measurement",p.measurements.single().id)
  assertEquals(2,app.packageManager.getPackageInfo(app.packageName,0).versionCode)
  val roundTrip=ru.forma.domain.decodeBackup(app.repository.exportBackup())
  assertEquals(p,roundTrip.profiles.single())
  // Isolate subsequent UI journeys from this synthetic update fixture.
  app.database.clearAllTables()
 }
}
