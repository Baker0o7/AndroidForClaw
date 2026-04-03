# Build and Release Guide for AndroidForClaw

## Instructions for Building and Releasing Signed APK

### 1. Keystore Setup
- Generate a keystore using the command:
  ```bash
  keytool -genkey -v -keystore your_keystore_name.jks -keyalg RSA -keysize 2048 -validity 10000 -alias your_alias_name
  ```
- Follow the prompts to set up your keystore password and other details.

### 2. Build Commands
- To build the signed APK, navigate to your project directory and run:
  ```bash
  ./gradlew assembleRelease
  ```
- Make sure to replace the default signing configuration in the `build.gradle` file if required:
  ```groovy
  android {
      signingConfigs {
          release {
              storeFile file('path_to_your_keystore/your_keystore_name.jks')
              storePassword 'your_keystore_password'
              keyAlias 'your_alias_name'
              keyPassword 'your_key_password'
          }
      }
  }
  ```

### 3. APK Location
- After building, the signed APK will be located at:
  ```
  app/build/outputs/apk/release/app-release.apk
  ```

### Additional Notes
- Ensure that you have the latest version of the Android SDK and Gradle installed. 
- Remember to keep your keystore and passwords secure!