package ru.forma.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Offline-only E2EE export/import. The password is never rememberSaveable,
 * DataStore, Room, saved-state Bundle, logcat or a network request. No password
 * can be recovered by FORMA. Legacy JSON remains a separate explicit action.
 */
@Composable internal fun EncryptedBackupPanel(vm:FormaViewModel) {
 val busy by vm.encryptedBackupBusy.collectAsState()
 val selectedProfile by vm.current.collectAsState()
 val profileSnapshot by vm.profileSnapshot.collectAsState()
 // 0 closed, 1 selected-profile export, 2 explicitly all-profile export,
 // 3 restore encrypted copy. Never persist passwords across process death.
 var mode by remember{mutableIntStateOf(0)}
 var password by remember{mutableStateOf("")}
 var confirmation by remember{mutableStateOf("")}
 var staged by remember{mutableStateOf<CharArray?>(null)}
 var stagedAll by remember{mutableStateOf(false)}
 var stagedProfileId by remember{mutableStateOf<String?>(null)}
 var stagedProfileIds by remember{mutableStateOf<Set<String>?>(null)}

 val export=rememberLauncherForActivityResult(
  ActivityResultContracts.CreateDocument("application/octet-stream")
 ){uri->
  val secret=staged
  staged=null
  if(uri!=null&&secret!=null)vm.exportEncryptedBackup(uri,secret,stagedAll,stagedProfileId,stagedProfileIds)
  else {
   secret?.fill('\u0000')
   if(uri!=null)vm.error.value="Пароль не сохранился после выбора файла. Повторите создание копии."
  }
 }
 val restore=rememberLauncherForActivityResult(
  ActivityResultContracts.OpenDocument()
 ){uri->
  val secret=staged
  staged=null
  if(uri!=null&&secret!=null)vm.importEncryptedBackup(uri,secret)
  else {
   secret?.fill('\u0000')
   if(uri!=null)vm.error.value="Пароль не сохранился после выбора файла. Повторите импорт."
  }
 }
 fun dismiss(){
  mode=0;password="";confirmation=""
 }
 Panel("Защищённая резервная копия"){
  Text("Пароль шифрует данные прямо на телефоне. Он не сохраняется в FORMA и не отправляется в сеть. Без него восстановление невозможно — храните пароль отдельно от файла.")
  Action("Сохранить защищённую копию профиля",{mode=1},!busy)
  TextButton({mode=2},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy){
   Text("Защищённая копия всех профилей")
  }
  OutlinedButton({mode=3},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy){
   Text("Восстановить защищённую копию")
  }
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  Text("После записи FORMA повторно прочитает зашифрованный файл и проверит его целостность. Это не заменяет независимую проверку восстановления. Ручной перенос не является облачной синхронизацией.",style=MaterialTheme.typography.bodySmall)
 }
 if(mode!=0){
  val isRestore=mode==3
  val allowed=if(isRestore)password.isNotEmpty()
   else password.length in 12..1024&&password==confirmation
  AlertDialog(
   onDismissRequest={dismiss()},
   title={Text(if(isRestore)"Открыть защищённую копию" else "Задать пароль копии")},
   text={
    Column(modifier=Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
     if(mode==2)Text("Файл включит персональные данные ВСЕХ локальных пользователей. Создавайте его только с их разрешения.")
     Text(if(isRestore)
      "Введите пароль этой копии. При ошибке файл не будет импортирован и существующие данные останутся без изменений."
      else "Не менее 12 символов. FORMA не сможет восстановить забытый пароль.")
     OutlinedTextField(
      value=password,onValueChange={password=it.take(1024)},
      label={Text("Пароль")},singleLine=true,
      visualTransformation=PasswordVisualTransformation(),
      modifier=Modifier.fillMaxWidth()
     )
     if(!isRestore)OutlinedTextField(
      value=confirmation,onValueChange={confirmation=it.take(1024)},
      label={Text("Повторите пароль")},singleLine=true,
      visualTransformation=PasswordVisualTransformation(),
      modifier=Modifier.fillMaxWidth()
     )
    }
   },
   confirmButton={
    TextButton(enabled=allowed,onClick={
     val option=mode
     val secret=password.toCharArray()
     dismiss()
     staged?.fill('\u0000')
     staged=secret
     stagedAll=option==2
     stagedProfileId=if(option==1)selectedProfile?.id else null
     stagedProfileIds=if(option==2)profileSnapshot.profiles.map{it.id}.toSet() else null
     if(option==3)restore.launch(arrayOf("*/*"))
     else export.launch("FORMA-" +
      (if(option==2)"all-profiles-" else "profile-") +
      LocalDate.now().toString()+".forma")
    }){Text(if(isRestore)"Выбрать файл" else "Создать файл")}
   },
   dismissButton={TextButton(onClick={dismiss()}){Text("Отмена")}}
  )
 }
}
