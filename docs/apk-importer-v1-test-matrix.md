# APK Importer V1 - matriz manual de pruebas

> Dispositivo objetivo: Motorola moto g06, Android 15 / SDK 35, ABI arm64-v8a.

## PRUEBA 1
- Abrir A.
- Abrir B.
- Alternar 20 veces.
- Resultado esperado: ambas permanecen funcionales.

## PRUEBA 2
- Enviar 10 mensajes A → B.
- Resultado esperado: 10 `messageId` únicos y 0 fallidos.

## PRUEBA 3
- Cerrar VirtualSpace desde Recientes.
- Resultado esperado: A cerrada, B cerrada, VAPP0 FREE, VAPP1 FREE, sesiones activas 0.

## PRUEBA 4
- Reabrir VirtualSpace.
- Abrir A y B.
- Resultado esperado: sin sesiones obsoletas ni tokens inválidos.

## PRUEBA 5
- Importar APK externo simple mediante el botón “Importar APK”.
- Resultado esperado: análisis correcto, copia privada y estado READY o BLOCKED con razón explícita.

## PRUEBA 6
- Abrir APK externo cooperativo READY.
- Resultado esperado: slot reservado y Activity/proxy visible. Para APK genérico no cooperativo V1 debe bloquear ejecución con mensaje explícito.

## PRUEBA 7
- Cerrar APK.
- Resultado esperado: slot liberado.

## PRUEBA 8
- Reabrir APK 20 veces.
- Resultado esperado: sin fugas de sesión, sin reutilización de launchToken antiguo.

## PRUEBA 9
- Cerrar host desde Recientes con APK externo abierto.
- Resultado esperado: proceso externo detenido y slot libre.

## PRUEBA 10
- Reiniciar teléfono.
- Abrir VirtualSpace.
- Resultado esperado: reconciliación limpia y slots FREE si no hay proceso real.

## PRUEBA 11
- Importar APK corrupto.
- Resultado esperado: error legible y sin paquete parcial.

## PRUEBA 12
- Importar split APK incompleto.
- Resultado esperado: SPLIT_APK_UNSUPPORTED o BLOCKED con razón de split.

## PRUEBA 13
- Poco almacenamiento.
- Resultado esperado: importación cancelada sin temporales huérfanos en `filesDir/virtual-imports/tmp`.

## PRUEBA 14
- Cambiar APK importado después de copiarlo.
- Resultado esperado: APK_HASH_MISMATCH o rechazo de verificación antes de ClassLoader.

## PRUEBA 15
- Forzar muerte de VAPP0.
- Resultado esperado: sesión marcada como detenida y slot recuperado.

## Logs a capturar si falla
- `adb logcat -d -s Valcrono RuntimeLaunch RuntimeSlot RuntimeReclaimer RuntimeRecovery DexLoader Importer UI`
- `adb shell pidof com.valcrono.virtualspace`
- Captura de pantalla de Apps y Procesos.
