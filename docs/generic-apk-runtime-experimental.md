# Valcrono VirtualSpace generic APK runtime (experimental)

This iteration separates cooperative Valcrono apps from ordinary Android APKs.

## Runtime modes

- `COOPERATIVE`: APKs that declare and implement `VirtualAppEntryPoint`. They continue to run through the existing two-slot runtime.
- `GENERIC_EXPERIMENTAL`: reserved for a future real Android Activity host. It must not be assigned until the runtime can provide Context, Resources, Window, Instrumentation and lifecycle safely.
- `INSPECTION_ONLY`: APKs that can be parsed and stored but cannot yet be executed without lying to the user.

## Current generic status

The importer now parses Android `MAIN`/`LAUNCHER` intent filters from the binary manifest using `apk-parser` and stores the Android launcher separately from the cooperative entry point. It also records native ABI facts, DEX count, components, requested permissions, split markers and structured compatibility/blocking reason codes.

Native libraries no longer block an APK by themselves. The importer compares APK ABIs with `Build.SUPPORTED_ABIS`; incompatible native-only APKs receive `ABI_UNSUPPORTED`, while matching native APKs are classified with `GENERIC_NATIVE_COMPATIBLE` but remain `INSPECTION_ONLY` until a true generic Activity host exists.

## Android 15 limitation

A conventional Activity cannot be started by instantiating its class. A safe host needs a virtual Context, Resources/assets, PackageManager facade, Window, Instrumentation/lifecycle bridge, storage isolation and hidden-API diagnostics. Until that is implemented and tested, Valcrono exposes diagnostics and inspection actions instead of a false "ready" state.
