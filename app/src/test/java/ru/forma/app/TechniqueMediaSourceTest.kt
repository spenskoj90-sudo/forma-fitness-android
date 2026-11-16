package ru.forma.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.forma.domain.*
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[28])
class TechniqueMediaSourceTest {
 private val exercise=exerciseCatalog.first()
 private val bytes="synthetic unit fixture, not a video or accepted content".toByteArray()
 private fun entry()=TechniqueMediaEvidence(exercise.id,exercise.equipment,1,
  "technique/${exercise.id}/v1.mp4",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},
  "SYNTHETIC license","SYNTHETIC provenance","SYNTHETIC reviewer","SYNTHETIC review",
  TechniqueCaption("setup","start","motion","finish","range","breath","errors"))
 private fun load(manifest:String=Json.encodeToString(TechniqueManifest(1,listOf(entry()))),
  admitted:List<TechniqueMediaEvidence> = listOf(entry()),asset:ByteArray?=bytes,
  supported:Boolean=true,target:Exercise=exercise):TechniqueMediaResult=runBlocking {
  AssetTechniqueMediaSource(openAsset={path->
   ByteArrayInputStream(if(path==TECHNIQUE_MANIFEST)manifest.toByteArray() else asset?:throw FileNotFoundException())
  },admitted=admitted,probe={_,_->supported}).load(target)
 }
 @Test fun validAdmittedIdentityBytesAndMetadataReachFormatProbe() {
  assertTrue(load() is TechniqueMediaResult.Ready)
 }
 @Test fun plausibleMetadataCannotAdmitItself() {
  assertEquals(TechniqueMediaResult.Rejected,load(admitted=emptyList()))
 }
 @Test fun wrongIdentityOrEquipmentCannotResolve() {
  assertEquals(TechniqueMediaResult.Rejected,load(target=exercise.copy(id="unknown")))
  assertEquals(TechniqueMediaResult.Rejected,load(target=exercise.copy(equipment=Equipment.entries.first{it!=exercise.equipment})))
 }
 @Test fun tamperedMissingOversizeAndUnsupportedAssetsAreUnavailable() {
  assertEquals(TechniqueMediaResult.Rejected,load(asset=bytes+0))
  assertEquals(TechniqueMediaResult.Unavailable,load(asset=null))
  assertEquals(TechniqueMediaResult.Rejected,load(asset=ByteArray(MAX_TECHNIQUE_BYTES+1)))
  assertEquals(TechniqueMediaResult.Unsupported,load(supported=false))
 }
 @Test fun duplicateConflictingUnknownAndMalformedManifestsFailClosed() {
  val normal=Json.encodeToString(TechniqueManifest(1,listOf(entry())))
  listOf("{",normal.replace("\"version\":1","\"version\":2"),
   normal.replace("\"version\":1","\"version\":1,\"version\":1"),
   normal.replace("\"version\":1","\"version\":1,\"vers\\u0069on\":1"),
   normal.replace("\"reviewer\":","\"reviewer\":\"forged\",\"reviewer\":"),
   normal.replace("\"version\":1","\"unknown\":0,\"version\":1"),
   Json.encodeToString(TechniqueManifest(1,listOf(entry(),entry()))))
   .forEach{assertEquals(it,TechniqueMediaResult.Rejected,load(manifest=it))}
 }
 @Test fun emptyProductionAdmissionIsHonestUnavailable() {
  assertEquals(TechniqueMediaResult.Unavailable,load(manifest="{\"version\":1,\"entries\":[]}",admitted=emptyList()))
 }
 @Test fun probingRunsOffCallingThreadAndVerifiedBytesAreRetained()=runBlocking {
  val caller=Thread.currentThread();var worker:Thread?=null
  val entry=entry();val manifest=Json.encodeToString(TechniqueManifest(1,listOf(entry))).toByteArray()
  val result=AssetTechniqueMediaSource(openAsset={ByteArrayInputStream(if(it==TECHNIQUE_MANIFEST)manifest else bytes)},
   admitted=listOf(entry),probe={_,_->worker=Thread.currentThread();true}).load(exercise)
  assertNotSame(caller,worker)
  val ready=result as TechniqueMediaResult.Ready
  val out=ByteArray(bytes.size)
  ready.media.dataSource().use{assertEquals(bytes.size,it.readAt(0,out,0,out.size))}
  assertArrayEquals(bytes,out)
 }
 @Test fun manifestSizeIsBoundedBeforeParsingOrAssetReads() {
  assertEquals(TechniqueMediaResult.Rejected,load(manifest=" ".repeat(256*1024+1)))
 }
}
