package ru.forma.app

import org.junit.Assert.*
import org.junit.Test
import ru.forma.domain.Profile

/** Cold start must not interpret transient empty flows as a first install. */
class ProfileLoadingStateTest {
 private val one=Profile(id="one",name="First")
 private val two=Profile(id="two",name="Second")
 private val ready=ProfileSnapshot(LocalLoadStatus.READY,listOf(one,two))

 @Test fun missingFirstRoomOrDataStoreEmissionCannotShowAnEditor() {
  assertFalse(canRenderProfileUi(ProfileSnapshot(LocalLoadStatus.LOADING),
   SettingsSnapshot(LocalLoadStatus.READY),null))
  assertFalse(canRenderProfileUi(ready,SettingsSnapshot(LocalLoadStatus.LOADING),one))
  assertFalse(canRenderProfileUi(ProfileSnapshot(LocalLoadStatus.FAILED),
   SettingsSnapshot(LocalLoadStatus.READY),null))
  assertFalse(canRenderProfileUi(ready,SettingsSnapshot(LocalLoadStatus.FAILED),one))
 }

 @Test fun confirmedEmptyDatabaseShowsNewProfileOnlyAfterSettingsLoad() {
  val empty=ProfileSnapshot(LocalLoadStatus.READY)
  assertFalse(canRenderProfileUi(empty,SettingsSnapshot(LocalLoadStatus.LOADING),null))
  assertTrue(canRenderProfileUi(empty,SettingsSnapshot(LocalLoadStatus.READY),null))
 }

 @Test fun aSelectedAccountCannotBrieflyRenderAnotherLocalProfile() {
  val selected=SettingsSnapshot(LocalLoadStatus.READY,Preferences(selected="two"))
  assertFalse(canRenderProfileUi(ready,selected,one))
  assertTrue(canRenderProfileUi(ready,selected,two))
  val fallback=SettingsSnapshot(LocalLoadStatus.READY,Preferences(selected="missing"))
  assertTrue(canRenderProfileUi(ready,fallback,one))
  assertFalse(canRenderProfileUi(ready,fallback,two))
 }
}
