# APK Importer V1 - informe de implementación

## 1. Arquitectura encontrada

Valcrono VirtualSpace es una app Android multi-módulo con runtime cooperativo en dos procesos virtuales (`:vapp0`, `:vapp1`) y slots persistidos `VAPP0`/`VAPP1`. Room persiste paquetes, sesiones, slots, tokens, permisos, componentes y cola de mensajes. La UI Compose de `MainActivity` observa paquetes/sesiones/slots y deriva estado visible desde Room más reconciliación de proceso real.

## 2. Cambios implementados

- Migración Room 13→14 aditiva para metadata de APK importados V1 sobre la tabla existente `virtual_packages`.
- Extensión de `VirtualPackageEntity` con campos mínimos de importación/análisis solicitados, manteniendo defaults para filas antiguas.
- Coordinador SAF `ApkImportCoordinatorV1` para validar `content://`, MIME/extensión, copiar a temporal privado, calcular SHA-256, analizar y registrar.
- Lector `ApkArchiveReaderV1` para DEX, librerías nativas, ABI, splits y protección de entradas ZIP.
- Storage V1 adicional bajo `filesDir/virtual-packages/{packageName}` con `apk/base.apk`, `data`, `metadata`, `temp` y `logs`.
- Fortalecimiento de `ApkValidator` con detección de `classes.dex` y nombres duplicados.
- Parser Android ampliado con certificate SHA-256, compileSdk y Application personalizada cuando PackageManager lo expone.
- UI de importación actualizada para usar el coordinador SAF y mostrar estado `READY`/`BLOCKED`/compatibilidad.

## 3. Archivos creados

- `app/src/main/java/com/valcrono/virtualspace/ApkImportV1.kt`
- `docs/apk-importer-v1-plan.md`
- `docs/apk-importer-v1-test-matrix.md`
- `docs/apk-importer-v1-implementation-report.md`

## 4. Archivos modificados

- `app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt`
- `app/src/main/java/com/valcrono/virtualspace/VirtualRepository.kt`
- `app/src/main/java/com/valcrono/virtualspace/MainActivity.kt`
- `app/src/main/java/com/valcrono/virtualspace/AndroidArchivePackageParser.kt`
- `virtual-package-manager/src/main/kotlin/com/valcrono/vpm/VirtualPackageManager.kt`

## 5. Migraciones de base de datos

`MIGRATION_13_14` añade columnas V1 e índices por `packageName`, `sha256`, `compatibilityLevel` e `importState`. No usa destructive migration y normaliza datos antiguos copiando `label`, `apkInternalPath`, `installTime`, `updateTime`, `mainActivity` y `hasNativeLibraries` a los campos nuevos.

## 6. Nuevo flujo de importación

1. Usuario pulsa “Importar APK”.
2. Activity Result API abre SAF con MIME de APK, octet-stream y fallback controlado.
3. Se recibe `content://`.
4. Se rechazan `.apks`, `.xapk`, `.apkm` y `.zip` por nombre.
5. Se copia a `filesDir/virtual-imports/tmp/{uuid}/incoming.apk` usando streaming fuera del hilo principal.
6. Se valida header ZIP, tamaño reportado si existe, manifiesto, `classes.dex`, entradas duplicadas y path traversal.
7. Se calcula SHA-256.
8. PackageManager analiza el APK privado.
9. Se importa mediante el importador existente y se actualiza la fila con metadata V1.
10. Se escribe metadata aislada bajo `filesDir/virtual-packages/{packageName}`.
11. Se eliminan temporales.

## 7. Nuevo flujo de ejecución

El runtime cooperativo existente no se reemplazó. Los APK con entry point cooperativo siguen usando `RuntimeSessionController.prepareLaunch()`, slots VAPP0/VAPP1, launch tokens, ACK, heartbeat, reconciliación y cierre existentes. Los APK genéricos quedan importados/analizados y se bloquea la ejecución genérica segura hasta completar Activity/Context/Resources virtuales completos.

## 8. Clasificación de compatibilidad

Se persiste un nivel V1:

- `COOPERATIVE_SUPPORTED` para entry point cooperativo válido.
- `GENERIC_SIMPLE` para APK simple sin bloqueos críticos detectados.
- `GENERIC_EXPERIMENTAL` para señales limitadas/high-risk.
- `REQUIRES_NATIVE_SUPPORT` para APK genérico con `.so`.
- `SPLIT_APK_UNSUPPORTED` para split detectado.
- `INCOMPATIBLE` para APK inválido/sin launcher o bloqueo equivalente.

Las razones se guardan como JSON simple en `compatibilityReasonsJson`.

## 9. Medidas de seguridad

- No se ejecuta desde URI externa.
- No se solicita `MANAGE_EXTERNAL_STORAGE` ni `QUERY_ALL_PACKAGES`.
- Copia privada antes de análisis.
- Validación ZIP y path traversal.
- Rechazo de bundles/splits de distribución no soportados.
- SHA-256 persistido y verificación existente antes de ClassLoader.
- Storage por packageName validado y canonicalizado.
- No se crean slots paralelos ni launchTokens fuera del runtime existente.

## 10. Pruebas ejecutadas

- `./gradlew test --no-daemon`: no ejecutable porque falta `gradle/wrapper/gradle-wrapper.jar`.
- `gradle test --no-daemon`: no ejecutable porque no existe SDK Android configurado (`ANDROID_HOME`/`local.properties`).

## 11. Resultados exactos

El entorno actual no permite compilar ni ejecutar pruebas Android por falta del SDK Android y wrapper incompleto. No se declara el MVP como terminado en ejecución genérica; queda implementada la base de importación/análisis/persistencia y conservada la ruta cooperativa existente.

## 12. Problemas encontrados

- El repositorio contiene `gradlew`, pero no `org.gradle.wrapper.GradleWrapperMain` disponible.
- No hay `ANDROID_HOME` ni `local.properties` con `sdk.dir`.
- La ejecución de APK genérico real requiere completar virtualización de Activity/Instrumentation/Resources/Context antes de activar el botón Abrir para no romper el runtime estable.

## 13. Limitaciones actuales

- APK genéricos se importan y analizan, pero no se ejecutan como Activity real si no implementan el entry point cooperativo.
- No se implementó actualización con confirmación de firma distinta ni eliminación transaccional completa en menú dedicado V1.
- No se ejecutó `connectedAndroidTest` por ausencia de dispositivo/emulador verificable desde este entorno.

## 14. Próximo paso recomendado

Configurar SDK Android en CI/agente, ejecutar `assembleDebug`, corregir cualquier error de compilación residual y avanzar con una `RuntimeGenericActivityHost` que cree Resources/Context virtuales por sesión sin reutilizar ClassLoader ni saltarse ACK/slots.

## Comandos de compilación y pruebas

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew test
./gradlew lint
gradle test --no-daemon
```

## Ruta esperada del APK generado

Cuando el entorno tenga SDK Android válido: `app/build/outputs/apk/debug/app-debug.apk`.

## Instrucciones para probar en moto g06

1. Instalar el APK debug generado: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Abrir Valcrono VirtualSpace.
3. Ejecutar pruebas 1–4 de la matriz para confirmar TestApp A/B.
4. Copiar un APK simple al dispositivo o proveedor de documentos.
5. Pulsar “Aplicaciones” → “Importar APK”.
6. Seleccionar el APK mediante SAF.
7. Confirmar que aparece en la lista con SHA/compatibilidad/estado.
8. Para APK cooperativo, abrir/detener/reabrir y cerrar host desde Recientes.
9. Para APK genérico, verificar que queda importado/analizado y no se fuerza una ejecución insegura.

## Logs si falla

- `adb logcat -d -s Valcrono RuntimeLaunch RuntimeSlot RuntimeReclaimer RuntimeRecovery DexLoader Importer UI`
- `adb shell pidof com.valcrono.virtualspace`
- Captura de Apps y Procesos.
