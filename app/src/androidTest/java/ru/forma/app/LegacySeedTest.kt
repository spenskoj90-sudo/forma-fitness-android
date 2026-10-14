package ru.forma.app
import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*
@RunWith(AndroidJUnit4::class)
class LegacySeedTest{
 private val rule=createAndroidComposeRule<MainActivity>()
 @get:Rule val rules:TestRule=if(Build.VERSION.SDK_INT>=33)RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(rule)else rule
 @Test fun persistLegacyProfile()=runBlocking{
  val app=rule.activity.application as FormaApp
  val p=Profile(id="legacy-update",name="Тест обновления",weightKg=88.0,measurements=listOf(Measurement(id="old-measurement",dateMs=1000,values=mapOf("weight" to 88.0))))
  val s=stopSession(startSession(p.id,generateProgram(p).first(),0))
  app.repository.saveProfile(finishSession(p,s,1000))
 }
}
