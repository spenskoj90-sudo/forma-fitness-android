package ru.forma.app

import org.junit.Assert.*
import org.junit.Test

/** Synthetic local identities only; never a UID from the SAF file picker. */
class BackupExportScopeTest {
 private val ids=setOf("a","b")

 @Test fun selectedProfileCannotChangeWhilePickerIsOpen() {
  requireBackupExportScope(false,"a",null,"a","a",ids)
  requireBackupExportScope(false,"a",null,"a","",ids)
  for (item in listOf(
   Triple("a","b","b"),Triple("a","a","b"),Triple("a",null,"a"),
   Triple(null,"a","a"),Triple("z","z","z")
  )) {
   assertThrows(IllegalArgumentException::class.java) {
    requireBackupExportScope(false,item.first,null,item.second,item.third,ids)
   }
  }
 }

 @Test fun exportAllNeverWidensBeyondTheConfirmedProfileSet() {
  requireBackupExportScope(true,null,ids,null,"a",ids)
  for (offered in listOf(null,emptySet(),setOf("a"),setOf("a","b","c"))) {
   assertThrows(IllegalArgumentException::class.java) {
    requireBackupExportScope(true,null,offered,null,"a",ids)
   }
  }
 }
}
