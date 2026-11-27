package ru.forma.app

/**
 * Android's system document picker may outlive the profile selected at launch.
 * These scopes are transient local evidence, not auth credentials or cloud grants.
 */
internal fun requireBackupExportScope(
 allProfiles: Boolean,
 expectedProfileId: String?,
 expectedProfileIds: Set<String>?,
 currentProfileId: String?,
 selectedProfileId: String,
 actualProfileIds: Set<String>
) {
 if (allProfiles) {
  require(expectedProfileIds?.isNotEmpty()==true &&
   expectedProfileIds==actualProfileIds) {
   "Состав локальных профилей изменился. Повторите выбор файла для общей копии."
  }
 } else {
  require(!expectedProfileId.isNullOrBlank() &&
   expectedProfileId==currentProfileId &&
   expectedProfileId in actualProfileIds &&
   (selectedProfileId.isBlank() || selectedProfileId==expectedProfileId)) {
   "Текущий профиль сменился. Повторите выбор файла для резервной копии."
  }
 }
}
