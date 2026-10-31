package ru.forma.app

/** Convert a rejected foreground-service request into a durable paused session. */
internal suspend fun requestWorkoutServiceOrPause(
 repository:ProfileRepository,
 profileId:String,
 sessionId:String,
 now:()->Long,
 isRequestCurrent:()->Boolean,
 launch:()->Unit
){
 try{launch()}
 catch(e:RuntimeException){
  try{
   repository.pauseWhenServiceUnavailable(profileId,sessionId,now(),isRequestCurrent)
  }catch(persistenceError:Exception){
   throw IllegalStateException(
    "Сопровождение не запустилось, а сохранить паузу не удалось. Проверьте тренировку.",
    persistenceError
   )
  }
  throw IllegalStateException(
   "Не удалось запустить сопровождение. Тренировка поставлена на паузу.",e
  )
 }
}
