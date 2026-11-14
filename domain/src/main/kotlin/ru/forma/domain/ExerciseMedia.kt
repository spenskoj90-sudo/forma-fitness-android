package ru.forma.domain

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Required accessible technique stages; content needs human technique review. */
@Serializable
data class TechniqueCaption(
 val setup:String,val start:String,val motion:String,val finish:String,
 val rangeOfMotion:String,val breathing:String,val errors:String
) {
 internal fun complete()=listOf(setup,start,motion,finish,rangeOfMotion,breathing,errors).all{it.isNotBlank()}
}

/** Evidence is trusted only when admitted by source review, not by filling in strings. */
@Serializable
data class TechniqueMediaEvidence(
 val exerciseId:String,val equipment:Equipment,val revision:Int,
 val offlinePath:String,val sha256:String,val license:String,val provenance:String,
 val reviewer:String,val reviewReference:String,val caption:TechniqueCaption
)

/** No licensed, instructor-reviewed demonstration assets have been admitted yet. */
val techniqueMediaCatalog:List<TechniqueMediaEvidence> = emptyList()

/**
 * Identity and integrity boundary for a trusted packaged manifest and local reader.
 * Returns null for unavailable/ambiguous evidence so the app keeps its text fallback.
 * Hash/metadata validation does not itself establish licensing or technique accuracy.
 * No URL resolution or network fetch is performed. Review evidence must bind the
 * exact hash, revision and captions before an entry joins the trusted manifest.
 */
fun resolveTechniqueMedia(
 exerciseId:String,evidence:List<TechniqueMediaEvidence>,readAsset:(String)->ByteArray?
):TechniqueMediaEvidence? {
 if(exerciseCatalog.none{it.id==exerciseId})return null
 if(evidence.map{it.exerciseId}.distinct().size!=evidence.size)return null
 if(evidence.map{it.sha256}.distinct().size!=evidence.size)return null
 if(evidence.any{item->
   val exercise=exerciseCatalog.singleOrNull{it.id==item.exerciseId}
   exercise==null || exercise.equipment!=item.equipment || item.revision<=0 ||
    item.sha256.length!=64 || item.sha256.any{it !in "0123456789abcdef"} ||
    listOf(item.license,item.provenance,item.reviewer,item.reviewReference).any{it.isBlank()} ||
    !item.caption.complete() || item.offlinePath !in listOf(
     "technique/${item.exerciseId}/v${item.revision}.mp4",
     "technique/${item.exerciseId}/v${item.revision}.webm")
  })return null
 val item=evidence.singleOrNull{it.exerciseId==exerciseId}?:return null
 val bytes=try{readAsset(item.offlinePath)}
  catch(cancelled:java.util.concurrent.CancellationException){throw cancelled}
  catch(_:Exception){return null}
  ?:return null
 val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
 return item.takeIf{actual==it.sha256}
}
