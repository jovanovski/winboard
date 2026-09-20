plugins {
    alias(libs.plugins.android.application)
}

android {
    /*
     * WinBoard is its own application, with its own package name.
     *
     * The keyboard came out of a launcher, where it shared that launcher's namespace and
     * read its accent across a process boundary. None of that is true any more: nothing here
     * knows the launcher exists, nothing here reads another application's settings, and this
     * installs, updates and is uninstalled on its own.
     */
    namespace = "rocks.gorjan.winboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "rocks.gorjan.winboard"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /*
         * Which processors the bundled native code is carried for.
         *
         * Vosk is the only native part. Its AAR ships seven architectures totalling forty
         * megabytes, three of which (mips, mips64, armeabi) have not been supported by
         * Android for years and one of which (x86) is a 32-bit emulator nobody runs.
         *
         * What is left is the two every real phone uses. The emulator's x86_64 is gone too,
         * which saves a further ten megabytes at the cost of not being able to test
         * dictation on an emulator - this is a keyboard for phones, and it is tested on one.
         */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        /*
         * Calling an API the phone does not have, treated as the release-stopper it is.
         *
         * `lintVital` is the pass wired into assembleRelease, and it runs only the issues
         * lint marks FATAL. NewApi is merely an ERROR, so a release build can run lint, pass,
         * and ship a call that does not exist on a phone this app supports. minSdk is 29 and
         * compileSdk is 36, so there are seven API levels of room for one - and in an input
         * method the failure is text entry dying everywhere on the phone at once.
         */
        fatal += "NewApi"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)

    /*
     * The GIF panel's frames. The emoji panel's `gif` half draws animated GIFs in a grid,
     * which no platform drawable does.
     */
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.29")

    /*
     * Offline speech recognition, for dictation.
     *
     * Vosk runs entirely on the phone: no account, no network, nothing sent anywhere. That
     * is the whole reason it is here rather than the platform's own recogniser, which on most
     * Android phones is Google's and transcribes in the cloud - and which on some phones
     * cannot be used at all, because no speech-to-text engine is installed.
     *
     * JNA is not optional: vosk-android is a thin Kotlin layer over a native library and
     * reaches it through JNA rather than hand-written JNI, so leaving it out compiles and
     * then fails at the first call.
     */
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
