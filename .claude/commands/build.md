---
description: Build the Ridestr Android apps (rider and/or driver)
---

# Build Ridestr Apps

Build the Ridestr Android applications using the project's batch files.

## Mode

Based on `$ARGUMENTS`:
- `rider` or `rider-app` - Build only the rider app
- `driver` or `drivestr` - Build only the driver app
- `both` or `all` or empty - Build both apps

## Build Commands

### Rider App
```bash
cmd //c "C:\Users\Iwill\Documents\Projects\ridestr 2026\build_rider.bat" 2>&1
```

### Driver App (Drivestr)
```bash
cmd //c "C:\Users\Iwill\Documents\Projects\ridestr 2026\build_drivestr.bat" 2>&1
```

## Process

1. Parse `$ARGUMENTS` to determine which app(s) to build
2. Run the appropriate batch file(s)
3. Report build results including:
   - Success or failure status
   - Any warnings (especially deprecation warnings)
   - Build time
   - APK location if successful

## APK Locations

After successful builds:
- **Rider APK**: `rider-app/build/outputs/apk/debug/rider-app-debug.apk`
- **Driver APK**: `drivestr/build/outputs/apk/debug/drivestr-debug.apk`

## Error Handling

If a build fails:
1. Report the error clearly
2. Show relevant error messages from the Gradle output
3. Suggest potential fixes if the error is recognizable

## Example Usage

- `/build` - Builds both apps
- `/build rider` - Builds only the rider app
- `/build driver` - Builds only the driver app
