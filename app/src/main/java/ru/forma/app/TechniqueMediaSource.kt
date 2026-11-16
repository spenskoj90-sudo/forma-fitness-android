package ru.forma.app

import android.content.res.AssetManager
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.forma.domain.*

internal const val TECHNIQUE_MANIFEST="technique/manifest.json"
internal const val MAX_TECHNIQUE_BYTES=16*1024*1024
private const val MAX_MANIFEST_BYTES=256*1024
@Serializable internal data class TechniqueManifest(val version:Int,val entries:List<TechniqueMediaEvidence>)
internal sealed interface TechniqueMediaResult {
 data object Unavailable:TechniqueMediaResult
 data object Rejected:TechniqueMediaResult
 data object Unsupported:TechniqueMediaResult
 data class Ready(val media:VerifiedTechniqueMedia):TechniqueMediaResult
}
internal fun interface TechniqueMediaSource { suspend fun load(exercise:Exercise):TechniqueMediaResult }

/** Owns a private snapshot: playback cannot reopen a different/unverified file. */
internal class VerifiedTechniqueMedia(val evidence:TechniqueMediaEvidence,private val bytes:ByteArray) {
 fun dataSource():MediaDataSource=TechniqueBytes(bytes)
}
private class TechniqueBytes(private val bytes:ByteArray):MediaDataSource() {
 override fun getSize()=bytes.size.toLong()
 override fun readAt(position:Long,buffer:ByteArray,offset:Int,size:Int):Int {
  if(position<0 || offset<0 || size<0 || offset>buffer.size-size)throw IOException("Invalid media range")
  if(size==0)return 0
  if(position>=bytes.size)return -1
  val count=minOf(size,bytes.size-position.toInt())
  System.arraycopy(bytes,position.toInt(),buffer,offset,count)
  return count
 }
 override fun close()=Unit
}
private class RejectedEvidence:IOException()
private fun InputStream.bounded(limit:Int):ByteArray=use{stream->
 val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
 while(true){
  val n=stream.read(buffer,0,minOf(buffer.size,limit-out.size()+1))
  if(n<0)break
  if(n==0)throw IOException("Empty asset read")
  if(out.size()+n>limit)throw RejectedEvidence()
  out.write(buffer,0,n)
 }
 out.toByteArray()
}

/** Only the reviewed source registry can admit evidence; asset metadata cannot self-certify. */
internal class AssetTechniqueMediaSource(
 private val openAsset:(String)->InputStream,
 private val admitted:List<TechniqueMediaEvidence> = techniqueMediaCatalog,
 private val probe:(ByteArray,String)->Boolean = ::supportedTechniqueVideo
):TechniqueMediaSource {
 constructor(assets:AssetManager,admitted:List<TechniqueMediaEvidence> = techniqueMediaCatalog):
  this({assets.open(it)},admitted)
 override suspend fun load(exercise:Exercise):TechniqueMediaResult=withContext(Dispatchers.IO){
  if(exerciseCatalog.singleOrNull{it.id==exercise.id}?.equipment!=exercise.equipment)
   return@withContext TechniqueMediaResult.Rejected
  try {
   val text=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(openAsset(TECHNIQUE_MANIFEST).bounded(MAX_MANIFEST_BYTES))).toString()
   requireUniqueJsonFields(text)
   val manifest=Json.decodeFromString<TechniqueManifest>(text)
   if(manifest.version!=1 || manifest.entries.size>exerciseCatalog.size ||
    manifest.entries.sortedBy{it.exerciseId}!=admitted.sortedBy{it.exerciseId})
    return@withContext TechniqueMediaResult.Rejected
   if(manifest.entries.none{it.exerciseId==exercise.id})return@withContext TechniqueMediaResult.Unavailable
   var snapshot:ByteArray?=null
   var readFailure:IOException?=null
   val evidence=resolveTechniqueMedia(exercise.id,manifest.entries){path->
    try{openAsset(path).bounded(MAX_TECHNIQUE_BYTES).also{snapshot=it}}
    catch(error:IOException){readFailure=error;null}
   }
   if(evidence==null)return@withContext if(readFailure!=null && readFailure !is RejectedEvidence)
    TechniqueMediaResult.Unavailable else TechniqueMediaResult.Rejected
   val bytes=snapshot?:return@withContext TechniqueMediaResult.Rejected
   if(!probe(bytes,evidence.offlinePath))return@withContext TechniqueMediaResult.Unsupported
   TechniqueMediaResult.Ready(VerifiedTechniqueMedia(evidence,bytes))
  }catch(cancelled:java.util.concurrent.CancellationException){throw cancelled}
   catch(_:RejectedEvidence){TechniqueMediaResult.Rejected}
   catch(_:java.io.FileNotFoundException){TechniqueMediaResult.Unavailable}
   catch(_:kotlinx.serialization.SerializationException){TechniqueMediaResult.Rejected}
   catch(_:IllegalArgumentException){TechniqueMediaResult.Rejected}
   catch(_:java.nio.charset.CharacterCodingException){TechniqueMediaResult.Rejected}
   catch(_:IOException){TechniqueMediaResult.Unavailable}
   catch(_:SecurityException){TechniqueMediaResult.Rejected}
 }
}

/** Initial portable allowlist: ISO MP4 with one decodable H.264 video track. No network. */
internal fun supportedTechniqueVideo(bytes:ByteArray,path:String):Boolean {
 if(!path.endsWith(".mp4") || bytes.size<12 || String(bytes,4,4,Charsets.US_ASCII)!="ftyp" ||
  String(bytes,8,4,Charsets.US_ASCII) !in setOf("isom","iso2","avc1","mp41","mp42"))return false
 val extractor=MediaExtractor()
 val source=TechniqueBytes(bytes)
 return try {
  extractor.setDataSource(source)
  val videos=(0 until extractor.trackCount).map{extractor.getTrackFormat(it)}
   .filter{it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true}
  if(videos.size!=1)return false
  val format=videos.single()
  val width=format.getInteger(MediaFormat.KEY_WIDTH);val height=format.getInteger(MediaFormat.KEY_HEIGHT)
  val duration=format.getLong(MediaFormat.KEY_DURATION)
  format.getString(MediaFormat.KEY_MIME)=="video/avc" && width>0 && height>0 &&
   maxOf(width,height)<=1920 && width.toLong()*height<=1920L*1080 && duration in 1..180_000_000L &&
   MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)!=null
 }catch(_:Exception){false}finally{extractor.release();source.close()}
}
