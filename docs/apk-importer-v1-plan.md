# APK Importer V1 - auditoría y plan

## Arquitectura encontrada

- Proyecto Gradle multi-módulo: `:app`, `:core`, `:virtual-runtime-api`, `:virtual-package-manager`, `:virtual-storage`, `:virtual-process`, `:virtual-file-manager`, `:compat-android15`, `:testapp-a` y `:testapp-b`.
- La app host declara `MainActivity`, `RuntimeProxyActivity0` en `:vapp0`, `RuntimeProxyActivity1` en `:vapp1`, `RuntimeSlot0Service`, `RuntimeSlot1Service`, `SessionKeeperService` y `HostTaskSupervisorService`.
- Room vive en `app/src/main/java/com/valcrono/virtualspace/VirtualRoom.kt`; `ValcronoDatabase` está en versión 13 antes de este trabajo y contiene paquetes, componentes, permisos, sesiones runtime, launch tokens, slots y mensajes.
- `VirtualRepository` centraliza acceso a Room, importación cooperativa, verificación SHA-256 y `VirtualStorageManager`.
- El runtime estable se basa en `RuntimeSessionRepository`, `RuntimeSessionController`, `RuntimeSlotRepository`, `RuntimeSlotReclaimer`, `RuntimeRecoveryManager`, `RuntimeProxyActivity0/1` y los servicios `RuntimeSlot0Service`/`RuntimeSlot1Service`.
- La carga cooperativa actual usa `AndroidVirtualApkClassLoaderFactory` con `DexClassLoader`, verificación de hash y directorio privado bajo `codeCacheDir/virtual-dex/{packageName}`.
- El almacenamiento virtual actual usa `VirtualStorageManager` y `VirtualPathResolver` bajo `filesDir/virtual/users/{userId}/{packageName}` con validación de packageName y rutas relativas.
- La UI está en Compose dentro de `MainActivity`; la pantalla de apps ya expone “Importar APK”, tarjetas por paquete, archivos, procesos, diagnóstico y menús.

## Flujo runtime actual

1. UI selecciona abrir paquete.
2. `RuntimeSessionRepository.resolveOpenDecision()` reconcilia slots/procesos reales antes de decidir warm-resume o cold-start.
3. `RuntimeSessionController.prepareLaunch()` crea o actualiza sesión, genera `launchAttemptId`, `launchToken`, reserva slot real mediante `RuntimeSlotRepository` y registra token.
4. UI envía `RuntimeLaunchRequest` al servicio del slot y abre `RuntimeProxyActivity0/1`.
5. El proceso `:vappN` valida sessionId, slotId, launchAttemptId, launchToken, reservationToken, runtimeGeneration y slotEpoch.
6. El proceso carga ClassLoader cooperativo, arranca entry point, acepta ACK, actualiza slot a activo y emite heartbeats.
7. Watchdog/reconciler verifican heartbeat, PID y estado de slots; si el proceso no existe, liberan slot y paran sesiones.
8. Cierre, pausa, STOP y onTaskRemoved pasan por controladores y reclaimer, evitando slots ocupados fantasma.

## Fuentes autoritativas de estado

- Paquete importado: fila `virtual_packages`, hash real del APK privado y existencia de storage privado.
- Sesión: combinación de `virtual_runtime_sessions`, `runtime_slots`, heartbeat reciente, PID vivo por `Os.kill(pid, 0)` y host/runtime generation.
- Slot: `runtime_slots` es el registro persistido, pero solo es válido tras reconciliación con proceso real.
- Mensajería: `virtual_messages` con estados persistidos y dispatcher host.
- Archivos: `VirtualPathResolver` con canonicalPath y raíz por usuario/paquete.

## Riesgos detectados

- El flujo SAF existente copiaba primero a `cacheDir` sin validar MIME/extensión/header/manifest/classes.dex antes de parsear.
- La entidad `VirtualPackageEntity` ya existía con forma antigua; ampliar la misma tabla evita una tabla paralela, pero exige migración aditiva segura.
- `SecureApkImporter` ya valida ZIP, pero faltaba exigir `classes.dex`, detectar nombres duplicados y escribir metadatos JSON equivalentes.
- El runtime actual solo ejecuta APK cooperativos con `VirtualAppEntryPoint`; ejecutar Activity genérica real exige una capa más amplia de Instrumentation/Context/Resources. En V1 se integra persistencia/importación sólida y se mantiene ejecución solo para cooperativos hasta que el paquete genérico tenga ruta segura completa.

## Plan de implementación

1. Añadir migración Room 13→14 con columnas de importación/análisis V1 sobre `virtual_packages`, preservando filas de TestApp A/B.
2. Fortalecer SAF: tipos permitidos, rechazo de formatos bundle, validación de URI, copia streaming con SHA-256 y temporal privado.
3. Fortalecer `ApkValidator`/parser: ZIP seguro, `AndroidManifest.xml`, `classes.dex`, dexCount, libs, splits, señales GMS/Firebase/WebView/servicios especiales.
4. Clasificar compatibilidad con niveles solicitados persistibles y razones en JSON simple.
5. Crear almacenamiento aislado adicional `filesDir/virtual-packages/{packageName}` con `apk/base.apk`, `data`, `metadata`, `temp`, `logs`, sin romper `virtual/users` existente.
6. Mostrar diagnóstico previo/post-análisis en UI y registrar paquetes READY/BLOCKED/ERROR.
7. Mantener apertura cooperativa con slots existentes y bloquear de forma explícita ejecución genérica incompleta, sin crear slots paralelos ni reutilizar tokens.
8. Documentar matriz manual e informe final.
