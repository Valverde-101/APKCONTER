# Valcrono VirtualSpace

Prototipo Fase 1 de un contenedor Android privado sin root para importar APKs simples, registrar metadatos, crear almacenamiento virtual y lanzar una `ProxyActivity` mínima.

## Módulos

- `app`: UI Compose, importación SAF, exportación inicial, `ProxyActivity`, Room schema y cargador `DexClassLoader` Android.
- `core`: logs, reloj y SHA-256 por streaming.
- `virtual-package-manager`: validación ZIP/APK, scanner de compatibilidad, importador seguro y parser fallback.
- `virtual-storage`: estructura de datos virtuales y resolución segura de rutas con validación de paquete/usuario.
- `virtual-file-manager`: listado, operaciones y checksums.
- `virtual-process`: registro de procesos, tokens de lanzamiento con expiración e intent router explícito.
- `compat-android15`: diagnóstico de ABI, espacio y tamaño de página.
- `testapp-a`, `testapp-b`: APKs de prueba propios.

## Estado

- Fase 1A: PARCIAL. La importación, almacenamiento, validación, scanner y exportación inicial existen, pero el parser real de `AndroidManifest.xml` binario y la persistencia completa en Room deben completarse.
- Fase 1B: PARCIAL. `ProxyActivity` valida token, SHA-256 y ruta virtual y puede escribir en almacenamiento virtual, pero la ejecución completa de actividades externas mediante `DexClassLoader` aún requiere integración de `VirtualContext`.

## Compilación esperada

```bash
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

En el entorno actual, la resolución del Android Gradle Plugin desde Google Maven puede fallar por restricciones de red/proxy. En Android Studio con SDK 35 y acceso a repositorios Android, la APK principal esperada queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Runtime cooperativo de Fase 1

La ejecución de APKs en esta iteración es **PROTOTIPO FUNCIONAL PARA APPS COOPERATIVAS**: el host carga la clase declarada en `com.valcrono.virtualspace.ENTRY_POINT` mediante `DexClassLoader`, verifica que implementa `VirtualAppEntryPoint`, crea un `VirtualAppEnvironment` aislado por paquete y renderiza un modelo `VirtualContent` seguro en `ProxyActivity`.

No es ejecución universal de cualquier Activity Android arbitraria. Esa compatibilidad queda para fases posteriores con proxies e instrumentación más avanzados.

## Compilar APK con GitHub Actions

1. Sube los cambios al repositorio de GitHub.
2. Abre la pestaña `Actions`.
3. Selecciona el workflow `Build Android APKs`.
4. Pulsa `Run workflow`.
5. Elige la rama `main`.
6. Espera a que la compilación termine correctamente.
7. Abre la ejecución finalizada.
8. Baja hasta la sección `Artifacts`.
9. Descarga `valcrono-virtualspace-debug-apks`.
10. Descomprime el ZIP.

El artifact contiene:

```text
valcrono-virtualspace-debug-apks/
├── app-debug.apk
├── testapp-a-debug.apk
└── testapp-b-debug.apk
```

Uso de cada APK:

- `app-debug.apk`: es Valcrono VirtualSpace. Es el único APK que se instala normalmente en el Motorola Moto G06.
- `testapp-a-debug.apk`: no debe instalarse normalmente para esta prueba. Cópialo al almacenamiento del teléfono e impórtalo desde Valcrono VirtualSpace.
- `testapp-b-debug.apk`: no debe instalarse normalmente para esta prueba. Cópialo al almacenamiento del teléfono e impórtalo desde Valcrono VirtualSpace.

Rutas originales generadas por Gradle:

```text
app/build/outputs/apk/debug/app-debug.apk
testapp-a/build/outputs/apk/debug/testapp-a-debug.apk
testapp-b/build/outputs/apk/debug/testapp-b-debug.apk
```

## Instalación en Motorola Moto G06

1. Descarga el artifact `valcrono-virtualspace-debug-apks` desde GitHub Actions.
2. Descomprime el ZIP.
3. Copia los tres APK al teléfono.
4. Permite instalación desde fuentes desconocidas para el explorador o navegador utilizado.
5. Instala solamente `app-debug.apk`.
6. Abre Valcrono VirtualSpace.
7. Importa `testapp-a-debug.apk`.
8. Importa `testapp-b-debug.apk`.
9. Ejecuta las pruebas del espacio virtual desde la app.

Al ser una compilación debug, Android puede mostrar advertencias normales de instalación mediante sideload.

## Workflow de CI

El workflow usa JDK 17, Gradle 8.9 instalado por GitHub Actions, Android SDK, Platform 35 y Build Tools 35.0.0. Ejecuta `clean`, `test`, `lint`, compila `:testapp-a`, `:testapp-b` y `:app`, verifica que existan los tres APK, imprime tamaño y SHA-256, y publica artifacts durante 14 días.
