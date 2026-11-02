name: Build AutoBTS v2.0 APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build-android-apk:
    runs-on: ubuntu-latest

    steps:
      - name: 1. Tải mã nguồn từ kho lưu trữ
        uses: actions/checkout@v4

      - name: 2. Cài đặt Java JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: 3. Cài đặt bộ biên dịch Gradle 8.6
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.6'

      - name: 4. Tự động tạo khung cấu hình Android & Manifest
        run: |
          # Tạo gradle.properties
          cat << 'EOF' > gradle.properties
          org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
          android.useAndroidX=true
          android.nonTransitiveRClass=true
          EOF

          # Tạo settings.gradle.kts
          cat << 'EOF' > settings.gradle.kts
          pluginManagement {
              repositories {
                  google()
                  mavenCentral()
                  gradlePluginPortal()
              }
          }
          dependencyResolutionManagement {
              repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
              repositories {
                  google()
                  mavenCentral()
              }
          }
          rootProject.name = "AutoBTSMapper"
          include(":app")
          EOF

          # Tạo build.gradle.kts ở thư mục gốc
          cat << 'EOF' > build.gradle.kts
          plugins {
              id("com.android.application") version "8.2.2" apply false
              id("org.jetbrains.kotlin.android") version "1.9.22" apply false
          }
          EOF

          # Tạo thư mục app và file app/build.gradle.kts nếu chưa có
          mkdir -p app/src/main/java/com/autosignal/btsmapper
          if [ ! -f app/build.gradle.kts ]; then
          cat << 'EOF' > app/build.gradle.kts
          plugins {
              id("com.android.application")
              id("org.jetbrains.kotlin.android")
          }

          android {
              namespace = "com.autosignal.btsmapper"
              compileSdk = 34

              defaultConfig {
                  applicationId = "com.autosignal.btsmapper"
                  minSdk = 26
                  targetSdk = 34
                  versionCode = 2
                  versionName = "2.0"
              }

              compileOptions {
                  sourceCompatibility = JavaVersion.VERSION_17
                  targetCompatibility = JavaVersion.VERSION_17
              }
              kotlinOptions {
                  jvmTarget = "17"
              }
          }

          dependencies {
              implementation("androidx.core:core-ktx:1.12.0")
              implementation("androidx.appcompat:appcompat:1.6.1")
              implementation("com.google.android.material:material:1.11.0")
              implementation("org.osmdroid:osmdroid-android:6.1.18")
              implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
          }
          EOF
          fi

          # Tạo file AndroidManifest.xml nếu chưa có
          if [ ! -f app/src/main/AndroidManifest.xml ]; then
          cat << 'EOF' > app/src/main/AndroidManifest.xml
          <?xml version="1.0" encoding="utf-8"?>
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">

              <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
              <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
              <uses-permission android:name="android.permission.READ_PHONE_STATE" />
              <uses-permission android:name="android.permission.INTERNET" />
              <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

              <application
                  android:allowBackup="true"
                  android:label="AutoBTS v2.0"
                  android:supportsRtl="true"
                  android:usesCleartextTraffic="true"
                  android:theme="@style/Theme.AppCompat.NoActionBar">

                  <activity
                      android:name=".MainActivity"
                      android:exported="true"
                      android:screenOrientation="portrait"
                      android:keepScreenOn="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
          EOF
          fi

      - name: 5. Biên dịch ra file APK
        run: gradle assembleDebug --no-daemon

      - name: 6. Đẩy file APK lên mục Tải về (Artifacts)
        uses: actions/upload-artifact@v4
        with:
          name: AutoBTS-v2.0-Installer
          path: app/build/outputs/apk/debug/app-debug.apk
