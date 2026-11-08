package ru.forma.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.Measurement
import ru.forma.domain.Profile

/** Synthetic local journal, never a user's measurements or network provider. */
@RunWith(AndroidJUnit4::class)
class BodyHistoryPaginationTest {
 @get:Rule val rule=createComposeRule()

 @Test fun oldestMeasurementIsOnlyShownAfterPagingAndEditedWithoutDuplicate(){
  val measurements=(1..23).map{n->
   Measurement(id="m-$n",dateMs=100000L+n*1000L,
    values=mapOf("weight" to (60+n).toDouble()))
  }
  val profile=Profile(id="synthetic",name="Testing",measurements=measurements)
  var savedId:String?=null
  var saveCalls=0
  rule.setContent { MaterialTheme { BodyScreen(profile){values,id,dateMs,draftId,done->
   savedId=id
   saveCalls++
   assertEquals(61.0,values["weight"]!!,0.0)
   assertEquals(measurements.first().dateMs,dateMs)
   done()
  } } }
  rule.onNodeWithText("Показано 10 из 23 замеров.",substring=true).assertExists()
  rule.onNodeWithText("Вес, кг: 61.0").assertDoesNotExist()
  rule.onNodeWithTag("body-measurements-more").performScrollTo().performClick()
  rule.onNodeWithText("Показано 20 из 23 замеров.",substring=true).assertExists()
  rule.onNodeWithText("Вес, кг: 61.0").assertDoesNotExist()
  rule.onNodeWithTag("body-measurements-more").performScrollTo().performClick()
  rule.onNodeWithText("Показано 23 из 23 замеров.",substring=true).assertExists()
  rule.onNodeWithText("Вес, кг: 61.0").assertExists()
  rule.onNodeWithTag("body-measurements-more").assertDoesNotExist()
  rule.onAllNodesWithText("Исправить запись").onLast().performScrollTo().performClick()
  rule.onNodeWithTag("body-measure-weight").assertTextContains("61.0")
  rule.onNodeWithText("Исправить замер").assertExists()
  assertEquals(0,saveCalls)
  rule.onNodeWithText("Сохранить замеры").performScrollTo().performClick()
  assertEquals(1,saveCalls)
  assertEquals("m-1",savedId)
  // UI never mutates the immutable input / replaces the original history.
  assertEquals(23,profile.measurements.size)
  assertEquals("m-1",profile.measurements.first().id)
 }

 @Test fun emptyJournalAndProfileSwitchDoNotReuseOldPages(){
  val a=Profile(id="a",name="A",measurements=(1..21).map{
   Measurement(id="a-$it",dateMs=it.toLong(),values=mapOf("weight" to 60.0))
  })
  val b=Profile(id="b",name="B")
  rule.setContent{MaterialTheme{BodyScreen(a){_,_,_,_,_->}}}
  rule.onNodeWithTag("body-measurements-more").performScrollTo().performClick()
  rule.onNodeWithText("Показано 20 из 21 замеров.",substring=true).assertExists()
  // Separate composition with a different id cannot carry or edit A's records.
  rule.setContent{MaterialTheme{BodyScreen(b){_,_,_,_,_->}}}
  rule.onNodeWithText("Сохранённых замеров пока нет.").assertExists()
  rule.onNodeWithText("Показано 20 из 21 замеров.",substring=true).assertDoesNotExist()
 }
}
